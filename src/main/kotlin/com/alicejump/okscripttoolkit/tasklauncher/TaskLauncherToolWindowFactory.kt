package com.alicejump.okscripttoolkit.tasklauncher

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.alicejump.okscripttoolkit.core.OkProjectDataService
import com.alicejump.okscripttoolkit.core.AccountStoreService
import com.alicejump.okscripttoolkit.core.ProjectDirResolution
import com.alicejump.okscripttoolkit.core.RunDir
import com.alicejump.okscripttoolkit.toolbox.ToolboxService
import com.alicejump.okscripttoolkit.ui.ToolbarAction
import com.alicejump.okscripttoolkit.ui.openCharacterManager
import com.alicejump.okscripttoolkit.settings.OkScriptToolkitSettings
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.ui.JBColor
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.WrapLayout
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.util.concurrent.CompletableFuture
import java.nio.file.Paths
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class TaskLauncherToolWindowFactory : ToolWindowFactory {

    companion object {
        private val LOG = Logger.getInstance(TaskLauncherToolWindowFactory::class.java)
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = TaskLauncherPanel(project)
        val content = ContentFactory.getInstance().createContent(panel.mainPanel, "Tasks", false)
        toolWindow.contentManager.addContent(content)

        toolWindow.contentManager.addContentManagerListener(object : ContentManagerListener {
            override fun contentRemoved(event: ContentManagerEvent) {
                panel.onDispose()
            }
        })
    }
}

class TaskLauncherPanel(private val project: Project) {

    companion object {
        private val LOG = Logger.getInstance(TaskLauncherPanel::class.java)
        /** Jackson 的 ObjectMapper 线程安全且构造昂贵；本文件原先在 6 处各 new 一个，这里收敛成一个 */
        private val objectMapper = ObjectMapper()
        private const val DEFAULT_PYTHON_PATH = "python"
        private val OK_BORDER = TaskLauncherTheme.BORDER_OK
        private val BAD_BORDER = TaskLauncherTheme.BORDER_ERR
        private const val LF_CHAR: Char = 0x0A.toChar()
        private const val OK_JSON_FIELD = "ok-script.jsonField"

        // 状态语义色（亮 / 暗主题各一套）；色调编号见 TaskRowState.TONE_*
        // 语义色统一收敛到 TaskLauncherTheme（对应 VS Code 侧 tokens.css 的 token 单点）；
        // 这里保留原名作别名，包内既有引用零改动。tasklauncher 包内禁止再写 Color(0x…) 字面量。
        private val COLOR_GOOD = TaskLauncherTheme.OK
        private val COLOR_WARN = TaskLauncherTheme.WARN
        private val COLOR_BAD = TaskLauncherTheme.ERR
        private val COLOR_TRIGGER = TaskLauncherTheme.TRIGGER

        private fun colorForTone(tone: Int): JBColor = TaskLauncherTheme.colorForTone(tone)

        /** 一级页签下标（tabbedPane.addTab 的添加顺序） */
        private const val TAB_TASKS = 0
        private const val TAB_RUNNER = 2

        /**
         * 任务页改用**上下分栏**的宽度阈值（px）。
         * 再窄时左右分栏两边都不够用 —— 右侧工具窗默认停靠宽度就在这个值以下。
         */
        private const val TASK_PAGE_STACK_WIDTH = 560

        /** 配置页二级导航宽度（px）：放得下中文导航项，又不至于白占宽窗的地方 */
        private const val CONFIG_NAV_WIDTH = 118

        /** 配置页二级导航：CardLayout 的键（顺序与导航列表一一对应） */
        private const val NAV_GLOBAL = "config.global"
        private const val NAV_ACCOUNT = "config.account"
        private const val NAV_PROJECT = "config.project"
        private val NAV_KEYS = listOf(NAV_GLOBAL, NAV_ACCOUNT, NAV_PROJECT)
    }

    val mainPanel: JPanel
    private val taskService = TaskLauncherService(project)
    private val accountStoreService = AccountStoreService(project)
    private val toolboxService = ToolboxService.getInstance(project)

    /** 已勾选「启用」的触发任务 key（module::Class），持久化到 .idea/ok-script-toolkit-tasks.json */
    private val enabledTriggers = linkedSetOf<String>()

    /**
     * 左栏任务卡列表（对齐 VS Code 侧 media/console/taskCard.js 的编排）：
     * 触发 / 一次性两个可折叠组 + 一次性任务按 schema.groupName 二级分组 + 搜索。
     * 之前是「操作/任务/状态」三列平铺 JTable —— probe 顺序直出、无分组、无搜索，
     * 与 VS Code 侧的任务编排完全对不上。
     */
    private val taskCardList = TaskCardListPanel(CardHost())
    private val taskSearchField = SearchTextField(false)

    /** 执行队列条（#queueStrip 的等价物）：一次性队列非空时显示任务名 chips */
    private val queueStrip = JPanel(WrapLayout(FlowLayout.LEFT, 4, 1)).apply {
        isOpaque = false
        isVisible = false
    }
    /** 状态栏瞬时消息：可能很长（保存错误 / 加载进度）⇒ 省略而不是把右侧进度条挤掉 */
    private val statusLabel = EllipsizingLabel()
    private val schemaWarningLabel = JBLabel().apply {
        foreground = TaskLauncherTheme.WARN
        isVisible = false
    }
    private val progressBar = JProgressBar()

    // ── 健康点（#9 rc-health 的 Swing 等价物）────────────────────────
    // 健康点 = 状态色的最小可视化单元，与状态文字同源同色（状态栏常驻；
    // 运行器页的执行器状态卡用同一套取色规则，见 syncHealthBar）。
    private val healthDot = TaskLauncherTheme.HealthDot()

    // ── 一级页签（IA 重设计）：任务 / 配置 / 运行器 / 工具 ───────────
    // 之前执行器启停、全局配置、账号、游戏连接、角色入口全挂在任务页四周，
    // 页面越堆越臃肿。现在按「先分页面、再分组、再展示内容」拆成四个一级页签：
    // 任务页只管选任务看参数；运行态归运行器；全局配置/账号/项目归配置；
    // 各独立编辑器入口归工具。
    private lateinit var tabbedPane: JBTabbedPane

    /** 任务页顶部的执行器状态胶囊：点击跳运行器页（运行态完整视图在那边） */
    private val executorPillDot = TaskLauncherTheme.HealthDot()
    /** 状态文案可能很长（「执行器运行中 · N 个触发任务已入列」）⇒ 窄窗里省略而不是把刷新按钮挤掉 */
    private val executorPillLabel = EllipsizingLabel()

    // ── 任务页的两个半区（分栏方向随宽度自适应，见 applyTaskPageOrientation）──
    /** 半区一：搜索 + 执行器胶囊 + 队列条 + 任务卡列表 */
    private val taskListHost = JPanel(BorderLayout())
    /** 半区二：当前任务详情（头部 / 参数 / 页脚） */
    private val taskDetailPane = JPanel(BorderLayout())
    private var taskPageHost: JPanel? = null
    private var taskSplitter: com.intellij.openapi.ui.Splitter? = null
    /** 当前是否上下分栏（true = 列表在上、详情在下） */
    private var taskPageStacked = true

    // ── 运行器页（执行器状态 / 队列与轮询 / 执行环境 / 游戏连接）─────
    // ⚠️ 必须声明在 init 块之前：Kotlin 按文本顺序执行初始化器，
    // init → initUI() → showDetailPlaceholder() 会读下面的控件字段。
    private val runnerDot = TaskLauncherTheme.HealthDot()
    private val runnerStatusLabel = JBLabel().apply { font = font.deriveFont(Font.BOLD) }
    private val runnerCurrentChip = JBLabel().apply { isVisible = false }
    private val runnerStartButton = JButton(OkScriptToolkitBundle.message("taskLauncher.startExecutor"))
    private val runnerPauseButton = JButton(OkScriptToolkitBundle.message("taskLauncher.pause"))
    private val runnerResumeButton = JButton(OkScriptToolkitBundle.message("taskLauncher.resume"))
    private val runnerStopCurrentButton = JButton(OkScriptToolkitBundle.message("taskLauncher.stopCurrent"))
    private val runnerCloseButton = JButton(OkScriptToolkitBundle.message("taskLauncher.closeExecutor"))
    private val runnerQueuePanel = JPanel(WrapLayout(FlowLayout.LEFT, 4, 1))
    private val runnerPollingPanel = JPanel(WrapLayout(FlowLayout.LEFT, 4, 1))
    /** 执行环境卡取值（长路径不截断，见 [wrappingValueLabel]） */
    private val envProjectRootValue = wrappingValueLabel()
    private val envPythonValue = wrappingValueLabel()
    private val envSandboxValue = wrappingValueLabel()
    private val envSnapshotsValue = wrappingValueLabel()

    /** 探针采集到的全局配置组（配置页「全局配置」卡片数据源；applyProbeResult 更新） */
    private var globalConfigGroups: List<TaskLauncherService.GlobalConfigGroup> = emptyList()
    private var multiAccountInfo = TaskLauncherService.MultiAccountInfo()
    private var accountEditor: AccountEditorDialog? = null

    /** 悬停弹层抑制截止时间：点选/切换后 1.2s 内不弹（对齐主仓库约定） */
    private var hoverSuppressUntil = 0L

    /** 悬停弹层定时器：卡片 mouseEntered 时重启，800ms 后弹（弹层单例由 activeHoverPopup 保证） */
    private var hoverTimer: Timer? = null

    private val hoverPopupDelayMs = 800

    /** 任务卡分组折叠状态（tasks.json uiState 的内存快照；applyProbeResult 按项目根刷新） */
    private var uiCollapseState: Map<String, Boolean> = emptyMap()

