package com.alicejump.okscripttoolkit.tasklauncher

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.alicejump.okscripttoolkit.toolbox.ToolboxService
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.fasterxml.jackson.databind.ObjectMapper
import java.awt.BorderLayout
import java.io.File
import java.io.OutputStreamWriter
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.JComponent
import javax.swing.JPanel
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

        /** Run 标签页里那条件工具栏的 ActionPlace（自定义 place，纯图标按钮） */
        private const val CONSOLE_TOOLBAR_PLACE = "ok-script-executor-console"
    }

    /** 执行器状态快照（由 run_executor.py 的 OK_TOOLKIT_STATE 标记行驱动） */
    data class ExecutorState(
        /** idle（未启动）/ connecting（启动中）/ running（已连接并轮询） */
        val status: String = "idle",
        /** 进程退出码：null = 用户关闭 / 启动失败（无退出信息）；非 0 = 异常退出 */
        val exitCode: Int? = null,
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

    /** 输出环形缓冲：控制台被关掉后重开时回放，上限 [MAX_RECENT_LINES] 行 */
    private val recentOutput = ArrayDeque<String>()
    private val recentOutputLock = Object()

    // ── Run 工具窗口控制台 ─────────────────────────────────────────────
    // 日志不再进插件面板，而是显示成 Run 工具窗口里的一张标签页（等价 IDE 跑一个
    // run configuration 的观感）。字段只在 EDT 上读写，consoleLock 只用于跨线程可见性。

    private var consoleView: ConsoleView? = null
    private var consoleDescriptor: RunContentDescriptor? = null
    private val consoleLock = Object()

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
        // 统一走 EDT：保证控制台里行的先后顺序与缓冲一致
        SwingUtilities.invokeLater { printLine(line) }
    }

    // ── Run 工具窗口控制台 ─────────────────────────────────────────────

    /**
     * 打开（必要时创建）Run 工具窗口里的「ok-script 执行器」标签页。
     *
     * [fresh] = true 表示执行器重新启动：先关掉旧标签页再开一张新的，
     * 与 IDE 里「重新运行会开新标签页」的行为一致。调用线程任意。
     */
    fun showConsole(fresh: Boolean = false) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater { showConsole(fresh) }
            return
        }
        try {
            val manager = RunContentManager.getInstance(project)
            val executor = DefaultRunExecutor.getRunExecutorInstance()

            if (fresh) {
                consoleDescriptor?.let { previous ->
                    synchronized(consoleLock) {
                        if (consoleDescriptor === previous) {
                            consoleView = null
                            consoleDescriptor = null
                        }
                    }
                    manager.removeRunContent(executor, previous)
                }
            }

            val existing = consoleView
            val existingDescriptor = consoleDescriptor
            if (existing != null && existingDescriptor != null) {
                manager.toFrontRunContent(executor, existingDescriptor)
                return
            }

            val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
            val descriptor = RunContentDescriptor(
                console,
                null,
                buildConsoleComponent(console),
                OkScriptToolkitBundle.message("taskLauncher.consoleTitle"),
                AllIcons.Actions.Execute,
            )
            descriptor.isActivateToolWindowWhenAdded = true
            descriptor.isSelectContentWhenAdded = true
            // 用户关掉标签页时平台会 dispose 掉 descriptor（连带 console），
            // 这里同步清空引用，下次 showConsole() 才会重建而不是往死控制台里写
            Disposer.register(descriptor) {
                synchronized(consoleLock) {
                    if (consoleDescriptor === descriptor) {
                        consoleView = null
                        consoleDescriptor = null
                    }
                }
            }
            synchronized(consoleLock) {
                consoleView = console
                consoleDescriptor = descriptor
            }
            manager.showRunContent(executor, descriptor)
            // 控制台被关掉后重开：回放环形缓冲，历史不至于断掉
            for (line in recentOutputLines()) console.print(line + "\n", contentTypeFor(line))
        } catch (e: Exception) {
            LOG.warn("Failed to open the executor run console", e)
        }
    }

    /** 控制台标签页是否开着（决定「查看日志」按钮是拉前台还是新建） */
    fun hasConsole(): Boolean = synchronized(consoleLock) { consoleView != null }

    /**
     * 控制台内容 = 顶部工具栏 + ConsoleView。
     *
     * 平台只在走 `ProgramRunner` 的常规运行路径时才用 RunContentBuilder 装配工具栏；
     * 直接 `showRunContent()` 一个自建 descriptor 是不带工具栏的，所以这里自己拼一条：
     * 前两个是本插件动作（停止当前任务 / 关闭执行器），后面接 ConsoleView 自带的
     * 清除、滚动到末尾、暂停输出、自动换行等动作。
     */
    private fun buildConsoleComponent(console: ConsoleView): JComponent {
        val group = DefaultActionGroup()
        group.add(executorAction(
            OkScriptToolkitBundle.message("taskLauncher.stopCurrent"),
            AllIcons.Actions.Suspend,
        ) { stopCurrent() })
        group.add(executorAction(
            OkScriptToolkitBundle.message("taskLauncher.closeExecutor"),
            AllIcons.Actions.Cancel,
        ) { stopExecutor() })
        group.addSeparator()
        for (action in console.createConsoleActions()) group.add(action)

        val toolbar = ActionManager.getInstance()
            .createActionToolbar(CONSOLE_TOOLBAR_PLACE, group, true)
        toolbar.targetComponent = console.component
        return JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.NORTH)
            add(console.component, BorderLayout.CENTER)
        }
    }

    /** Run 工具栏上的执行器动作：可用性按「当前是否有任务在跑」实时刷新 */
    private fun executorAction(text: String, icon: javax.swing.Icon, onClick: () -> Unit): AnAction =
        object : AnAction(text, text, icon) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = isRunning()
            }

            override fun actionPerformed(e: AnActionEvent) {
                onClick()
            }
        }

    /** 只往控制台写：控制台被用户关掉时静默丢弃（日志仍在环形缓冲里） */
    private fun printLine(line: String) {
        val console = consoleView ?: return
        console.print(line + "\n", contentTypeFor(line))
    }

    private fun contentTypeFor(line: String): ConsoleViewContentType = when {
        line.contains("ERROR") || line.contains("Traceback") || line.contains("Exception") ->
            ConsoleViewContentType.ERROR_OUTPUT
        line.contains("WARN") -> ConsoleViewContentType.LOG_WARNING_OUTPUT
        else -> ConsoleViewContentType.NORMAL_OUTPUT
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
        // 每次启动都开一张新的 Run 标签页（等价 IDE 的「重新运行」），日志从第一行起就在那儿
        showConsole(fresh = true)
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
                val message = OkScriptToolkitBundle.message("taskLauncher.launchFailed", e.message ?: "")
                recordAndEmit(message)
                // 启动失败≠用户关闭：经 controlError 标记，健康条显红而不是绿色
                onExecutorExit(null, startupError = message)
            }
        }
        return true
    }

    private fun onExecutorExit(exitCode: Int?, startupError: String? = null) {
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
            exitCode = exitCode,
            // 启动失败时非空：健康条据此显红（正常退出/用户关闭保持绿）
            controlError = startupError,
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

    /**
     * 全局配置快照即时推送（#7 配置接管，对齐 VS Code 侧 gparams 命令）。
     * 推整个快照映射 {组名: {键: 值}}，执行器侧防抖应用；无运行进程返回 false。
     */
    fun pushGlobalParams(json: String): Boolean = sendCommand("gparams $json")

    /**
     * 关闭执行器：先请它自己退出，超时再强杀进程树。
     *
     * 三处状态都必须让用户看得见，否则 UI 会停在"执行器还开着"的错误认知上：
     * 1. 已经没在执行器 —— 也要给一行反馈，不然用户点了按钮毫无动静，只会以为卡了；
     * 2. `stop` 命令没送出去 —— 要说明接下来是强杀，而不是静默换一种终止方式；
     * 3. 超时强杀 —— 由 [killProcessTree] 在真正动手时记录。
     */
    fun stopExecutor() {
        val proc = process
        if (proc == null || !proc.isAlive) {
            recordAndEmit(OkScriptToolkitBundle.message("taskLauncher.executorNotRunning"))
            return
        }
        recordAndEmit("--- ${OkScriptToolkitBundle.message("taskLauncher.stoppingExecutor")} ---")
        if (!sendCommand("stop")) {
            recordAndEmit(OkScriptToolkitBundle.message("taskLauncher.executorForceKilling"))
            killProcessTree(proc, OkScriptToolkitBundle.message("taskLauncher.executorForceKilling"))
            return
        }
        cancelForceKill()
        val task = object : TimerTask() {
            override fun run() {
                forceKillTask = null
                val alive = process
                if (alive != null && alive.isAlive) {
                    killProcessTree(alive, OkScriptToolkitBundle.message("taskLauncher.executorKillTimeout"))
                }
            }
        }
        forceKillTask = task
        forceKillTimer.schedule(task, FORCE_KILL_DELAY_MS)
    }

    private fun cancelForceKill() {
        forceKillTask?.cancel()
        forceKillTask = null
    }

    /**
     * Windows 上 taskkill /F /T 终止进程树（阻塞调用已移出 EDT）。
     *
     * [reason] 会被记录到输出面板：强杀是"最后手段"，用户必须能区分
     * "执行器自己优雅退出了" 和 "被我们打死了" —— 后者往往意味着退出流程有 bug。
     */
    private fun killProcessTree(proc: Process, reason: String) {
        recordAndEmit("--- $reason ---")
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
