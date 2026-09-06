package com.alicejump.okscripttoolkit.tasklauncher

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.alicejump.okscripttoolkit.toolbox.ToolboxService
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File
import java.io.OutputStreamWriter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

/**
 * 任务运行器（项目级服务）：持有运行中任务的进程与控制状态，对应 VSCode 版
 * TaskLauncherViewProvider 中与 UI 无关的进程管理部分。
 *
 * 任务的生命周期独立于任务工具窗：关闭工具窗只解绑视图，任务继续在后台运行，
 * 重新打开工具窗时回放输出缓冲并重新同步状态；项目关闭（dispose）才终止任务。
 */
@Service(Service.Level.PROJECT)
class TaskRunnerService(private val project: Project) : Disposable {

    companion object {
        private val LOG = Logger.getInstance(TaskRunnerService::class.java)

        fun getInstance(project: Project): TaskRunnerService = project.service()

        private const val MAX_RECENT_LINES = 2000
    }

    data class RunnerState(
        val running: Boolean = false,
        val paused: Boolean = false,
        val stopping: Boolean = false,
        val task: TaskLauncherService.TaskInfo? = null,
        /** 任务结束原因（已停止/完成/失败 + 退出码），运行中为 null */
        val finishMessage: String? = null,
        /** 最近一次控制命令错误（OK_TOOLKIT_ERROR 标记行） */
        val controlError: String? = null,
    )

    private val toolboxService = ToolboxService.getInstance(project)

    @Volatile
    private var process: Process? = null
    @Volatile
    private var currentTask: TaskLauncherService.TaskInfo? = null
    @Volatile
    private var currentProjectDir = ""
    private val stopping = AtomicBoolean(false)

    @Volatile
    private var paused = false

    /** 调试浮层当前是否生效（启动沿用工具箱状态，运行中经 overlay_* 标记行同步） */
    @Volatile
    private var overlayActive = false

    private val outputListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val stateListeners = CopyOnWriteArrayList<(RunnerState) -> Unit>()

    /** 输出环形缓冲：工具窗重开时回放，上限 [MAX_RECENT_LINES] 行 */
    private val recentOutput = ArrayDeque<String>()
    private val recentOutputLock = Object()

    // ── State ─────────────────────────────────────────────────────────

    fun isRunning(): Boolean = process?.isAlive == true

    fun currentState(): RunnerState = RunnerState(
        running = isRunning(),
        paused = paused,
        stopping = stopping.get(),
        task = currentTask,
    )

    fun addOutputListener(listener: (String) -> Unit) {
        outputListeners.add(listener)
    }

    fun removeOutputListener(listener: (String) -> Unit) {
        outputListeners.remove(listener)
    }

    fun addStateListener(listener: (RunnerState) -> Unit) {
        stateListeners.add(listener)
    }

    fun removeStateListener(listener: (RunnerState) -> Unit) {
        stateListeners.remove(listener)
    }

    fun recentOutputLines(): List<String> = synchronized(recentOutputLock) {
        recentOutput.toList()
    }

    /** 任务启动器等 UI 的启动期日志也进统一输出管道（缓冲 + 监听器） */
    fun log(line: String) {
        recordAndEmit(line)
    }

    private fun recordAndEmit(line: String) {
        synchronized(recentOutputLock) {
            recentOutput.addLast(line)
            while (recentOutput.size > MAX_RECENT_LINES) recentOutput.removeFirst()
        }
        outputListeners.forEach { it(line) }
    }

    private fun emitState(transform: (RunnerState) -> RunnerState = { it }) {
        val state = currentState().let(transform)
        SwingUtilities.invokeLater {
            stateListeners.forEach { it(state) }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────

    fun start(
        task: TaskLauncherService.TaskInfo,
        pythonPath: String,
        command: List<String>,
        projectDir: String,
        env: Map<String, String>,
        timeoutSeconds: Int,
    ) {
        if (isRunning()) return
        stopping.set(false)
        paused = false
        overlayActive = false
        currentProjectDir = projectDir
        synchronized(recentOutputLock) { recentOutput.clear() }

        recordAndEmit("=== ${task.displayName} ===")
        CompletableFuture.runAsync {
            try {
                val processBuilder = ProcessBuilder(listOf(pythonPath) + command)
                    .directory(File(projectDir))
                processBuilder.environment().let { pe -> env.forEach { (k, v) -> pe[k] = v } }

                val proc = processBuilder.start()
                process = proc
                currentTask = task
                // 浮层开关等工具箱命令改经服务转发（与视图生命周期解耦）
                toolboxService.registerTaskCommandWriter(::sendCommand)
                emitState()

                Thread {
                    try {
                        proc.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                            recordAndEmit(line)
                            scanControlMarkers(line)
                        }
                    } catch (_: Exception) { }
                }.apply { isDaemon = true; start() }

                Thread {
                    try {
                        proc.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                            recordAndEmit(line)
                        }
                    } catch (_: Exception) { }
                }.apply { isDaemon = true; start() }

                Thread {
                    val exitCode = try {
                        proc.waitFor()
                    } catch (e: InterruptedException) {
                        null
                    }
                    onTaskProcessExit(exitCode)
                }.apply { isDaemon = true; start() }

                if (timeoutSeconds > 0) {
                    Thread {
                        try {
                            Thread.sleep(timeoutSeconds * 1000L)
                            if (process?.isAlive == true) {
                                recordAndEmit(OkScriptToolkitBundle.message("taskLauncher.taskTimeout"))
                                stop()
                            }
                        } catch (_: InterruptedException) { }
                    }.apply { isDaemon = true; start() }
                }
            } catch (e: Exception) {
                LOG.error("Failed to run task", e)
                recordAndEmit(OkScriptToolkitBundle.message("taskLauncher.launchFailed", e.message ?: ""))
                emitState()
            }
        }
    }