    private val paramPanel = object : JPanel(GridBagLayout()), Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visibleRect: java.awt.Rectangle, orientation: Int, direction: Int) = 16
        override fun getScrollableBlockIncrement(visibleRect: java.awt.Rectangle, orientation: Int, direction: Int) =
            (visibleRect.height - 16).coerceAtLeast(16)
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
    }
    private val paramFields = mutableMapOf<String, JComponent>()

    private var tasks = listOf<TaskLauncherService.TaskInfo>()
    private var configModule = "src.config"
    private var schemas = mapOf<String, TaskLauncherService.TaskSchema>()
    private val saveTimer = javax.swing.Timer(400, null)
    private var pendingSave: PendingTaskSave? = null
    /** 防抖写盘仍要保持用户操作顺序；commonPool 的独立任务可能倒序完成。 */
    private var taskSaveTail: CompletableFuture<Boolean> = CompletableFuture.completedFuture(true)
    private var triggerSaveTail: CompletableFuture<Boolean> = CompletableFuture.completedFuture(true)
    @Volatile
    private var lastTaskSaveError = ""
    @Volatile
    private var lastTriggerSaveError = ""
    /** 必须在 init 前初始化：init 会立即调用 loadTasks，声明在后面会把代数重置为 0。 */
    @Volatile
    private var loadGeneration = 0
    @Volatile
    private var disposed = false
    /** 当前表格/参数面板所属的项目根；设置切换后异步保存仍须写回原项目。 */
    private var displayedProjectDir = ""

    // ── 右侧详情区（选中谁就显示谁）──
    private val detailTitle = EllipsizingLabel()
    private val detailKindChip = JBLabel()
    private val detailStateChip = JBLabel()
    private val detailActionButton = JButton()
    /** 类名·模块（详情区第二行，弱化色；窄窗里省略而不是硬裁一半） */
    private val detailClassLine = EllipsizingLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    /** 任务描述：完整换行展示（IA 重设计：描述给足空间，不再单行截断） */
    private val detailDescription = JTextArea().apply {
        isEditable = false
        isFocusable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        border = null
    }
    /** 详情页脚的快照操作（IA 重设计：⇄/⟲ 从卡片挪到这里，属于「当前任务详情」） */
    private val detailSyncButton = JButton(OkScriptToolkitBundle.message("taskLauncher.syncDefaultBtn"))
    private val detailResetButton = JButton(OkScriptToolkitBundle.message("taskLauncher.resetDefaultBtn"))
    /** 详情区当前展示的任务；null = 未选中，显示占位提示 */
    private var detailTask: TaskLauncherService.TaskInfo? = null

    /** 详情头部已渲染过描述的任务：状态推送高频调 renderDetailHeader，描述只在任务切换时重设 */
    private var detailDescriptionFor: TaskLauncherService.TaskInfo? = null

    // ── 配置页（全局配置 / 账号覆盖 / 项目配置）─────────────────────
    /** 全局配置内联卡片「改动即存」的防抖（对齐任务参数的 400ms 自动保存语义） */
    private val globalSaveTimer = javax.swing.Timer(400, null)
    private var pendingGlobalSave: PendingGlobalSave? = null

    /** 「全局配置」区宿主：探针结果到达后整块重建（组数与字段都可能变） */
    private val globalConfigHost = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }
    /** 「账号覆盖」摘要：账号数 / 带覆盖的账号数 */
    private val accountSummaryLabel = JBLabel()
    /** 「项目配置」只读值（与执行环境卡同源，只是另一处展示） */
    private val projectRootValue = wrappingValueLabel()
    private val projectPythonValue = wrappingValueLabel()
    private val projectSandboxValue = wrappingValueLabel()

    /**
     * 待落盘的全局配置组：控件列表 + 基线快照。
     * 读值推迟到防抖到期时做 —— 输入过程中的中间态（比如 JSON 还没补完括号）
     * 不该在每次按键时就判为非法。
     */
    private class PendingGlobalSave(
        val group: TaskLauncherService.GlobalConfigGroup,
        val controls: List<GlobalConfigEditor.FieldControl>,
        val base: Map<String, Any?>,
    )

    /** 状态栏右侧的「查看日志」入口：把 Run 工具窗口的控制台拉到前台 */
    private val viewLogButton = JButton(OkScriptToolkitBundle.message("taskLauncher.viewLog"))

    /** 任务进程与运行状态由项目级服务持有：工具窗关闭不影响后台任务 */
    private val taskRunner = TaskRunnerService.getInstance(project)

    // ── Toolbox（游戏连接 + 调试浮层，状态由 ToolboxService 持有；控件住运行器页）──
    private val connectGameButton = JButton(OkScriptToolkitBundle.message("toolbox.connectGame"))
    private val disconnectGameButton = JButton(OkScriptToolkitBundle.message("toolbox.disconnect"))
    private val gameStatusLabel = JBLabel()
    private val toolboxStatusLabel = JBLabel()
    private val overlayCheckBox = JCheckBox(OkScriptToolkitBundle.message("toolbox.overlay"))
    /** 防止 renderToolbox 回写复选框选中态时再次触发用户切换事件 */
    private var updatingOverlayCheckbox = false

    /** 参数树折叠组展开状态（跨重渲染保留，对齐 VSCode state.openConfigGroups） */
    private val openConfigGroups = HashSet<String>()
    /** 当前参数树的显隐/重复行同步器（loadTaskParams 装配，字段变更时先同步可见性再落盘） */
    private var visibilityRefresher: (() -> Unit)? = null
    private var currentRenderer: SchemaTreeRenderer? = null

    private val toolboxStateListener: (ToolboxService.ToolboxState, String) -> Unit = { state, _ ->
        SwingUtilities.invokeLater { renderToolbox(state) }
    }
    private val toolboxStatusListener: (String) -> Unit = { text ->
        SwingUtilities.invokeLater { toolboxStatusLabel.text = text }
    }
    private val runnerStateListener: (TaskRunnerService.ExecutorState) -> Unit = { state ->
        SwingUtilities.invokeLater { syncRunnerState(state) }
    }

    init {
        mainPanel = JPanel(BorderLayout())
        saveTimer.isRepeats = false
        saveTimer.addActionListener { flushPendingSave() }
        initUI()
        toolboxService.addStateListener(toolboxStateListener)
        toolboxService.addStatusListener(toolboxStatusListener)
        renderToolbox(toolboxService.loadState(detectProjectPath()))
        // 回放后台任务的既有输出并同步运行状态（工具窗重开场景）
        syncRunnerState(taskRunner.currentState())
        taskRunner.addStateListener(runnerStateListener)
        loadTasks()
    }

    /** 执行器是否服务于本窗口当前解析的项目（未启动视为是）。跨项目防护见 [ensureExecutor] */
    private fun executorMatchesProject(): Boolean =
        !taskRunner.isActive() || taskRunner.runningProjectDir == detectProjectPath()

    /** 展示用快照：跨项目时净化（不显示对方的 current/queue/paused）—— 对齐 VS Code projectMismatch 语义 */
    private fun runnerStateForDisplay(): TaskRunnerService.ExecutorState {
        val state = taskRunner.currentState()
        if (executorMatchesProject()) return state
        return state.copy(current = "", currentIsTrigger = false, onetimeQueue = emptyList(), paused = false)
    }

    /** 按执行器状态刷新状态栏、任务页胶囊、运行器页卡片与卡片列表（EDT） */
    private fun syncRunnerState(state: TaskRunnerService.ExecutorState) {
        // 跨项目防护（对齐 VS Code pushExecutorState / applySnapshot）：执行器属于
        // 别的项目时，它的当前任务 / 队列 / 触发集合 / 暂停态一律不进本窗口 ——
        // statusLabel 显示 mismatch 提示，其余按净化后的快照渲染（对齐 VS Code 的
        // projectMismatch 横幅 + 置空 current/queue/paused）。
        val mismatched = !executorMatchesProject()
        val visible = if (mismatched) runnerStateForDisplay() else state
        statusLabel.text = if (mismatched) {
            OkScriptToolkitBundle.message("taskLauncher.projectMismatch")
        } else {
            val statusText = when (visible.status) {
                "connecting" -> OkScriptToolkitBundle.message("taskLauncher.executorConnecting")
                "running" -> if (visible.paused) {
                    OkScriptToolkitBundle.message("taskLauncher.executorPaused")
                } else {
                    OkScriptToolkitBundle.message("taskLauncher.executorRunning", visible.enabledTriggers.size)
                }
                else -> visible.finishMessage ?: OkScriptToolkitBundle.message("taskLauncher.executorIdle")
            }
            // 控制命令失败时把错误拼在状态前（run_executor.py 在命令失败后不推状态，
            // 错误会一直保留到下一条状态快照到达）
            visible.controlError?.let { "$it — $statusText" } ?: statusText
        }
        // 净化后的快照喂给健康点：跨项目时不显示对方 current，但「有执行器在跑」如实呈现
        syncHealthBar(visible)
        // 别的项目的触发集合不能 adopt 成本窗口的勾选（enabledTriggers 会写进本项目的 tasks.json）
        if (!mismatched && visible.status == "running") syncTriggerCheckboxes(state)
        renderTaskStatuses(visible)
        // 状态 chip 与「启用/停用轮询」按钮文案跟着执行器状态走
        renderDetailHeader(detailTask, visible)
    }

    /**
     * 状态 → 各处健康点取色 + 运行器页状态卡 + 任务页胶囊（三处同源同色一次成线）。
     * [statusLabel] 承载瞬时消息（加载/入队/保存错误）；这里的胶囊与运行器状态文字
     * 只讲执行器本身的稳定状态，互不覆盖。
     */
    private fun syncHealthBar(state: TaskRunnerService.ExecutorState) {
        val dotColor = when {
            state.controlError != null -> TaskLauncherTheme.ERR
            state.status == "running" && state.paused -> TaskLauncherTheme.PAUSE
            state.status == "running" || state.status == "connecting" -> TaskLauncherTheme.RUN
            // 正常结束（退出码 0 或用户关闭无退出码）才是绿色；非 0 退出码 = 异常退出 → 红
            state.finishMessage != null && (state.exitCode == null || state.exitCode == 0) -> TaskLauncherTheme.OK
            state.finishMessage != null -> TaskLauncherTheme.ERR
            else -> UIUtil.getLabelDisabledForeground()
        }
        healthDot.color = dotColor
        executorPillDot.color = dotColor
        runnerDot.color = dotColor

        val statusText = when {
            !executorMatchesProject() -> OkScriptToolkitBundle.message("taskLauncher.projectMismatch")
            state.status == "connecting" -> OkScriptToolkitBundle.message("taskLauncher.executorConnecting")
            state.status == "running" && state.paused -> OkScriptToolkitBundle.message("taskLauncher.executorPaused")
            state.status == "running" -> OkScriptToolkitBundle.message("taskLauncher.executorRunning", state.enabledTriggers.size)
            else -> OkScriptToolkitBundle.message("taskLauncher.executorIdle")
        }
        executorPillLabel.text = statusText
        // 窄窗里胶囊文案会省略，完整状态留在 tooltip
        executorPillLabel.toolTipText = statusText
        runnerStatusLabel.text = statusText

        // 执行器生命周期按钮（运行器页；原工具栏在 IA 重设计后移除）
        val active = state.status == "running" || state.status == "connecting"
        runnerStartButton.isEnabled = !active
        runnerPauseButton.isEnabled = state.status == "running" && !state.paused
        runnerResumeButton.isEnabled = state.status == "running" && state.paused
        runnerStopCurrentButton.isEnabled = state.current.isNotEmpty()
        runnerCloseButton.isEnabled = active

        // 当前任务 chip（运行器状态卡）
        val running = state.status == "running" && state.current.isNotEmpty()
        if (running) {
            TaskLauncherTheme.styleChip(
                runnerCurrentChip,
                if (state.currentIsTrigger) TaskLauncherTheme.TRIGGER else TaskLauncherTheme.ONETIME,
                displayNameOf(state.current),
            )
            runnerCurrentChip.isVisible = true
            runnerCurrentChip.toolTipText = state.current
        } else {
            runnerCurrentChip.isVisible = false
            runnerCurrentChip.toolTipText = null
        }

        // 队列与轮询清单（chips 可点击 → 跳任务页定位）
        renderRunListPanel(
            runnerQueuePanel,
            state.onetimeQueue,
            TaskLauncherTheme.ONETIME,
            OkScriptToolkitBundle.message("taskLauncher.runnerQueueEmpty"),
        )
        renderRunListPanel(
            runnerPollingPanel,
            state.enabledTriggers,
            TaskLauncherTheme.TRIGGER,
            OkScriptToolkitBundle.message("taskLauncher.runnerPollingEmpty"),
        )
    }

    /** 运行器页「队列与轮询」的清单面板：chips 显示，点击跳任务页定位 */
    private fun renderRunListPanel(panel: JPanel, keys: List<String>, color: JBColor, emptyText: String) {
        panel.removeAll()
        if (keys.isEmpty()) {
            panel.add(mutedLabel(emptyText))
        } else {
            for (key in keys) {
                val name = displayNameOf(key)
                val chip = JBLabel(name)
                TaskLauncherTheme.styleChip(chip, color, name)
                chip.toolTipText = OkScriptToolkitBundle.message("taskLauncher.locateInTasks")
                chip.addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseClicked(e: java.awt.event.MouseEvent) {
                        locateTask(key)
                    }
                })
                panel.add(chip)
            }
        }
        panel.revalidate()
        panel.repaint()
    }

    /** 从运行器页跳回任务页：选中卡片并加载右侧详情与参数 */
    private fun locateTask(taskKey: String) {
        tabbedPane.selectedIndex = TAB_TASKS
        taskCardList.selectTask(taskKey)
        tasks.firstOrNull { taskKeyOf(it) == taskKey }?.let { loadTaskParams(it) }
    }

    /** 任务 key（module::Class）→ 显示名；未知任务退回 key 本身 */
    private fun displayNameOf(taskKey: String): String {
        schemas[taskKey]?.displayName?.let { return it }
        val cls = taskKey.substringAfter("::", taskKey)
        return tasks.firstOrNull { it.module == taskKey.substringBefore("::") && it.className == cls }
            ?.displayName ?: taskKey
    }

    /**
     * 布局：一级四页签（任务 / 配置 / 运行器 / 工具）+ 跨页签常驻的状态栏。
     *
     * IA 重设计：此前工具栏（启停/账号）、角色入口、工具箱条全挂在任务页四周，
     * 所有功能往一个页面堆，页面越来越臃肿。现在：
     * - 任务页 = 搜索 + 执行器胶囊 + 队列条 + 左列表右详情（描述给足空间、参数自适应）；
     * - 配置页 = 全局配置（内联卡片）/ 账号覆盖 / 项目配置，左侧二级导航；
     * - 运行器页 = 执行器状态、队列与轮询、执行环境、游戏连接；
     * - 工具页 = 各独立编辑器/工具窗口的入口。
     * 日志仍在 Run 工具窗口（见 [TaskRunnerService.showConsole]），运行器页放入口。
     */
    private fun initUI() {
        globalSaveTimer.isRepeats = false
        globalSaveTimer.addActionListener { flushPendingGlobalSave() }

        // ── 任务页 ─────────────────────────────────────────────
        wireTaskPageActions()
        buildTaskPagePanes()
        val taskPage = JPanel(BorderLayout())
        taskPage.isOpaque = false
        taskPageHost = taskPage
        // 先按「窄」建起来（首次布局前拿不到真实宽度），componentResized 时按真实宽度校正
        applyTaskPageOrientation(force = true)

        // ── 其余页签 ───────────────────────────────────────────
        tabbedPane = JBTabbedPane()
        tabbedPane.addTab(OkScriptToolkitBundle.message("taskLauncher.tabTasks"), taskPage)
        tabbedPane.addTab(OkScriptToolkitBundle.message("taskLauncher.tabConfig"), buildConfigPage())
        tabbedPane.addTab(OkScriptToolkitBundle.message("taskLauncher.tabRunner"), buildRunnerPage())
        tabbedPane.addTab(OkScriptToolkitBundle.message("taskLauncher.tabTools"), buildToolsPage())

        // ── 状态栏（跨页签常驻）：[健康点][瞬时消息][schema 警告] …… [进度条] ──
        // 用 BorderLayout 而不是 FlowLayout：消息长短不定，FlowLayout 在窄窗里
        // 会把后面的 schema 警告直接切掉；这里让消息自己省略、警告始终可见。
        val healthRow = JPanel(BorderLayout(6, 0))
        healthRow.isOpaque = false
        healthRow.add(healthDot, BorderLayout.WEST)
        healthRow.add(statusLabel, BorderLayout.CENTER)
        healthRow.add(schemaWarningLabel, BorderLayout.EAST)

        val statusBar = JPanel(BorderLayout(8, 0))
        statusBar.border = BorderFactory.createEmptyBorder(2, 4, 2, 4)
        statusBar.add(healthRow, BorderLayout.CENTER)
        progressBar.preferredSize = Dimension(90, 20)
        progressBar.isVisible = false
        val statusEast = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0))
        statusEast.isOpaque = false
        statusEast.add(progressBar)
        statusBar.add(statusEast, BorderLayout.EAST)

        mainPanel.layout = BorderLayout()
        mainPanel.add(tabbedPane, BorderLayout.CENTER)
        mainPanel.add(statusBar, BorderLayout.SOUTH)
        // 工具窗宽度变了就重算任务页分栏方向（只在跨过阈值那一次真的重建 Splitter）
        mainPanel.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) {
                applyTaskPageOrientation()
            }
        })

        showDetailPlaceholder()
    }

    /**
     * 任务页里**只该挂一次**的监听器。
     *
     * 分栏方向切换会重建 `Splitter`（两个半区面板本身复用，见 [applyTaskPageOrientation]），
     * 监听器若写在构建函数里就会重复挂 —— 点一次任务触发两次 `loadTaskParams`。
     */
    private fun wireTaskPageActions() {
        taskSearchField.textEditor.emptyText.setText(OkScriptToolkitBundle.message("taskLauncher.searchTasks"))
        taskSearchField.toolTipText = OkScriptToolkitBundle.message("taskLauncher.searchTasks")
        taskSearchField.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = applySearch()
            override fun removeUpdate(e: DocumentEvent?) = applySearch()
            override fun changedUpdate(e: DocumentEvent?) = applySearch()
        })
        detailActionButton.addActionListener { onDetailAction() }
        detailSyncButton.addActionListener { detailTask?.let { syncTaskDefault(it) } }
        detailResetButton.addActionListener { detailTask?.let { resetTaskDefault(it) } }
    }

    /**
     * 任务页的两个半区（只建一次，分栏方向切换时复用）：
     * 半区一 = 搜索 + 执行器胶囊 + 队列条 + 卡片列表；半区二 = 当前任务详情与参数。
     *
     * 工具条**分两行**：搜索框一行、状态胶囊 + 刷新一行。挤在一行时胶囊的长文案
     * 会按 preferred 宽度吃掉整行，`BorderLayout.CENTER` 里的搜索框被压到看不见。
     */
    private fun buildTaskPagePanes() {
        val refreshButton = JButton(AllIcons.Actions.Refresh).apply {
            toolTipText = OkScriptToolkitBundle.message("taskLauncher.refresh")
            isFocusable = false
            addActionListener { loadTasks() }
        }

        // 执行器状态胶囊：运行态摘要 + 点击跳运行器页（运行态的完整视图在那边）
        val openRunnerTab = object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                tabbedPane.selectedIndex = TAB_RUNNER
            }
        }
        val pill = JPanel(BorderLayout(6, 0)).apply {
            isOpaque = false
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border(), 1, true),
                BorderFactory.createEmptyBorder(3, 10, 3, 10),
            )
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            toolTipText = OkScriptToolkitBundle.message("taskLauncher.runnerTabHint")
            add(executorPillDot, BorderLayout.WEST)
            add(executorPillLabel, BorderLayout.CENTER)
            // 子标签会吃掉点击（Swing 事件不冒泡）⇒ 三处挂同一个监听
            addMouseListener(openRunnerTab)
            executorPillDot.addMouseListener(openRunnerTab)
            executorPillLabel.addMouseListener(openRunnerTab)
        }

        val toolbar = JPanel(BorderLayout(0, 4))
        toolbar.isOpaque = false
        toolbar.border = BorderFactory.createEmptyBorder(8, 8, 4, 8)
        toolbar.add(taskSearchField, BorderLayout.NORTH)
        val pillRow = JPanel(BorderLayout(6, 0))
        pillRow.isOpaque = false
        pillRow.add(pill, BorderLayout.CENTER)
        pillRow.add(refreshButton, BorderLayout.EAST)
        toolbar.add(pillRow, BorderLayout.SOUTH)

        val listScrollPane = JBScrollPane(taskCardList)
        listScrollPane.border = BorderFactory.createEmptyBorder()
        queueStrip.border = BorderFactory.createEmptyBorder(0, 8, 2, 8)

        val taskListPane = JPanel(BorderLayout())
        taskListPane.isOpaque = false
        taskListPane.add(toolbar, BorderLayout.NORTH)
        taskListPane.add(queueStrip, BorderLayout.SOUTH)
        // 队列条夹在工具栏与列表之间：外层再套一层 BorderLayout
        taskListHost.removeAll()
        taskListHost.isOpaque = false
        taskListHost.add(taskListPane, BorderLayout.NORTH)
        taskListHost.add(listScrollPane, BorderLayout.CENTER)

        val paramScrollPane = JBScrollPane(paramPanel)
        paramScrollPane.border = BorderFactory.createEmptyBorder()
        taskDetailPane.removeAll()
        taskDetailPane.isOpaque = false
        taskDetailPane.add(buildDetailHeader(), BorderLayout.NORTH)
        taskDetailPane.add(paramScrollPane, BorderLayout.CENTER)
        taskDetailPane.add(buildDetailFooter(), BorderLayout.SOUTH)
    }

    /**
     * 任务页分栏方向随宽度自适应。
     *
     * 这个工具窗默认停靠右侧、宽度只有三四百像素：左右分栏时左列表只占 42%（约 150px），
     * 任务卡与参数表单两边都不够用（用户反馈任务卡「露一半」）。窄时改成**上下分栏**
     * （列表在上、详情在下），两个半区都能用满整宽；把工具窗拉宽或弹出成独立窗口后再回到左右分栏。
     *
     * 只换 `Splitter`，两个半区面板复用 —— 重建面板会重复挂监听器。
     */
    private fun applyTaskPageOrientation(force: Boolean = false) {
        val host = taskPageHost ?: return
        val width = host.width
        if (!force && width <= 0) return
        val stacked = width < TASK_PAGE_STACK_WIDTH
        if (!force && host.componentCount > 0 && stacked == taskPageStacked) return
        taskPageStacked = stacked

        // 先摘掉旧 Splitter 的两个组件（setXxxComponent(null) 只做 remove，不会再 add）
        taskSplitter?.let {
            it.firstComponent = null
            it.secondComponent = null
        }
        host.removeAll()
        // Splitter 的 boolean 是「分栏轴是否沿高度」：true = 上下分栏，false = 左右分栏
        val splitter = com.intellij.openapi.ui.Splitter(stacked, 0.42f)
        splitter.firstComponent = taskListHost
        splitter.secondComponent = taskDetailPane
        splitter.setDividerWidth(4)
        taskSplitter = splitter
        host.add(splitter, BorderLayout.CENTER)
        host.revalidate()
        host.repaint()
    }

    // ── 左列表：搜索 / 队列条 / 悬停（表格渲染器已由 TaskCardListPanel 取代） ──

    /** 搜索框输入 → 卡片列表过滤（对齐 webview setSearch：搜索激活时忽略折叠） */
    private fun applySearch() {
        taskCardList.setSearch(taskSearchField.text)
    }

    /** 执行队列条：一次性队列非空时显示「执行队列」+ 任务名 chips（#queueStrip 等价物） */
    private fun renderQueueStrip(state: TaskRunnerService.ExecutorState) {
        queueStrip.removeAll()
        val queue = if (executorMatchesProject()) state.onetimeQueue else emptyList()
        if (queue.isEmpty()) {
            queueStrip.isVisible = false
        } else {
            val title = mutedLabel(OkScriptToolkitBundle.message("taskLauncher.queueTitle"))
            queueStrip.add(title)
            for (key in queue) {
                val chip = JBLabel(displayNameOf(key))
                TaskLauncherTheme.styleChip(chip, TaskLauncherTheme.ONETIME, displayNameOf(key))
                chip.toolTipText = key
                queueStrip.add(chip)
            }
            queueStrip.isVisible = true
        }
        queueStrip.revalidate()
        queueStrip.repaint()
    }

    /** 卡片悬停：800ms 后弹只读摘要；移出即取消（弹层单例在 showHoverPopup） */
    private fun scheduleTaskHover(task: TaskLauncherService.TaskInfo, anchor: JComponent) {
        hoverTimer?.stop()
        if (System.currentTimeMillis() < hoverSuppressUntil) return
        hoverTimer = Timer(hoverPopupDelayMs) { showTaskSummaryPopup(task, anchor) }.apply {
            isRepeats = false
            start()
        }
    }

    private fun cancelTaskHover() {
        hoverTimer?.stop()
    }

    // ── 右详情区：任务头 ──────────────────────────────────────────────

    /**
     * 详情区头部：任务名 + 类型 chip + 状态 chip + 主操作按钮，下面是类名·模块与
     * **完整描述**（IA 重设计：描述从卡片挪进详情区，给足空间完整换行）。
     * 触发任务的主操作是「启用/停用轮询」（等价卡片勾选框），一次性任务是「运行任务」。
     */
    private fun buildDetailHeader(): JPanel {
        detailTitle.font = detailTitle.font.deriveFont(Font.BOLD)
        // 名称拿剩余宽度（放不下就省略），两个 chip 拿 preferred 宽度永不裁切 ——
        // FlowLayout 在窄窗里会把后面的 chip 直接切掉（与任务卡同款坑）。
        val chips = JPanel(WrapLayout(FlowLayout.RIGHT, 6, 0))
        chips.isOpaque = false
        chips.add(detailKindChip)
        chips.add(detailStateChip)
        val titleRow = JPanel(BorderLayout(6, 0))
        titleRow.isOpaque = false
        titleRow.add(detailTitle, BorderLayout.CENTER)
        titleRow.add(chips, BorderLayout.EAST)

        // 监听器在 wireTaskPageActions() 里挂一次（本函数会随分栏方向切换被重调）
        val actionRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        actionRow.isOpaque = false
        actionRow.add(detailActionButton)

        // 纵向：标题行 → 类名·模块 → 主操作 → 描述（描述随宽度完整换行，高度自适应）
        val identity = JPanel()
        identity.layout = BoxLayout(identity, BoxLayout.Y_AXIS)
        identity.isOpaque = false
        identity.add(titleRow)
        detailClassLine.alignmentX = java.awt.Component.LEFT_ALIGNMENT
        identity.add(detailClassLine)
        actionRow.alignmentX = java.awt.Component.LEFT_ALIGNMENT
        identity.add(actionRow)
        detailDescription.alignmentX = java.awt.Component.LEFT_ALIGNMENT
        identity.add(detailDescription)

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(8, 10, 6, 10)
            add(identity, BorderLayout.CENTER)
        }
    }

    /** 详情页脚：⇄/⟲ 快照操作（IA 重设计后从卡片挪来，作用于当前选中任务）+ 自动保存提示 */
    private fun buildDetailFooter(): JPanel {
        // 监听器在 wireTaskPageActions() 里挂一次（本函数会随分栏方向切换被重调）
        val hint = SchemaFieldUi.WrappingDescription(OkScriptToolkitBundle.message("taskLauncher.autosaveHint"))
        val buttons = JPanel(WrapLayout(FlowLayout.LEFT, 6, 4))
        buttons.isOpaque = false
        buttons.add(detailSyncButton)
        buttons.add(detailResetButton)
        // 按钮拿 preferred 宽度、提示文字拿剩余宽度：窄窗里提示换行，按钮不会被挤掉
        val row = JPanel(BorderLayout(6, 0))
        row.isOpaque = false
        row.add(buttons, BorderLayout.WEST)
        row.add(hint, BorderLayout.CENTER)
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor.border()),
                BorderFactory.createEmptyBorder(2, 10, 6, 10),
            )
            add(row, BorderLayout.CENTER)
        }
    }

    /** 类型/状态 chip：细圆角描边 + 同色文字（随主题） */
    private fun styleChip(label: JBLabel, color: JBColor, text: String) {
        label.text = text
        label.foreground = color
        label.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(color, 1, true),
            BorderFactory.createEmptyBorder(0, 6, 0, 6),
        )
    }

    /** 未选中任务：右栏给一条明确的占位提示（运行态整体视图在「运行器」页） */
    private fun showDetailPlaceholder() {
        paramPanel.removeAll()
        paramFields.clear()
        visibilityRefresher = null
        currentRenderer = null
        detailDescriptionFor = null
        detailDescription.text = ""
        detailClassLine.text = ""
        detailSyncButton.isEnabled = false
        detailResetButton.isEnabled = false
        val hint = mutedLabel(OkScriptToolkitBundle.message("taskLauncher.detailPlaceholder"))
        hint.border = BorderFactory.createEmptyBorder(12, 2, 12, 2)
        paramPanel.add(hint, GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            gridwidth = 2
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.NORTH
            insets = Insets(8, 4, 4, 4)
        })
        paramPanel.add(JPanel().apply { isOpaque = false }, GridBagConstraints().apply {
            gridx = 0
            gridy = 1
            gridwidth = 2
            weighty = 1.0
            fill = GridBagConstraints.BOTH
        })
        paramPanel.revalidate()
        paramPanel.repaint()
        renderDetailHeader(null)
    }

    /** 弱化文字（--text-muted 语义：只用于辅助信息，不用于正文） */
    private fun mutedLabel(text: String): JBLabel {
        val label = JBLabel(text)
        label.foreground = UIUtil.getLabelDisabledForeground()
        return label
    }

    /**
     * 弱化**可换行**提示（长句专用）。
     *
     * [mutedLabel] 是单行标签 —— 窄工具窗里会被硬裁剪（正是「东西看不全」的来源之一）；
     * 长提示一律走这个：按宽度换行、高度自适应。
     */
    private fun mutedHint(text: String): JTextArea = SchemaFieldUi.WrappingDescription(text)

    private fun openAccountEditor() {
        val projectDir = checkedProjectPath(statusLabel, "taskLauncher.noProject") ?: return
        if (displayedProjectDir.isNotBlank() && displayedProjectDir != projectDir) {
            loadTasks()
            return
        }
        if (!multiAccountInfo.hasStoreModule) {
            JOptionPane.showMessageDialog(
                mainPanel,
                OkScriptToolkitBundle.message("taskLauncher.accountNoEditor"),
                OkScriptToolkitBundle.message("taskLauncher.accounts"),
                JOptionPane.WARNING_MESSAGE,
            )
            return
        }
        accountEditor?.takeIf { it.isOpen() }?.let {
            it.focus()
            return
        }
        accountEditor = AccountEditorDialog(
            parent = mainPanel,
            project = project,
            service = accountStoreService,
            projectDir = projectDir,
            info = multiAccountInfo,
            schemas = schemas,
            globalGroups = globalConfigGroups,
            onAccountListSaved = { loadTasks() },
        ).also { it.open() }
    }

    /** 任务卡悬停弹出（#9 任务卡 hover 概览）：hover 800ms 弹只读摘要（计时在 scheduleTaskHover）。
     *  弹层单实例：新弹层显示前先 cancel 旧的，避免多张卡间停留时叠加。 */
    private var activeHoverPopup: com.intellij.openapi.ui.popup.JBPopup? = null

    /** 显示悬停弹层（单实例互斥：先取消上一个） */
    private fun showHoverPopup(builder: () -> com.intellij.openapi.ui.popup.JBPopup) {
        activeHoverPopup?.takeIf { !it.isDisposed }?.cancel()
        activeHoverPopup = builder()
    }

    /** 任务摘要弹层：kind / 参数改动量 / schema 错误（只读，无交互按钮）。
     *  [anchorCard] 为悬停的任务卡，弹层锚在卡片左侧。参数不能叫 anchor ——
     *  会遮蔽下方 GridBagConstraints.apply{} 的 anchor 字段（与 sectionCard 的 fill 同款坑）。 */
    private fun showTaskSummaryPopup(task: TaskLauncherService.TaskInfo, anchorCard: JComponent) {
        val key = taskKeyOf(task)
        val schema = schemas[key]
        val content = JPanel(GridBagLayout())
        content.isOpaque = false
        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            anchor = GridBagConstraints.WEST
            insets = Insets(2, 10, 2, 10)
        }
        val kind = taskKindOf(task)
        content.add(
            JBLabel(
                OkScriptToolkitBundle.message(
                    if (kind == TaskRowState.TRIGGER) "taskLauncher.triggerTask" else "taskLauncher.oneTimeTask",
                ),
            ),
            gbc,
        )
        gbc.gridy++
        if (schema == null) {
            content.add(mutedLabel(OkScriptToolkitBundle.message("taskLauncher.schemaNotProbed")), gbc)
        } else if (schema.broken) {
            val error = JBLabel(OkScriptToolkitBundle.message("taskLauncher.schemaBrokenDetail", schema.error ?: ""))
            error.foreground = TaskLauncherTheme.ERR
            content.add(error, gbc)
        } else {
            val edited = taskService.getTaskConfig(key, taskDataRoot()).params?.size ?: 0
            content.add(
                mutedLabel(
                    OkScriptToolkitBundle.message("taskLauncher.schemaFieldCount", edited, schema.fields.size),
                ),
                gbc,
            )
        }
        showHoverPopup {
            JBPopupFactory.getInstance()
                .createComponentPopupBuilder(content, anchorCard)
                .setTitle(task.displayName)
                .setRequestFocus(false)
                .setResizable(false)
                .setMovable(false)
                .setCancelOnClickOutside(true)
                .createPopup()
                .also { popup ->
                    // 弹层放在卡片左侧（对齐 webview：绝不压到卡片）；左侧空间不足时直接不弹
                    val bounds = anchorCard.graphicsConfiguration?.bounds
                    val onScreen = try {
                        anchorCard.locationOnScreen
                    } catch (_: java.awt.IllegalComponentStateException) {
                        popup.cancel()
                        return@also
                    }
                    val popupWidth = popup.content.preferredSize.width + 40
                    if (bounds == null || onScreen.x - bounds.x < popupWidth + 12) {
                        popup.cancel() // 没有左侧空间时保留点击详情入口，不遮住任务卡
                    } else {
                        popup.show(RelativePoint(anchorCard, java.awt.Point(-popupWidth - 12, 0)))
                    }
                }
        }
    }

    /** 字段值截断显示（弹层摘要用；不解析语义，只转文本） */
    private fun formatFieldValue(raw: Any?): String {
        if (raw == null) return "—"
        val text = raw.toString()
        return if (text.length > 40) text.take(40) + "…" else text
    }

    private fun renderDetailHeader(
        task: TaskLauncherService.TaskInfo?,
        state: TaskRunnerService.ExecutorState = taskRunner.currentState(),
    ) {
        detailTask = task
        val visible = task != null
        detailTitle.isVisible = visible
        detailKindChip.isVisible = visible
        detailStateChip.isVisible = visible
        detailActionButton.isVisible = visible
        if (task == null) return

        val isTrigger = taskKindOf(task) == TaskRowState.TRIGGER
        detailTitle.text = task.displayName
        styleChip(
            detailKindChip,
            if (isTrigger) COLOR_TRIGGER else TaskLauncherTheme.ONETIME,
            OkScriptToolkitBundle.message(
                if (isTrigger) "taskLauncher.triggerTask" else "taskLauncher.oneTimeTask",
            ),
        )
        val cell = statusCellFor(task, state)
        styleChip(detailStateChip, colorForTone(cell.tone), cell.text)
        detailActionButton.text = when {
            !isTrigger -> OkScriptToolkitBundle.message("taskLauncher.run")
            enabledTriggers.contains(taskKeyOf(task)) -> OkScriptToolkitBundle.message("taskLauncher.disableTrigger")
            else -> OkScriptToolkitBundle.message("taskLauncher.enableTrigger")
        }
    }

    /** 详情区主操作：触发任务 = 切换入列轮询，一次性任务 = 入队执行一次 */
    private fun onDetailAction() {
        val task = detailTask ?: return
        if (taskKindOf(task) == TaskRowState.TRIGGER) {
            setTriggerEnabled(task, !enabledTriggers.contains(taskKeyOf(task)))
            renderDetailHeader(task)
        } else {
            enqueueTask(task)
        }
    }

    // ── 配置页 / 运行器页 / 工具页（IA 重设计）───────────────────────
    // 原先全挂在任务页四周的执行器启停、全局配置、账号、游戏连接、角色入口，
    // 按「先分页面、再分组、再展示内容」拆到三个页签。状态来源不变
    // （TaskRunnerService / ToolboxService / 探针结果），只是换了住处。

    /**
     * 运行器页：执行器状态 / 队列与轮询 / 执行环境 / 游戏连接，两列卡片网格。
     * 执行器生命周期（启动 / 暂停 / 继续 / 停止当前 / 关闭）**只在这里** ——
     * 任务页只留一枚点击跳转的状态胶囊，避免同一动作出现第二条入口。
     */
    private fun buildRunnerPage(): JPanel {
        runnerStartButton.addActionListener { startExecutorManual() }
        runnerPauseButton.addActionListener { sendControlCommand("pause") }
        runnerResumeButton.addActionListener { sendControlCommand("resume") }
        runnerStopCurrentButton.addActionListener { stopCurrentTask() }
        runnerCloseButton.addActionListener { closeExecutor() }

        val statusCard = pageCard(OkScriptToolkitBundle.message("taskLauncher.runnerExecStatus")) { body ->
            val head = JPanel(WrapLayout(FlowLayout.LEFT, 6, 0))
            head.isOpaque = false
            head.add(runnerDot)
            head.add(runnerStatusLabel)
            head.add(runnerCurrentChip)
            body.add(head)

            val buttons = JPanel(WrapLayout(FlowLayout.LEFT, 4, 0))
            buttons.isOpaque = false
            buttons.add(runnerStartButton)
            buttons.add(runnerPauseButton)
            buttons.add(runnerResumeButton)
            buttons.add(runnerStopCurrentButton)
            buttons.add(runnerCloseButton)
            viewLogButton.toolTipText = OkScriptToolkitBundle.message("taskLauncher.viewLogHint")
            viewLogButton.addActionListener { taskRunner.showConsole() }
            buttons.add(viewLogButton)
            body.add(buttons)
        }

        val queueCard = pageCard(OkScriptToolkitBundle.message("taskLauncher.runnerQueuePolling")) { body ->
            body.add(mutedLabel(OkScriptToolkitBundle.message("taskLauncher.runCenter.queue")))
            body.add(runnerQueuePanel)
            body.add(mutedLabel(OkScriptToolkitBundle.message("taskLauncher.runCenter.triggers")))
            body.add(runnerPollingPanel)
        }

        val settingsButton = JButton(OkScriptToolkitBundle.message("taskLauncher.openSettings"))
        settingsButton.addActionListener { openSettings() }
        val rescanButton = JButton(OkScriptToolkitBundle.message("taskLauncher.rescanTasks"))
        rescanButton.addActionListener { loadTasks() }
        val envCard = pageCard(OkScriptToolkitBundle.message("taskLauncher.runnerEnv")) { body ->
            body.add(envRow(OkScriptToolkitBundle.message("taskLauncher.envProjectRoot"), envProjectRootValue))
            body.add(envRow(OkScriptToolkitBundle.message("taskLauncher.envPython"), envPythonValue))
            body.add(envRow(OkScriptToolkitBundle.message("taskLauncher.envSandbox"), envSandboxValue))
            body.add(envRow(OkScriptToolkitBundle.message("taskLauncher.envSnapshots"), envSnapshotsValue))
            val buttons = JPanel(WrapLayout(FlowLayout.LEFT, 4, 0))
            buttons.isOpaque = false
            buttons.add(settingsButton)
            buttons.add(rescanButton)
            body.add(buttons)
        }

        // 游戏连接：控件与监听器原样搬自旧工具箱条，语义零改动（状态仍由 ToolboxService 持有）
        connectGameButton.addActionListener { connectGame() }
        disconnectGameButton.addActionListener { disconnectGame() }
        overlayCheckBox.toolTipText = OkScriptToolkitBundle.message("toolbox.overlayHint")
        overlayCheckBox.addActionListener {
            if (updatingOverlayCheckbox) return@addActionListener
            val projectDir = checkedProjectPath(toolboxStatusLabel, "toolbox.noProject")
            if (projectDir == null) {
                updatingOverlayCheckbox = true
                overlayCheckBox.isSelected = !overlayCheckBox.isSelected
                updatingOverlayCheckbox = false
                return@addActionListener
            }
            toolboxService.setOverlayEnabled(projectDir, detectPythonPath(), overlayCheckBox.isSelected)
        }
        gameStatusLabel.foreground = UIUtil.getContextHelpForeground()
        toolboxStatusLabel.foreground = UIUtil.getContextHelpForeground()
        val gameCard = pageCard(OkScriptToolkitBundle.message("taskLauncher.runnerGame")) { body ->
            val buttons = JPanel(WrapLayout(FlowLayout.LEFT, 4, 0))
            buttons.isOpaque = false
            buttons.add(connectGameButton)
            buttons.add(disconnectGameButton)
            body.add(buttons)
            body.add(gameStatusLabel)
            body.add(overlayCheckBox)
            body.add(toolboxStatusLabel)
        }

        refreshEnvironmentInfo()
        // 单列而不是两列网格：工具窗默认只有三四百像素宽，两列时每张卡不到 180px，
        // 卡里的「键 = 值」行与按钮全被挤坏。
        return singleColumnPage(listOf(statusCard, queueCard, envCard, gameCard))
    }

    /**
     * 配置页：全局配置 / 账号覆盖 / 项目配置，左侧二级导航 + 右侧内容（CardLayout）。
     *
     * 全局配置改成**内联卡片 + 改动即存**（400ms 防抖，与任务参数同一套自动保存语义）——
     * 旧路径是「点组行弹对话框」，多一层操作、也看不到别的组。
     */
    private fun buildConfigPage(): JPanel {
        val cards = JPanel(java.awt.CardLayout())
        cards.isOpaque = false
        cards.add(buildGlobalConfigSection(), NAV_GLOBAL)
        cards.add(buildAccountSection(), NAV_ACCOUNT)
        cards.add(buildProjectSection(), NAV_PROJECT)

        val nav = JList(
            arrayOf(
                OkScriptToolkitBundle.message("taskLauncher.configNavGlobal"),
                OkScriptToolkitBundle.message("taskLauncher.configNavAccount"),
                OkScriptToolkitBundle.message("taskLauncher.configNavProject"),
            ),
        )
        nav.selectionMode = ListSelectionModel.SINGLE_SELECTION
        nav.selectedIndex = 0
        nav.border = BorderFactory.createEmptyBorder(8, 6, 8, 6)
        nav.addListSelectionListener { event ->
            if (event.valueIsAdjusting) return@addListSelectionListener
            val index = nav.selectedIndex.coerceIn(NAV_KEYS.indices)
            (cards.layout as java.awt.CardLayout).show(cards, NAV_KEYS[index])
        }
        // 固定宽度的二级导航，不用比例分栏：比例在窄工具窗里会把导航压到 90px 以下
        // （中文导航项放不下），在宽窗里又会白占几百像素；固定宽度两头都合适。
        val navScroll = JBScrollPane(nav)
        navScroll.border = BorderFactory.createMatteBorder(0, 0, 0, 1, JBColor.border())
        navScroll.preferredSize = Dimension(CONFIG_NAV_WIDTH, 0)
        val navHost = JPanel(BorderLayout())
        navHost.isOpaque = false
        navHost.add(navScroll, BorderLayout.CENTER)

        val page = JPanel(BorderLayout())
        page.isOpaque = false
        page.add(navHost, BorderLayout.WEST)
        page.add(cards, BorderLayout.CENTER)
        return page
    }

    /**
     * 工具页：各独立编辑器标签页 / 工具窗口的入口清单。
     *
     * 这些功能都**不住**任务启动器工具窗里（角色管理是编辑器标签页，模板 / 临时截图 /
     * 模板素材各自是独立工具窗口），这里只放入口，不复制它们的界面。
     */
    private fun buildToolsPage(): JPanel {
        val card = pageCard(OkScriptToolkitBundle.message("taskLauncher.tabTools")) { body ->
            body.add(mutedHint(OkScriptToolkitBundle.message("taskLauncher.toolsHint")))
            body.add(
                toolRow(
                    OkScriptToolkitBundle.message("taskLauncher.toolCharacterTitle"),
                    OkScriptToolkitBundle.message("taskLauncher.toolCharacterDesc"),
                ) { openCharacterManager(project) },
            )
            body.add(
                toolRow(
                    OkScriptToolkitBundle.message("taskLauncher.toolTemplatesTitle"),
                    OkScriptToolkitBundle.message("taskLauncher.toolTemplatesDesc"),
                ) { showToolWindow("ok-script Templates") },
            )
            body.add(
                toolRow(
                    OkScriptToolkitBundle.message("taskLauncher.toolTempShotsTitle"),
                    OkScriptToolkitBundle.message("taskLauncher.toolTempShotsDesc"),
                ) { showToolWindow("ok-script Temp Shots") },
            )
            body.add(
                toolRow(
                    OkScriptToolkitBundle.message("taskLauncher.toolAssetsTitle"),
                    OkScriptToolkitBundle.message("taskLauncher.toolAssetsDesc"),
                ) { showToolWindow("ok-script Assets") },
            )
        }
        return singleColumnPage(listOf(card))
    }

    // ── 页签布局小件 ────────────────────────────────────────────────

    /** 页签内的卡片（IA 重设计的 rcard / gcard 等价物）：圆角描边 + 加粗标题 + 纵向内容体 */
    private fun pageCard(title: String, build: (body: JPanel) -> Unit): JPanel {
        val body = JPanel()
        body.layout = BoxLayout(body, BoxLayout.Y_AXIS)
        body.isOpaque = false
        build(body)
        for (child in body.components) {
            (child as? JComponent)?.let {
                // 显式 setter：Component 静态类型上 alignmentX 是只读合成属性
                it.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT)
                it.setAlignmentY(java.awt.Component.TOP_ALIGNMENT)
            }
        }
        val titleLabel = JBLabel(title)
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD)
        return JPanel(BorderLayout(0, 6)).apply {
            isOpaque = false
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border(), 1, true),
                BorderFactory.createEmptyBorder(8, 12, 10, 12),
            )
            add(titleLabel, BorderLayout.NORTH)
            add(body, BorderLayout.CENTER)
        }
    }

    /** 两列卡片网格：宽度均分、卡片高度随内容；内容超高时由外层滚动条接管 */
    private fun pageGrid(cards: List<JPanel>): JPanel {
        val grid = JPanel(GridBagLayout())
        grid.isOpaque = false
        grid.border = BorderFactory.createEmptyBorder(12, 14, 12, 14)
        cards.forEachIndexed { index, card ->
            grid.add(card, GridBagConstraints().apply {
                gridx = index % 2
                gridy = index / 2
                weightx = 0.5
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.NORTHWEST
                insets = Insets(0, 0, 12, 12)
            })
        }
        // 底部弹簧：卡片顶对齐，不被纵向拉伸
        grid.add(JPanel().apply { isOpaque = false }, GridBagConstraints().apply {
            gridx = 0
            gridy = (cards.size + 1) / 2
            gridwidth = 2
            weighty = 1.0
            fill = GridBagConstraints.BOTH
        })
        return grid
    }

    /** 单列页面（配置页 / 工具页用）：卡片纵向排列 + 外边距，内容超高时可滚 */
    private fun singleColumnPage(cards: List<JPanel>): JPanel {
        val column = JPanel(GridBagLayout())
        column.isOpaque = false
        column.border = BorderFactory.createEmptyBorder(12, 14, 12, 14)
        cards.forEachIndexed { index, card ->
            column.add(card, GridBagConstraints().apply {
                gridx = 0
                gridy = index
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.NORTHWEST
                insets = Insets(0, 0, 10, 0)
            })
        }
        column.add(JPanel().apply { isOpaque = false }, GridBagConstraints().apply {
            gridx = 0
            gridy = cards.size
            weighty = 1.0
            fill = GridBagConstraints.BOTH
        })
        return column
    }

    /** 内容装进滚动面板（页签内容超过窗口高度时可滚，横向永不出现滚动条） */
    private fun scrollablePage(content: JComponent): JPanel {
        val scroll = JBScrollPane(content)
        scroll.border = BorderFactory.createEmptyBorder()
        scroll.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        val page = JPanel(BorderLayout())
        page.isOpaque = false
        page.add(scroll, BorderLayout.CENTER)
        return page
    }

    /** 可换行的弱化值标签：长路径不截断，等宽字体便于逐字符比对 */
    private fun wrappingValueLabel(): JTextArea = SchemaFieldUi.WrappingDescription("").apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
    }

    /** 「键 + 值」行（执行环境 / 项目配置用）：键弱化小字在上，值可换行在下 */
    private fun envRow(key: String, value: JTextArea): JPanel {
        val row = JPanel(BorderLayout(0, 2))
        row.isOpaque = false
        row.add(mutedLabel(key), BorderLayout.NORTH)
        row.add(value, BorderLayout.CENTER)
        return row
    }

    private fun setEnvValue(area: JTextArea, text: String) {
        area.text = text
        area.toolTipText = text
    }

    /** 工具页入口行：标题 + 说明 + 右侧「打开」按钮 */
    private fun toolRow(title: String, description: String, action: () -> Unit): JPanel {
        val titleLabel = JBLabel(title)
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD)
        val text = JPanel()
        text.layout = BoxLayout(text, BoxLayout.Y_AXIS)
        text.isOpaque = false
        text.add(titleLabel)
        text.add(mutedHint(description))

        val open = JButton(OkScriptToolkitBundle.message("taskLauncher.toolOpen"))
        open.addActionListener { action() }
        return JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor.border()),
                BorderFactory.createEmptyBorder(6, 2, 6, 2),
            )
            add(text, BorderLayout.CENTER)
            add(open, BorderLayout.EAST)
        }
    }

    /** 打开本插件的设置页（执行环境卡与项目配置区的「打开设置…」共用） */
    private fun openSettings() {
        com.intellij.openapi.options.ShowSettingsUtil.getInstance()
            .showSettingsDialog(project, com.alicejump.okscripttoolkit.settings.OkScriptToolkitConfigurable::class.java)
    }

    /** 把某个独立工具窗口拉到前台（工具页入口用；窗口不存在时静默忽略） */
    private fun showToolWindow(id: String) {
        com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow(id)?.show()
    }

    // ── 配置页：全局配置（内联卡片 + 改动即存）──────────────────────

    /** 「全局配置」区整块重建：每组一张卡片（组数与字段都可能随探针结果变化） */
    private fun renderGlobalConfig() {
        globalConfigHost.removeAll()
        if (globalConfigGroups.isEmpty()) {
            globalConfigHost.add(mutedHint(OkScriptToolkitBundle.message("taskLauncher.configGlobalEmpty")))
        } else {
            for (group in globalConfigGroups) globalConfigHost.add(globalConfigCard(group))
        }
        for (child in globalConfigHost.components) {
            (child as? JComponent)?.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT)
        }
        globalConfigHost.revalidate()
        globalConfigHost.repaint()
    }

    private fun buildGlobalConfigSection(): JPanel {
        renderGlobalConfig()
        val card = pageCard(OkScriptToolkitBundle.message("taskLauncher.configNavGlobal")) { body ->
            body.add(mutedHint(OkScriptToolkitBundle.message("taskLauncher.configGlobalHint")))
            body.add(globalConfigHost)
        }
        return singleColumnPage(listOf(card))
    }

    /**
     * 全局配置组内联卡片：字段控件 + 「改动即存」。
     * 可见性规则复用对话框路径的 [GlobalConfigEditor.installVisibility]（布尔子项跟随父级），
     * 与任务参数面板同款语义 —— 两条路径不该有两套显隐规则。
     */
    private fun globalConfigCard(group: TaskLauncherService.GlobalConfigGroup): JPanel {
        val existing = taskService.loadGlobalConfigs(taskDataRoot())[group.name].orEmpty()
        // 控件列表要先建好才能被自己的回调捕获（回调触发时列表已填满），故用可变列表分两步填
        val controls = mutableListOf<GlobalConfigEditor.FieldControl>()
        for (field in group.fields) {
            val value = if (existing.containsKey(field.key)) existing[field.key] else field.value ?: field.default
            controls += GlobalConfigEditor.makeControl(field, value, project) {
                scheduleGlobalSave(group, controls, existing)
            }
        }
        val form = JPanel(GridBagLayout())
        form.isOpaque = false
        val rowsByKey = linkedMapOf<String, JPanel>()
        var rowIndex = 0
        for (control in controls) {
            val row = SchemaFieldUi.row(control.field, control.component)
            rowsByKey[control.field.key] = row
            form.add(row, GridBagConstraints().apply {
                gridx = 0
                gridy = rowIndex++
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.NORTHWEST
                insets = Insets(1, 8, 1, 8)
            })
        }
        GlobalConfigEditor.installVisibility(controls, rowsByKey, form)
        return pageCard(group.displayName ?: group.name) { body -> body.add(form) }
    }

    /** 内联卡片改动：登记待存快照并重启 400ms 防抖（连续输入只落一次盘） */
    private fun scheduleGlobalSave(
        group: TaskLauncherService.GlobalConfigGroup,
        controls: List<GlobalConfigEditor.FieldControl>,
        base: Map<String, Any?>,
    ) {
        pendingGlobalSave = PendingGlobalSave(group, controls, base)
        globalSaveTimer.restart()
    }

    /**
     * 防抖到期：读控件 → 校验 → 落盘 → 推送执行器。
     * 读值推迟到这一刻才做 —— 输入过程中的中间态（比如 JSON 还没补完括号）
     * 不该在每次按键时就判为非法。值非法时不落盘，只在状态栏提示。
     */
    private fun flushPendingGlobalSave() {
        val pending = pendingGlobalSave ?: return
        pendingGlobalSave = null
        val name = pending.group.displayName ?: pending.group.name

        val values = pending.base.toMutableMap()
        var invalid: String? = null
        for (control in pending.controls) {
            try {
                values[control.field.key] = control.read()
            } catch (_: IllegalArgumentException) {
                invalid = control.field.displayKey ?: control.field.key
                break
            }
        }
        if (invalid != null) {
            statusLabel.text = OkScriptToolkitBundle.message("taskLauncher.gconfigInvalidValue", invalid)
            return
        }

        val root = taskDataRoot()
        if (values == taskService.loadGlobalConfigs(root)[pending.group.name].orEmpty()) return
        try {
            taskService.saveGlobalConfigGroup(pending.group.name, values, root)
            pushGlobalSnapshot(taskService.loadGlobalConfigs(root), root)
            statusLabel.text = OkScriptToolkitBundle.message("taskLauncher.gconfigSaved", name)
        } catch (e: Exception) {
            LOG.warn("Failed to save global configuration", e)
            JOptionPane.showMessageDialog(
                mainPanel,
                OkScriptToolkitBundle.message("taskLauncher.gconfigSaveFailed", e.message ?: ""),
                name,
                JOptionPane.ERROR_MESSAGE,
            )
        }
    }

    // ── 配置页：账号覆盖 / 项目配置 ─────────────────────────────────

    /** 账号覆盖区：账号注册表摘要 + 打开账号编辑器（多账号存储由 AccountEditorDialog 承载） */
    private fun buildAccountSection(): JPanel {
        val openButton = JButton(OkScriptToolkitBundle.message("taskLauncher.accounts"))
        openButton.addActionListener { openAccountEditor() }
        accountSummaryLabel.foreground = UIUtil.getContextHelpForeground()
        val card = pageCard(OkScriptToolkitBundle.message("taskLauncher.configNavAccount")) { body ->
            body.add(mutedHint(OkScriptToolkitBundle.message("taskLauncher.configAccountHint")))
            body.add(accountSummaryLabel)
            body.add(openButton)
        }
        return singleColumnPage(listOf(card))
    }

    /** 项目配置区：只读摘要（值来自设置 / 探针）+ 打开设置入口 */
    private fun buildProjectSection(): JPanel {
        val settingsButton = JButton(OkScriptToolkitBundle.message("taskLauncher.openSettings"))
        settingsButton.addActionListener { openSettings() }
        val card = pageCard(OkScriptToolkitBundle.message("taskLauncher.configNavProject")) { body ->
            body.add(mutedHint(OkScriptToolkitBundle.message("taskLauncher.configProjectHint")))
            body.add(envRow(OkScriptToolkitBundle.message("taskLauncher.envProjectRoot"), projectRootValue))
            body.add(envRow(OkScriptToolkitBundle.message("taskLauncher.envPython"), projectPythonValue))
            body.add(envRow(OkScriptToolkitBundle.message("taskLauncher.envSandbox"), projectSandboxValue))
            body.add(settingsButton)
        }
        return singleColumnPage(listOf(card))
    }

    /** 执行环境卡与项目配置区的只读取值（设置 / 探针变化后刷新） */
    private fun refreshEnvironmentInfo() {
        val projectDir = detectProjectPath()
        val unset = OkScriptToolkitBundle.message("taskLauncher.envUnset")
        val python = detectPythonPath()
        val sandbox = if (projectDir.isBlank()) unset else RunDir.forProject(projectDir)
        val snapshots = if (tasks.isEmpty()) {
            unset
        } else {
            OkScriptToolkitBundle.message(
                "taskLauncher.envSnapshotsSummary",
                tasks.size,
                tasks.count { snapshotDiffersFromFactory(taskKeyOf(it)) },
            )
        }
        setEnvValue(envProjectRootValue, projectDir.ifBlank { unset })
        setEnvValue(envPythonValue, python)
        setEnvValue(envSandboxValue, sandbox)
        setEnvValue(envSnapshotsValue, snapshots)
        setEnvValue(projectRootValue, projectDir.ifBlank { unset })
        setEnvValue(projectPythonValue, python)
        setEnvValue(projectSandboxValue, sandbox)
    }

    /** 账号覆盖区摘要：账号总数 / 带覆盖的账号数（探针完成后刷新） */
    private fun refreshAccountSummary() {
        val info = multiAccountInfo
        accountSummaryLabel.text = when {
            !info.hasStoreModule -> OkScriptToolkitBundle.message("taskLauncher.configAccountUnavailable")
            info.accountCount == null -> OkScriptToolkitBundle.message("taskLauncher.configAccountHint")
            else -> OkScriptToolkitBundle.message(
                "taskLauncher.configAccountSummary",
                info.accountCount,
                info.overrideAccounts ?: 0,
            )
        }
    }

    /** 当前工具箱状态渲染到工具箱条（EDT 调用） */
    private fun renderToolbox(state: ToolboxService.ToolboxState) {
        disconnectGameButton.isVisible = state.game != null
        gameStatusLabel.text = if (state.game != null) {
            val game = state.game!!
            OkScriptToolkitBundle.message(
                "toolbox.gameConnected",
                game.title.ifEmpty { game.hwnd.toString() },
                game.pid,
            )
        } else {
            OkScriptToolkitBundle.message("toolbox.gameNotConnected")
        }
        updatingOverlayCheckbox = true
        overlayCheckBox.isSelected = state.overlay
        updatingOverlayCheckbox = false
    }

    private fun connectGame() {
        val projectDir = checkedProjectPath(toolboxStatusLabel, "toolbox.noProject") ?: return
        // 只锁自己的按钮（对齐 VS Code：连接进行中「断开」仍可点，操作经服务层排队生效）
        connectGameButton.isEnabled = false
        toolboxService.connectGame(projectDir, detectPythonPath()).whenComplete { _, _ ->
            SwingUtilities.invokeLater {
                connectGameButton.isEnabled = true
            }
        }
    }

    private fun disconnectGame() {
        val projectDir = checkedProjectPath(toolboxStatusLabel, "toolbox.noProject") ?: return
        // 只锁自己的按钮：connect_game.py 连接中最长等 150s，期间用户必须能改点「断开」
        disconnectGameButton.isEnabled = false
        toolboxService.disconnectGame(projectDir, detectPythonPath()).whenComplete { _, _ ->
            SwingUtilities.invokeLater {
                disconnectGameButton.isEnabled = true
            }
        }
    }

    /**
     * ok-script 项目根（设置优先，回退到含 `config.py` 的工作区根）。
     *
     * 规则收敛在 [ProjectDirResolution] —— 此前插件里有三份各自漂移的实现，
     * 其中服务层那份只取 `project.basePath`，导致界面判定用设置、跑脚本却用工作区根。
     *
     * 谓词也不用在这里写了：`ProjectDirResolution.resolve` 的便捷重载用真实文件系统判定，
     * 各消费点（这里 / `ScreenshotCapture` / `ProjectConventionConfig`）共用同一份。
     */
    private fun detectProjectPath(): String = ProjectDirResolution.resolve(
        configured = OkScriptToolkitSettings.getInstance(project).okScriptProjectPath(),
        basePath = project.basePath.orEmpty(),
        homeDir = System.getProperty("user.home").orEmpty(),
    )

    /** 表格仍展示旧项目时，编辑和异步保存都继续使用表格所属的根。 */
    private fun taskDataRoot(): String = displayedProjectDir.ifBlank { taskService.getTargetRoot() }

    private fun checkedProjectPath(messageLabel: JBLabel, missingKey: String): String? {
        val path = detectProjectPath()
        if (path.isBlank()) {
            messageLabel.text = OkScriptToolkitBundle.message(missingKey)
            return null
        }
        if (!ProjectDirResolution.isExistingDirectory(path)) {
            messageLabel.text = OkScriptToolkitBundle.message("projectDir.invalid", path)
            return null
        }
        return path
    }

    private fun detectPythonPath(): String {
        val settings = OkScriptToolkitSettings.getInstance(project)
        val configured = settings.okScriptPython()
        if (configured.isNotBlank()) return configured

        val projectDir = detectProjectPath()
        if (projectDir.isNotBlank()) {
            val venvPy = Paths.get(projectDir, ".venv", "Scripts", "python.exe").toFile()
            if (venvPy.exists()) return venvPy.absolutePath
            val venvPyUnix = Paths.get(projectDir, ".venv", "bin", "python").toFile()
            if (venvPyUnix.exists()) return venvPyUnix.absolutePath
        }
        return DEFAULT_PYTHON_PATH
    }

    private fun loadTasks() {
        // 切项目/刷新前先提交旧面板的防抖编辑，避免后续编辑覆盖唯一的 pendingSave。
        saveTimer.stop()
        flushPendingSave().join()
        triggerSaveTail.join()
        // 即使新路径无效，也要先作废仍在后台运行的旧探针。
        val generation = ++loadGeneration
        val projectDir = checkedProjectPath(statusLabel, "taskLauncher.noProject") ?: run {
            progressBar.isIndeterminate = false
            progressBar.isVisible = false
            return
        }
        statusLabel.text = OkScriptToolkitBundle.message("taskLauncher.loading")
        schemaWarningLabel.isVisible = false
        schemaWarningLabel.toolTipText = null
        progressBar.isIndeterminate = true
        progressBar.isVisible = true

        CompletableFuture.supplyAsync {
            val dataService = project.service<OkProjectDataService>()
            val locale = dataService.currentLocale()
            val poDirectory = OkScriptToolkitSettings.getInstance(project).poDirectory()
            val pythonPath = detectPythonPath()
            // parse_config_tasks.py 是纯 AST 解析（快）：每次刷新都重跑，
            // 保证新增任务能被发现（对齐 VSCode 行为）。
            // ⚠️ 必须把 projectDir 传下去：服务层默认用工作区根，而项目根
            // 可能来自 okScriptProjectPath 设置，两者不同时会去错的目录找 config.py。
            val parseResult = taskService.parseConfigTasks(pythonPath, locale, projectDir)

            // ① 先用「缓存 + 解析出的新任务桩」渲染一次，避免对着空白等全量 import。
            val cached = taskService.loadSchemaCache(projectDir, locale)
            val immediate = if (cached.ok && cached.schemas != null) {
                cached.copy(
                    schemas = mergeTaskLists(cached.schemas!!, parseResult),
                    configModule = parseResult.configModule.takeIf { parseResult.ok } ?: cached.configModule,
                )
            } else {
                parseOnlyResult(parseResult, projectDir, locale)
            }
            SwingUtilities.invokeLater {
                if (disposed || generation != loadGeneration || projectDir != detectProjectPath()) return@invokeLater
                applyProbeResult(immediate, finished = false, sourceProjectDir = projectDir)
            }

            // ② 然后**无条件**全量采集（对齐 VSCode 的 probeSchemasInBackground）。
            //
            // ⚠️ 这里曾经写成「缓存有效就直接 return」，后果有两个且都很隐蔽：
            //   1. 新增的任务不在缓存里，只能拿到「只有类名、没有字段」的桩
            //      （parse 结果里没有 name，displayName 兜底成类名）→ 界面只显示类名；
            //   2. 改了某任务 default_config 后缓存永不失效，参数表单一直是旧的。
            // 父仓是无条件采集的，所以两端表现不一致。
            val probeResult = taskService.probeTaskSchemas(pythonPath, locale, poDirectory, projectDir)
            if (probeResult.ok && probeResult.schemas != null) {
                val withConfigModule = probeResult.copy(
                    configModule = probeResult.configModule ?: parseResult.configModule.takeIf { parseResult.ok },
                )
                runCatching { taskService.saveSchemaCache(projectDir, locale, withConfigModule) }
                    .onFailure { LOG.warn("Task schemas loaded but cache could not be saved", it) }
                TaskLoadOutcome(withConfigModule)
            } else {
                // 采集失败：保留缓存或 AST 列表，同时明确告知用户参数与全局配置可能缺失。
                // 原先直接返回 immediate，最终状态仍显示「已加载」，把退化伪装成成功。
                val reason = probeResult.error ?: OkScriptToolkitBundle.message("taskLauncher.schemaScanUnknownError")
                LOG.warn("Task schema scan failed for $projectDir: $reason")
                TaskLoadOutcome(immediate, reason, cached.ok && cached.schemas != null)
            }
        }.thenAccept { outcome ->
            SwingUtilities.invokeLater {
                if (disposed || generation != loadGeneration || projectDir != detectProjectPath()) return@invokeLater
                applyProbeResult(outcome.result, finished = true, sourceProjectDir = projectDir)
                if (outcome.probeError != null) {
                    schemaWarningLabel.text = OkScriptToolkitBundle.message(
                        if (outcome.usedCache) "taskLauncher.schemaScanCached" else "taskLauncher.schemaScanNamesOnly",
                    )
                    schemaWarningLabel.toolTipText = outcome.probeError
                    schemaWarningLabel.isVisible = true
                }
            }
        }.exceptionally { throwable ->
            SwingUtilities.invokeLater {
                if (disposed || generation != loadGeneration || projectDir != detectProjectPath()) return@invokeLater
                progressBar.isIndeterminate = false
                progressBar.isVisible = false
                statusLabel.text = "Error: ${throwable.message}"
                LOG.error("Failed to load tasks", throwable)
            }
            null
        }
    }

    /**
     * 采集失败时的降级结果：只列出任务、不带参数。
     *
     * `displayName` 会是**类名** —— `parse_config_tasks.py` 只输出
     * `module`/`class`/`kind`，没有 `name`，真正的显示名来自 schema。
     * 所以一旦走到这里，界面就会"只显示类名"，这是**预期内的降级**而非正常状态。
     */
    private fun parseOnlyResult(
        parseResult: TaskLauncherService.TaskListResult,
        projectDir: String,
        locale: String,
    ): TaskLauncherService.SchemaProbeResult =
        if (parseResult.ok && parseResult.tasks.isNotEmpty()) {
            TaskLauncherService.SchemaProbeResult(
                ok = true,
                schemas = parseResult.tasks.associate { task ->
                    val key = "${task.module}::${task.className}"
                    key to TaskLauncherService.TaskSchema(
                        displayName = task.displayName,
                        kind = task.kind,
                    )
                },
                total = parseResult.tasks.size,
                projectDir = projectDir,
                locale = locale,
                configModule = parseResult.configModule,
            )
        } else {
            TaskLauncherService.SchemaProbeResult(ok = false, error = parseResult.error)
        }

    /**
     * 把采集结果落到界面上。
     *
     * [finished] 为 false 时只渲染列表、不动进度条 —— 用于"先用缓存快速首屏"，
     * 真正的收尾（隐藏进度条）留给最后一次调用。
     */
    private fun applyProbeResult(
        result: TaskLauncherService.SchemaProbeResult,
        finished: Boolean,
        sourceProjectDir: String,
    ) {
        if (finished) {
            progressBar.isIndeterminate = false
            progressBar.isVisible = false
        }

        if (result.ok && result.schemas != null) {
            displayedProjectDir = sourceProjectDir
            schemas = result.schemas
            if (result.configModule != null) {
                configModule = result.configModule
            }
            // 配置页「全局配置」区数据源（#7）
            globalConfigGroups = result.globalConfigGroups
            multiAccountInfo = result.multiAccount
            accountEditor?.takeIf { it.isOpen() }?.updateMetadata(result.multiAccount, result.schemas, result.globalConfigGroups)
            renderGlobalConfig()
            refreshAccountSummary()

            tasks = result.schemas.map { (key, schema) ->
                val parts = key.split("::")
                TaskLauncherService.TaskInfo(
                    module = parts.getOrElse(0) { "" },
                    className = parts.getOrElse(1) { key },
                    displayName = schema.displayName ?: parts.getOrElse(1) { key },
                    kind = schema.kind,
                )
            }

            // 勾选集合来自插件自己的持久化文件；执行器运行中则以它的快照为准
            triggerSaveTail.join()
            enabledTriggers.clear()
            enabledTriggers.addAll(taskService.loadEnabledTriggers(sourceProjectDir))

            // 折叠状态随项目根切换（tasks.json uiState；对齐 VS Code uiState 的复用语义）
            uiCollapseState = taskService.loadUiState(sourceProjectDir)

            // 重建卡片列表（触发/一次性分组 + groupName 二级分组 + 搜索过滤都在 TaskListGrouping）
            taskCardList.setTasks(tasks)
            refreshEnvironmentInfo()

            // 别的项目的触发集合不能 adopt（会写进本项目的 tasks.json）；卡片状态用净化快照
            if (executorMatchesProject() && taskRunner.currentState().status == "running") {
                syncTriggerCheckboxes(taskRunner.currentState())
            }
            renderTaskStatuses(runnerStateForDisplay())

            // 参数面板开着时跟随刷新（对齐 VS Code：重渲染后 refreshDrawer 重挂新表单）
            val previousSelection = detailTask?.let { taskKeyOf(it) }
            if (previousSelection != null && tasks.any { taskKeyOf(it) == previousSelection }) {
                loadTaskParams(detailTask!!)
            } else {
                detailTask = null
                showDetailPlaceholder()
            }

            statusLabel.text = OkScriptToolkitBundle.message("taskLauncher.loaded", tasks.size)

            // #7 配置接管：物化全局配置组 + 每个任务的参数快照（新增键 > 0 时覆盖状态提示）。
            // 缓存首屏（finished=false）只物化不提示 —— 对齐 VS Code：状态条消息只在探针完成后出。
            val materialized = materializeTaskSnapshots(result.schemas, sourceProjectDir) +
                if (result.globalConfigGroups.isNotEmpty()) {
                    materializeGlobalSnapshots(result.globalConfigGroups, sourceProjectDir)
                } else 0
            if (finished && materialized > 0) {
                statusLabel.text = OkScriptToolkitBundle.message("taskLauncher.gconfigMaterialized", materialized)
            }
        } else {
            if (!finished) return
            statusLabel.text = "Failed: ${result.error}"
            JOptionPane.showMessageDialog(
                mainPanel,
                "Failed to load tasks: ${result.error}",
                "Error",
                JOptionPane.ERROR_MESSAGE,
            )
        }
    }

    /**
     * 物化**每个任务**的参数快照并落盘（配置接管 —— 对齐 VS Code materializeAllSnapshots
     * 的任务半边；全局组半边见 [materializeGlobalSnapshots]）。
     *
     * 规则同全局组，复用 [GlobalSnapshotRules.materialize]：existing 为空（首建）→ 全部键
     * 继承 f.value（项目当前值原样进快照）；非空（重探针）→ 已有键保留、新键取 f.defaultOrValue()。
     * broken schema（探针失败的任务）与无字段的任务跳过。
     *
     * 之前只物化全局组，任务参数只在用户**打开并编辑过**该任务时才进 tasks.json ——
     * 未编辑过的任务在执行器侧始终实时跟随项目 configs（不是冻结快照），与 VS Code
     * 「UI 显示 = 实际执行」的接管语义不一致。
     *
     * @return 新增键数（0 = 全部快照无变化，不落盘不推送）
     */
    private fun materializeTaskSnapshots(
        schemas: Map<String, TaskLauncherService.TaskSchema>,
        root: String,
    ): Int {
        val added = taskService.saveMaterializedTaskParams(schemas, root)
        // 探针可在执行器启动后完成：物化出的新键必须即时推给常驻执行器
        if (added > 0) pushParamOverrides(root)
        return added
    }

    /**
     * 物化全局配置快照并落盘（#7 配置接管）。
     *
     * 规则在 [GlobalSnapshotRules]（纯对象）：每组 existing 为空（首建）→ 全部继承
     * f.value；非空（重探针）→ 已有键保留（孤儿键不删）、新键取 f.defaultOrValue()。
     * 快照有实质变化时落盘，并在执行器运行中把新快照经 gparams 推给它 ——
     * 否则要重启执行器才生效（对齐 VS Code 侧 consolePanel 的物化+推送时机）。
     *
     * @return 新增键数（0 = 快照无变化，不落盘不推送）
     */
    private fun materializeGlobalSnapshots(
        groups: List<TaskLauncherService.GlobalConfigGroup>,
        root: String,
    ): Int {
        val added = taskService.saveMaterializedGlobalConfigs(groups, root)
        if (added > 0) {
            pushGlobalSnapshot(taskService.loadGlobalConfigs(root), root)
        }
        return added
    }

    /** 全局配置快照即时推送：执行器运行中才推（gparams 是全量快照，幂等可重放） */
    private fun pushGlobalSnapshot(snapshots: Map<String, Map<String, Any?>>, root: String) {
        if (!taskRunner.isRunning() || taskRunner.runningProjectDir != root) return
        if (snapshots.isEmpty()) return
        taskRunner.pushGlobalParams(objectMapper.writeValueAsString(snapshots), root)
    }

    private fun loadTaskParams(task: TaskLauncherService.TaskInfo) {
        // 切换任务前保存上一张表单；单个 pendingSave 不能被下一张表单覆盖。
        saveTimer.stop()
        flushPendingSave().join()
        paramPanel.removeAll()
        paramFields.clear()
        visibilityRefresher = null
        currentRenderer = null
        // 右栏头部跟着选中项走：名称 / 类型 chip / 状态 chip / 主操作按钮
        renderDetailHeader(task)

        val taskKey = "${task.module}::${task.className}"
        val schema = schemas[taskKey]

        var row = 0

        if (schema != null && !schema.broken && schema.fields.isNotEmpty()) {
            val separator = JSeparator()
            paramPanel.add(separator, GridBagConstraints().apply {
                gridx = 0; gridy = row; gridwidth = 2
                fill = GridBagConstraints.HORIZONTAL
                insets = Insets(4, 0, 4, 0)
            })
            row++

            // 树形渲染：boolean 条件显隐 + sub_configs 折叠组 + configGroups/groupSelector
            // （对齐 VSCode 版 configPanel.js renderSchema）
            val renderer = SchemaTreeRenderer(task, schema, initialRow = row)
            currentRenderer = renderer
            renderer.render(paramPanel)
            visibilityRefresher = {
                // 只刷新可见性，不同步重复行（避免覆盖用户在后续行的编辑）
                renderer.applyVisibility()
            }
        } else {
            val gbc = GridBagConstraints().apply {
                gridx = 0; gridy = row; gridwidth = 2
                anchor = GridBagConstraints.NORTHWEST
                insets = Insets(10, 10, 10, 10)
            }
            val text = when {
                schema == null -> OkScriptToolkitBundle.message("taskLauncher.schemaNotProbed")
                schema.broken -> OkScriptToolkitBundle.message("taskLauncher.schemaBrokenDetail", schema.error.orEmpty())
                else -> OkScriptToolkitBundle.message("taskLauncher.noConfigParameters")
            }
            paramPanel.add(JBLabel(text), gbc)
        }

        paramPanel.add(JPanel().apply { isOpaque = false }, GridBagConstraints().apply {
            gridx = 0; gridy = GridBagConstraints.RELATIVE; gridwidth = 2
            weighty = 1.0; fill = GridBagConstraints.BOTH
        })

        paramPanel.revalidate()
        paramPanel.repaint()
    }

    /** schema 缓存与最新任务列表合并：新增任务补空 schema，消失任务剔除 */
    /** 见 [TaskSchemaMerge]：缓存 + 解析结果对齐，用于"先用缓存快速首屏"。 */
    private fun mergeTaskLists(
        cached: Map<String, TaskLauncherService.TaskSchema>,
        parseResult: TaskLauncherService.TaskListResult,
    ): Map<String, TaskLauncherService.TaskSchema> =
        TaskSchemaMerge.merge(cached, parseResult.tasks, parseResult.ok)

    /** 取值控件：剥掉滚动面板/说明包装，返回真正持值的控件 */
    private fun valueControlOf(component: JComponent): JComponent = when (component) {
        is JScrollPane -> (component.viewport?.view as? JComponent)?.let { valueControlOf(it) } ?: component
        is JPanel -> {
            // 递归查找子组件中的取值控件
            // 优先查找 JTextField（cascade_drop_down 的隐藏叶子字段）
            val children = component.components.filterIsInstance<JComponent>()
            val found = children.filterIsInstance<JTextField>().firstOrNull()
                ?: children.map { valueControlOf(it) }
                    .firstOrNull { child ->
                        child is JCheckBox || child is JSpinner ||
                            child is JTextArea || child is JComboBox<*> || child is JList<*> ||
                            child is ListEditorComponent
                    }
            found ?: component
        }
        else -> component
    }

    /**
     * 下拉/多选列表的显示渲染器：option_labels 按索引对应，无标签时显示原始值。
     *
     * 用 [textListCellRenderer] 而非 `listCellRenderer { text(...) }`：后者的 lambda 接收者是
     * `LcrRow`，带 `@ApiStatus.Experimental`，Plugin Verifier 会对 2026.3 EAP 报 9 处
     * experimental API usage（3×LcrRow 接口 + 3×getValue + 3×text$default）。
     * [textListCellRenderer] 走同一套 Kotlin UI DSL 渲染管线（圆角选中、缩放、无障碍），
     * 但签名里不出现 `LcrRow`，也是平台在 `SimpleListCellRenderer.create` 废弃说明里指定的替代品。
     *
     * 注意只能用它**单参**重载：`textListCellRenderer(nullValue, textExtractor)` 在 251 里
     * 还是 `@ApiStatus.Internal`，会触发 verifier 的 INTERNAL_API_USAGES（进而让 verifyPlugin 失败）。
     * 空值因此由 lambda 自己兜底成 ""。
     */
    private fun optionLabelRenderer(options: List<*>, labels: List<*>): javax.swing.ListCellRenderer<Any?> =
        textListCellRenderer<Any?> { value ->
            if (value == null) {
                ""
            } else {
                val index = options.indexOf(value)
                if (index >= 0) labels.getOrNull(index)?.toString() ?: value.toString() else value.toString()
            }
        }

    // ── sub_configs 参数树（对齐 VSCode 版 configPanel.js）──

    private data class OptionGroupSpec(val key: String, val label: String, val children: List<String>)

    /**
     * 字段 sub_configs 解析：boolean 字段取 "true"/"false" 子键做行内显隐规则；
     * 其余字段每个选项变成一个可折叠子配置组（标签取 sub_config_labels）。
     */
    private data class SubConfigRuleSet(
        val booleanRules: Map<String, List<String>>?,
        val optionGroups: List<OptionGroupSpec>,
    )

    /**
     * schema 参数树渲染器：
     * - configGroups/groupSelector：选择器字段隐藏；全部注册组渲染为可折叠组，
     *   组键命中的字段作为组头（不重复渲染为行）；
     * - boolean sub_configs：子字段紧跟父字段行内渲染（缩进），随父开关值显隐（递归、防环）；
     * - 非 boolean sub_configs：每个选项一个永久折叠组（默认收起），跨组共享字段可重复渲染，
     *   变更时同步各重复行取值。
     */
    private inner class SchemaTreeRenderer(
        private val task: TaskLauncherService.TaskInfo,
        private val schema: TaskLauncherService.TaskSchema,
        private val initialRow: Int = 0,
    ) {
        private val taskKey = "${task.module}::${task.className}"
        private val fieldsByKey = schema.fields.associateBy { it.key }
        private val groups: Map<String, List<String>> = schema.configGroups.orEmpty()

        /**
         * 折叠吸收显隐后的实际分组表：分组名的 `sub_configs` 里「不在 children 中」的子项
         * 会被补进 children。渲染与嵌套判定都必须读这张表，否则被吸收的子项会没有渲染通道。
         * 由 [render] 在渲染前计算。
         */
        private var effectiveGroups: Map<String, List<String>> = groups
        private val selectorKey = schema.groupSelector?.takeIf { fieldsByKey.containsKey(it) }.orEmpty()
        private val groupLabels: Map<String, String> = schema.groupLabels.orEmpty()

        private val renderedFields = HashSet<String>()
        private val renderedGroups = HashSet<String>()
        private val rowsByKey = HashMap<String, MutableList<Pair<JComponent, JComponent>>>()
        private val groupEntries = mutableListOf<Triple<JPanel, JPanel, Boolean>>()
        private val inlineRules = HashMap<String, Map<String, List<String>>>()
        private val parentsByChild = HashMap<String, MutableList<String>>()
        /** 跟踪用户编辑的控件：key -> 编辑过的控件 */
        private val editedControls = HashMap<String, JComponent>()
        /** 同步进行中标记：防止重复同步时递归调用 */
        private var syncingInProgress = false

        /** 每个容器的下一行号（宿主面板混有 timeout/separator，不能按组件数推算） */
        private val rowCounter = HashMap<JPanel, Int>()

        private fun nextRow(container: JPanel): Int {
            val current = rowCounter[container] ?: 0
            rowCounter[container] = current + 1
            return current
        }

        fun render(host: JPanel) {
            if (initialRow > 0) {
                rowCounter[host] = initialRow
            }
            // 折叠吸收显隐（分组优先、显隐其次）：
            //   1. 已是分组容器的 key，其 sub_configs 一律不再作为显隐规则；
            //   2. 这些 sub_configs 里「不在 children 中」的子项被吸收进 children，
            //      否则它们只剩 inline 一条渲染通道，会因规则 1 彻底消失；
            //   3. 分组名自身的配置值仍照常渲染成控件。
            val merged: MutableMap<String, List<String>> = HashMap(groups)
            for (field in schema.fields) {
                if (field.key !in groups) continue
                val rules = subConfigRules(field)?.booleanRules ?: continue
                val declared = groups[field.key].orEmpty()
                val extra = SchemaTreeOverlap.absorbedChildren(
                    declared = declared,
                    inlineChildren = rules.values.flatten(),
                )
                if (extra.isNotEmpty()) merged[field.key] = declared + extra
            }
            effectiveGroups = merged
            for (field in schema.fields) {
                if (SchemaTreeOverlap.shouldIgnoreInlineRules(groups.keys, field.key)) continue
                subConfigRules(field)?.booleanRules?.let { rules ->
                    inlineRules[field.key] = rules
                    for (children in rules.values) {
                        for (child in children) {
                            parentsByChild.getOrPut(child) { mutableListOf() }.add(field.key)
                        }
                    }
                }
            }

            val optionControlled = HashSet<String>()
            val inlineControlled = HashSet<String>()
            for (field in schema.fields) {
                for (group in subConfigRules(field)?.optionGroups.orEmpty()) {
                    optionControlled.addAll(group.children)
                }
            }
            for (rules in inlineRules.values) {
                for (keys in rules.values) inlineControlled.addAll(keys)
            }
            val groupNames = groups.keys.toSet()
            val groupChildren = effectiveGroups.values.flatten().toSet()

            for (field in schema.fields) {
                if (field.key == selectorKey || field.key in optionControlled ||
                    field.key in inlineControlled || field.key in groupNames || field.key in groupChildren
                ) {
                    continue
                }
                renderFieldTree(field.key, host, emptySet())
            }

            val nestedGroups = HashSet<String>()
            for ((parent, children) in effectiveGroups) {
                for (child in children) {
                    if (child != parent && effectiveGroups.containsKey(child)) nestedGroups.add(child)
                }
            }
            val renderRegisteredGroup = { key: String ->
                renderGroup(
                    key = key,
                    label = groupLabels[key] ?: key,
                    children = effectiveGroups[key].orEmpty(),
                    container = host,
                    path = listOf("config-group", key),
                    duplicateChildren = true,
                    headerField = key.takeIf { fieldsByKey.containsKey(key) },
                )
            }
            groups.keys.filter { it !in nestedGroups }.forEach { renderRegisteredGroup(it) }
            groups.keys.filter { it !in renderedGroups }.forEach { renderRegisteredGroup(it) }

            for (field in schema.fields) {
                if (field.key == selectorKey || field.key in renderedFields || field.key in optionControlled ||
                    field.key in groupNames || field.key in groupChildren
                ) {
                    continue
                }
                renderFieldTree(field.key, host, emptySet(), subConfig = field.key in inlineControlled)
            }

            syncDuplicateRows()
            applyVisibility()
        }

        private fun renderFieldTree(
            key: String,
            container: JPanel,
            checking: Set<String>,
            subConfig: Boolean = false,
            duplicate: Boolean = false,
            path: List<String> = listOf("field", key),
        ): Boolean {
            if (key in checking || !fieldsByKey.containsKey(key)) return false
            val next = checking + key
            if (!renderFieldRow(key, container, subConfig, duplicate)) return false

            inlineRules[key]?.let { rules ->
                for (child in rules.values.flatten().distinct()) {
                    renderFieldTree(child, container, next, subConfig = true, duplicate = duplicate, path = path + child)
                }
            }
            for (group in subConfigRules(fieldsByKey.getValue(key))?.optionGroups.orEmpty()) {
                renderGroup(
                    key = "$key:${group.key}",
                    label = group.label,
                    children = group.children,
                    container = container,
                    path = path + "sub-config" + group.key,
                    duplicateChildren = true,
                    headerField = null,
                )
            }
            return true
        }

        /** 字段名称、可换行说明、控件纵向排列，整个字段随右栏宽度伸缩。 */
        private fun renderFieldRow(
            key: String,
            container: JPanel,
            subConfig: Boolean,
            duplicate: Boolean,
        ): Boolean {
            val field = fieldsByKey[key] ?: return false
            if (!duplicate && key in renderedFields) return false
            if (!duplicate) renderedFields.add(key)

            val control = createFieldComponent(field, task)
            val rowPanel = SchemaFieldUi.row(field, control, subConfig)
            val grow = nextRow(container)
            container.add(rowPanel, GridBagConstraints().apply {
                gridx = 0; gridy = grow; gridwidth = 2
                fill = GridBagConstraints.HORIZONTAL
                weightx = 1.0
                anchor = GridBagConstraints.NORTHWEST
                insets = Insets(2, 4, 2, 4)
            })
            paramFields[key] = valueControlOf(control)
            rowsByKey.getOrPut(key) { mutableListOf() }.add(rowPanel to control)
            return true
        }

        /** 可折叠子配置组：组键命中的字段作为组头，组体默认收起（展开状态跨重渲染保留） */
        private fun renderGroup(
            key: String,
            label: String,
            children: List<String>,
            container: JPanel,
            path: List<String>,
            duplicateChildren: Boolean,
            headerField: String?,
        ): Boolean {
            if (key in renderedGroups) return false
            renderedGroups.add(key)

            val body = JPanel(GridBagLayout())
            val stateKey = "$taskKey::${path.joinToString(">")}"

            val toggle = buildToggle(body, stateKey)

            // 组头字段（始终可见）；其 boolean 行内子字段留在组体内
            if (headerField != null && fieldsByKey.containsKey(headerField)) {
                val field = fieldsByKey.getValue(headerField)
                val control = createFieldComponent(field, task)
                val fieldRow = SchemaFieldUi.row(field, control)
                paramFields[headerField] = valueControlOf(control)
                rowsByKey.getOrPut(headerField) { mutableListOf() }.add(fieldRow to control)
                inlineRules[headerField]?.let { rules ->
                    // 该标题字段自身的内联子字段默认渲染在组内；但若同一批 key 也出现在
                    // configGroups 的 children 里，下面的循环会渲染它们，此处必须跳过，
                    // 否则每个子字段都会被渲染两遍（ok-gf2 的「活动层 -> 喝水/吃饭」曾因此重复）。
                    val skip = SchemaTreeOverlap.inlineChildrenToSkip(
                        headerField = headerField,
                        groupChildren = children,
                        inlineChildren = rules.values.flatten(),
                    )
                    for (child in rules.values.flatten().distinct()) {
                        if (child in skip) continue
                        renderFieldTree(child, body, setOf(headerField), subConfig = true, path = path + child)
                    }
                }
                val headerPanel = JPanel(BorderLayout(6, 0))
                headerPanel.isOpaque = false
                headerPanel.add(fieldRow, BorderLayout.CENTER)
                headerPanel.add(toggle, BorderLayout.EAST)
                val groupPanel = buildGroupPanel(labelOf = null, header = headerPanel, body = body)
                addToContainer(container, groupPanel)
                groupEntries.add(Triple(groupPanel, body, true))
            } else {
                val groupPanel = buildGroupPanel(labelOf = label, header = null, body = body, toggle = toggle)
                addToContainer(container, groupPanel)
                groupEntries.add(Triple(groupPanel, body, false))
            }

            val childKeys = children.distinct().filter { it != headerField }
            for (child in childKeys) {
                if (effectiveGroups.containsKey(child)) {
                    renderGroup(
                        key = child,
                        label = groupLabels[child] ?: child,
                        children = effectiveGroups[child].orEmpty(),
                        container = body,
                        path = path + child,
                        duplicateChildren = duplicateChildren,
                        headerField = child.takeIf { fieldsByKey.containsKey(child) },
                    )
                } else {
                    renderFieldTree(child, body, emptySet(), duplicate = duplicateChildren, path = path + child)
                }
            }

            if (body.componentCount == 0) {
                toggle.isVisible = false
                body.isVisible = true
            }
            return true
        }

        private fun buildToggle(body: JPanel, stateKey: String): JButton {
            val toggle = JButton("▼")
            toggle.margin = Insets(0, 4, 0, 4)
            toggle.isContentAreaFilled = false
            toggle.isFocusable = false
            toggle.addActionListener {
                val open = !body.isVisible
                body.isVisible = open
                toggle.text = if (open) "▲" else "▼"
                if (open) openConfigGroups.add(stateKey) else openConfigGroups.remove(stateKey)
                paramPanel.revalidate()
                paramPanel.repaint()
            }
            body.isVisible = stateKey in openConfigGroups
            toggle.text = if (body.isVisible) "▲" else "▼"
            return toggle
        }

        private fun buildGroupPanel(
            labelOf: String?,
            header: JComponent?,
            body: JPanel,
            toggle: JButton? = null,
        ): JPanel {
            val north = JPanel(BorderLayout(6, 0))
            north.isOpaque = false
            if (header != null) {
                north.add(header, BorderLayout.CENTER)
            } else {
                val title = JBLabel(labelOf.orEmpty()).apply { font = font.deriveFont(Font.BOLD) }
                north.add(title, BorderLayout.CENTER)
                val east = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 0))
                east.isOpaque = false
                toggle?.let { east.add(it) }
                north.add(east, BorderLayout.EAST)
            }
            north.border = BorderFactory.createEmptyBorder(3, 6, 3, 6)
            body.isOpaque = false
            body.border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
            return JPanel(BorderLayout()).apply {
                border = BorderFactory.createLineBorder(JBColor.border())
                isOpaque = false
                add(north, BorderLayout.NORTH)
                add(body, BorderLayout.CENTER)
            }
        }

        private fun addToContainer(container: JPanel, component: JComponent) {
            container.add(component, GridBagConstraints().apply {
                gridx = 0; gridy = nextRow(container); gridwidth = 2
                fill = GridBagConstraints.HORIZONTAL
                weightx = 1.0
                insets = Insets(4, 2, 4, 2)
            })
        }

        /** 重复渲染的同一字段（跨组共享）变更时，以第一行为准同步其余行的控件值 */
        fun syncDuplicateRows() {
            if (syncingInProgress) return
            syncingInProgress = true
            try {
                for ((key, rows) in rowsByKey) {
                    if (rows.size < 2) continue
                    val source = valueControlOf(rows.first().second)
                    for ((_, target) in rows.drop(1)) {
                        syncControlValue(key, source, valueControlOf(target))
                    }
                }
            } finally {
                syncingInProgress = false
            }
        }

        private fun syncControlValue(key: String, source: JComponent, target: JComponent) {
            when (source) {
                is JCheckBox -> (target as? JCheckBox)?.let { if (it.isSelected != source.isSelected) it.isSelected = source.isSelected }
                is JSpinner -> (target as? JSpinner)?.let { if (it.value != source.value) it.value = source.value }
                is JTextField -> (target as? JTextField)?.let { if (it.text != source.text) it.text = source.text }
                is JTextArea -> (target as? JTextArea)?.let { if (it.text != source.text) it.text = source.text }
                is JComboBox<*> -> (target as? JComboBox<*>)?.let { if (it.selectedItem != source.selectedItem) it.selectedItem = source.selectedItem }
                is JList<*> -> (target as? JList<*>)?.let {
                    // ListSelectionModel 接口没有 selectionEquals/selectionInterval，
                    // 直接比对 selectedIndices 更简单也更可靠
                    if (!it.selectedIndices.contentEquals(source.selectedIndices)) {
                        it.clearSelection()
                        for (idx in source.selectedIndices) {
                            if (idx < it.model.size) it.selectionModel.addSelectionInterval(idx, idx)
                        }
                    }
                }
                is ListEditorComponent -> (target as? ListEditorComponent)?.let {
                    if (it.value != source.value) it.replaceValue(source.value)
                }
                else -> LOG.debug("No value sync for duplicated field $key of ${source.javaClass.simpleName}")
            }
        }

        /** boolean 行内显隐：所有 boolean 父级自身可见且当前取值映射包含该子字段（防环） */
        private fun visible(key: String, checking: Set<String> = emptySet()): Boolean {
            val parents = parentsByChild[key] ?: return true
            if (key in checking) return false
            val next = checking + key
            return parents.all { parentKey ->
                val parent = fieldsByKey[parentKey] ?: return@all false
                visible(parentKey, next) &&
                    inlineRules[parentKey]?.get(booleanValueOf(parent).toString())?.contains(key) == true
            }
        }

        fun applyVisibility() {
            for ((key, rows) in rowsByKey) {
                val v = visible(key)
                for ((label, component) in rows) {
                    label.isVisible = v
                    component.isVisible = v
                }
            }
            // 子组先于父组：父组内没有可见字段或子组时隐藏，组头自身有开关的保留。
            for ((panel, body, hasHeaderField) in groupEntries.asReversed()) {
                panel.isVisible = hasHeaderField || body.components.any { it.isVisible }
            }
            paramPanel.revalidate()
            paramPanel.repaint()
        }

        /** 获取字段的值控件：优先返回用户编辑的控件，否则返回最后一个实际显示的控件 */
        fun getValueControl(key: String): JComponent? {
            // 优先返回用户编辑的控件
            editedControls[key]?.let { return valueControlOf(it) }
            val rows = rowsByKey[key] ?: return null
            return if (rows.size > 1) {
                // 找到最后一个实际显示的控件
                rows.lastOrNull { it.second.isShowing }?.second?.let { valueControlOf(it) }
                    ?: valueControlOf(rows.last().second)
            } else {
                valueControlOf(rows.first().second)
            }
        }

        /** 标记控件为已编辑（同步期间忽略） */
        fun markEdited(key: String, component: JComponent) {
            if (!syncingInProgress) {
                editedControls[key] = component
            }
        }

        /** 从编辑的控件同步到其他重复控件 */
        fun syncFromEdited() {
            if (syncingInProgress) return
            syncingInProgress = true
            try {
                for ((key, source) in editedControls) {
                    val rows = rowsByKey[key] ?: continue
                    for ((_, target) in rows) {
                        val targetControl = valueControlOf(target)
                        if (targetControl !== source) {
                            syncControlValue(key, source, targetControl)
                        }
                    }
                }
            } finally {
                syncingInProgress = false
            }
        }

        private fun booleanValueOf(field: TaskLauncherService.TaskParamField): Boolean {
            // 优先使用实时的 checkbox 值
            val liveControl = getValueControl(field.key)
            if (liveControl is JCheckBox) return liveControl.isSelected
            // 回退到持久化的值
            val taskConfig = taskService.getTaskConfig("${task.module}::${task.className}", taskDataRoot())
            return when (val v = taskConfig.params?.get(field.key) ?: field.value ?: field.default) {
                is Boolean -> v
                is String -> v.trim().equals("true", ignoreCase = true)
                is Int -> v != 0
                is Long -> v != 0L
                is Double -> v != 0.0
                null -> false
                else -> true
            }
        }

        private fun subConfigRules(field: TaskLauncherService.TaskParamField): SubConfigRuleSet? {
            val rules = field.type?.get("sub_configs") as? Map<*, *> ?: return null
            val labels = field.type?.get("sub_config_labels") as? Map<*, *> ?: emptyMap<Any, Any>()
            if (field.default is Boolean || field.value is Boolean) {
                val result = linkedMapOf<String, List<String>>()
                for ((choice, controlled) in rules) {
                    val normalized = choice.toString().lowercase()
                    if (normalized == "true" || normalized == "false") {
                        result[normalized] = normalizeConfigKeys(controlled)
                    }
                }
                return if (result.isEmpty()) null else SubConfigRuleSet(result, emptyList())
            }
            val optionGroups = rules.entries.map { (choice, controlled) ->
                OptionGroupSpec(
                    key = choice.toString(),
                    label = labels[choice]?.toString() ?: choice.toString(),
                    children = normalizeConfigKeys(controlled),
                )
            }
            return if (optionGroups.isEmpty()) null else SubConfigRuleSet(null, optionGroups)
        }

        private fun normalizeConfigKeys(value: Any?): List<String> = when (value) {
            is String -> listOf(value)
            is List<*> -> value.filterIsInstance<String>()
            else -> emptyList()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun createFieldComponent(field: TaskLauncherService.TaskParamField, task: TaskLauncherService.TaskInfo): JComponent {
        val owningRenderer = currentRenderer ?: throw IllegalStateException("createFieldComponent called without active renderer")
        val taskKey = "${task.module}::${task.className}"
        val taskConfig = taskService.getTaskConfig(taskKey, taskDataRoot())
        val savedValue = taskConfig.params?.get(field.key)
        val currentValue = savedValue ?: field.value ?: field.default

        val typeName = field.type?.get("type")?.toString().orEmpty()
        val options = (field.type?.get("options") as? List<*>).takeIf { !it.isNullOrEmpty() }
        val optionLabels = field.type?.get("option_labels") as? List<*> ?: emptyList<Any>()

        val component = when {
            field.type?.get("type") == "bool" || currentValue is Boolean -> {
                JCheckBox("", currentValue as? Boolean ?: false).also { cb ->
                    cb.addActionListener {
                        owningRenderer.markEdited(field.key, cb)
                        autoSaveTaskConfig(task)
                    }
                }
            }

            typeName == "drop_down" || (typeName.isEmpty() && options != null && currentValue !is List<*>) -> {
                // 对齐 VSCode buildDropDown：option_labels 按索引做显示标签，保存原始值
                val comboBox = JComboBox(options!!.toTypedArray())
                comboBox.renderer = optionLabelRenderer(options, optionLabels)
                comboBox.selectedItem = currentValue
                    comboBox.addActionListener {
                        owningRenderer.markEdited(field.key, comboBox)
                        autoSaveTaskConfig(task)
                    }
                comboBox
            }

            typeName == "multi_selection" || (typeName.isEmpty() && options != null && currentValue is List<*>) -> {
                val options = options ?: emptyList<Any>()
                val list = JList(options.toTypedArray())
                list.cellRenderer = optionLabelRenderer(options, optionLabels)
                list.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
                if (currentValue is List<*>) {
                    val selectedIndices = currentValue.mapNotNull { options.indexOf(it).takeIf { i -> i >= 0 } }
                    list.selectedIndices = selectedIndices.toIntArray()
                }
                list.addListSelectionListener {
                    owningRenderer.markEdited(field.key, list)
                    autoSaveTaskConfig(task)
                }
                JBScrollPane(list)
            }

            field.type?.get("type") == "cascade_drop_down" -> {
                // 级联下拉：组 → 叶子两级联动（对齐 VSCode 版），值存叶子
                val options = field.type?.get("options") as? Map<*, *> ?: emptyMap<Any, Any>()
                val categoryLabels = field.type?.get("category_labels") as? Map<*, *>
                val leafLabels = field.type?.get("option_labels") as? Map<*, *>
                val leafField = JTextField(currentValue?.toString() ?: "")
                leafField.isVisible = false
                val groupCombo = JComboBox(options.keys.toTypedArray())
                // 对齐 VSCode buildCascadeSelect：组名/叶子用本地化标签，保存原始值
                groupCombo.renderer = textListCellRenderer<Any?> { group ->
                    val key = group?.toString()
                    (categoryLabels?.get(key) ?: key)?.toString() ?: ""
                }
                val leafCombo = JComboBox<String>()
                leafCombo.renderer = textListCellRenderer<Any?> { leafValue ->
                    val leaf = leafValue?.toString()
                    val values = options[groupCombo.selectedItem] as? List<*>
                    val idx = values?.indexOfFirst { it?.toString() == leaf } ?: -1
                    val labelsForGroup = (groupCombo.selectedItem as? String)?.let { leafLabels?.get(it) } as? List<*>
                    labelsForGroup?.getOrNull(idx)?.toString() ?: leaf ?: ""
                }
                fun fillLeaves(group: Any?) {
                    leafCombo.removeAllItems()
                    (options[group] as? List<*>)?.forEach { leafCombo.addItem(it.toString()) }
                }
                val initial = currentValue?.toString()
                if (!initial.isNullOrBlank()) {
                    // 优先选中包含当前叶子的组
                    val ownerGroup = options.entries.firstOrNull { (it.value as? List<*>)?.contains(initial) == true }?.key
                    if (ownerGroup != null) groupCombo.selectedItem = ownerGroup
                }
                fillLeaves(groupCombo.selectedItem)
                if (!initial.isNullOrBlank()) leafCombo.selectedItem = initial
                groupCombo.addActionListener {
                    fillLeaves(groupCombo.selectedItem)
                    owningRenderer.markEdited(field.key, leafField)
                    autoSaveTaskConfig(task)
                }
                leafCombo.addActionListener {
                    if (leafCombo.selectedItem != null) {
                        leafField.text = leafCombo.selectedItem?.toString()
                        owningRenderer.markEdited(field.key, leafField)
                        autoSaveTaskConfig(task)
                    }
                }
                val combos = JPanel(BorderLayout(4, 0))
                combos.add(groupCombo, BorderLayout.CENTER)
                combos.add(leafCombo, BorderLayout.EAST)
                JPanel(BorderLayout()).apply {
                    add(combos, BorderLayout.CENTER)
                    add(leafField, BorderLayout.SOUTH)
                }
            }

            currentValue is List<*> -> {
                if (currentValue.any { it is Map<*, *> }) {
                    // 对象数组（条件/动作序列）：无结构化编辑器，回退多行 JSON（避免 toString 破坏数据）
                    jsonSequenceArea(currentValue, task, field.key)
                } else {
                    // 对齐 VSCode buildList：折叠摘要 + 「修改」弹窗（ModifyListDialog 语义）
                    // onChanged 回传的是新值（List<Any?>），而 markEdited 要的是控件本身，
                    // 所以先声明再赋值，让回调能引用到组件实例。
                    lateinit var editor: ListEditorComponent
                    editor = ListEditorComponent(
                        project = project,
                        dialogTitle = field.displayKey ?: field.key,
                        typeMeta = field.type,
                        initialValue = currentValue,
                        onChanged = {
                            owningRenderer.markEdited(field.key, editor)
                            autoSaveTaskConfig(task)
                        },
                    )
                    editor
                }
            }

            field.type?.get("type") == "cond_sequence_editor" -> jsonSequenceArea(currentValue, task, field.key)

            currentValue is Int -> {
                JSpinner(SpinnerNumberModel(currentValue, Int.MIN_VALUE, Int.MAX_VALUE, 1)).also { sp ->
                    sp.addChangeListener {
                        owningRenderer.markEdited(field.key, sp)
                        autoSaveTaskConfig(task)
                    }
                }
            }
            currentValue is Double -> {
                JSpinner(SpinnerNumberModel(currentValue, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, 0.1)).also { sp ->
                    sp.addChangeListener {
                        owningRenderer.markEdited(field.key, sp)
                        autoSaveTaskConfig(task)
                    }
                }
            }

            else -> {
                val text = currentValue?.toString() ?: ""
                
                if (field.type?.get("type") == "text_edit" || text.contains(LF_CHAR) || text.length > 80) {
                    val area = JTextArea(text)
                    area.rows = 3
                    area.lineWrap = true
                    area.document.addDocumentListener(object : DocumentListener {
                        override fun insertUpdate(e: DocumentEvent?) {
                            owningRenderer.markEdited(field.key, area)
                            autoSaveTaskConfig(task)
                        }
                        override fun removeUpdate(e: DocumentEvent?) {
                            owningRenderer.markEdited(field.key, area)
                            autoSaveTaskConfig(task)
                        }
                        override fun changedUpdate(e: DocumentEvent?) {
                            owningRenderer.markEdited(field.key, area)
                            autoSaveTaskConfig(task)
                        }
                    })
                    return JBScrollPane(area).apply { preferredSize = Dimension(200, 70) }
                }
                val textField = JTextField(text)
                textField.columns = 20
                textField.document.addDocumentListener(object : DocumentListener {
                    private var insideUpdate = false
                    override fun insertUpdate(e: DocumentEvent?) = scheduleSave()
                    override fun removeUpdate(e: DocumentEvent?) = scheduleSave()
                    override fun changedUpdate(e: DocumentEvent?) = scheduleSave()
                    private fun scheduleSave() {
                        if (!insideUpdate) {
                            insideUpdate = true
                            SwingUtilities.invokeLater {
                                owningRenderer.markEdited(field.key, textField)
                                autoSaveTaskConfig(task)
                                insideUpdate = false
                            }
                        }
                    }
                })
                textField
            }
        }
        return component
    }

    /**
     * 读取文本域值：条件/动作序列等 JSON 域在合法时保存解析后的结构（对齐 VSCode 结构化
     * 编辑器的数组/对象语义）；空/非法文本返回 null（跳过保存，保持默认值）。
     */
    private fun textAreaValue(area: JTextArea): Any? {
        val text = area.text
        if (text.isBlank()) return null
        if (area.getClientProperty(OK_JSON_FIELD) == true) {
            val parsed = runCatching {
                val node = objectMapper.readTree(text.trim())
                objectMapper.convertValue(node, Any::class.java)
            }.getOrNull()
            // JSON 字段：解析成功返回结构化值，失败返回最后有效的值
            if (parsed != null) return parsed
            // 返回最后有效的值（如果有）
            @Suppress("UNCHECKED_CAST")
            return area.getClientProperty("lastValidValue") as? Any
        }
        return text
    }

    /** 条件/动作序列：多行 JSON 编辑 + 非法 JSON 红边提示（VSCode 版为结构化编辑器，此处为务实折中） */
    private fun jsonSequenceArea(
        currentValue: Any?,
        task: TaskLauncherService.TaskInfo,
        fieldKey: String,
    ): JComponent {
        val mapper = objectMapper
        // 跟踪最后有效的值，用于 JSON 无效时保留
        var lastValidValue: Any? = currentValue
        val area = JTextArea(
            when (val v = currentValue) {
                null -> ""
                is String -> v
                else -> runCatching { mapper.writerWithDefaultPrettyPrinter().writeValueAsString(v) }.getOrDefault(v.toString())
            },
        )
        area.rows = 4
        area.lineWrap = true
        area.putClientProperty(OK_JSON_FIELD, true)
        fun validateJson() {
            val text = area.text.trim()
            val parsed = if (text.isEmpty()) null else runCatching { mapper.readTree(text) }.getOrNull()
            area.border = BorderFactory.createLineBorder(if (parsed != null || text.isEmpty()) OK_BORDER else BAD_BORDER)
            // 解析成功时更新最后有效的值
            if (parsed != null) {
                lastValidValue = runCatching { mapper.convertValue(parsed, Any::class.java) }.getOrNull()
                area.putClientProperty("lastValidValue", lastValidValue)
            }
        }
        area.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) {
                currentRenderer?.markEdited(fieldKey, area)
                validateJson()
                autoSaveTaskConfig(task)
            }
            override fun removeUpdate(e: DocumentEvent?) {
                currentRenderer?.markEdited(fieldKey, area)
                validateJson()
                autoSaveTaskConfig(task)
            }
            override fun changedUpdate(e: DocumentEvent?) {
                currentRenderer?.markEdited(fieldKey, area)
                validateJson()
                autoSaveTaskConfig(task)
            }
        })
        validateJson()
        // 将最后有效的值存储到客户端属性中，供 textAreaValue 使用
        area.putClientProperty("lastValidValue", lastValidValue)
        return JBScrollPane(area).apply { preferredSize = Dimension(200, 90) }
    }

    private fun autoSaveTaskConfig(task: TaskLauncherService.TaskInfo) {
        // 参数变更在 EDT 上高频触发：先构建快照，文件 IO 经 400ms 防抖后放到后台执行
        // 从编辑的控件同步到其他重复控件
        currentRenderer?.syncFromEdited()
        visibilityRefresher?.invoke()
        val root = taskDataRoot()
        val taskKey = "${task.module}::${task.className}"
        val config = buildTaskConfig(task, root)
        pendingSave = PendingTaskSave(root, taskKey, config)
        saveTimer.restart()
    }

    private data class PendingTaskSave(
        val root: String,
        val taskKey: String,
        val config: TaskLauncherService.TaskConfig,
    )

    private fun flushPendingSave(): CompletableFuture<Boolean> {
        val (root, taskKey, config) = pendingSave ?: return taskSaveTail
        pendingSave = null
        taskSaveTail = taskSaveTail.thenApplyAsync { _ ->
            try {
                taskService.saveTaskConfig(taskKey, config, root)
                // 执行器是常驻进程：参数覆盖必须即时推送，否则要重启执行器才生效
                pushParamOverrides(root)
                lastTaskSaveError = ""
                true
            } catch (e: Exception) {
                LOG.warn("Failed to save task config for $taskKey", e)
                lastTaskSaveError = e.message.orEmpty()
                // 静默丢保存 = 参数编辑无声丢失（对齐 VS Code：showErrorMessage + webview 状态条）
                com.intellij.notification.NotificationGroupManager.getInstance()
                    .getNotificationGroup("okScriptToolkit")
                    .createNotification(
                        OkScriptToolkitBundle.message("taskLauncher.saveFailed", e.message ?: "unknown"),
                        com.intellij.notification.NotificationType.ERROR,
                    )
                    .notify(project)
                false
            }
        }
        return taskSaveTail
    }

    /**
     * 由表单当前值构建任务配置。
     *
     * **起点是既有快照的副本**（对齐 VS Code sanitizeTaskConfig 的「永不删键」决议）：
     * schema 已不存在的孤儿键（default_config 里删掉的旧键）原样保留 —— 键回归 schema
     * 时设置自动复活。之前只收集表单控件，用户一编辑就把孤儿键从 tasks.json 里抹掉了。
     * 表单没渲染出来的键（schema 未就绪 / 隐藏字段）同样走这条路径保留。
     *
     * **legacy 字段必须带过来**：`extraArgs` / `env` 在单进程执行器模型下已不生效
     * （[warnLegacyPerTaskSettings] 会在启动时提示一次），但 UI 上早就没有它们的入口了 ——
     * 如果这里只返回 `params`，用户的历史配置会在下一次自动保存时被**静默清空**。
     *
     * VS Code 版在 `taskLauncher.ts:406-408` 明确做了这件事
     * （`if (!config.extraArgs && existing.extraArgs) config.extraArgs = existing.extraArgs;`），
     * IntelliJ 侧原先漏了 —— 同一份 `tasks.json` 会在两端之间来回丢数据。
     *
     * 注意「不让用户手填的字段被清掉」比「及时清理废弃字段」更重要：
     * 清理是单向不可逆的，而多留两个已知不生效的键只是噪音。
     */
    private fun buildTaskConfig(task: TaskLauncherService.TaskInfo, root: String): TaskLauncherService.TaskConfig {
        val existing = taskService.getTaskConfig(taskKeyOf(task), root)
        val params = LinkedHashMap<String, Any>(existing.params.orEmpty())
        for ((key, component) in paramFields) {
            // 使用 renderer 的 getValueControl 方法获取值控件
            val actualComponent = currentRenderer?.getValueControl(key) ?: component
            when (actualComponent) {
                is JCheckBox -> params[key] = actualComponent.isSelected
                is JSpinner -> params[key] = actualComponent.value
                // 空串也是有效值：用户明确清空参数 → 执行器收到 ""；
                // 丢键会让执行器回退项目配置值，UI 显示与实际执行就不一致了（对齐 VS Code buildText）
                is JTextField -> params[key] = actualComponent.text
                is JTextArea ->
                    if (actualComponent.getClientProperty(OK_JSON_FIELD) == true) {
                        // JSON 字段：非法/清空时保持既有值（对齐 VS Code：解析失败不提交新值也不删旧值）
                        textAreaValue(actualComponent)?.let { params[key] = it }
                    } else {
                        params[key] = actualComponent.text
                    }
                // 对齐 VSCode：表单当前值全量保存（包括空列表）
                is ListEditorComponent -> params[key] = actualComponent.value
                is JComboBox<*> -> actualComponent.selectedItem?.let { params[key] = it }
                is JList<*> -> {
                    val selectedValues = actualComponent.selectedValuesList.toList()
                    params[key] = selectedValues
                }
            }
        }
        return TaskLauncherService.TaskConfig(
            params = params.ifEmpty { null },
            // 原样保留：UI 已无入口，丢了就再也找不回来
            extraArgs = existing.extraArgs,
            env = existing.env,
        )
    }

    /**
     * 工具栏「启动执行器」：显式拉起常驻执行器。已勾选的触发任务会按启用集合入列轮询。
     * 环境不满足时 [ensureExecutor] 会自己弹错误提示。
     */
    private fun startExecutorManual() {
        if (ensureExecutor()) renderTaskStatuses(taskRunner.currentState())
    }

    /** 一次性任务：入队到常驻执行器，执行一次后自动出队 */
    private fun enqueueTask(task: TaskLauncherService.TaskInfo) {
        if (!ensureExecutor()) return
        if (!taskRunner.enqueueOnetime(taskKeyOf(task), taskDataRoot())) {
            statusLabel.text = OkScriptToolkitBundle.message("taskLauncher.executorNotRunning")
            return
        }
        statusLabel.text = OkScriptToolkitBundle.message("taskLauncher.enqueued", task.displayName)
    }

    /** 停掉当前正在执行的任务，轮询继续 */
    private fun stopCurrentTask() {
        if (!taskRunner.isRunning()) {
            statusLabel.text = OkScriptToolkitBundle.message("taskLauncher.executorNotRunning")
            return
        }
        // 跨项目防护（对齐 VS Code stopCurrent 的 isCurrentProjectExecutor 门）：别打断别的项目的任务
        if (!executorMatchesProject()) return
        if (!taskRunner.stopCurrent(detectProjectPath())) {
            statusLabel.text = OkScriptToolkitBundle.message("taskLauncher.executorNotRunning")
        }
    }

    /** 关闭常驻执行器（进程级；与「停止当前任务」不同）。跨项目时保留：这是解除 mismatch 的出口 */
    private fun closeExecutor() {
        if (!taskRunner.isActive()) {
            statusLabel.text = OkScriptToolkitBundle.message("taskLauncher.executorNotRunning")
            return
        }
        taskRunner.stopExecutor()
    }

    /**
     * 确保常驻执行器已启动：一次连接游戏，之后全部触发任务由框架 TaskExecutor 循环
     * 轮询；一次性任务经 stdin 入队。环境变量里带上启用集合与全量参数覆盖。
     */
    private fun ensureExecutor(): Boolean {
        val projectDir = checkedProjectPath(statusLabel, "taskLauncher.noProject") ?: return false
        // 设置已切到别的项目而表格尚未刷新：旧任务不能向新项目执行器入队。
        if (displayedProjectDir.isNotBlank() && displayedProjectDir != projectDir) {
            loadTasks()
            return false
        }
        // 首次任务可能在启动后立刻执行；先等表单的防抖保存落盘并推送完成。
        saveTimer.stop()
        if (!flushPendingSave().join()) {
            statusLabel.text = OkScriptToolkitBundle.message("taskLauncher.saveFailed", lastTaskSaveError)
            return false
        }
        if (!triggerSaveTail.join()) {
            statusLabel.text = OkScriptToolkitBundle.message("taskLauncher.saveFailed", lastTriggerSaveError)
            return false
        }
        if (taskRunner.isActive()) {
            // 跨项目防护（对齐 VS Code ensureExecutor 的 projectMismatch）：执行器是
            // 项目级服务、常驻后台 —— 用户改了 okScriptProjectPath 再点启动/入队，
            // 命令会静默打进旧项目的执行器。明确挡下并提示，让用户先停掉再换项目。
            if (taskRunner.runningProjectDir != projectDir) {
                statusLabel.text = OkScriptToolkitBundle.message("taskLauncher.projectMismatch")
                JOptionPane.showMessageDialog(
                    mainPanel,
                    OkScriptToolkitBundle.message("taskLauncher.projectMismatch"),
                    OkScriptToolkitBundle.message("taskLauncher.startExecutor"),
                    JOptionPane.ERROR_MESSAGE,
                )
                return false
            }
            return true
        }
        val pythonPath = detectPythonPath()

        val env = mutableMapOf<String, String>()
        env["PYTHONIOENCODING"] = "utf-8"
        env["PYTHONUTF8"] = "1"
        // 触发任务启用集合：执行器以它为准，项目 configs 里残留的 _enabled 会被覆盖
        env["OK_TOOLKIT_TRIGGERS"] = objectMapper.writeValueAsString(enabledTriggers.toList())
        // 配置沙箱：执行器把 ok 框架的配置/截图读写全部改道到这里，绝不碰项目 configs/。
        // IntelliJ 侧数据文件都在 .idea 下，故沙箱与之并列（VS Code 版对应 .vscode）。
        // 路径与探针共用 RunDir，避免两处各写一份字面量后静默错位。
        env[RunDir.ENV] = RunDir.forProject(projectDir)
        env["OK_TOOLKIT_LOCALE"] = project.service<OkProjectDataService>().currentLocale()
        val overrides = allParamOverrides(projectDir)
        if (overrides.isNotEmpty()) {
            env["OK_LANG_HINTS_INJECT"] = objectMapper.writeValueAsString(overrides)
        }
        // 全局配置快照（#7 配置接管）：非空才注入，执行器侧拿它覆盖框架/项目 store 的
        // 全局配置。物化语义（首建继承当前值、新键取默认、孤儿键保留）由探针采集后的
        // GlobalSnapshotRules 负责，这里只管把快照带给执行器。
        val gconfig = taskService.loadGlobalConfigs(projectDir)
        if (!gconfig.isNullOrEmpty()) {
            env["OK_TOOLKIT_GCONFIG"] = objectMapper.writeValueAsString(gconfig)
        }
        warnLegacyPerTaskSettings(projectDir)

        // 工具箱共享配置：执行器启动无感沿用调试浮层开关与游戏连接
        val toolboxState = toolboxService.loadState(projectDir)
        if (toolboxState.overlay) {
            env["OK_TOOLKIT_USE_OVERLAY"] = "1"
            taskRunner.log(OkScriptToolkitBundle.message("toolbox.overlayEnabledLog"))
        }
        toolboxState.game?.let { game ->
            // 实际复用由 connect_game.py 写入的 configs/devices.json selected_hwnd 驱动，
            // 这里仅记录连接来源，便于确认执行器与工具箱操作的是同一个窗口。
            taskRunner.log(
                OkScriptToolkitBundle.message(
                    "toolbox.reuseConnection",
                    game.title.ifEmpty { game.hwnd.toString() },
                    game.pid,
                ),
            )
        }

        statusLabel.text = OkScriptToolkitBundle.message("taskLauncher.executorStarting", projectDir)
        return taskRunner.start(
            pythonPath = pythonPath,
            command = taskService.buildExecutorCommand(configModule),
            projectDir = projectDir,
            env = env,
            enabledTriggers = enabledTriggers.toList(),
        )
    }

    /** 全量参数覆盖：{module::Class: {key: value}}，执行器按任务各自取用 */
    private fun allParamOverrides(root: String): Map<String, Map<String, Any>> {
        val projectConfig = taskService.loadTaskConfigs().projects[root]
            ?: return emptyMap()
        val overrides = linkedMapOf<String, Map<String, Any>>()
        for ((key, config) in projectConfig.tasks) {
            val params = config.params ?: continue
            // 推送持久化的完整 params（含 schema 之外的孤儿键）—— 对齐 VS Code collectOverrides：
            // 孤儿键是「配置接管 · 永不删键」决议的一部分，键回归 schema 时设置要能自动复活，
            // 半路过滤掉就复活不了了。执行器侧本来就按任务取自己的键，未知键无害。
            if (params.isNotEmpty()) overrides[key] = params
        }
        return overrides
    }

    /** 参数覆盖即时推送（执行器是常驻进程，不推就要重启才生效） */
    private fun pushParamOverrides(root: String) {
        if (!taskRunner.isRunning() || taskRunner.runningProjectDir != root) return
        val json = objectMapper.writeValueAsString(allParamOverrides(root))
        taskRunner.pushParams(json, root)
    }

    private data class TaskLoadOutcome(
        val result: TaskLauncherService.SchemaProbeResult,
        val probeError: String? = null,
        val usedCache: Boolean = false,
    )

    /** 历史配置里的 extraArgs / env 在单进程模型下无法按任务生效，启动时提示一次 */
    private fun warnLegacyPerTaskSettings(root: String) {
        val tasks = taskService.loadTaskConfigs().projects[root]?.tasks ?: return
        val affected = tasks.values.count { !it.extraArgs.isNullOrBlank() || !it.env.isNullOrEmpty() }
        if (affected > 0) {
            taskRunner.log(OkScriptToolkitBundle.message("taskLauncher.legacySettingsIgnored", affected))
        }
    }

    private fun taskKeyOf(task: TaskLauncherService.TaskInfo): String = "${task.module}::${task.className}"

    private fun taskKindOf(task: TaskLauncherService.TaskInfo): String =
        task.kind ?: schemas[taskKeyOf(task)]?.kind ?: TaskRowState.ONETIME

    /** 勾选集合与执行器快照对齐（执行器运行中它为准）；卡片勾选框经 refreshStatuses 同步 */
    private fun syncTriggerCheckboxes(state: TaskRunnerService.ExecutorState) {
        if (state.status != "idle" && enabledTriggers.toList() != state.enabledTriggers) {
            enabledTriggers.clear()
            enabledTriggers.addAll(state.enabledTriggers)
            persistEnabledTriggers()
        }
        taskCardList.refreshStatuses()
    }

    /** 状态刷新：逐卡原地更新 + 队列条（对齐 webview 的 updateRunningState + renderQueueStrip） */
    private fun renderTaskStatuses(state: TaskRunnerService.ExecutorState) {
        taskCardList.refreshStatuses()
        renderQueueStrip(state)
    }

    /** 状态单元格：文案 + 语义色调 + 徽标/启动按钮的可用性（卡片与详情区 chip 共用） */
    private data class StatusCell(val text: String, val tone: Int, val launchEnabled: Boolean)

    /** 状态：触发任务看入列 / 轮询，一次性任务看排队 / 执行 / schema 健康度 */
    private fun statusCellFor(
        task: TaskLauncherService.TaskInfo,
        state: TaskRunnerService.ExecutorState,
    ): StatusCell {
        val key = taskKeyOf(task)
        val isTrigger = taskKindOf(task) == TaskRowState.TRIGGER
        val schema = schemas[key]
        val readyText = OkScriptToolkitBundle.message("taskLauncher.statusReady")
        val text = when {
            state.current == key -> OkScriptToolkitBundle.message(
                if (isTrigger) "taskLauncher.triggerPolling" else "taskLauncher.taskExecuting",
            )
            // 执行器没跑时不能显示「已入列」—— 那时根本没在轮询，会误导用户。
            // 改成「已启用」表达「已记录，待启动」（对齐 VSCode 端 armed 徽标）。
            isTrigger -> {
                val armed = enabledTriggers.contains(key)
                val running = state.status == "running" || state.status == "connecting"
                when {
                    !armed -> OkScriptToolkitBundle.message("taskLauncher.triggerDisabled")
                    running -> OkScriptToolkitBundle.message("taskLauncher.triggerEnqueued")
                    else -> OkScriptToolkitBundle.message("taskLauncher.triggerArmed")
                }
            }
            state.onetimeQueue.contains(key) -> OkScriptToolkitBundle.message("taskLauncher.taskQueued")
            schema?.broken == true -> OkScriptToolkitBundle.message("taskLauncher.schemaBroken")
            schema?.error != null -> OkScriptToolkitBundle.message("taskLauncher.schemaError")
            else -> readyText
        }
        // ▶启动按钮：执行器属于别的项目 / 该任务正排队或执行中时禁用（对齐 webview launch.disabled）
        val launchEnabled = !taskRunner.isActive() && executorMatchesProject() &&
            state.current != key && !state.onetimeQueue.contains(key)
        return StatusCell(
            text = text,
            tone = TaskRowState.statusTone(
                kind = if (isTrigger) TaskRowState.TRIGGER else TaskRowState.ONETIME,
                running = state.current == key,
                enabled = enabledTriggers.contains(key),
                queued = state.onetimeQueue.contains(key),
                schemaBroken = schema?.broken == true,
                schemaError = schema?.error != null,
            ),
            launchEnabled = launchEnabled,
        )
    }

    /**
     * 触发任务勾选：只更新持久化集合并即时入列 / 出列。
     *
     * 这里**不会**拉起执行器 —— 勾选只是「记录我要跑哪些触发任务」的意图，
     * 不等于「现在开始跑」。想真正启动请点工具栏的启动按钮（[startExecutorManual]）。
     * 执行器已在运行时勾选依然即时生效（对齐 VSCode 端 setTriggerEnabled）。
     */
    private fun setTriggerEnabled(task: TaskLauncherService.TaskInfo, enabled: Boolean) {
        if (displayedProjectDir.isNotBlank() && displayedProjectDir != detectProjectPath()) {
            loadTasks()
            return
        }
        val root = taskDataRoot()
        val key = taskKeyOf(task)
        if (enabled) enabledTriggers.add(key) else enabledTriggers.remove(key)
        persistEnabledTriggers()
        renderTaskStatuses(runnerStateForDisplay())
        if (taskRunner.isActive()) {
            // 勾选是本项目的数据（已持久化），但别把它推给别的项目的执行器
            // （对齐 VS Code setTriggerEnabled 的 isCurrentProjectExecutor 门）
            if (!executorMatchesProject()) return
            if (!taskRunner.setTriggerEnabled(key, enabled, root)) {
                statusLabel.text = OkScriptToolkitBundle.message("taskLauncher.executorNotRunning")
            }
        }
    }

    private fun persistEnabledTriggers() {
        val root = taskDataRoot()
        val keys = enabledTriggers.toList()
        triggerSaveTail = triggerSaveTail.thenApplyAsync { _ ->
            try {
                taskService.saveEnabledTriggers(keys, root)
                lastTriggerSaveError = ""
                true
            } catch (e: Exception) {
                LOG.warn("Failed to save enabled triggers", e)
                lastTriggerSaveError = e.message.orEmpty()
                com.intellij.notification.NotificationGroupManager.getInstance()
                    .getNotificationGroup("okScriptToolkit")
                    .createNotification(
                        OkScriptToolkitBundle.message("taskLauncher.saveFailed", lastTriggerSaveError),
                        com.intellij.notification.NotificationType.ERROR,
                    )
                    .notify(project)
                false
            }
        }
    }

    // ── 任务卡列表宿主（TaskCardHost 实现）───────────────────────────

    private inner class CardHost : TaskCardHost {
        override fun taskList(): List<TaskLauncherService.TaskInfo> = tasks

        override fun schemaOf(task: TaskLauncherService.TaskInfo): TaskLauncherService.TaskSchema? =
            schemas[taskKeyOf(task)]

        override fun kindOf(task: TaskLauncherService.TaskInfo): String = taskKindOf(task)

        override fun isTriggerEnabled(taskKey: String): Boolean = enabledTriggers.contains(taskKey)

        override fun cardStatusOf(task: TaskLauncherService.TaskInfo): TaskCardStatus {
            val cell = statusCellFor(task, runnerStateForDisplay())
            return TaskCardStatus(cell.text, cell.tone, cell.launchEnabled)
        }

        override fun isCollapsed(foldKey: String): Boolean = uiCollapseState[foldKey] == true

        override fun onCollapseChanged(foldKey: String, collapsed: Boolean) {
            hoverSuppressUntil = System.currentTimeMillis() + 1200
            uiCollapseState = uiCollapseState + (foldKey to collapsed)
            // 折叠是低频操作：同步落盘（saveUiStateValue 内部有 storeLock，与其它写路径互不交错）
            try {
                taskService.saveUiStateValue(foldKey, collapsed, taskDataRoot())
            } catch (e: Exception) {
                LOG.warn("Failed to persist UI collapse state", e)
            }
        }

        override fun onTaskActivated(task: TaskLauncherService.TaskInfo) {
            hoverSuppressUntil = System.currentTimeMillis() + 1200
            cancelTaskHover()
            loadTaskParams(task)
        }

        override fun onToggleTrigger(task: TaskLauncherService.TaskInfo, enabled: Boolean) {
            hoverSuppressUntil = System.currentTimeMillis() + 1200
            setTriggerEnabled(task, enabled)
        }

        override fun onRunTask(task: TaskLauncherService.TaskInfo) {
            hoverSuppressUntil = System.currentTimeMillis() + 1200
            enqueueTask(task)
        }

        override fun onTaskHover(task: TaskLauncherService.TaskInfo, anchor: JComponent) {
            scheduleTaskHover(task, anchor)
        }

        override fun onTaskHoverEnd() {
            cancelTaskHover()
        }
    }

    /**
     * 覆盖徽标（对齐 webview snapshotDiffersFromFactory）：任一键快照值 ≠ 出厂值
     * （default 显式声明且不相等），或存在孤儿键（default_config 已删）。全量接管后
     * params 恒非空，旧的「非空即亮」判断会失去意义。
     */
    private fun snapshotDiffersFromFactory(taskKey: String): Boolean {
        val params = taskService.getTaskConfig(taskKey, taskDataRoot()).params ?: return false
        if (params.isEmpty()) return false
        val known = HashSet<String>()
        schemas[taskKey]?.fields?.forEach { f ->
            known.add(f.key)
            if (f.hasDefault && params.containsKey(f.key) && params[f.key] != f.default) return true
        }
        return params.keys.any { it !in known }
    }

    /**
     * 卡片上的快照操作（⇄ / ⟲）公共骨架：守卫 schema、先提交防抖中的表单编辑，
     * 再在最新快照上应用 [mutation]，落盘并推送执行器。返回键变更数；schema 不就绪时返回 null。
     */
    private fun mutateTaskSnapshot(
        task: TaskLauncherService.TaskInfo,
        mutation: (params: LinkedHashMap<String, Any?>, schema: TaskLauncherService.TaskSchema) -> Int,
    ): Int? {
        val key = taskKeyOf(task)
        val schema = schemas[key] ?: return null
        if (schema.broken || schema.fields.isEmpty()) return null
        // 防抖中的表单编辑先落盘：卡面操作必须基于最新快照，且不能被随后的 flush 覆盖
        saveTimer.stop()
        flushPendingSave().join()
        val root = taskDataRoot()
        return try {
            val existing = taskService.getTaskConfig(key, root)
            val params = LinkedHashMap<String, Any?>(existing.params.orEmpty())
            val changed = mutation(params, schema)
            if (changed > 0) {
                // 探针 JSON 允许显式 null；与 GlobalSnapshotRules 同款强转
                @Suppress("UNCHECKED_CAST")
                taskService.saveTaskConfig(key, existing.copy(params = params as Map<String, Any>), root)
                // 执行器是常驻进程：快照变化必须即时推送，否则要重启才生效
                pushParamOverrides(root)
                // 参数面板开着时跟随刷新（对齐 VS Code snapshotUpdated → refreshDrawer）
                if (detailTask?.let { taskKeyOf(it) } == key) loadTaskParams(task)
                renderTaskStatuses(runnerStateForDisplay())
            }
            changed
        } catch (e: Exception) {
            LOG.warn("Failed to mutate task snapshot for $key", e)
            com.intellij.notification.NotificationGroupManager.getInstance()
                .getNotificationGroup("okScriptToolkit")
                .createNotification(
                    OkScriptToolkitBundle.message("taskLauncher.saveFailed", e.message ?: "unknown"),
                    com.intellij.notification.NotificationType.ERROR,
                )
                .notify(project)
            null
        }
    }

    /**
     * ⇄ 同步 default（对齐 VS Code syncDefaultToSnapshot 决议 1：并集扩张）——
     * 补缺键（取出厂值 default ?? value），已有键值不动，孤儿键保留。
     */
    private fun syncTaskDefault(task: TaskLauncherService.TaskInfo) {
        val added = mutateTaskSnapshot(task) { params, schema ->
            var count = 0
            for (f in schema.fields) {
                if (params.containsKey(f.key)) continue
                params[f.key] = f.defaultOrValue()
                count++
            }
            count
        } ?: return
        statusLabel.text = if (added > 0) {
            OkScriptToolkitBundle.message("taskLauncher.syncDefaultDone", added)
        } else {
            OkScriptToolkitBundle.message("taskLauncher.syncDefaultNoop")
        }
    }

    /**
     * ⟲ 恢复默认（对齐 VS Code resetSnapshotToDefault 决议 2：出厂值）——
     * 快照里 default 仍存在的键重置为出厂值（显式 null 也算）；孤儿键保留。
     */
    private fun resetTaskDefault(task: TaskLauncherService.TaskInfo) {
        val reset = mutateTaskSnapshot(task) { params, schema ->
            var count = 0
            for (f in schema.fields) {
                if (!f.hasDefault) continue
                if (params[f.key] == f.default) continue
                params[f.key] = f.default
                count++
            }
            count
        } ?: return
        statusLabel.text = if (reset > 0) {
            OkScriptToolkitBundle.message("taskLauncher.resetDefaultDone", reset)
        } else {
            OkScriptToolkitBundle.message("taskLauncher.resetDefaultNoop")
        }
    }

    private fun sendControlCommand(command: String) {
        if (!taskRunner.isRunning()) {
            statusLabel.text = OkScriptToolkitBundle.message("taskLauncher.executorNotRunning")
            return
        }
        // 跨项目防护（对齐 VS Code：pause/resume/stopCurrent 都先过 isCurrentProjectExecutor）
        if (!executorMatchesProject()) return
        if (!taskRunner.sendCommand(command, detectProjectPath())) {
            statusLabel.text = OkScriptToolkitBundle.message("toolbox.sendCommandFailed", command)
        }
    }

    fun onDispose() {
        // 只解绑视图：常驻执行器由 TaskRunnerService 持有，工具窗关闭后继续在后台运行，
        // 日志也留在 Run 工具窗口里
        disposed = true
        loadGeneration++
        saveTimer.stop()
        flushPendingSave().join()
        triggerSaveTail.join()
        globalSaveTimer.stop()
        flushPendingGlobalSave()
        toolboxService.removeStateListener(toolboxStateListener)
        toolboxService.removeStatusListener(toolboxStatusListener)
        taskRunner.removeStateListener(runnerStateListener)
        accountEditor?.close()
    }
}


