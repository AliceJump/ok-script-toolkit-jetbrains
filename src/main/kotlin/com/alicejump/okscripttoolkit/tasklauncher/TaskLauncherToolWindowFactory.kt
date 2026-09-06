package com.alicejump.okscripttoolkit.tasklauncher

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.alicejump.okscripttoolkit.core.OkProjectDataService
import com.alicejump.okscripttoolkit.toolbox.ToolboxService
import com.alicejump.okscripttoolkit.ui.ToolbarAction
import com.alicejump.okscripttoolkit.settings.OkScriptToolkitSettings
import com.intellij.ui.JBColor
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

    private val taskTableModel = DefaultTableModel(arrayOf("Task", "Type", "Status"), 0)
    private val taskTable = JBTable(taskTableModel)
    private val refreshAction = ToolbarAction(AllIcons.Actions.Refresh, OkScriptToolkitBundle.message("taskLauncher.refresh")) { loadTasks() }
    private val runAction = ToolbarAction(AllIcons.Actions.Execute, OkScriptToolkitBundle.message("taskLauncher.run")) { runSelectedTask() }
    private val stopAction = ToolbarAction(AllIcons.Actions.Suspend, OkScriptToolkitBundle.message("taskLauncher.stop")) { taskRunner.stop() }
    private val pauseAction = ToolbarAction(AllIcons.Actions.Pause, OkScriptToolkitBundle.message("taskLauncher.pause")) { sendControlCommand("pause") }
    private val resumeAction = ToolbarAction(AllIcons.Actions.Play_forward, OkScriptToolkitBundle.message("taskLauncher.resume")) { sendControlCommand("resume") }
    private lateinit var actionToolbar: com.intellij.openapi.actionSystem.ActionToolbar
    private val statusLabel = JBLabel()
    private val progressBar = JProgressBar()

    private val paramPanel = JPanel(GridBagLayout())
    private val paramFields = mutableMapOf<String, JComponent>()
    private val timeoutSpinner = JSpinner(SpinnerNumberModel(0, 0, 7 * 24 * 60 * 60, 1))

    private var tasks = listOf<TaskLauncherService.TaskInfo>()
    private var configModule = "src.config"
    private var schemas = mapOf<String, TaskLauncherService.TaskSchema>()
    private val consoleArea = JBTextArea()
    private val saveTimer = javax.swing.Timer(400, null)

    /** 任务进程与运行状态由项目级服务持有：工具窗关闭不影响后台任务 */
    private val taskRunner = TaskRunnerService.getInstance(project)

    // ── Toolbox（游戏连接 + 调试浮层，状态由 ToolboxService 持有）──
    private val connectGameButton = JButton(OkScriptToolkitBundle.message("toolbox.connectGame"))
    private val disconnectGameButton = JButton(OkScriptToolkitBundle.message("toolbox.disconnect"))
    private val gameStatusLabel = JBLabel()
    private val toolboxStatusLabel = JBLabel()
    private val overlayCheckBox = JCheckBox(OkScriptToolkitBundle.message("toolbox.overlay"))
    /** 防止 renderToolbox 回写复选框选中态时再次触发用户切换事件 */
    private var updatingOverlayCheckbox = false

    private val toolboxStateListener: (ToolboxService.ToolboxState, String) -> Unit = { state, _ ->
        SwingUtilities.invokeLater { renderToolbox(state) }
    }
    private val toolboxStatusListener: (String) -> Unit = { text ->
        SwingUtilities.invokeLater { toolboxStatusLabel.text = text }
    }
    private val runnerOutputListener: (String) -> Unit = { line ->
        SwingUtilities.invokeLater { appendConsole(line) }
    }
    private val runnerStateListener: (TaskRunnerService.RunnerState) -> Unit = { state ->
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

    /** 按运行器状态刷新工具栏按钮与状态栏（EDT） */
    private fun syncRunnerState(state: TaskRunnerService.RunnerState) {
        runAction.isEnabled2 = !state.running
        stopAction.isEnabled2 = state.running
        pauseAction.isEnabled2 = state.running && !state.paused
        resumeAction.isEnabled2 = state.running && state.paused
        actionToolbar.updateActionsImmediately()
        state.controlError?.let { statusLabel.text = "Task control error: $it" }
        if (!state.running) {
            state.finishMessage?.let { statusLabel.text = it }
        }
    }

    private fun initUI() {
        val clearConsoleAction = ToolbarAction(AllIcons.Actions.GC, OkScriptToolkitBundle.message("taskLauncher.clearConsole")) {
            consoleArea.text = ""
        }
        stopAction.isEnabled2 = false
        pauseAction.isEnabled2 = false
        resumeAction.isEnabled2 = false

        val actionGroup = com.intellij.openapi.actionSystem.DefaultActionGroup(
            refreshAction, runAction, stopAction, pauseAction, resumeAction, clearConsoleAction,
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
        northPane.add(buildToolboxBar(), BorderLayout.SOUTH)

        mainPanel.add(northPane, BorderLayout.NORTH)
        mainPanel.add(centerPane, BorderLayout.CENTER)
        mainPanel.add(statusBar, BorderLayout.SOUTH)
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
                        key to TaskLauncherService.TaskSchema(displayName = task.displayName)
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
                        )
                    }

                    taskTableModel.rowCount = 0
                    for (task in tasks) {
                        val taskKey = "${task.module}::${task.className}"
                        val schema = schemas[taskKey]
                        val kind = schema?.kind ?: "onetime"
                        val status = when {
                            schema?.broken == true -> "Broken"
                            schema?.error != null -> "Error"
                            else -> "Ready"
                        }
                        taskTableModel.addRow(arrayOf(task.displayName, kind, status))
                    }

                    statusLabel.text = "Loaded ${tasks.size} tasks"
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

        val taskKey = "${task.module}::${task.className}"
        val schema = schemas[taskKey]

        var row = 0

        val timeoutLabel = JBLabel("${OkScriptToolkitBundle.message("taskLauncher.timeout")}:")
        paramPanel.add(timeoutLabel, GridBagConstraints().apply {
            gridx = 0; gridy = row
            anchor = GridBagConstraints.WEST
            insets = Insets(4, 8, 4, 4)
        })
        val timeoutPanel = JPanel(BorderLayout())
        timeoutPanel.add(timeoutSpinner, BorderLayout.CENTER)
        timeoutPanel.add(JBLabel("s"), BorderLayout.EAST)
        paramPanel.add(timeoutPanel, GridBagConstraints().apply {
            gridx = 1; gridy = row
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            insets = Insets(4, 4, 4, 8)
        })

        val taskConfig = taskService.getTaskConfig(taskKey)
        timeoutSpinner.value = taskConfig.timeout ?: 0
        timeoutSpinner.addChangeListener {
            autoSaveTaskConfig(task)
        }
        row++

        if (schema != null && schema.fields.isNotEmpty()) {
            val separator = JSeparator()
            paramPanel.add(separator, GridBagConstraints().apply {
                gridx = 0; gridy = row; gridwidth = 2
                fill = GridBagConstraints.HORIZONTAL
                insets = Insets(4, 0, 4, 0)
            })
            row++

            val groups = schema.configGroups
            if (groups.isNullOrEmpty()) {
                for (field in schema.fields) {
                    row = appendFieldRow(field, task, row)
                }
            } else {
                // 对齐 VSCode 版：按 configGroups 分组渲染（每组一个 titled 子面板），
                // 不属于任何组的字段平铺在"通用"下
                val groupedKeys = groups.values.flatten().toSet()
                for ((groupName, fieldKeys) in groups) {
                    val groupFields = fieldKeys.mapNotNull { key -> schema.fields.firstOrNull { it.key == key } }
                    if (groupFields.isEmpty()) continue
                    val groupPanel = JPanel(GridBagLayout())
                    groupPanel.border = BorderFactory.createTitledBorder(groupName)
                    var grow = 0
                    for (field in groupFields) {
                        val label = JBLabel("${field.displayKey ?: field.key}:")
                        val component = withFieldDescription(field, createFieldComponent(field, task))
                        groupPanel.add(label, GridBagConstraints().apply {
                            gridx = 0; gridy = grow
                            anchor = GridBagConstraints.WEST
                            insets = Insets(3, 6, 3, 4)
                        })
                        groupPanel.add(component, GridBagConstraints().apply {
                            gridx = 1; gridy = grow
                            fill = GridBagConstraints.HORIZONTAL
                            weightx = 1.0
                            insets = Insets(3, 4, 3, 6)
                        })
                        paramFields[field.key] = component
                        grow++
                    }
                    paramPanel.add(groupPanel, GridBagConstraints().apply {
                        gridx = 0; gridy = row; gridwidth = 2
                        fill = GridBagConstraints.HORIZONTAL
                        weightx = 1.0
                        insets = Insets(4, 2, 4, 2)
                    })
                    row++
                }
                val others = schema.fields.filter { it.key !in groupedKeys }
                for (field in others) {
                    row = appendFieldRow(field, task, row)
                }
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

    /** 下拉/多选列表的显示渲染器：option_labels 按索引对应，无标签时显示原始值 */
    private fun optionLabelRenderer(options: List<*>, labels: List<*>): javax.swing.ListCellRenderer<Any?> =
        com.intellij.ui.SimpleListCellRenderer.create<Any?>("") { value ->
            val index = options.indexOf(value)
            if (index >= 0) labels.getOrNull(index)?.toString() ?: value.toString()
            else value.toString()
        }

    private fun appendFieldRow(
        field: TaskLauncherService.TaskParamField,
        task: TaskLauncherService.TaskInfo,
        row: Int,
    ): Int {
        val label = JBLabel("${field.displayKey ?: field.key}:")
        val component = withFieldDescription(field, createFieldComponent(field, task))

        paramPanel.add(label, GridBagConstraints().apply {
            gridx = 0; gridy = row
            anchor = GridBagConstraints.WEST
            insets = Insets(4, 8, 4, 4)
        })
        paramPanel.add(component, GridBagConstraints().apply {
            gridx = 1; gridy = row
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            insets = Insets(4, 4, 4, 8)
        })
        paramFields[field.key] = component
        return row + 1
    }

    @Suppress("UNCHECKED_CAST")
    private fun createFieldComponent(field: TaskLauncherService.TaskParamField, task: TaskLauncherService.TaskInfo): JComponent {
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
                    cb.addActionListener { autoSaveTaskConfig(task) }
                }
            }

            typeName == "drop_down" || (typeName.isEmpty() && options != null && currentValue !is List<*>) -> {
                // 对齐 VSCode buildDropDown：option_labels 按索引做显示标签，保存原始值
                val comboBox = JComboBox(options!!.toTypedArray())
                comboBox.renderer = optionLabelRenderer(options, optionLabels)
                comboBox.selectedItem = currentValue
                comboBox.addActionListener { autoSaveTaskConfig(task) }
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
                list.addListSelectionListener { autoSaveTaskConfig(task) }
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
                groupCombo.renderer = com.intellij.ui.SimpleListCellRenderer.create("") { group ->
                    (categoryLabels?.get(group) ?: group)?.toString() ?: ""
                }
                val leafCombo = JComboBox<String>()
                leafCombo.renderer = com.intellij.ui.SimpleListCellRenderer.create("") { leaf ->
                    val values = options[groupCombo.selectedItem] as? List<*>
                    val idx = values?.indexOfFirst { it?.toString() == leaf } ?: -1
                    val labelsForGroup = (groupCombo.selectedItem as? String)?.let { leafLabels?.get(it) } as? List<*>
                    labelsForGroup?.getOrNull(idx)?.toString() ?: leaf
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
                    autoSaveTaskConfig(task)
                }
                leafCombo.addActionListener {
                    if (leafCombo.selectedItem != null) {
                        leafField.text = leafCombo.selectedItem?.toString()
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
                    jsonSequenceArea(currentValue, task)
                } else {
                    // 对齐 VSCode buildList：折叠摘要 + 「修改」弹窗（ModifyListDialog 语义）
                    ListEditorComponent(
                        project = project,
                        dialogTitle = field.displayKey ?: field.key,
                        typeMeta = field.type,
                        initialValue = currentValue,
                        onChanged = { autoSaveTaskConfig(task) },
                    )
                }
            }

            field.type?.get("type") == "cond_sequence_editor" -> jsonSequenceArea(currentValue, task)

            currentValue is Int -> {
                JSpinner(SpinnerNumberModel(currentValue, Int.MIN_VALUE, Int.MAX_VALUE, 1)).also { sp ->
                    sp.addChangeListener { autoSaveTaskConfig(task) }
                }
            }
            currentValue is Double -> {
                JSpinner(SpinnerNumberModel(currentValue, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, 0.1)).also { sp ->
                    sp.addChangeListener { autoSaveTaskConfig(task) }
                }
            }

            else -> {
                val text = currentValue?.toString() ?: ""
                
                if (field.type?.get("type") == "text_edit" || text.contains(LF_CHAR) || text.length > 80) {
                    val area = JTextArea(text)
                    area.rows = 3
                    area.lineWrap = true
                    area.document.addDocumentListener(object : DocumentListener {
                        override fun insertUpdate(e: DocumentEvent?) = autoSaveTaskConfig(task)
                        override fun removeUpdate(e: DocumentEvent?) = autoSaveTaskConfig(task)
                        override fun changedUpdate(e: DocumentEvent?) = autoSaveTaskConfig(task)
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
            if (parsed != null) return parsed
        }
        return text
    }

    /** 条件/动作序列：多行 JSON 编辑 + 非法 JSON 红边提示（VSCode 版为结构化编辑器，此处为务实折中） */
    private fun jsonSequenceArea(
        currentValue: Any?,
        task: TaskLauncherService.TaskInfo,
    ): JComponent {
        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
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
            val valid = text.isEmpty() || runCatching { mapper.readTree(text) }.isSuccess
            area.border = BorderFactory.createLineBorder(if (valid) OK_BORDER else BAD_BORDER)
        }
        area.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) { validateJson(); autoSaveTaskConfig(task) }
            override fun removeUpdate(e: DocumentEvent?) { validateJson(); autoSaveTaskConfig(task) }
            override fun changedUpdate(e: DocumentEvent?) { validateJson(); autoSaveTaskConfig(task) }
        })
        validateJson()
        return JBScrollPane(area).apply { preferredSize = Dimension(200, 90) }
    }

    private fun autoSaveTaskConfig(task: TaskLauncherService.TaskInfo) {
        // 参数变更在 EDT 上高频触发：先构建快照，文件 IO 经 400ms 防抖后放到后台执行
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
            } catch (e: Exception) {
                LOG.warn("Failed to save task config for $taskKey", e)
            }
        }
    }

    private fun buildTaskConfig(task: TaskLauncherService.TaskInfo): TaskLauncherService.TaskConfig {
        val params = mutableMapOf<String, Any>()
        for ((key, component) in paramFields) {
            when (component) {
                is JCheckBox -> params[key] = component.isSelected
                is JSpinner -> params[key] = component.value
                is JTextField -> if (component.text.isNotBlank()) params[key] = component.text
                is JTextArea -> textAreaValue(component)?.let { params[key] = it }
                // 对齐 VSCode：表单当前值全量保存（包括空列表）
                is ListEditorComponent -> params[key] = component.value
                is JComboBox<*> -> component.selectedItem?.let { params[key] = it }
                is JList<*> -> {
                    val selectedValues = component.selectedValuesList.toList()
                    if (selectedValues.isNotEmpty()) params[key] = selectedValues
                }
            }
        }
        val timeout = (timeoutSpinner.value as? Number)?.toInt()?.takeIf { it > 0 }
        return TaskLauncherService.TaskConfig(
            timeout = timeout,
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

    private fun runSelectedTask() {
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
        if (taskRunner.isRunning()) {
            JOptionPane.showMessageDialog(
                mainPanel,
                OkScriptToolkitBundle.message("taskLauncher.taskRunning"),
                "Warning",
                JOptionPane.WARNING_MESSAGE,
            )
            return
        }

        val task = tasks[selectedRow]
        val projectDir = detectProjectPath()
        if (projectDir.isBlank()) {
            statusLabel.text = OkScriptToolkitBundle.message("taskLauncher.noProject")
            return
        }

        val pythonPath = detectPythonPath()
        val taskConfig = taskService.getTaskConfig("${task.module}::${task.className}")
        val command = taskService.buildRunTaskCommand(task, configModule)

        // 对齐 VSCode：额外参数在 "--" 之后追加；解析失败报错并中止启动
        val extraArgs = try {
            taskService.parseExtraArgs(taskConfig.extraArgs)
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(
                mainPanel,
                OkScriptToolkitBundle.message("taskLauncher.launchFailed", e.message ?: ""),
                "Error",
                JOptionPane.ERROR_MESSAGE,
            )
            return
        }

        val env = mutableMapOf<String, String>()
        env["PYTHONIOENCODING"] = "utf-8"
        env["PYTHONUTF8"] = "1"
        // 任务独立 env 覆盖基础变量（参数注入/工具箱键在其后写入、不会被覆盖）
        taskConfig.env?.forEach { (k, v) -> env[k] = v }

        val paramOverrides = getParamOverrides()
        if (paramOverrides.isNotEmpty()) {
            val injectKey = "${task.module}::${task.className}"
            val inject = mapOf(injectKey to paramOverrides)
            val injectJson = com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(inject)
            env["OK_LANG_HINTS_INJECT"] = injectJson
        }

        // 工具箱共享配置：任务启动无感沿用调试浮层开关与游戏连接
        val toolboxState = toolboxService.loadState(projectDir)
        if (toolboxState.overlay) {
            env["OK_TOOLKIT_USE_OVERLAY"] = "1"
            taskRunner.log(OkScriptToolkitBundle.message("toolbox.overlayEnabledLog"))
        }
        toolboxState.game?.let { game ->
            // 实际复用由 connect_game.py 写入的 configs/devices.json selected_hwnd 驱动，
            // 这里仅记录连接来源，便于确认任务与工具箱操作的是同一个窗口。
            taskRunner.log(
                OkScriptToolkitBundle.message(
                    "toolbox.reuseConnection",
                    game.title.ifEmpty { game.hwnd.toString() },
                    game.pid,
                ),
            )
        }

        val fullCommand = command + listOf("--") + extraArgs

        statusLabel.text = "Running: ${task.displayName}..."
        taskRunner.start(
            task = task,
            pythonPath = pythonPath,
            command = fullCommand,
            projectDir = projectDir,
            env = env,
            timeoutSeconds = taskConfig.timeout ?: 0,
        )
    }

    private fun sendControlCommand(command: String) {
        if (!taskRunner.isRunning()) {
            JOptionPane.showMessageDialog(
                mainPanel,
                OkScriptToolkitBundle.message("taskLauncher.noTaskRunning"),
                "Warning",
                JOptionPane.WARNING_MESSAGE,
            )
            return
        }
        if (!taskRunner.sendCommand(command)) {
            statusLabel.text = OkScriptToolkitBundle.message("toolbox.sendCommandFailed", command)
        }
    }

    private fun getParamOverrides(): Map<String, Any> {
        val overrides = mutableMapOf<String, Any>()
        for ((key, component) in paramFields) {
            when (component) {
                is JCheckBox -> overrides[key] = component.isSelected
                is JSpinner -> overrides[key] = component.value
                is JTextField -> if (component.text.isNotBlank()) overrides[key] = component.text
                is JTextArea -> textAreaValue(component)?.let { overrides[key] = it }
                is ListEditorComponent -> overrides[key] = component.value
                is JComboBox<*> -> component.selectedItem?.let { overrides[key] = it }
                is JList<*> -> {
                    val selectedValues = component.selectedValuesList.toList()
                    if (selectedValues.isNotEmpty()) overrides[key] = selectedValues
                }
            }
        }
        return overrides
    }

    fun onDispose() {
        // 只解绑视图：任务进程由 TaskRunnerService 持有，工具窗关闭后台任务继续运行
        toolboxService.removeStateListener(toolboxStateListener)
        toolboxService.removeStatusListener(toolboxStatusListener)
        taskRunner.removeOutputListener(runnerOutputListener)
        taskRunner.removeStateListener(runnerStateListener)
    }
}


