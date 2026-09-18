package com.alicejump.okscripttoolkit.tasklauncher

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.alicejump.okscripttoolkit.toolbox.ToolboxService
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import java.io.OutputStreamWriter
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.SwingUtilities

/**
 * 执行器会话（项目级服务）：持有**唯一的常驻执行器进程**及其状态，
 * 对应 VSCode 版 TaskLauncherViewProvider 中与 UI 无关的进程管理部分。
 *
 * 进程模型与旧版「一次启动 = 一个任务进程」完全不同：
 * python/run_executor.py 只启动一次，连接一次游戏，然后由 ok-script 框架原生的
 * TaskExecutor 循环轮询全部已启用的触发任务；一次性任务以入队方式交给同一个进程。
 * 因此这里维护的是「执行器状态快照」而不是「当前任务」。
 *
 * 执行器的生命周期独立于任务工具窗：关闭工具窗只解绑视图，执行器继续在后台运行，
 * 重新打开工具窗时回放输出缓冲并重新同步状态；项目关闭（dispose）才终止执行器。
 */
@Service(Service.Level.PROJECT)
class TaskRunnerService(private val project: Project) : Disposable {

    companion object {
        private val LOG = Logger.getInstance(TaskRunnerService::class.java)
        private val objectMapper = ObjectMapper()

        fun getInstance(project: Project): TaskRunnerService = project.service()

        private const val MAX_RECENT_LINES = 2000

        /** 收到 stop 后仍未退出则强杀进程树的兜底延时（毫秒） */
        private const val FORCE_KILL_DELAY_MS = 12_000L

        private const val MARKER_STATE = "OK_TOOLKIT_STATE:"
    }

    /** 执行器状态快照（由 run_executor.py 的 OK_TOOLKIT_STATE 标记行驱动） */
    data class ExecutorState(
        /** idle（未启动）/ connecting（启动中）/ running（已连接并轮询） */
        val status: String = "idle",
        val paused: Boolean = false,
        /** 当前正在执行的任务 key（module::Class），空闲为空串 */
        val current: String = "",
        val currentIsTrigger: Boolean = false,
        /** 一次性任务等待队列 */
        val onetimeQueue: List<String> = emptyList(),
        /** 执行器侧的触发任务启用集合（权威值） */
        val enabledTriggers: List<String> = emptyList(),
        /** 执行器结束原因（关闭/异常退出），运行中为 null */
        val finishMessage: String? = null,
        /** 最近一次控制命令错误（OK_TOOLKIT_ERROR 标记行） */
        val controlError: String? = null,
    )

    private val toolboxService = ToolboxService.getInstance(project)

    @Volatile
    private var process: Process? = null

    @Volatile
    private var connecting = false

    @Volatile
    private var currentProjectDir = ""

    /** 调试浮层当前是否生效（启动沿用工具箱状态，运行中经 overlay_* 标记同步） */
    @Volatile
    private var overlayActive = false

    /** 最新状态快照；进程结束后只保留 enabledTriggers（UI 勾选态不丢） */
    @Volatile
    private var snapshot = ExecutorState()

    private val forceKillTimer = Timer("ok-script-executor-kill", true)

    @Volatile
    private var forceKillTask: TimerTask? = null

    private val outputListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val stateListeners = CopyOnWriteArrayList<(ExecutorState) -> Unit>()

    /** 输出环形缓冲：工具窗重开时回放，上限 [MAX_RECENT_LINES] 行 */
    private val recentOutput = ArrayDeque<String>()
    private val recentOutputLock = Object()

    // ── State ─────────────────────────────────────────────────────────

    fun isRunning(): Boolean = process?.isAlive == true

    fun currentState(): ExecutorState = snapshot.copy(
        status = when {
            // connecting 优先于 isRunning()：进程是异步 spawn 的，在它真正起来之前
            // isRunning() 仍为 false，不能让 UI 闪回 idle
            connecting -> "connecting"
            !isRunning() -> "idle"
            else -> "running"
        },
    )

    /** 已入列的触发任务 key（执行器未启动时沿用上次快照，UI 勾选态不丢） */
    fun enabledTriggers(): List<String> = snapshot.enabledTriggers

