package com.alicejump.okscripttoolkit.tasklauncher

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.alicejump.okscripttoolkit.core.OkProjectDataService
import com.alicejump.okscripttoolkit.settings.OkScriptToolkitSettings
import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
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
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
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
    }

    val mainPanel: JPanel
    private val taskService = TaskLauncherService(project)

    private val taskTableModel = DefaultTableModel(arrayOf("Task", "Type", "Status"), 0)
    private val taskTable = JBTable(taskTableModel)
    private val refreshButton = JButton()
    private val runButton = JButton()
    private val stopButton = JButton()
    private val pauseButton = JButton()
    private val resumeButton = JButton()
    private val statusLabel = JBLabel()
    private val progressBar = JProgressBar()

    private val paramPanel = JPanel(GridBagLayout())
    private val paramFields = mutableMapOf<String, JComponent>()
    private val timeoutSpinner = JSpinner(SpinnerNumberModel(0, 0, 7 * 24 * 60 * 60, 1))

    private var tasks = listOf<TaskLauncherService.TaskInfo>()
    private var currentProcess: Process? = null
    private var currentTask: TaskLauncherService.TaskInfo? = null
    private var configModule = "src.config"
    private var schemas = mapOf<String, TaskLauncherService.TaskSchema>()
    private var paused = false
    private var stdoutRemainder = ""
    private val stopping = AtomicBoolean(false)
    private val consoleArea = JBTextArea()
    private val saveTimer = javax.swing.Timer(400, null)

    init {
        mainPanel = JPanel(BorderLayout())
        saveTimer.isRepeats = false
        saveTimer.addActionListener { flushPendingSave() }
        initUI()
        loadTasks()
    }

    private fun initUI() {
        val toolbar = JPanel()
        toolbar.layout = BoxLayout(toolbar, BoxLayout.X_AXIS)
        toolbar.border = BorderFactory.createEmptyBorder(4, 4, 4, 4)

        refreshButton.icon = AllIcons.Actions.Refresh
        refreshButton.toolTipText = OkScriptToolkitBundle.message("taskLauncher.refresh")
        refreshButton.addActionListener { loadTasks() }

        runButton.icon = AllIcons.Actions.Execute
        runButton.toolTipText = OkScriptToolkitBundle.message("taskLauncher.run")
        runButton.addActionListener { runSelectedTask() }

        stopButton.icon = AllIcons.Actions.Suspend
        stopButton.toolTipText = OkScriptToolkitBundle.message("taskLauncher.stop")
        stopButton.addActionListener { stopCurrentTask() }
        stopButton.isEnabled = false

        pauseButton.icon = AllIcons.Actions.Pause
        pauseButton.toolTipText = OkScriptToolkitBundle.message("taskLauncher.pause")
        pauseButton.addActionListener { sendControlCommand("pause") }
        pauseButton.isEnabled = false

        resumeButton.icon = AllIcons.Actions.Play_forward
        resumeButton.toolTipText = OkScriptToolkitBundle.message("taskLauncher.resume")
        resumeButton.addActionListener { sendControlCommand("resume") }
        resumeButton.isEnabled = false

        val clearConsoleButton = JButton(AllIcons.Actions.GC)
        clearConsoleButton.toolTipText = OkScriptToolkitBundle.message("taskLauncher.clearConsole")
        clearConsoleButton.addActionListener { consoleArea.text = "" }

        toolbar.add(refreshButton)
        toolbar.add(Box.createHorizontalStrut(4))
        toolbar.add(runButton)
        toolbar.add(Box.createHorizontalStrut(4))
        toolbar.add(stopButton)
        toolbar.add(Box.createHorizontalStrut(4))
        toolbar.add(pauseButton)
        toolbar.add(Box.createHorizontalStrut(4))
        toolbar.add(resumeButton)
        toolbar.add(Box.createHorizontalStrut(4))
        toolbar.add(clearConsoleButton)

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
        paramScrollPane.border = BorderFactory.createTitledBorder("Parameters")

        val statusBar = JPanel(BorderLayout())
        statusBar.border = BorderFactory.createEmptyBorder(2, 4, 2, 4)
        statusBar.add(statusLabel, BorderLayout.CENTER)
        progressBar.preferredSize = Dimension(200, 20)
        progressBar.isVisible = false
        statusBar.add(progressBar, BorderLayout.EAST)

        val splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT, tableScrollPane, paramScrollPane)
        splitPane.resizeWeight = 0.5

        // 输出控制台：对齐 VSCode 版的专属输出频道，展示任务 stdout/stderr
        consoleArea.isEditable = false
        consoleArea.lineWrap = false
        consoleArea.rows = 6
        val consoleScrollPane = JBScrollPane(consoleArea)
        consoleScrollPane.border = BorderFactory.createTitledBorder(OkScriptToolkitBundle.message("taskLauncher.console"))

        val centerPane = JSplitPane(JSplitPane.VERTICAL_SPLIT, splitPane, consoleScrollPane)
        centerPane.resizeWeight = 0.62

        mainPanel.add(toolbar, BorderLayout.NORTH)
        mainPanel.add(centerPane, BorderLayout.CENTER)
        mainPanel.add(statusBar, BorderLayout.SOUTH)
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
            val cachedResult = taskService.loadSchemaCache(projectDir, locale)
            if (cachedResult.ok && cachedResult.schemas != null) {
                return@supplyAsync cachedResult
            }
            val pythonPath = detectPythonPath()
            // parse_config_tasks.py 是纯 AST 解析（快），用它的 config_module 结果，
            // 对齐 VSCode 版不再硬编码 "src.config"
            val parseResult = taskService.parseConfigTasks(pythonPath, locale)
            val probeResult = taskService.probeTaskSchemas(pythonPath, locale)
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
                        val component = createFieldComponent(field, task)
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

    private fun appendFieldRow(
        field: TaskLauncherService.TaskParamField,
        task: TaskLauncherService.TaskInfo,
        row: Int,
    ): Int {
        val label = JBLabel("${field.displayKey ?: field.key}:")
        val component = createFieldComponent(field, task)

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

        val component = when {
            field.type?.get("type") == "bool" || currentValue is Boolean -> {
                JCheckBox("", currentValue as? Boolean ?: false).also { cb ->
                    cb.addActionListener { autoSaveTaskConfig(task) }
                }
            }

            field.type?.get("type") == "drop_down" -> {
                val options = field.type?.get("options") as? List<*> ?: emptyList<Any>()
                val comboBox = JComboBox(options.toTypedArray())
                comboBox.selectedItem = currentValue
                comboBox.addActionListener { autoSaveTaskConfig(task) }
                comboBox
            }

            field.type?.get("type") == "multi_selection" -> {
                val options = field.type?.get("options") as? List<*> ?: emptyList<Any>()
                val list = JList(options.toTypedArray())
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
                val leafField = JTextField(currentValue?.toString() ?: "")
                leafField.isVisible = false
                val groupCombo = JComboBox(options.keys.toTypedArray())
                val leafCombo = JComboBox<String>()
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

            field.type?.get("type") == "cond_sequence_editor" -> {
                // 条件序列：多行 JSON 编辑 + 非法 JSON 红边提示（VSCode 版为结构化编辑器，此处为务实折中）
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
                fun validateJson() {
                    val text = area.text.trim()
                    val valid = text.isEmpty() || runCatching { mapper.readTree(text) }.isSuccess
                    area.border = BorderFactory.createLineBorder(if (valid) Color(40, 120, 40) else Color(180, 40, 40))
                }
                area.document.addDocumentListener(object : DocumentListener {
                    override fun insertUpdate(e: DocumentEvent?) { validateJson(); autoSaveTaskConfig(task) }
                    override fun removeUpdate(e: DocumentEvent?) { validateJson(); autoSaveTaskConfig(task) }
                    override fun changedUpdate(e: DocumentEvent?) { validateJson(); autoSaveTaskConfig(task) }
                })
                validateJson()
                JBScrollPane(area).apply { preferredSize = Dimension(200, 90) }
            }

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
                val textField = JTextField(currentValue?.toString() ?: "")
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
                is JTextArea -> if (component.text.isNotBlank()) params[key] = component.text
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

        val task = tasks[selectedRow]
        val projectDir = detectProjectPath()
        if (projectDir.isBlank()) {
            statusLabel.text = OkScriptToolkitBundle.message("taskLauncher.noProject")
            return
        }

        val pythonPath = detectPythonPath()
        val taskConfig = taskService.getTaskConfig("${task.module}::${task.className}")
        val command = taskService.buildRunTaskCommand(task, configModule)

        val env = mutableMapOf<String, String>()
        env["PYTHONIOENCODING"] = "utf-8"
        env["PYTHONUTF8"] = "1"

        val paramOverrides = getParamOverrides()
        if (paramOverrides.isNotEmpty()) {
            val injectKey = "${task.module}::${task.className}"
            val inject = mapOf(injectKey to paramOverrides)
            val injectJson = com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(inject)
            env["OK_LANG_HINTS_INJECT"] = injectJson
        }

        val fullCommand = listOf(pythonPath) + command

        statusLabel.text = "Running: ${task.displayName}..."
        appendConsole("=== ${task.displayName} ===")
        runButton.isEnabled = false
        stopButton.isEnabled = true
        pauseButton.isEnabled = true
        paused = false
        stdoutRemainder = ""
        stopping.set(false)

        // 进程启动涉及 IO，移出 EDT
        CompletableFuture.runAsync {
            try {
                val processBuilder = ProcessBuilder(fullCommand)
                    .directory(File(projectDir))
                val envMap = processBuilder.environment()
                env.forEach { (k, v) -> envMap[k] = v }

                val process = processBuilder.start()
                currentProcess = process
                currentTask = task

                val stdoutReader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
                val stderrReader = BufferedReader(InputStreamReader(process.errorStream, Charsets.UTF_8))

                Thread {
                    try {
                        stdoutReader.lineSequence().forEach { line ->
                            appendConsole(line)
                            scanControlMarkers(line)
                        }
                    } catch (_: Exception) { }
                }.start()

                Thread {
                    try {
                        stderrReader.lineSequence().forEach { line ->
                            appendConsole(line)
                        }
                    } catch (_: Exception) { }
                }.start()

            Thread {
                try {
                    val exitCode = process.waitFor()
                    SwingUtilities.invokeLater {
                        statusLabel.text = if (stopping.get()) {
                            OkScriptToolkitBundle.message("taskLauncher.taskStopped")
                        } else if (exitCode == 0) {
                            OkScriptToolkitBundle.message("taskLauncher.taskCompleted")
                        } else {
                            OkScriptToolkitBundle.message("taskLauncher.taskFailed") + " (exit code $exitCode)"
                        }
                        runButton.isEnabled = true
                        stopButton.isEnabled = false
                        pauseButton.isEnabled = false
                        resumeButton.isEnabled = false
                        currentProcess = null
                        currentTask = null
                        paused = false
                    }
                } catch (e: InterruptedException) {
                    SwingUtilities.invokeLater {
                        statusLabel.text = OkScriptToolkitBundle.message("taskLauncher.taskStopped")
                        runButton.isEnabled = true
                        stopButton.isEnabled = false
                        pauseButton.isEnabled = false
                        resumeButton.isEnabled = false
                        currentProcess = null
                        currentTask = null
                        paused = false
                    }
                }
            }.start()

            val timeout = taskConfig.timeout ?: 0
            if (timeout > 0) {
                Thread {
                    try {
                        Thread.sleep(timeout * 1000L)
                        if (currentProcess?.isAlive == true) {
                            SwingUtilities.invokeLater {
                                statusLabel.text = OkScriptToolkitBundle.message("taskLauncher.taskTimeout")
                            }
                            stopCurrentTask()
                        }
                    } catch (_: InterruptedException) { }
                }.start()
            }
        } catch (e: Exception) {
            LOG.error("Failed to run task", e)
            SwingUtilities.invokeLater {
                statusLabel.text = "Failed: ${e.message}"
                runButton.isEnabled = true
                stopButton.isEnabled = false
                pauseButton.isEnabled = false
                resumeButton.isEnabled = false
                JOptionPane.showMessageDialog(
                    mainPanel,
                    "Failed to run task: ${e.message}",
                    "Error",
                    JOptionPane.ERROR_MESSAGE,
                )
            }
        }
        }
    }

    private fun sendControlCommand(command: String) {
        val process = currentProcess
        if (process == null || !process.isAlive) {
            JOptionPane.showMessageDialog(
                mainPanel,
                OkScriptToolkitBundle.message("taskLauncher.noTaskRunning"),
                "Warning",
                JOptionPane.WARNING_MESSAGE,
            )
            return
        }
        try {
            val writer = OutputStreamWriter(process.outputStream, Charsets.UTF_8)
            writer.write("$command\n")
            writer.flush()
        } catch (e: Exception) {
            LOG.error("Failed to send control command: $command", e)
            statusLabel.text = "Failed to send command: ${e.message}"
        }
    }

    private fun scanControlMarkers(line: String) {
        when {
            line.contains("OK_TOOLKIT_PAUSED") -> setPaused(true)
            line.contains("OK_TOOLKIT_RESUMED") -> setPaused(false)
            line.contains("OK_TOOLKIT_ERROR:") -> {
                val error = line.substringAfter("OK_TOOLKIT_ERROR:").trim()
                if (error.isNotBlank()) {
                    SwingUtilities.invokeLater {
                        statusLabel.text = "Task control error: $error"
                    }
                }
            }
        }
    }

    private fun setPaused(newPaused: Boolean) {
        if (paused == newPaused) return
        paused = newPaused
        SwingUtilities.invokeLater {
            pauseButton.isEnabled = newPaused.not() && currentProcess?.isAlive == true
            resumeButton.isEnabled = newPaused && currentProcess?.isAlive == true
        }
    }

    private fun getParamOverrides(): Map<String, Any> {
        val overrides = mutableMapOf<String, Any>()
        for ((key, component) in paramFields) {
            when (component) {
                is JCheckBox -> overrides[key] = component.isSelected
                is JSpinner -> overrides[key] = component.value
                is JTextField -> if (component.text.isNotBlank()) overrides[key] = component.text
                is JTextArea -> if (component.text.isNotBlank()) overrides[key] = component.text
                is JComboBox<*> -> component.selectedItem?.let { overrides[key] = it }
                is JList<*> -> {
                    val selectedValues = component.selectedValuesList.toList()
                    if (selectedValues.isNotEmpty()) overrides[key] = selectedValues
                }
            }
        }
        return overrides
    }

    private fun stopCurrentTask() {
        currentProcess?.let { process ->
            if (process.isAlive) {
                stopping.set(true)
                appendConsole("--- ${OkScriptToolkitBundle.message("taskLauncher.taskStopped")} ---")
                // taskkill /F /T 是阻塞调用，移出 EDT
                CompletableFuture.runAsync {
                    try {
                        val pid = process.pid()
                        if (pid > 0 && System.getProperty("os.name").lowercase().contains("win")) {
                            ProcessBuilder("taskkill", "/F", "/T", "/PID", pid.toString())
                                .redirectErrorStream(true)
                                .start()
                                .waitFor()
                        } else {
                            process.destroyForcibly()
                        }
                    } catch (e: Exception) {
                        LOG.warn("Failed to stop task process", e)
                        process.destroyForcibly()
                    }
                    SwingUtilities.invokeLater {
                        statusLabel.text = OkScriptToolkitBundle.message("taskLauncher.taskStopped")
                        runButton.isEnabled = true
                        stopButton.isEnabled = false
                        pauseButton.isEnabled = false
                        resumeButton.isEnabled = false
                        currentProcess = null
                        currentTask = null
                        paused = false
                    }
                }
            }
        }
    }

    fun onDispose() {
        stopCurrentTask()
    }
}
