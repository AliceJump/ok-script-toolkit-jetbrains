package com.alicejump.okscripttoolkit.tasklauncher

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.alicejump.okscripttoolkit.core.OkProjectDataService
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
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.UIUtil
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Color
import java.io.File
import java.util.concurrent.CompletableFuture
import java.nio.file.Files
import java.nio.file.Paths
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
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
        private const val DEFAULT_PYTHON_PATH = "python"
        private const val MAX_CONSOLE_CHARS = 400_000
        private val OK_BORDER = JBColor(Color(40, 120, 40), Color(76, 175, 80))
        private const val LF_CHAR: Char = 0x0A.toChar()
        private const val OK_JSON_FIELD = "ok-script.jsonField"
        private val BAD_BORDER = JBColor(Color(180, 40, 40), Color(239, 83, 80))
    }

    val mainPanel: JPanel
    private val taskService = TaskLauncherService(project)
    private val toolboxService = ToolboxService.getInstance(project)

    /** 每行的任务类型（trigger / onetime），供表格勾选列判断可编辑性 */
    private val rowKinds = mutableListOf<String>()

    /** 程序化改写表格时抑制 TableModelListener 的副作用 */
    private var updatingTableModel = false

    /** 已勾选「启用」的触发任务 key（module::Class），持久化到 .idea/ok-script-toolkit-tasks.json */
    private val enabledTriggers = linkedSetOf<String>()

    private val taskTableModel = object : DefaultTableModel(
        arrayOf(
            OkScriptToolkitBundle.message("taskLauncher.enableColumn"),
            OkScriptToolkitBundle.message("taskLauncher.taskColumn"),
            OkScriptToolkitBundle.message("taskLauncher.typeColumn"),
            OkScriptToolkitBundle.message("taskLauncher.statusColumn"),
        ),
        0,
    ) {
        // 必须返回包装类 Boolean（而不是基本类型 boolean），否则 Swing 不会套用复选框
        // 渲染器 / 编辑器；拆成提前 return 也避开了 if/else 推导出交叉类型的告警。
        override fun getColumnClass(columnIndex: Int): Class<*> {
            if (columnIndex == 0) return java.lang.Boolean::class.javaObjectType
            return String::class.java
        }

        /** 只有触发任务可以勾选启用；一次性任务用工具栏的「运行」入队 */
        override fun isCellEditable(row: Int, column: Int): Boolean =
            column == 0 && rowKinds.getOrElse(row) { "onetime" } == "trigger"
    }
    private val taskTable = JBTable(taskTableModel)
    private val refreshAction = ToolbarAction(AllIcons.Actions.Refresh, OkScriptToolkitBundle.message("taskLauncher.refresh")) { loadTasks() }
    private val runAction = ToolbarAction(AllIcons.Actions.Execute, OkScriptToolkitBundle.message("taskLauncher.run")) { enqueueSelectedTask() }
    private val stopCurrentAction = ToolbarAction(AllIcons.Actions.Suspend, OkScriptToolkitBundle.message("taskLauncher.stopCurrent")) { stopCurrentTask() }
    private val closeExecutorAction = ToolbarAction(AllIcons.Actions.Cancel, OkScriptToolkitBundle.message("taskLauncher.closeExecutor")) { closeExecutor() }
    private val pauseAction = ToolbarAction(AllIcons.Actions.Pause, OkScriptToolkitBundle.message("taskLauncher.pause")) { sendControlCommand("pause") }
    private val resumeAction = ToolbarAction(AllIcons.Actions.Play_forward, OkScriptToolkitBundle.message("taskLauncher.resume")) { sendControlCommand("resume") }
    private lateinit var actionToolbar: com.intellij.openapi.actionSystem.ActionToolbar
    private val statusLabel = JBLabel()
    private val progressBar = JProgressBar()

    private val paramPanel = JPanel(GridBagLayout())
    private val paramFields = mutableMapOf<String, JComponent>()

    private var tasks = listOf<TaskLauncherService.TaskInfo>()
    private var configModule = "src.config"
    private var schemas = mapOf<String, TaskLauncherService.TaskSchema>()
    private val consoleArea = JBTextArea()
    private val saveTimer = javax.swing.Timer(400, null)

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
    private val runnerOutputListener: (String) -> Unit = { line ->
        SwingUtilities.invokeLater { appendConsole(line) }
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
        taskRunner.recentOutputLines().forEach { appendConsole(it) }
        syncRunnerState(taskRunner.currentState())
        taskRunner.addOutputListener(runnerOutputListener)
        taskRunner.addStateListener(runnerStateListener)
        loadTasks()
    }

    /** 按执行器状态刷新工具栏按钮、状态栏与勾选列（EDT） */
    private fun syncRunnerState(state: TaskRunnerService.ExecutorState) {
        val active = state.status == "running" || state.status == "connecting"
        runAction.isEnabled2 = state.status != "connecting"
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
        syncTriggerCheckboxes(state)
        renderTaskStatuses(state)
    }

    private fun initUI() {
        val clearConsoleAction = ToolbarAction(AllIcons.Actions.GC, OkScriptToolkitBundle.message("taskLauncher.clearConsole")) {
            consoleArea.text = ""
        }
        stopCurrentAction.isEnabled2 = false
        closeExecutorAction.isEnabled2 = false
        pauseAction.isEnabled2 = false
        resumeAction.isEnabled2 = false

        val actionGroup = com.intellij.openapi.actionSystem.DefaultActionGroup(
            refreshAction, runAction, stopCurrentAction, closeExecutorAction, pauseAction, resumeAction,
            clearConsoleAction,
        )
        actionToolbar = com.intellij.openapi.actionSystem.ActionManager.getInstance()
            .createActionToolbar("ok-script-tasks", actionGroup, true)
        actionToolbar.targetComponent = mainPanel
        val toolbar = actionToolbar.component
        toolbar.border = BorderFactory.createEmptyBorder(2, 2, 2, 6)

        taskTable.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        taskTable.showHorizontalLines = true
        taskTable.showVerticalLines = false
        taskTable.selectionModel.addListSelectionListener {
            val selectedRow = taskTable.selectedRow
            if (selectedRow >= 0 && selectedRow < tasks.size) {
                loadTaskParams(tasks[selectedRow])
            }
        }
        // 触发任务的勾选列：勾上 = 入列轮询，取消 = 出列（等价 ok-script GUI 的启用开关）
        taskTableModel.addTableModelListener { event ->
            if (updatingTableModel) return@addTableModelListener
            if (event.type != javax.swing.event.TableModelEvent.UPDATE || event.column != 0) {
                return@addTableModelListener
            }
            val row = event.firstRow
            if (row < 0 || row >= tasks.size || rowKinds.getOrElse(row) { "onetime" } != "trigger") {
                return@addTableModelListener
            }
            setTriggerEnabled(tasks[row], taskTableModel.getValueAt(row, 0) == true)
        }
        taskTable.columnModel.getColumn(0).apply {
            preferredWidth = 44
            maxWidth = 44
            resizable = false
        }
        taskTable.columnModel.getColumn(1).preferredWidth = 240
        taskTable.columnModel.getColumn(2).preferredWidth = 70
        taskTable.columnModel.getColumn(3).preferredWidth = 120

        val tableScrollPane = JBScrollPane(taskTable)

        val paramScrollPane = JBScrollPane(paramPanel)
        paramScrollPane.border = BorderFactory.createTitledBorder(OkScriptToolkitBundle.message("taskLauncher.parameters"))

        val statusBar = JPanel(BorderLayout())
        statusBar.border = BorderFactory.createEmptyBorder(2, 4, 2, 4)
        statusBar.add(statusLabel, BorderLayout.CENTER)
        progressBar.preferredSize = Dimension(200, 20)
        progressBar.isVisible = false
        statusBar.add(progressBar, BorderLayout.EAST)

        val splitPane = com.intellij.openapi.ui.Splitter(true, 0.5f)
        splitPane.firstComponent = tableScrollPane
        splitPane.secondComponent = paramScrollPane

        // 输出控制台：对齐 VSCode 版的专属输出频道，展示任务 stdout/stderr
        consoleArea.isEditable = false
        consoleArea.lineWrap = false
        consoleArea.rows = 6
        val consoleScrollPane = JBScrollPane(consoleArea)
        consoleScrollPane.border = BorderFactory.createTitledBorder(OkScriptToolkitBundle.message("taskLauncher.console"))

        val centerPane = com.intellij.openapi.ui.Splitter(true, 0.62f)
        centerPane.firstComponent = splitPane
        centerPane.secondComponent = consoleScrollPane

        val northPane = JPanel(BorderLayout())
        northPane.add(toolbar, BorderLayout.NORTH)
        northPane.add(buildCharactersEntry(), BorderLayout.CENTER)
        northPane.add(buildToolboxBar(), BorderLayout.SOUTH)

        mainPanel.add(northPane, BorderLayout.NORTH)
        mainPanel.add(centerPane, BorderLayout.CENTER)
        mainPanel.add(statusBar, BorderLayout.SOUTH)
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

    private fun detectProjectPath(): String {
        val settings = OkScriptToolkitSettings.getInstance(project)
        val configured = settings.okScriptProjectPath()
        if (configured.isNotBlank()) {
            val path = configured.replace("~", System.getProperty("user.home"))
            if (Files.isDirectory(Paths.get(path))) return path
        }
        val basePath = project.basePath ?: return ""
        val candidates = listOf(
            Paths.get(basePath, "src", "config.py"),
            Paths.get(basePath, "config.py"),
        )
        return if (candidates.any { Files.exists(it) }) basePath else ""
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
            // 保证 schema 缓存命中时新增任务也能出现（对齐 VSCode 行为）
            val parseResult = taskService.parseConfigTasks(pythonPath, locale)
            val cachedResult = taskService.loadSchemaCache(projectDir, locale)
            if (cachedResult.ok && cachedResult.schemas != null) {
                return@supplyAsync cachedResult.copy(
                    schemas = mergeTaskLists(cachedResult.schemas!!, parseResult),
                    configModule = parseResult.configModule.takeIf { parseResult.ok } ?: cachedResult.configModule,
                )
            }
            val probeResult = taskService.probeTaskSchemas(pythonPath, locale, poDirectory)
            if (probeResult.ok && probeResult.schemas != null) {
                val withConfigModule = probeResult.copy(
                    configModule = probeResult.configModule ?: parseResult.configModule.takeIf { parseResult.ok },
                )
                taskService.saveSchemaCache(projectDir, locale, withConfigModule)
                return@supplyAsync withConfigModule
            }
            if (parseResult.ok && parseResult.tasks.isNotEmpty()) {
                // 探测失败但任务列表可用：至少能列出任务（无 schema 参数）
                return@supplyAsync TaskLauncherService.SchemaProbeResult(
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
            }
            probeResult
        }.thenAccept { result ->
            SwingUtilities.invokeLater {
                progressBar.isIndeterminate = false
                progressBar.isVisible = false

                if (result.ok && result.schemas != null) {
                    schemas = result.schemas
                    if (result.configModule != null) {
                        configModule = result.configModule
                    }

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

                    updatingTableModel = true
                    try {
                        taskTableModel.rowCount = 0
                        rowKinds.clear()
                        for (task in tasks) {
                            val kind = taskKindOf(task)
                            rowKinds.add(kind)
                            taskTableModel.addRow(
                                // 显式 Any? 元素类型：混合 Boolean / String 时 arrayOf 会推导出
                                // Comparable<...> & Serializable 交叉类型并触发告警
                                arrayOf<Any?>(
                                    kind == "trigger" && enabledTriggers.contains(taskKeyOf(task)),
                                    task.displayName,
                                    OkScriptToolkitBundle.message(
                                        if (kind == "trigger") "taskLauncher.triggerTask" else "taskLauncher.oneTimeTask",
                                    ),
                                    "",
                                ),
                            )
                        }
                    } finally {
                        updatingTableModel = false
                    }
                    syncTriggerCheckboxes(taskRunner.currentState())
                    renderTaskStatuses(taskRunner.currentState())

                    statusLabel.text = OkScriptToolkitBundle.message("taskLauncher.loaded", tasks.size)
                } else {
                    statusLabel.text = "Failed: ${result.error}"
                    JOptionPane.showMessageDialog(
                        mainPanel,
                        "Failed to load tasks: ${result.error}",
                        "Error",
                        JOptionPane.ERROR_MESSAGE,
                    )
                }
            }
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

    private fun loadTaskParams(task: TaskLauncherService.TaskInfo) {
        paramPanel.removeAll()
        paramFields.clear()
        visibilityRefresher = null

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
    private fun mergeTaskLists(
        cached: Map<String, TaskLauncherService.TaskSchema>,
        parseResult: TaskLauncherService.TaskListResult,
    ): Map<String, TaskLauncherService.TaskSchema> {
        if (!parseResult.ok) return cached
        val merged = cached.toMutableMap()
        for (task in parseResult.tasks) {
            val key = "${task.module}::${task.className}"
            if (!merged.containsKey(key)) {
                merged[key] = TaskLauncherService.TaskSchema(displayName = task.displayName)
            }
        }
        val validKeys = parseResult.tasks.map { "${it.module}::${it.className}" }.toSet()
        return merged.filterKeys { it in validKeys }
    }

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
            for (field in schema.fields) {
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
            val groupChildren = groups.values.flatten().toSet()

            for (field in schema.fields) {
                if (field.key == selectorKey || field.key in optionControlled ||
                    field.key in inlineControlled || field.key in groupNames || field.key in groupChildren
                ) {
                    continue
                }
                renderFieldTree(field.key, host, emptySet())
            }

            val nestedGroups = HashSet<String>()
            for ((parent, children) in groups) {
                for (child in children) {
                    if (child != parent && groups.containsKey(child)) nestedGroups.add(child)
                }
            }
            val renderRegisteredGroup = { key: String ->
                renderGroup(
                    key = key,
                    label = groupLabels[key] ?: key,
                    children = groups[key].orEmpty(),
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
            val control = createFieldComponent(field, task)
            val component = withFieldDescription(field, control)
            val indent = if (subConfig) 24 else 0
            val grow = nextRow(container)
            container.add(label, GridBagConstraints().apply {
                gridx = 0; gridy = grow
                anchor = GridBagConstraints.WEST
                insets = Insets(3, 8 + indent, 3, 4)
            })
            container.add(component, GridBagConstraints().apply {
                gridx = 1; gridy = grow
                fill = GridBagConstraints.HORIZONTAL
                weightx = 1.0
                insets = Insets(3, 4 + indent, 3, 6)
            })
            paramFields[key] = valueControlOf(control)
            rowsByKey.getOrPut(key) { mutableListOf() }.add(label to component)
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

            // 组头字段（始终可见）；其 boolean 行内子字段留在组体内
            if (headerField != null && fieldsByKey.containsKey(headerField)) {
                val field = fieldsByKey.getValue(headerField)
                val fieldLabel = JBLabel("${field.displayKey ?: field.key}:")
                val control = createFieldComponent(field, task)
                val component = withFieldDescription(field, control)
                paramFields[headerField] = valueControlOf(control)
                rowsByKey.getOrPut(headerField) { mutableListOf() }.add(fieldLabel to component)
                inlineRules[headerField]?.let { rules ->
                    for (child in rules.values.flatten().distinct()) {
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
                if (groups.containsKey(child)) {
                    renderGroup(
                        key = child,
                        label = groupLabels[child] ?: child,
                        children = groups[child].orEmpty(),
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
                val node = com.fasterxml.jackson.databind.ObjectMapper().readTree(text.trim())
                com.fasterxml.jackson.databind.ObjectMapper().convertValue(node, Any::class.java)
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
        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
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
        return TaskLauncherService.TaskConfig(
            params = params.ifEmpty { null },
        )
    }

    private fun appendConsole(line: String) {
        SwingUtilities.invokeLater {
            var text = consoleArea.text
            if (text.length > MAX_CONSOLE_CHARS) text = text.substring(text.length / 2)
            consoleArea.text = text
            consoleArea.append(line + "\n")
            consoleArea.caretPosition = consoleArea.document.length
        }
    }

    /** 一次性任务：入队到常驻执行器，执行一次后自动出队（触发任务改用勾选列） */
    private fun enqueueSelectedTask() {
        val selectedRow = taskTable.selectedRow
        if (selectedRow < 0 || selectedRow >= tasks.size) {
            JOptionPane.showMessageDialog(
                mainPanel,
                OkScriptToolkitBundle.message("taskLauncher.noTaskSelected"),
                "Warning",
                JOptionPane.WARNING_MESSAGE,
            )
            return
        }
        val task = tasks[selectedRow]
        if (taskKindOf(task) == "trigger") {
            JOptionPane.showMessageDialog(
                mainPanel,
                OkScriptToolkitBundle.message("taskLauncher.triggerUsesCheckbox"),
                "Warning",
                JOptionPane.WARNING_MESSAGE,
            )
            return
        }
        if (!ensureExecutor()) return
        val key = taskKeyOf(task)
        if (!taskRunner.enqueueOnetime(key)) {
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
        env["OK_TOOLKIT_TRIGGERS"] = ObjectMapper().writeValueAsString(enabledTriggers.toList())
        val overrides = allParamOverrides()
        if (overrides.isNotEmpty()) {
            env["OK_LANG_HINTS_INJECT"] = ObjectMapper().writeValueAsString(overrides)
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
        val projectConfig = taskService.loadTaskConfigs().projects[taskService.getProjectRoot()]
            ?: return emptyMap()
        val overrides = linkedMapOf<String, Map<String, Any>>()
        for ((key, config) in projectConfig.tasks) {
            val params = config.params
            if (!params.isNullOrEmpty()) overrides[key] = params
        }
        return overrides
    }

    /** 参数覆盖即时推送（执行器是常驻进程，不推就要重启才生效） */
    private fun pushParamOverrides() {
        if (!taskRunner.isRunning()) return
        val json = ObjectMapper().writeValueAsString(allParamOverrides())
        taskRunner.pushParams(json)
    }

    /** 历史配置里的 extraArgs / env 在单进程模型下无法按任务生效，启动时提示一次 */
    private fun warnLegacyPerTaskSettings() {
        val tasks = taskService.loadTaskConfigs().projects[taskService.getProjectRoot()]?.tasks ?: return
        val affected = tasks.values.count { !it.extraArgs.isNullOrBlank() || !it.env.isNullOrEmpty() }
        if (affected > 0) {
            taskRunner.log(OkScriptToolkitBundle.message("taskLauncher.legacySettingsIgnored", affected))
        }
    }

    private fun taskKeyOf(task: TaskLauncherService.TaskInfo): String = "${task.module}::${task.className}"

    private fun taskKindOf(task: TaskLauncherService.TaskInfo): String =
        task.kind ?: schemas[taskKeyOf(task)]?.kind ?: "onetime"

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
                if (rowKinds.getOrElse(row) { "onetime" } != "trigger") continue
                val want = enabledTriggers.contains(taskKeyOf(tasks[row]))
                if (taskTableModel.getValueAt(row, 0) != want) taskTableModel.setValueAt(want, row, 0)
            }
        } finally {
            updatingTableModel = false
        }
    }

    /** 状态列：触发任务看入列 / 轮询，一次性任务看排队 / 执行 / schema 健康度 */
    private fun renderTaskStatuses(state: TaskRunnerService.ExecutorState) {
        for (row in tasks.indices) {
            val text = statusTextFor(tasks[row], state)
            if (taskTableModel.getValueAt(row, 3) != text) taskTableModel.setValueAt(text, row, 3)
        }
    }

    private fun statusTextFor(task: TaskLauncherService.TaskInfo, state: TaskRunnerService.ExecutorState): String {
        val key = taskKeyOf(task)
        val isTrigger = taskKindOf(task) == "trigger"
        if (state.current == key) {
            return OkScriptToolkitBundle.message(
                if (isTrigger) "taskLauncher.triggerPolling" else "taskLauncher.taskExecuting",
            )
        }
        if (isTrigger) {
            return OkScriptToolkitBundle.message(
                if (enabledTriggers.contains(key)) "taskLauncher.triggerEnqueued" else "taskLauncher.triggerDisabled",
            )
        }
        if (state.onetimeQueue.contains(key)) {
            return OkScriptToolkitBundle.message("taskLauncher.taskQueued")
        }
        val schema = schemas[key]
        return when {
            schema?.broken == true -> OkScriptToolkitBundle.message("taskLauncher.schemaBroken")
            schema?.error != null -> OkScriptToolkitBundle.message("taskLauncher.schemaError")
            else -> OkScriptToolkitBundle.message("taskLauncher.statusReady")
        }
    }

    /** 触发任务勾选：更新持久化集合并即时入列 / 出列；未启动执行器时按需拉起 */
    private fun setTriggerEnabled(task: TaskLauncherService.TaskInfo, enabled: Boolean) {
        val key = taskKeyOf(task)
        if (enabled) enabledTriggers.add(key) else enabledTriggers.remove(key)
        persistEnabledTriggers()
        renderTaskStatuses(taskRunner.currentState())
        if (taskRunner.isRunning()) {
            if (!taskRunner.setTriggerEnabled(key, enabled)) {
                statusLabel.text = OkScriptToolkitBundle.message("taskLauncher.executorNotRunning")
            }
        } else if (enabled) {
            ensureExecutor()
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
        // 只解绑视图：常驻执行器由 TaskRunnerService 持有，工具窗关闭后继续在后台运行
        toolboxService.removeStateListener(toolboxStateListener)
        toolboxService.removeStatusListener(toolboxStatusListener)
        taskRunner.removeOutputListener(runnerOutputListener)
        taskRunner.removeStateListener(runnerStateListener)
    }
}