    fun addOutputListener(listener: (String) -> Unit) {
        outputListeners.add(listener)
    }

    fun removeOutputListener(listener: (String) -> Unit) {
        outputListeners.remove(listener)
    }

    fun addStateListener(listener: (ExecutorState) -> Unit) {
        stateListeners.add(listener)
    }

    fun removeStateListener(listener: (ExecutorState) -> Unit) {
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

    private fun emitState(transform: (ExecutorState) -> ExecutorState = { it }) {
        val state = currentState().let(transform)
        SwingUtilities.invokeLater {
            stateListeners.forEach { it(state) }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────

    /**
     * 启动常驻执行器；已在运行则返回 false。
     * 环境变量由调用方准备（含 OK_TOOLKIT_TRIGGERS 启用集合与参数覆盖）。
     */
    fun start(
        pythonPath: String,
        command: List<String>,
        projectDir: String,
        env: Map<String, String>,
        enabledTriggers: List<String>,
    ): Boolean {
        if (isRunning()) return false
        connecting = true
        currentProjectDir = projectDir
        snapshot = ExecutorState(status = "connecting", enabledTriggers = enabledTriggers)
        synchronized(recentOutputLock) { recentOutput.clear() }
        emitState()

        CompletableFuture.runAsync {
            try {
                val processBuilder = ProcessBuilder(listOf(pythonPath) + command)
                    .directory(File(projectDir))
                processBuilder.environment().let { pe -> env.forEach { (k, v) -> pe[k] = v } }

                val proc = processBuilder.start()
                process = proc
                // 浮层开关等工具箱命令改经服务转发（与视图生命周期解耦）
                toolboxService.registerTaskCommandWriter(::sendCommand)
                // 浮层互斥：执行器进程自带 overlay，通知工具箱停掉独立浮层宿主
                toolboxService.onExecutorRunningChanged(true)
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
                    onExecutorExit(exitCode)
                }.apply { isDaemon = true; start() }

            } catch (e: Exception) {
                LOG.error("Failed to start executor", e)
                recordAndEmit(OkScriptToolkitBundle.message("taskLauncher.launchFailed", e.message ?: ""))
                onExecutorExit(null)
            }
        }
        return true
    }

    private fun onExecutorExit(exitCode: Int?) {
        val wasForced = forceKillTask != null
        cancelForceKill()
        process = null
        connecting = false
        val message = when {
            exitCode == null -> OkScriptToolkitBundle.message("taskLauncher.executorClosed")
            exitCode == 0 -> OkScriptToolkitBundle.message("taskLauncher.executorClosed")
            else -> OkScriptToolkitBundle.message("taskLauncher.executorExitCode", exitCode)
        }
        if (!wasForced && exitCode != null && exitCode != 0) {
            LOG.warn("Executor exited with code $exitCode")
        }
        snapshot = ExecutorState(
            status = "idle",
            // 保留启用集合，重开工具窗 / 重启执行器时沿用用户勾选
            enabledTriggers = snapshot.enabledTriggers,
            finishMessage = message,
        )
        toolboxService.registerTaskCommandWriter(null)
        // 浮层互斥：执行器退出，把独立浮层宿主交还给工具箱
        // （error / 正常退出都走这里，onExecutorRunningChanged 内部会去重）
        toolboxService.onExecutorRunningChanged(false)
        emitState()
    }

    // ── 命令 ──────────────────────────────────────────────────────────

    /** 触发任务入列 / 出列（等价 ok-script GUI 的启用开关） */
    fun setTriggerEnabled(key: String, enabled: Boolean): Boolean =
        sendCommand(if (enabled) "trigger_enable $key" else "trigger_disable $key")

    /** 一次性任务入队：由常驻执行器执行一次后自动出队 */
    fun enqueueOnetime(key: String): Boolean = sendCommand("onetime_enqueue $key")

    /** 停掉当前正在执行的任务，轮询继续 */
    fun stopCurrent(): Boolean = sendCommand("task_disable")

    /** 参数覆盖即时推送（执行器是常驻进程，不推就要重启才生效） */
    fun pushParams(json: String): Boolean = sendCommand("params $json")

    /** 关闭执行器：先请它自己退出，超时再强杀进程树 */
    fun stopExecutor() {
        val proc = process ?: return
        if (!proc.isAlive) return
        recordAndEmit("--- ${OkScriptToolkitBundle.message("taskLauncher.stoppingExecutor")} ---")
        if (!sendCommand("stop")) {
            killProcessTree(proc)
            return
        }
        cancelForceKill()
        val task = object : TimerTask() {
            override fun run() {
                forceKillTask = null
                val alive = process
                if (alive != null && alive.isAlive) killProcessTree(alive)
            }
        }
        forceKillTask = task
        forceKillTimer.schedule(task, FORCE_KILL_DELAY_MS)
    }

    private fun cancelForceKill() {
        forceKillTask?.cancel()
        forceKillTask = null
    }

    /** Windows 上 taskkill /F /T 终止进程树（阻塞调用已移出 EDT） */
    private fun killProcessTree(proc: Process) {
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
                LOG.warn("Failed to kill executor process", e)
                proc.destroyForcibly()
            }
        }
    }

