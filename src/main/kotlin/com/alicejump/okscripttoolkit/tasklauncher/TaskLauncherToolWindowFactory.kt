package com.alicejump.okscripttoolkit.tasklauncher

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.alicejump.okscripttoolkit.core.OkProjectDataService
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
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.UIUtil
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Color
import java.util.concurrent.CompletableFuture
import java.nio.file.Files
import java.nio.file.Paths
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

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

        /** 左侧列表里参数标签列的最小宽度：所有行的标签右对齐在同一条竖线上 */
        private const val LABEL_COLUMN_WIDTH = 88

        /**
         * 操作列（左栏第 0 列）：触发任务是复选框、一次性任务是运行按钮。
         * 表格里多处按序号取列，集中成常量免得改列序时漏掉某处。
         */
        private const val ACTION_COLUMN = 0

        // 状态语义色（亮 / 暗主题各一套）；色调编号见 TaskRowState.TONE_*
        // 语义色统一收敛到 TaskLauncherTheme（对应 VS Code 侧 tokens.css 的 token 单点）；
        // 这里保留原名作别名，包内既有引用零改动。tasklauncher 包内禁止再写 Color(0x…) 字面量。
        private val COLOR_GOOD = TaskLauncherTheme.OK
        private val COLOR_WARN = TaskLauncherTheme.WARN
        private val COLOR_BAD = TaskLauncherTheme.ERR
        private val COLOR_TRIGGER = TaskLauncherTheme.TRIGGER

        private fun colorForTone(tone: Int): JBColor = TaskLauncherTheme.colorForTone(tone)
    }

    val mainPanel: JPanel
    private val taskService = TaskLauncherService(project)
    private val toolboxService = ToolboxService.getInstance(project)

    /** 每行的任务类型（trigger / onetime），供表格勾选列判断可编辑性 */
    private val rowKinds = mutableListOf<String>()

    /** 每行状态的语义色调（见 TONE_*），状态列渲染器据此着色 */
    private val rowTones = mutableListOf<Int>()

    /** 程序化改写表格时抑制 TableModelListener 的副作用 */
    private var updatingTableModel = false

    /** 已勾选「启用」的触发任务 key（module::Class），持久化到 .idea/ok-script-toolkit-tasks.json */
    private val enabledTriggers = linkedSetOf<String>()

    /**
     * 左栏是「主」列表：操作 / 任务 / 状态三列。
     *
     * **类型不再靠图标表达** —— 操作列直接给出各自真正可点的控件：
     * 触发任务是一个复选框（勾选即入列轮询），一次性任务是一枚运行按钮（点一下入队跑一次）。
     * 任务列只显示名字，不放任何图标（放过的图标看着能点、实际不能点）。
     */
    private val taskTableModel = object : DefaultTableModel(
        arrayOf(
            OkScriptToolkitBundle.message("taskLauncher.actionColumn"),
            OkScriptToolkitBundle.message("taskLauncher.taskColumn"),
            OkScriptToolkitBundle.message("taskLauncher.statusColumn"),
        ),
        0,
    ) {
        // 必须返回包装类 Boolean（而不是基本类型 boolean），否则 Swing 不会套用复选框
        // 渲染器 / 编辑器；拆成提前 return 也避开了 if/else 推导出交叉类型的告警。
        override fun getColumnClass(columnIndex: Int): Class<*> {
            if (columnIndex == ACTION_COLUMN) return java.lang.Boolean::class.javaObjectType
            return String::class.java
        }

        /**
         * 只有触发任务可以「编辑」—— 那一格是复选框。
         * 一次性任务那一格是运行按钮，编辑语义在 [installActionColumnClick] 里，不走编辑器。
         */
        override fun isCellEditable(row: Int, column: Int): Boolean =
            column == ACTION_COLUMN && rowKinds.getOrElse(row) { TaskRowState.ONETIME } == TaskRowState.TRIGGER
    }
    private val taskTable = JBTable(taskTableModel)
    private val refreshAction = ToolbarAction(AllIcons.Actions.Refresh, OkScriptToolkitBundle.message("taskLauncher.refresh")) { loadTasks() }
    /** 显式启动执行器：勾选触发任务不再隐式拉起，启动行为集中在这里 */
    private val startExecutorAction = ToolbarAction(AllIcons.Actions.Execute, OkScriptToolkitBundle.message("taskLauncher.startExecutor")) { startExecutorManual() }
    private val stopCurrentAction = ToolbarAction(AllIcons.Actions.Suspend, OkScriptToolkitBundle.message("taskLauncher.stopCurrent")) { stopCurrentTask() }
    private val closeExecutorAction = ToolbarAction(AllIcons.Actions.Cancel, OkScriptToolkitBundle.message("taskLauncher.closeExecutor")) { closeExecutor() }
    private val pauseAction = ToolbarAction(AllIcons.Actions.Pause, OkScriptToolkitBundle.message("taskLauncher.pause")) { sendControlCommand("pause") }
    private val resumeAction = ToolbarAction(AllIcons.Actions.Play_forward, OkScriptToolkitBundle.message("taskLauncher.resume")) { sendControlCommand("resume") }
    private lateinit var actionToolbar: com.intellij.openapi.actionSystem.ActionToolbar
    private val statusLabel = JBLabel()
    private val progressBar = JProgressBar()

    // ── 健康度条（#9 rc-health 的 Swing 等价物）──────────────────────
    // 健康点 = 状态色的最小可视化单元，与状态文字同源同色；当前任务 chip 揭示
    // 「此刻在跑什么」，触发/一次性用不同描边色（复用 detailKindChip 的色语义）。
    private val healthDot = TaskLauncherTheme.HealthDot()
    private val currentTaskChip = JBLabel()
    private var lastRunnerStatus = ""

    // ── 运行中心（#9 rc-queue / gpop 的 Swing 等价物）────────────────
    // ⚠️ 必须声明在 init 块之前：Kotlin 按文本顺序执行初始化器，init → initUI()
    // → showDetailPlaceholder() → renderRunCenter() 会读下面这些字段；
    // 声明在 init 之后 = 构造期读 JVM 默认值（NPE），且初始化器随后把
    // showDetailPlaceholder() 设的 runCenterVisible=true 覆盖回 false。
    /** 详情区当前渲染的是运行中心（无选中任务）——syncRunnerState 时跟随刷新 */
    private var runCenterVisible = false

    /** 探针采集到的全局配置组（运行中心「全局配置」区数据源；applyProbeResult 更新） */
    private var globalConfigGroups: List<TaskLauncherService.GlobalConfigGroup> = emptyList()

    /** 悬停弹层抑制截止时间：点选/切换后 1.2s 内不弹（对齐主仓库约定） */
    private var hoverSuppressUntil = 0L

    private val hoverPopupDelayMs = 800

    /** 最近一次物化的新增键数（供 applyProbeResult 的状态提示取用） */
    private var lastMaterializedCount = 0

    private val paramPanel = JPanel(GridBagLayout())
    private val paramFields = mutableMapOf<String, JComponent>()

    private var tasks = listOf<TaskLauncherService.TaskInfo>()
    private var configModule = "src.config"
    private var schemas = mapOf<String, TaskLauncherService.TaskSchema>()
    private val saveTimer = javax.swing.Timer(400, null)

    // ── 右侧详情区（选中谁就显示谁）──
    private val detailTitle = JBLabel()
    private val detailKindChip = JBLabel()
    private val detailStateChip = JBLabel()
    private val detailActionButton = JButton()
    /** 详情区当前展示的任务；null = 未选中，显示占位提示 */
    private var detailTask: TaskLauncherService.TaskInfo? = null

    /** 状态栏右侧的「查看日志」入口：把 Run 工具窗口的控制台拉到前台 */
    private val viewLogButton = JButton(OkScriptToolkitBundle.message("taskLauncher.viewLog"))

    /** 任务进程与运行状态由项目级服务持有：工具窗关闭不影响后台任务 */
    private val taskRunner = TaskRunnerService.getInstance(project)

    // ── Toolbox（游戏连接 + 调试浮层，状态由 ToolboxService 持有）──
    /** 角色管理入口按钮，见 buildCharactersEntry()。 */
    private val charactersButton = JButton(OkScriptToolkitBundle.message("characterManager.open"))
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

    /** 按执行器状态刷新工具栏按钮、状态栏与勾选列（EDT） */
    private fun syncRunnerState(state: TaskRunnerService.ExecutorState) {
        val active = state.status == "running" || state.status == "connecting"
        // 显式启动：执行器起来之前才可用，起来后让位给暂停/停止（对齐 VSCode 端按钮显隐）
        startExecutorAction.isEnabled2 = !active
        stopCurrentAction.isEnabled2 = state.current.isNotEmpty()
        closeExecutorAction.isEnabled2 = active
        pauseAction.isEnabled2 = state.status == "running" && !state.paused
        resumeAction.isEnabled2 = state.status == "running" && state.paused
        actionToolbar.updateActionsAsync()
        val statusText = when (state.status) {
            "connecting" -> OkScriptToolkitBundle.message("taskLauncher.executorConnecting")
            "running" -> if (state.paused) {
                OkScriptToolkitBundle.message("taskLauncher.executorPaused")
            } else {
                OkScriptToolkitBundle.message("taskLauncher.executorRunning", state.enabledTriggers.size)
            }
            else -> state.finishMessage ?: OkScriptToolkitBundle.message("taskLauncher.executorIdle")
        }
        // 控制命令失败时把错误拼在状态前（run_executor.py 在命令失败后不推状态，
        // 错误会一直保留到下一条状态快照到达）
        statusLabel.text = state.controlError?.let { "$it — $statusText" } ?: statusText
        syncHealthBar(state)
        syncTriggerCheckboxes(state)
        renderTaskStatuses(state)
        // 状态 chip 与「启用/停用轮询」按钮文案跟着执行器状态走
        renderDetailHeader(detailTask, state)
        // 运行中心在详情区挂着时跟随状态刷新（无选中任务 = 运行中心可见）
        if (detailTask == null && runCenterVisible) {
            renderRunCenter(state)
        }
    }

    /** 健康点取色 + 当前任务 chip（#9 rc-health）：与状态文字同源，一次状态一条视觉线 */
    private fun syncHealthBar(state: TaskRunnerService.ExecutorState) {
        healthDot.color = when {
            state.controlError != null -> TaskLauncherTheme.ERR
            state.status == "running" && state.paused -> TaskLauncherTheme.PAUSE
            state.status == "running" || state.status == "connecting" -> TaskLauncherTheme.RUN
            state.finishMessage != null -> TaskLauncherTheme.OK
            else -> UIUtil.getLabelDisabledForeground()
        }
        val running = state.status == "running" && state.current.isNotEmpty()
        if (running) {
            val isTrigger = state.currentIsTrigger
            TaskLauncherTheme.styleChip(
                currentTaskChip,
                if (isTrigger) TaskLauncherTheme.TRIGGER else TaskLauncherTheme.ONETIME,
                displayNameOf(state.current),
            )
            currentTaskChip.isVisible = true
            currentTaskChip.toolTipText = state.current
        } else {
            currentTaskChip.isVisible = false
            currentTaskChip.toolTipText = null
        }
        lastRunnerStatus = state.status
    }

    /** 任务 key（module::Class）→ 显示名；未知任务退回 key 本身 */
    private fun displayNameOf(taskKey: String): String {
        schemas[taskKey]?.displayName?.let { return it }
        val cls = taskKey.substringAfter("::", taskKey)
        return tasks.firstOrNull { it.module == taskKey.substringBefore("::") && it.className == cls }
            ?.displayName ?: taskKey
    }

    /**
     * 布局：左「主」列表 / 右「详」参数，只有一个水平分隔条。
     *
     * 旧版是上（列表）/ 中（参数）/ 下（日志）三段纵向堆叠，列表与参数都被拉满整个
     * 窗口宽度 —— 一行就是一条又矮又长的条条。现在日志交给 Run 工具窗口
     * （见 [TaskRunnerService.showConsole]），面板只留列表 + 参数，改成左右分栏。
     */
    private fun initUI() {
        stopCurrentAction.isEnabled2 = false
        closeExecutorAction.isEnabled2 = false
        pauseAction.isEnabled2 = false
        resumeAction.isEnabled2 = false

        val actionGroup = com.intellij.openapi.actionSystem.DefaultActionGroup(
            refreshAction, startExecutorAction, stopCurrentAction, closeExecutorAction, pauseAction, resumeAction,
        )
        actionToolbar = com.intellij.openapi.actionSystem.ActionManager.getInstance()
            .createActionToolbar("ok-script-tasks", actionGroup, true)
        actionToolbar.targetComponent = mainPanel
        val toolbar = actionToolbar.component
        toolbar.border = BorderFactory.createEmptyBorder(2, 2, 2, 6)

        taskTable.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        taskTable.showHorizontalLines = true
        taskTable.showVerticalLines = false
        taskTable.rowHeight = 24
        taskTable.selectionModel.addListSelectionListener {
            val selectedRow = taskTable.selectedRow
            if (selectedRow >= 0 && selectedRow < tasks.size) {
                loadTaskParams(tasks[selectedRow])
            } else {
                showDetailPlaceholder()
            }
        }
        // 触发任务的勾选列：勾上 = 入列轮询，取消 = 出列（等价 ok-script GUI 的启用开关）
        taskTableModel.addTableModelListener { event ->
            if (updatingTableModel) return@addTableModelListener
            if (event.type != javax.swing.event.TableModelEvent.UPDATE || event.column != 0) {
                return@addTableModelListener
            }
            val row = event.firstRow
            if (row < 0 || row >= tasks.size || rowKinds.getOrElse(row) { TaskRowState.ONETIME } != TaskRowState.TRIGGER) {
                return@addTableModelListener
            }
            setTriggerEnabled(tasks[row], taskTableModel.getValueAt(row, 0) == true)
        }
        installTableRenderers()
        installActionColumnClick()
        installTaskHoverPopup()

        val tableScrollPane = JBScrollPane(taskTable)
        tableScrollPane.border = BorderFactory.createEmptyBorder()

        val paramScrollPane = JBScrollPane(paramPanel)
        paramScrollPane.border = BorderFactory.createEmptyBorder()

        val detailPane = JPanel(BorderLayout())
        detailPane.add(buildDetailHeader(), BorderLayout.NORTH)
        detailPane.add(paramScrollPane, BorderLayout.CENTER)

        val splitPane = com.intellij.openapi.ui.Splitter(false, 0.42f)
        splitPane.firstComponent = tableScrollPane
        splitPane.secondComponent = detailPane

        // 健康度条：[健康点] [状态文字] [当前任务 chip] …… [进度条] [查看日志]
        // 健康点与状态文字同源同色（syncRunnerState 统一驱动）
        val healthRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        healthRow.isOpaque = false
        healthRow.add(healthDot)
        healthRow.add(statusLabel)
        healthRow.add(currentTaskChip)
        currentTaskChip.isVisible = false

        val statusBar = JPanel(BorderLayout(8, 0))
        statusBar.border = BorderFactory.createEmptyBorder(2, 4, 2, 4)
        statusBar.add(healthRow, BorderLayout.CENTER)
        progressBar.preferredSize = Dimension(120, 20)
        progressBar.isVisible = false
        viewLogButton.toolTipText = OkScriptToolkitBundle.message("taskLauncher.viewLogHint")
        viewLogButton.addActionListener { taskRunner.showConsole() }
        val statusEast = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0))
        statusEast.isOpaque = false
        statusEast.add(progressBar)
        statusEast.add(viewLogButton)
        statusBar.add(statusEast, BorderLayout.EAST)

        val northPane = JPanel(BorderLayout())
        northPane.add(toolbar, BorderLayout.NORTH)
        northPane.add(buildCharactersEntry(), BorderLayout.CENTER)
        northPane.add(buildToolboxBar(), BorderLayout.SOUTH)

        mainPanel.add(northPane, BorderLayout.NORTH)
        mainPanel.add(splitPane, BorderLayout.CENTER)
        mainPanel.add(statusBar, BorderLayout.SOUTH)

        showDetailPlaceholder()
    }

    // ── 左列表：渲染器 ────────────────────────────────────────────────

    /**
     * 操作列渲染器见 [TaskActionRenderer]：触发行画复选框（模型里是 Boolean，可交互），
     * 一次性行画运行按钮（模型里是 null，点击由 [installActionColumnClick] 处理）。
     */
    private fun installTableRenderers() {
        taskTable.columnModel.getColumn(0).apply {
            cellRenderer = TaskActionRenderer { row -> rowKinds.getOrElse(row) { TaskRowState.ONETIME } }
            // 要放得下一个复选框 / 一枚运行按钮，比原来的 30 稍宽。
            preferredWidth = 38
            maxWidth = 38
            resizable = false
        }
        taskTable.columnModel.getColumn(1).apply {
            cellRenderer = TaskNameRenderer()
            preferredWidth = 160
        }
        taskTable.columnModel.getColumn(2).apply {
            cellRenderer = StatusToneRenderer()
            preferredWidth = 64
            maxWidth = 96
        }
    }

    /**
     * 操作列里「一次性任务」那一格的点击 → 直接入队运行。
     *
     * 为什么不用 `TableCellEditor` 放真按钮：单元格编辑器要点两下
     * （第一下进入编辑态、第二下才按到按钮），而这里要的是"点一下就跑"。
     * 渲染器负责画成按钮，点击语义放在表格这一层。
     *
     * 只处理**一次性**行：触发行的那一格是复选框，由表格自带的 Boolean 编辑器接管，
     * 这里必须让开，否则一次点击会既改勾选又触发运行。
     */
    private fun installActionColumnClick() {
        taskTable.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(event: java.awt.event.MouseEvent) {
                if (!javax.swing.SwingUtilities.isLeftMouseButton(event)) return
                val row = taskTable.rowAtPoint(event.point)
                val column = taskTable.columnAtPoint(event.point)
                if (row < 0 || column != ACTION_COLUMN) return
                if (rowKinds.getOrElse(row) { TaskRowState.ONETIME } != TaskRowState.ONETIME) return
                taskTable.setRowSelectionInterval(row, row)
                runTaskAt(row)
            }
        })
    }

    /**
     * 任务列渲染器：**只显示任务名**。
     *
     * 曾经在名字前按类型挂图标（触发 = 轮询循环，一次性 = 运行三角）来替代单独的类型列，
     * 但那个图标看着像按钮、点了却没反应（真正能点的控件在操作列）。
     * 现在类型完全由操作列表达 —— 触发是复选框、一次性是运行按钮 ——
     * 名字列不再放任何图标；悬浮提示保留，作为文字上的补充。
     */
    private inner class TaskNameRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            icon = null
            val isTrigger = rowKinds.getOrElse(row) { TaskRowState.ONETIME } == TaskRowState.TRIGGER
            toolTipText = OkScriptToolkitBundle.message(
                if (isTrigger) "taskLauncher.triggerTask" else "taskLauncher.oneTimeTask",
            )
            return component
        }
    }

    /** 状态列渲染器：按语义着色（运行中绿 / 等待琥珀 / 异常红 / 其余次要色） */
    private inner class StatusToneRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            val tone = rowTones.getOrElse(row) { TaskRowState.TONE_NEUTRAL }
            foreground = if (isSelected) table.selectionForeground else colorForTone(tone)
            return component
        }
    }

    // ── 右详情区：任务头 ──────────────────────────────────────────────

    /**
     * 详情区头部：任务名 + 类型 chip + 状态 chip + 主操作按钮。
     * 触发任务的主操作是「启用/停用轮询」（等价左侧勾选框），一次性任务是「运行任务」。
     */
    private fun buildDetailHeader(): JPanel {
        detailTitle.font = detailTitle.font.deriveFont(Font.BOLD)
        val titleRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        titleRow.isOpaque = false
        titleRow.add(detailTitle)
        titleRow.add(detailKindChip)
        titleRow.add(detailStateChip)

        detailActionButton.addActionListener { onDetailAction() }
        val actionRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        actionRow.isOpaque = false
        actionRow.add(detailActionButton)

        return JPanel(BorderLayout(0, 6)).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(8, 8, 6, 8)
            add(titleRow, BorderLayout.NORTH)
            add(actionRow, BorderLayout.CENTER)
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

    /** 未选中任务时渲染运行中心（#9 rc-queue 的 Swing 等价物；右栏不该是空白占位文字） */
    private fun showDetailPlaceholder() {
        paramPanel.removeAll()
        paramFields.clear()
        visibilityRefresher = null
        currentRenderer = null
        runCenterVisible = true
        renderRunCenter(taskRunner.currentState())
        renderDetailHeader(null)
    }

    // ── 运行中心（#9 rc-queue / gpop 的 Swing 等价物）────────────────
    // 相关状态字段（runCenterVisible / globalConfigGroups / hoverSuppressUntil /
    // hoverPopupDelayMs / lastMaterializedCount）声明在 init 块之前的字段区 ——
    // 构造顺序约束，见那里的说明。

    /**
     * 运行中心：执行器此刻在跑什么 / 排了什么 / 轮询什么 / 全局配置有哪些。
     * 只读视图 —— 所有的动作（启动/停止/勾选）都在工具栏与任务行上，这里不给第二条入口。
     */
    private fun renderRunCenter(state: TaskRunnerService.ExecutorState) {
        paramPanel.removeAll()
        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.NORTH
            insets = Insets(8, 12, 0, 12)
        }

        // 标题行：运行中心 + 健康点（与 statusBar 同源同色，一次状态一条视觉线）
        val titleRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        titleRow.isOpaque = false
        val dot = TaskLauncherTheme.HealthDot()
        dot.color = healthDot.color
        val titleLabel = JBLabel(OkScriptToolkitBundle.message("taskLauncher.runCenter.title"))
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD)
        titleRow.add(dot)
        titleRow.add(titleLabel)
        paramPanel.add(titleRow, gbc)

        // 当前任务区
        gbc.gridy++
        paramPanel.add(
            sectionCard(OkScriptToolkitBundle.message("taskLauncher.runCenter.current")) { panel ->
                if (state.current.isEmpty()) {
                    panel.add(mutedLabel(OkScriptToolkitBundle.message("taskLauncher.runCenter.idle")))
                } else {
                    val chip = JBLabel()
                    TaskLauncherTheme.styleChip(
                        chip,
                        if (state.currentIsTrigger) TaskLauncherTheme.TRIGGER else TaskLauncherTheme.ONETIME,
                        displayNameOf(state.current),
                    )
                    chip.toolTipText = state.current
                    panel.add(chip)
                }
            },
            gbc,
        )

        // 一次性队列区：逐行列出，点击选中左侧对应任务行
        gbc.gridy++
        paramPanel.add(
            sectionCard(OkScriptToolkitBundle.message("taskLauncher.runCenter.queue")) { panel ->
                if (state.onetimeQueue.isEmpty()) {
                    panel.add(mutedLabel(OkScriptToolkitBundle.message("taskLauncher.runCenter.queueEmpty")))
                } else {
                    for (key in state.onetimeQueue) panel.add(runCenterRow(key))
                }
            },
            gbc,
        )

        // 触发轮询区
        gbc.gridy++
        paramPanel.add(
            sectionCard(OkScriptToolkitBundle.message("taskLauncher.runCenter.triggers")) { panel ->
                if (state.enabledTriggers.isEmpty()) {
                    panel.add(mutedLabel(OkScriptToolkitBundle.message("taskLauncher.runCenter.queueEmpty")))
                } else {
                    for (key in state.enabledTriggers) panel.add(runCenterRow(key))
                }
            },
            gbc,
        )

        // 全局配置区（#7）：组行 hover 弹只读字段摘要
        if (globalConfigGroups.isNotEmpty()) {
            gbc.gridy++
            paramPanel.add(
                sectionCard(OkScriptToolkitBundle.message("taskLauncher.gconfigSection")) { panel ->
                    for (group in globalConfigGroups) panel.add(globalGroupRow(group))
                },
                gbc,
            )
        }

        // 底部弹簧：内容顶对齐
        gbc.gridy++
        gbc.weighty = 1.0
        gbc.fill = GridBagConstraints.BOTH
        gbc.insets = Insets(0, 0, 0, 0)
        paramPanel.add(JPanel().apply { isOpaque = false }, gbc)

        paramPanel.revalidate()
        paramPanel.repaint()
    }

    /**
     * 运行中心区段：组头（弱化小标题）+ 内容体。行级面语义，不做描边方块墙。
     * 参数名不能叫 fill —— 会遮蔽 apply{} 里 GridBagConstraints.fill（Kotlin 局部
     * 作用域优先于隐式 receiver 成员，赋值会命中参数而不是字段）。
     */
    private fun sectionCard(title: String, build: (JPanel) -> Unit): JPanel {
        val section = JPanel(BorderLayout(0, 2))
        section.isOpaque = false
        section.add(mutedLabel(title), BorderLayout.NORTH)
        val body = JPanel(GridBagLayout())
        body.isOpaque = false
        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.WEST
        }
        val receiver = JPanel(BorderLayout())
        receiver.isOpaque = false
        build(receiver)
        body.add(receiver, gbc)
        section.add(body, BorderLayout.CENTER)
        return section
    }

    /** 弱化文字（--text-muted 语义：只用于辅助信息，不用于正文） */
    private fun mutedLabel(text: String): JBLabel {
        val label = JBLabel(text)
        label.foreground = UIUtil.getLabelDisabledForeground()
        return label
    }

    /**
     * 行级可点击区（#10 两层语义之二）：默认 stripe 浅底、无描边，hover 增强，
     * 点击选中左侧任务行。**hover 不承担「让用户发现可点击」的职责** ——
     * 默认底色即与周围内容有色差。
     */
    private fun runCenterRow(taskKey: String): JPanel {
        val row = JPanel(FlowLayout(FlowLayout.LEFT, 6, 1))
        row.isOpaque = true
        row.background = TaskLauncherTheme.rowBackground()
        row.border = BorderFactory.createEmptyBorder(1, 6, 1, 6)
        row.add(JBLabel(displayNameOf(taskKey)))
        row.toolTipText = taskKey
        row.addMouseListener(rowClickAdapter(row) { selectTaskRow(taskKey) })
        return row
    }

    /** 全局配置组行（#7/#9 gpop）：组名 + source 标注 + 字段数，hover 弹只读摘要 */
    private fun globalGroupRow(group: TaskLauncherService.GlobalConfigGroup): JPanel {
        val row = JPanel(FlowLayout(FlowLayout.LEFT, 6, 1))
        row.isOpaque = true
        row.background = TaskLauncherTheme.rowBackground()
        row.border = BorderFactory.createEmptyBorder(1, 6, 1, 6)
        row.add(JBLabel(group.displayName ?: group.name))
        if (group.source == "project_store") {
            row.add(mutedLabel(OkScriptToolkitBundle.message("taskLauncher.gconfigSourceProject")))
        }
        row.add(mutedLabel(group.fields.size.toString()))
        row.toolTipText = group.description
        val adapter = object : java.awt.event.MouseAdapter() {
            private var timer: Timer? = null

            override fun mouseEntered(e: java.awt.event.MouseEvent) {
                row.background = TaskLauncherTheme.rowHoverBackground()
                if (System.currentTimeMillis() < hoverSuppressUntil) return
                timer?.stop()
                timer = Timer(hoverPopupDelayMs) { showGroupSummaryPopup(row, group) }.apply {
                    isRepeats = false
                    start()
                }
            }

            override fun mouseExited(e: java.awt.event.MouseEvent) {
                row.background = TaskLauncherTheme.rowBackground()
                timer?.stop()
            }

            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                // 弹出层只读、无交互按钮（用户硬规则）：点击只做抑制，不给第二条入口
                hoverSuppressUntil = System.currentTimeMillis() + 1200
                timer?.stop()
            }
        }
        row.addMouseListener(adapter)
        return row
    }

    /** 行级区共用的 hover 背景/点击抑制适配器（点击回调由各行走） */
    private fun rowClickAdapter(row: JPanel, onClick: () -> Unit): java.awt.event.MouseAdapter =
        object : java.awt.event.MouseAdapter() {
            override fun mouseEntered(e: java.awt.event.MouseEvent) {
                row.background = TaskLauncherTheme.rowHoverBackground()
            }

            override fun mouseExited(e: java.awt.event.MouseEvent) {
                row.background = TaskLauncherTheme.rowBackground()
            }

            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                hoverSuppressUntil = System.currentTimeMillis() + 1200
                onClick()
            }
        }

    /** 选中左侧任务表中的行（触发 selection listener → loadTaskParams） */
    private fun selectTaskRow(taskKey: String) {
        val index = tasks.indexOfFirst { taskKeyOf(it) == taskKey }
        if (index >= 0 && index < taskTable.rowCount) {
            taskTable.setRowSelectionInterval(index, index)
        }
    }

    /** 全局配置组摘要弹层：只读、无交互按钮（用户硬规则），最多列 12 个字段。
     *  参数名不能叫 anchor —— 会遮蔽 apply{} 里 GridBagConstraints.anchor。 */
    private fun showGroupSummaryPopup(anchorComponent: JComponent, group: TaskLauncherService.GlobalConfigGroup) {
        val content = JPanel(GridBagLayout())
        content.isOpaque = false
        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            anchor = GridBagConstraints.WEST
            insets = Insets(2, 10, 2, 10)
        }
        for (field in group.fields.take(12)) {
            content.add(JBLabel("${field.displayKey ?: field.key} = ${formatFieldValue(field)}"), gbc)
            gbc.gridy++
        }
        if (group.fields.size > 12) {
            content.add(mutedLabel("… +${group.fields.size - 12}"), gbc)
        }
        showHoverPopup {
            JBPopupFactory.getInstance()
                .createComponentPopupBuilder(content, anchorComponent)
                .setTitle(group.displayName ?: group.name)
                .setRequestFocus(false)
                .setResizable(false)
                .setMovable(false)
                .setCancelOnClickOutside(true)
                .createPopup()
                .also { it.show(RelativePoint(anchorComponent, java.awt.Point(anchorComponent.width / 2, anchorComponent.height))) }
        }
    }

    /** 任务卡悬停弹出（#9 任务卡 hover 概览）：hover 800ms 弹只读摘要。
     *  弹层单实例：新弹层显示前先 cancel 旧的，避免多行间停留时叠加；
     *  mouseExited 停 timer 并重置 hoverRow —— 鼠标离开表格后不再弹出。 */
    private var activeHoverPopup: com.intellij.openapi.ui.popup.JBPopup? = null

    private fun installTaskHoverPopup() {
        var hoverRow = -1
        var timer: Timer? = null
        taskTable.addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
            override fun mouseMoved(e: java.awt.event.MouseEvent) {
                val row = taskTable.rowAtPoint(e.point)
                if (row == hoverRow) return
                hoverRow = row
                timer?.stop()
                if (row < 0 || row >= tasks.size) return
                if (System.currentTimeMillis() < hoverSuppressUntil) return
                val task = tasks[row]
                timer = Timer(hoverPopupDelayMs) { showTaskSummaryPopup(task, row) }.apply {
                    isRepeats = false
                    start()
                }
            }
        })
        // 点击（选中加载参数）后 1.2s 抑制：刚点完立刻悬停不再弹同一张卡
        taskTable.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                hoverSuppressUntil = System.currentTimeMillis() + 1200
                timer?.stop()
            }

            override fun mouseExited(e: java.awt.event.MouseEvent) {
                timer?.stop()
                hoverRow = -1
            }
        })
    }

    /** 显示悬停弹层（单实例互斥：先取消上一个） */
    private fun showHoverPopup(builder: () -> com.intellij.openapi.ui.popup.JBPopup) {
        activeHoverPopup?.takeIf { !it.isDisposed }?.cancel()
        activeHoverPopup = builder()
    }

    /** 任务摘要弹层：kind / 参数改动量 / schema 错误（只读，无交互按钮）。
     *  [row] 用于把弹层锚在悬停行上，而不是固定在表格左上角。 */
    private fun showTaskSummaryPopup(task: TaskLauncherService.TaskInfo, row: Int) {
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
            val config = taskService.loadTaskConfigs().projects[taskService.getWorkspaceRoot()]?.tasks?.get(key)
            val edited = config?.params?.size ?: 0
            content.add(
                mutedLabel(
                    OkScriptToolkitBundle.message("taskLauncher.schemaFieldCount", edited, schema.fields.size),
                ),
                gbc,
            )
        }
        showHoverPopup {
            JBPopupFactory.getInstance()
                .createComponentPopupBuilder(content, taskTable)
                .setTitle(task.displayName)
                .setRequestFocus(false)
                .setResizable(false)
                .setMovable(false)
                .setCancelOnClickOutside(true)
                .createPopup()
                .also { popup ->
                    val anchorRow = if (row in 0 until taskTable.rowCount) row else 1
                    val rect = taskTable.getCellRect(anchorRow, 1, true)
                    popup.show(RelativePoint(taskTable, java.awt.Point(rect.x, rect.y + rect.height)))
                }
        }
    }

    /** 字段值截断显示（弹层摘要用；不解析语义，只转文本） */
    private fun formatFieldValue(field: TaskLauncherService.TaskParamField): String {
        val raw = field.value ?: field.default ?: return "—"
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
            if (isTrigger) COLOR_TRIGGER else JBColor.BLUE,
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

    /**
     * 角色管理入口：VS Code 里「工具箱」侧边栏的第一行就是「打开角色技能管理面板」
     * （`toolboxOpenCharacterManager`），下面才是游戏连接与调试浮层。这里同样把
     * 整行按钮放在工具箱条上方 —— 角色管理已改为编辑器标签页，不再占工具窗。
     */
    private fun buildCharactersEntry(): JPanel {
        charactersButton.toolTipText = OkScriptToolkitBundle.message("characterManager.openDescription")
        charactersButton.addActionListener { openCharacterManager(project) }
        val panel = JPanel(BorderLayout())
        panel.border = BorderFactory.createEmptyBorder(2, 6, 4, 6)
        panel.add(charactersButton, BorderLayout.CENTER)
        return panel
    }

    /** 工具箱条：连接/断开游戏、连接状态、调试浮层开关（与 VS Code 侧栏工具箱同源状态） */
    private fun buildToolboxBar(): JPanel {
        connectGameButton.addActionListener { connectGame() }
        disconnectGameButton.addActionListener { disconnectGame() }
        overlayCheckBox.toolTipText = OkScriptToolkitBundle.message("toolbox.overlayHint")
        overlayCheckBox.addActionListener {
            if (updatingOverlayCheckbox) return@addActionListener
            val projectDir = detectProjectPath()
            val pythonPath = detectPythonPath()
            toolboxService.setOverlayEnabled(projectDir, pythonPath, overlayCheckBox.isSelected)
        }
        val buttons = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0))
        buttons.isOpaque = false
        buttons.add(connectGameButton)
        buttons.add(disconnectGameButton)

        val bar = JPanel(BorderLayout(8, 0))
        bar.border = BorderFactory.createEmptyBorder(2, 6, 2, 6)
        bar.add(buttons, BorderLayout.WEST)
        gameStatusLabel.foreground = UIUtil.getContextHelpForeground()
        bar.add(gameStatusLabel, BorderLayout.CENTER)
        toolboxStatusLabel.foreground = UIUtil.getContextHelpForeground()
        val east = JPanel(BorderLayout(8, 0))
        east.isOpaque = false
        east.add(toolboxStatusLabel, BorderLayout.CENTER)
        east.add(overlayCheckBox, BorderLayout.EAST)
        bar.add(east, BorderLayout.EAST)
        return bar
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
        val projectDir = detectProjectPath()
        if (projectDir.isBlank()) {
            toolboxStatusLabel.text = OkScriptToolkitBundle.message("toolbox.noProject")
            return
        }
        connectGameButton.isEnabled = false
        toolboxService.connectGame(projectDir, detectPythonPath()).whenComplete { _, _ ->
            SwingUtilities.invokeLater { connectGameButton.isEnabled = true }
        }
    }

    private fun disconnectGame() {
        val projectDir = detectProjectPath()
        if (projectDir.isBlank()) return
        toolboxService.disconnectGame(projectDir, detectPythonPath())
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
        val projectDir = detectProjectPath()
        if (projectDir.isBlank()) {
            statusLabel.text = OkScriptToolkitBundle.message("taskLauncher.noProject")
            return
        }

        statusLabel.text = OkScriptToolkitBundle.message("taskLauncher.loading")
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
            SwingUtilities.invokeLater { applyProbeResult(immediate, finished = false) }

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
                taskService.saveSchemaCache(projectDir, locale, withConfigModule)
                withConfigModule
            } else {
                // 采集失败：保持 ① 的结果 —— 至少任务列表可用（只是没有参数表单）
                immediate
            }
        }.thenAccept { result ->
            SwingUtilities.invokeLater { applyProbeResult(result, finished = true) }
        }.exceptionally { throwable ->
            SwingUtilities.invokeLater {
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
    private fun applyProbeResult(result: TaskLauncherService.SchemaProbeResult, finished: Boolean) {
        if (finished) {
            progressBar.isIndeterminate = false
            progressBar.isVisible = false
        }

        if (result.ok && result.schemas != null) {
            schemas = result.schemas
            if (result.configModule != null) {
                configModule = result.configModule
            }
            // 运行中心「全局配置」区数据源（#7）
            globalConfigGroups = result.globalConfigGroups

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
            enabledTriggers.clear()
            enabledTriggers.addAll(taskService.loadEnabledTriggers())

            // 刷新后尽量保住原来的选中项（按 module::Class 找回）
            val previousSelection = detailTask?.let { taskKeyOf(it) }
            updatingTableModel = true
            try {
                taskTableModel.rowCount = 0
                rowKinds.clear()
                rowTones.clear()
                for (task in tasks) {
                    val kind = taskKindOf(task)
                    rowKinds.add(kind)
                    rowTones.add(TaskRowState.TONE_NEUTRAL)
                    taskTableModel.addRow(
                        // 显式 Any? 元素类型：混合 Boolean / String 时 arrayOf 会推导出
                        // Comparable<...> & Serializable 交叉类型并触发告警。
                        // 第一格走 TaskRowState.checkboxValue：触发任务得到 Boolean，
                        // 一次性任务得到 null —— 由 TaskActionRenderer 按行类型决定
                        // 画复选框还是运行按钮。
                        arrayOf<Any?>(
                            TaskRowState.checkboxValue(kind, enabledTriggers.contains(taskKeyOf(task))),
                            task.displayName,
                            "",
                        ),
                    )
                }
            } finally {
                updatingTableModel = false
            }
            syncTriggerCheckboxes(taskRunner.currentState())
            renderTaskStatuses(taskRunner.currentState())
            restoreSelection(previousSelection)

            statusLabel.text = OkScriptToolkitBundle.message("taskLauncher.loaded", tasks.size)

            // #7 配置接管：探针成功后物化全局配置快照（新增键 > 0 时覆盖状态提示）
            if (result.globalConfigGroups.isNotEmpty() &&
                materializeGlobalSnapshots(result.globalConfigGroups) > 0
            ) {
                statusLabel.text = OkScriptToolkitBundle.message("taskLauncher.gconfigMaterialized", lastMaterializedCount)
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
     * 物化全局配置快照并落盘（#7 配置接管）。
     *
     * 规则在 [GlobalSnapshotRules]（纯对象）：每组 existing 为空（首建）→ 全部继承
     * f.value；非空（重探针）→ 已有键保留（孤儿键不删）、新键取 f.default ?: f.value。
     * 快照有实质变化时落盘，并在执行器运行中把新快照经 gparams 推给它 ——
     * 否则要重启执行器才生效（对齐 VS Code 侧 consolePanel 的物化+推送时机）。
     *
     * @return 新增键数（0 = 快照无变化，不落盘不推送）
     */
    private fun materializeGlobalSnapshots(groups: List<TaskLauncherService.GlobalConfigGroup>): Int {
        val existing = taskService.loadGlobalConfigs()
        val snapshots = existing.toMutableMap()
        var added = 0
        for (group in groups) {
            val (snap, count) = GlobalSnapshotRules.materialize(existing[group.name].orEmpty(), group.fields)
            snapshots[group.name] = snap
            added += count
        }
        if (added > 0) {
            lastMaterializedCount = added
            taskService.saveGlobalConfigs(snapshots)
            pushGlobalSnapshot(snapshots)
        }
        return added
    }

    /** 全局配置快照即时推送：执行器运行中才推（gparams 是全量快照，幂等可重放） */
    private fun pushGlobalSnapshot(snapshots: Map<String, Map<String, Any?>>) {
        if (!taskRunner.isRunning()) return
        if (snapshots.isEmpty()) return
        taskRunner.pushGlobalParams(objectMapper.writeValueAsString(snapshots))
    }

    private fun loadTaskParams(task: TaskLauncherService.TaskInfo) {
        paramPanel.removeAll()
        paramFields.clear()
        visibilityRefresher = null
        // 右栏头部跟着选中项走：名称 / 类型 chip / 状态 chip / 主操作按钮
        renderDetailHeader(task)
        // 已选中任务 → 详情区不再是运行中心，状态刷新不再驱动它
        runCenterVisible = false

        val taskKey = "${task.module}::${task.className}"
        val schema = schemas[taskKey]

        var row = 0

        val taskConfig = taskService.getTaskConfig(taskKey)

        if (schema != null && schema.fields.isNotEmpty()) {
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
        } else if (schema == null) {
            val gbc = GridBagConstraints().apply {
                gridx = 0; gridy = row; gridwidth = 2
                insets = Insets(10, 10, 10, 10)
            }
            paramPanel.add(JBLabel("No schema available for this task"), gbc)
        }

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

    /** 字段描述（displayDesc 优先）渲染在控件下方的小字说明；无描述时原样返回（对齐 VSCode） */
    private fun withFieldDescription(
        field: TaskLauncherService.TaskParamField,
        component: JComponent,
    ): JComponent {
        val text = field.displayDesc ?: field.desc ?: return component
        val description = JBLabel(text)
        description.foreground = UIUtil.getContextHelpForeground()
        description.font = description.font.deriveFont(description.font.size2D - 1f)
        description.verticalAlignment = javax.swing.SwingConstants.TOP
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(component, BorderLayout.CENTER)
            add(description, BorderLayout.SOUTH)
        }
    }

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

        /** 渲染单字段行（label + 控件两格）；返回是否实际渲染 */
        private fun renderFieldRow(
            key: String,
            container: JPanel,
            subConfig: Boolean,
            duplicate: Boolean,
        ): Boolean {
            val field = fieldsByKey[key] ?: return false
            if (!duplicate && key in renderedFields) return false
            if (!duplicate) renderedFields.add(key)

            val label = JBLabel("${field.displayKey ?: field.key}:")
            label.horizontalAlignment = SwingConstants.RIGHT
            val indent = if (subConfig) 24 else 0
            // 标签列定宽 + 右对齐：所有行的冒号落在同一条竖线上（子配置多缩进一档）
            label.preferredSize = Dimension(LABEL_COLUMN_WIDTH + indent, label.preferredSize.height)
            val control = createFieldComponent(field, task)
            val component = withFieldDescription(field, control)
            val grow = nextRow(container)
            container.add(label, GridBagConstraints().apply {
                gridx = 0; gridy = grow
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.WEST
                insets = Insets(4, 6, 4, 6)
            })
            container.add(naturalWidth(component), GridBagConstraints().apply {
                gridx = 1; gridy = grow
                fill = GridBagConstraints.HORIZONTAL
                weightx = 1.0
                insets = Insets(4, 0, 4, 6)
            })
            paramFields[key] = valueControlOf(control)
            rowsByKey.getOrPut(key) { mutableListOf() }.add(label to component)
            return true
        }

        /**
         * 把控件包进左对齐的 FlowLayout。
         *
         * 外层格子照旧吃掉横向剩余空间（fill + weightx），但 FlowLayout 不会拉伸子组件 ——
         * 控件保持自然宽度。旧版直接给控件 `weightx = 1.0` + `fill = HORIZONTAL`，
         * 文本框/下拉会被拉满整行宽度，就是「又矮又长的条条」的来源。
         */
        private fun naturalWidth(component: JComponent): JComponent =
            JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                add(component)
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

            // 组头字段（始终可见）；其 boolean 行内子字段留在组体内
            if (headerField != null && fieldsByKey.containsKey(headerField)) {
                val field = fieldsByKey.getValue(headerField)
                val fieldLabel = JBLabel("${field.displayKey ?: field.key}:")
                val control = createFieldComponent(field, task)
                val component = withFieldDescription(field, control)
                paramFields[headerField] = valueControlOf(control)
                rowsByKey.getOrPut(headerField) { mutableListOf() }.add(fieldLabel to component)
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
                headerPanel.add(fieldLabel, BorderLayout.WEST)
                headerPanel.add(component, BorderLayout.CENTER)
                val toggle = buildToggle(body, stateKey)
                headerPanel.add(toggle, BorderLayout.EAST)
                val groupPanel = buildGroupPanel(labelOf = null, header = headerPanel, body = body)
                addToContainer(container, groupPanel)
                return true
            }

            val toggle = buildToggle(body, stateKey)
            val groupPanel = buildGroupPanel(labelOf = label, header = null, body = body, toggle = toggle)
            addToContainer(container, groupPanel)

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
            if (stateKey in openConfigGroups) {
                body.isVisible = true
                toggle.text = "▲"
            }
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
                val east = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 0))
                east.isOpaque = false
                toggle?.let { east.add(it) }
                north.add(east, BorderLayout.EAST)
            }
            body.isOpaque = false
            return JPanel(BorderLayout()).apply {
                border = BorderFactory.createTitledBorder(labelOf ?: "")
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
            val taskConfig = taskService.getTaskConfig("${task.module}::${task.className}")
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
        val taskConfig = taskService.getTaskConfig(taskKey)
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
        val taskKey = "${task.module}::${task.className}"
        val config = buildTaskConfig(task)
        pendingSave = taskKey to config
        saveTimer.restart()
    }

    private var pendingSave: Pair<String, TaskLauncherService.TaskConfig>? = null

    private fun flushPendingSave() {
        val (taskKey, config) = pendingSave ?: return
        pendingSave = null
        CompletableFuture.runAsync {
            try {
                taskService.saveTaskConfig(taskKey, config)
                // 执行器是常驻进程：参数覆盖必须即时推送，否则要重启执行器才生效
                pushParamOverrides()
            } catch (e: Exception) {
                LOG.warn("Failed to save task config for $taskKey", e)
            }
        }
    }

    /**
     * 由表单当前值构建任务配置。
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
    private fun buildTaskConfig(task: TaskLauncherService.TaskInfo): TaskLauncherService.TaskConfig {
        val params = mutableMapOf<String, Any>()
        for ((key, component) in paramFields) {
            // 使用 renderer 的 getValueControl 方法获取值控件
            val actualComponent = currentRenderer?.getValueControl(key) ?: component
            when (actualComponent) {
                is JCheckBox -> params[key] = actualComponent.isSelected
                is JSpinner -> params[key] = actualComponent.value
                is JTextField -> if (actualComponent.text.isNotBlank()) params[key] = actualComponent.text
                is JTextArea -> textAreaValue(actualComponent)?.let { params[key] = it }
                // 对齐 VSCode：表单当前值全量保存（包括空列表）
                is ListEditorComponent -> params[key] = actualComponent.value
                is JComboBox<*> -> actualComponent.selectedItem?.let { params[key] = it }
                is JList<*> -> {
                    val selectedValues = actualComponent.selectedValuesList.toList()
                    params[key] = selectedValues
                }
            }
        }
        val existing = taskService.getTaskConfig(taskKeyOf(task))
        return TaskLauncherService.TaskConfig(
            params = params.ifEmpty { null },
            // 原样保留：UI 已无入口，丢了就再也找不回来
            extraArgs = existing.extraArgs,
            env = existing.env,
        )
    }

    /**
     * 运行第 [row] 行的任务。
     *
     * 入口只有操作列那枚运行按钮（[installActionColumnClick]）—— 工具栏上原来那个
     * 「运行」按钮已删除：它和「启动执行器」用的是**同一个图标**，两个挨着的按钮长得一模一样，
     * 而且操作列有了运行按钮之后就多余了。
     *
     * 触发行不该走到这里（那一格是复选框）；真到了就给出明确提示，而不是静默忽略。
     */
    private fun runTaskAt(row: Int) {
        val task = tasks.getOrNull(row) ?: return
        if (taskKindOf(task) == TaskRowState.TRIGGER) {
            JOptionPane.showMessageDialog(
                mainPanel,
                OkScriptToolkitBundle.message("taskLauncher.triggerUsesCheckbox"),
                "Warning",
                JOptionPane.WARNING_MESSAGE,
            )
            return
        }
        enqueueTask(task)
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
        if (!taskRunner.enqueueOnetime(taskKeyOf(task))) {
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
        if (!taskRunner.stopCurrent()) {
            statusLabel.text = OkScriptToolkitBundle.message("taskLauncher.executorNotRunning")
        }
    }

    /** 关闭常驻执行器（进程级；与「停止当前任务」不同） */
    private fun closeExecutor() {
        if (!taskRunner.isRunning()) {
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
        if (taskRunner.isRunning()) return true
        val projectDir = detectProjectPath()
        if (projectDir.isBlank()) {
            statusLabel.text = OkScriptToolkitBundle.message("taskLauncher.noProject")
            return false
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
        val overrides = allParamOverrides()
        if (overrides.isNotEmpty()) {
            env["OK_LANG_HINTS_INJECT"] = objectMapper.writeValueAsString(overrides)
        }
        // 全局配置快照（#7 配置接管）：非空才注入，执行器侧拿它覆盖框架/项目 store 的
        // 全局配置。物化语义（首建继承当前值、新键取默认、孤儿键保留）由探针采集后的
        // GlobalSnapshotRules 负责，这里只管把快照带给执行器。
        val gconfig = taskService.loadTaskConfigs().projects[taskService.getWorkspaceRoot()]?.globalConfigs
        if (!gconfig.isNullOrEmpty()) {
            env["OK_TOOLKIT_GCONFIG"] = objectMapper.writeValueAsString(gconfig)
        }
        warnLegacyPerTaskSettings()

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
    private fun allParamOverrides(): Map<String, Map<String, Any>> {
        val projectConfig = taskService.loadTaskConfigs().projects[taskService.getWorkspaceRoot()]
            ?: return emptyMap()
        val overrides = linkedMapOf<String, Map<String, Any>>()
        for ((key, config) in projectConfig.tasks) {
            val filtered = TaskParamWhitelist.filter(config.params, schemas[key]?.fields)
            if (filtered.isNotEmpty()) overrides[key] = filtered
        }
        return overrides
    }

    /** 参数覆盖即时推送（执行器是常驻进程，不推就要重启才生效） */
    private fun pushParamOverrides() {
        if (!taskRunner.isRunning()) return
        val json = objectMapper.writeValueAsString(allParamOverrides())
        taskRunner.pushParams(json)
    }

    /** 历史配置里的 extraArgs / env 在单进程模型下无法按任务生效，启动时提示一次 */
    private fun warnLegacyPerTaskSettings() {
        val tasks = taskService.loadTaskConfigs().projects[taskService.getWorkspaceRoot()]?.tasks ?: return
        val affected = tasks.values.count { !it.extraArgs.isNullOrBlank() || !it.env.isNullOrEmpty() }
        if (affected > 0) {
            taskRunner.log(OkScriptToolkitBundle.message("taskLauncher.legacySettingsIgnored", affected))
        }
    }

    private fun taskKeyOf(task: TaskLauncherService.TaskInfo): String = "${task.module}::${task.className}"

    private fun taskKindOf(task: TaskLauncherService.TaskInfo): String =
        task.kind ?: schemas[taskKeyOf(task)]?.kind ?: TaskRowState.ONETIME

    /** 刷新列表后恢复原选中行（按 module::Class 找回）；找不到就回到占位提示 */
    private fun restoreSelection(taskKey: String?) {
        val index = if (taskKey == null) -1 else tasks.indexOfFirst { taskKeyOf(it) == taskKey }
        if (index >= 0) {
            taskTable.selectionModel.setSelectionInterval(index, index)
        } else {
            taskTable.clearSelection()
            showDetailPlaceholder()
        }
    }

    /** 勾选列与持久化集合对齐；执行器运行中以它的快照为准 */
    private fun syncTriggerCheckboxes(state: TaskRunnerService.ExecutorState) {
        if (state.status != "idle" && enabledTriggers.toList() != state.enabledTriggers) {
            enabledTriggers.clear()
            enabledTriggers.addAll(state.enabledTriggers)
            persistEnabledTriggers()
        }
        if (updatingTableModel) return
        updatingTableModel = true
        try {
            for (row in tasks.indices) {
                if (rowKinds.getOrElse(row) { TaskRowState.ONETIME } != TaskRowState.TRIGGER) continue
                val want = enabledTriggers.contains(taskKeyOf(tasks[row]))
                if (taskTableModel.getValueAt(row, 0) != want) taskTableModel.setValueAt(want, row, 0)
            }
        } finally {
            updatingTableModel = false
        }
    }

    /** 状态单元格：文案 + 语义色调（色调给状态列渲染器与详情区 chip 共用） */
    private data class StatusCell(val text: String, val tone: Int)

    /** 状态列：触发任务看入列 / 轮询，一次性任务看排队 / 执行 / schema 健康度 */
    private fun renderTaskStatuses(state: TaskRunnerService.ExecutorState) {
        while (rowTones.size < tasks.size) rowTones.add(TaskRowState.TONE_NEUTRAL)
        if (rowTones.size > tasks.size) rowTones.subList(tasks.size, rowTones.size).clear()
        for (row in tasks.indices) {
            val cell = statusCellFor(tasks[row], state)
            rowTones[row] = cell.tone
            if (taskTableModel.getValueAt(row, 2) != cell.text) taskTableModel.setValueAt(cell.text, row, 2)
        }
    }

    private fun statusCellFor(
        task: TaskLauncherService.TaskInfo,
        state: TaskRunnerService.ExecutorState,
    ): StatusCell {
        val key = taskKeyOf(task)
        val isTrigger = taskKindOf(task) == TaskRowState.TRIGGER
        val schema = schemas[key]
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
            else -> OkScriptToolkitBundle.message("taskLauncher.statusReady")
        }
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
        val key = taskKeyOf(task)
        if (enabled) enabledTriggers.add(key) else enabledTriggers.remove(key)
        persistEnabledTriggers()
        renderTaskStatuses(taskRunner.currentState())
        if (taskRunner.isRunning()) {
            if (!taskRunner.setTriggerEnabled(key, enabled)) {
                statusLabel.text = OkScriptToolkitBundle.message("taskLauncher.executorNotRunning")
            }
        }
    }

    private fun persistEnabledTriggers() {
        val keys = enabledTriggers.toList()
        CompletableFuture.runAsync {
            try {
                taskService.saveEnabledTriggers(keys)
            } catch (e: Exception) {
                LOG.warn("Failed to save enabled triggers", e)
            }
        }
    }

    private fun sendControlCommand(command: String) {
        if (!taskRunner.isRunning()) {
            statusLabel.text = OkScriptToolkitBundle.message("taskLauncher.executorNotRunning")
            return
        }
        if (!taskRunner.sendCommand(command)) {
            statusLabel.text = OkScriptToolkitBundle.message("toolbox.sendCommandFailed", command)
        }
    }

    fun onDispose() {
        // 只解绑视图：常驻执行器由 TaskRunnerService 持有，工具窗关闭后继续在后台运行，
        // 日志也留在 Run 工具窗口里
        toolboxService.removeStateListener(toolboxStateListener)
        toolboxService.removeStatusListener(toolboxStatusListener)
        taskRunner.removeStateListener(runnerStateListener)
    }
}