    private fun onTaskProcessExit(exitCode: Int?) {
        val message = when {
            exitCode == null -> OkScriptToolkitBundle.message("taskLauncher.taskStopped")
            stopping.get() -> OkScriptToolkitBundle.message("taskLauncher.taskStopped")
            exitCode == 0 -> OkScriptToolkitBundle.message("taskLauncher.taskCompleted")
            else -> OkScriptToolkitBundle.message("taskLauncher.taskFailed") + " (exit code $exitCode)"
        }
        stopping.set(false)
        paused = false
        overlayActive = false
        currentTask = null
        process = null
        toolboxService.registerTaskCommandWriter(null)
        emitState { it.copy(running = false, paused = false, stopping = false, finishMessage = message) }
    }

    /** 停止任务：Windows 上 taskkill /F /T 终止进程树（阻塞调用已移出 EDT） */
    fun stop() {
        val proc = process ?: return
        if (!proc.isAlive) return
        stopping.set(true)
        recordAndEmit("--- ${OkScriptToolkitBundle.message("taskLauncher.taskStopped")} ---")
        emitState()
        CompletableFuture.runAsync {
            try {
                val pid = proc.pid()
                if (pid > 0 && System.getProperty("os.name").lowercase().contains("win")) {
                    ProcessBuilder("taskkill", "/F", "/T", "/PID", pid.toString())
                        .redirectErrorStream(true)
                        .start()
                        .waitFor()
                } else {
                    proc.destroyForcibly()
                }
            } catch (e: Exception) {
                LOG.warn("Failed to stop task process", e)
                proc.destroyForcibly()
            }
        }
    }

    /**
     * 向任务进程 stdin 写入控制命令（pause/resume/overlay_on/off）。
     * 无运行任务返回 false；调用线程任意。
     */
    fun sendCommand(command: String): Boolean {
        val proc = process ?: return false
        if (!proc.isAlive) return false
        return try {
            val writer = OutputStreamWriter(proc.outputStream, Charsets.UTF_8)
            writer.write("$command\n")
            writer.flush()
            true
        } catch (e: Exception) {
            LOG.warn("Failed to send control command: $command", e)
            recordAndEmit(OkScriptToolkitBundle.message("toolbox.sendCommandFailed", e.message ?: ""))
            false
        }
    }

    // ── Control markers ───────────────────────────────────────────────

    /** 按行扫描 run_task.py 输出的控制标记（标记行本身也会进输出缓冲） */
    private fun scanControlMarkers(line: String) {
        when {
            line.contains("OK_TOOLKIT_PAUSED") -> {
                paused = true
                emitState()
            }
            line.contains("OK_TOOLKIT_RESUMED") -> {
                paused = false
                emitState()
            }
            line.contains("OK_TOOLKIT_OVERLAY_ON") -> setTaskOverlayActive(true)
            line.contains("OK_TOOLKIT_OVERLAY_OFF") -> setTaskOverlayActive(false)
            line.contains("OK_TOOLKIT_ERROR:") -> {
                val error = line.substringAfter("OK_TOOLKIT_ERROR:").trim()
                if (error.isNotBlank()) {
                    emitState { it.copy(controlError = error) }
                }
            }
        }
    }

    /** 调试浮层以 run_task.py 的确认标记行为准；翻转时同步日志并回写工具箱共享状态 */
    private fun setTaskOverlayActive(active: Boolean) {
        if (overlayActive == active) return
        overlayActive = active
        recordAndEmit(
            if (active) OkScriptToolkitBundle.message("toolbox.overlayEnabledLog")
            else OkScriptToolkitBundle.message("toolbox.overlayDisabledLog"),
        )
        if (currentProjectDir.isNotBlank()) {
            toolboxService.onTaskOverlayMarker(currentProjectDir, active)
        }
    }

    override fun dispose() {
        toolboxService.registerTaskCommandWriter(null)
        stop()
    }
}