    /**
     * 向执行器 stdin 写入控制命令（trigger_enable / onetime_enqueue / pause / resume /
     * overlay_on|off / stop …）。无运行进程返回 false；调用线程任意。
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

    /** 按行扫描 run_executor.py 输出的控制标记（标记行本身也会进输出缓冲） */
    private fun scanControlMarkers(line: String) {
        when {
            line.contains(MARKER_STATE) -> applySnapshot(line.substringAfter(MARKER_STATE).trim())
            line.contains("OK_TOOLKIT_EXECUTOR_READY") -> {
                connecting = false
                snapshot = snapshot.copy(controlError = null)
                emitState()
            }
            line.contains("OK_TOOLKIT_PAUSED") -> {
                snapshot = snapshot.copy(paused = true, controlError = null)
                emitState()
            }
            line.contains("OK_TOOLKIT_RESUMED") -> {
                snapshot = snapshot.copy(paused = false, controlError = null)
                emitState()
            }
            line.contains("OK_TOOLKIT_OVERLAY_ON") -> setTaskOverlayActive(true)
            line.contains("OK_TOOLKIT_OVERLAY_OFF") -> setTaskOverlayActive(false)
            line.contains("OK_TOOLKIT_ERROR:") -> {
                val error = line.substringAfter("OK_TOOLKIT_ERROR:").trim()
                if (error.isNotBlank()) {
                    snapshot = snapshot.copy(controlError = error)
                    emitState()
                }
            }
        }
    }

    /**
     * 应用执行器状态快照（执行器侧的启用集合是权威值）。
     * 命令失败后 run_executor.py 不会回推状态，所以快照到达即视为上一次错误已翻篇。
     */
    private fun applySnapshot(payload: String) {
        if (payload.isBlank()) return
        val parsed = try {
            objectMapper.readTree(payload)
        } catch (e: Exception) {
            LOG.warn("Failed to parse executor state", e)
            return
        }
        val triggers = parsed.get("triggers")
        val enabled = if (triggers != null && triggers.isArray) {
            triggers.mapNotNull { node ->
                if (node.get("enabled")?.asBoolean() == true) node.get("key")?.asText(null) else null
            }
        } else {
            snapshot.enabledTriggers
        }
        snapshot = snapshot.copy(
            paused = parsed.get("paused")?.asBoolean() ?: false,
            current = parsed.get("current")?.asText(null) ?: "",
            currentIsTrigger = parsed.get("currentIsTrigger")?.asBoolean() ?: false,
            onetimeQueue = parsed.get("onetimeQueue")?.mapNotNull { it.asText(null) } ?: emptyList(),
            enabledTriggers = enabled,
            controlError = null,
        )
        emitState()
    }

    /** 调试浮层以 run_executor.py 的确认标记行为准；翻转时同步日志并回写工具箱共享状态 */
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
        cancelForceKill()
        forceKillTimer.cancel()
        toolboxService.registerTaskCommandWriter(null)
        stopExecutor()
    }
}
