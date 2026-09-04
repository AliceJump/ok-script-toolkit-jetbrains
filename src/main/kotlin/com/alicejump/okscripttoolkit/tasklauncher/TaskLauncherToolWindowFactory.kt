package com.alicejump.okscripttoolkit.tasklauncher

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.alicejump.okscripttoolkit.settings.OkScriptToolkitSettings
import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import javax.swing.*
import javax.swing.table.DefaultTableModel

/**
 * 任务启动器工具窗口工厂。
 * 对应 VS Code 版本的 TaskLauncherViewProvider。
 */
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

/**
 * 任务启动器面板。
 * 对应 VS Code 版本的 TaskLauncherViewProvider 中的 Webview UI。
 */
class TaskLauncherPanel(private val project: Project) {

    companion object {
        private val LOG = Logger.getInstance(TaskLauncherPanel::class.java)
        private const val DEFAULT_PYTHON_PATH = "python"
    }

    val mainPanel: JPanel
    private val taskService = TaskLauncherService(project)

    // UI 组件
    private val taskTableModel = DefaultTableModel(arrayOf("Task", "Type", "Status"), 0)
    private val taskTable = JBTable(taskTableModel)
    private val refreshButton = JButton()
    private val runButton = JButton()
    private val stopButton = JButton()
    private val statusLabel = JBLabel()
    private val progressBar = JProgressBar()

    // 参数面板
    private val paramPanel = JPanel(GridBagLayout())
    private val paramFields = mutableMapOf<String, JComponent>()

    // 状态
    private var tasks = listOf<TaskLauncherService.TaskInfo>()
    private var currentProcess: Process? = null
    private var currentTask: TaskLauncherService.TaskInfo? = null
    private var configModule = "src.config"
    private var schemas = mapOf<String, TaskLauncherService.TaskSchema>()

    init {
        mainPanel = JPanel(BorderLayout())
        initUI()
        loadTasks()
    }

    private fun initUI() {
        // 工具栏
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

        toolbar.add(refreshButton)
        toolbar.add(Box.createHorizontalStrut(4))
        toolbar.add(runButton)
        toolbar.add(Box.createHorizontalStrut(4))
        toolbar.add(stopButton)

        // 任务表格
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

        // 参数面板
        val paramScrollPane = JBScrollPane(paramPanel)
        paramScrollPane.border = BorderFactory.createTitledBorder("Parameters")

        // 底部状态栏
        val statusBar = JPanel(BorderLayout())
        statusBar.border = BorderFactory.createEmptyBorder(2, 4, 2, 4)
        statusBar.add(statusLabel, BorderLayout.CENTER)
        statusBar.add(progressBar, BorderLayout.EAST)
        progressBar.preferredSize = Dimension(200, 20)
        progressBar.isVisible = false

        // 分割面板
        val splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT, tableScrollPane, paramScrollPane)
        splitPane.resizeWeight = 0.5

        mainPanel.add(toolbar, BorderLayout.NORTH)
        mainPanel.add(splitPane, BorderLayout.CENTER)
        mainPanel.add(statusBar, BorderLayout.SOUTH)
    }

    /**
     * 自动检测项目路径。
     * 优先使用设置中的路径，否则尝试工作区根目录。
     */
    private fun detectProjectPath(): String {
        val settings = OkScriptToolkitSettings.getInstance(project)
        val configured = settings.okScriptProjectPath()
        if (configured.isNotBlank()) {
            val path = configured.replace("~", System.getProperty("user.home"))
            if (Files.isDirectory(Paths.get(path))) return path
        }

        // 自动检测：项目根目录下是否存在 src/config.py 或 config.py
        val basePath = project.basePath ?: return ""
        val candidates = listOf(
            Paths.get(basePath, "src", "config.py"),
            Paths.get(basePath, "config.py"),
        )
        return if (candidates.any { Files.exists(it) }) basePath else ""
    }

    /**
     * 自动检测 Python 解释器路径。
     * 优先使用设置中的路径，然后检查 .venv，最后使用默认值。
     */
    private fun detectPythonPath(): String {
        val settings = OkScriptToolkitSettings.getInstance(project)
        val configured = settings.okScriptPython()
        if (configured.isNotBlank()) return configured

        // 检查 .venv/Scripts/python.exe
        val projectDir = detectProjectPath()
        if (projectDir.isNotBlank()) {
            val venvPy = Paths.get(projectDir, ".venv", "Scripts", "python.exe").toFile()
            if (venvPy.exists()) return venvPy.absolutePath
            val venvPyUnix = Paths.get(projectDir, ".venv", "bin", "python").toFile()
            if (venvPyUnix.exists()) return venvPyUnix.absolutePath
        }

        return DEFAULT_PYTHON_PATH
    }

    /**
     * 加载任务列表。
     */
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
            // 先尝试从缓存加载
            val cachedResult = taskService.loadSchemaCache()
            if (cachedResult.ok && cachedResult.schemas != null) {
                return@supplyAsync cachedResult
            }

            // 重新探测
            val pythonPath = detectPythonPath()
            taskService.probeTaskSchemas(pythonPath)
        }.thenAccept { result ->
            SwingUtilities.invokeLater {
                progressBar.isIndeterminate = false
                progressBar.isVisible = false

                if (result.ok && result.schemas != null) {
                    schemas = result.schemas

                    // 从 schema 中提取任务列表
                    tasks = result.schemas.map { (key, schema) ->
                        val parts = key.split("::")
                        TaskLauncherService.TaskInfo(
                            module = parts.getOrElse(0) { "" },
                            className = parts.getOrElse(1) { key },
                            displayName = schema.displayName ?: parts.getOrElse(1) { key },
                        )
                    }

                    // 更新表格
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

    /**
     * 加载任务参数。
     */
    private fun loadTaskParams(task: TaskLauncherService.TaskInfo) {
        paramPanel.removeAll()
        paramFields.clear()

        val taskKey = "${task.module}::${task.className}"
        val schema = schemas[taskKey]

        if (schema == null) {
            val gbc = GridBagConstraints().apply {
                gridx = 0; gridy = 0; insets = Insets(10, 10, 10, 10)
            }
            paramPanel.add(JBLabel("No schema available for this task"), gbc)
            paramPanel.revalidate()
            paramPanel.repaint()
            return
        }

        var row = 0
        for (field in schema.fields) {
            val label = JBLabel("${field.displayKey ?: field.key}:")
            val component = createFieldComponent(field)

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
            row++
        }

        paramPanel.revalidate()
        paramPanel.repaint()
    }

    /**
     * 创建参数字段组件。
     */
    @Suppress("UNCHECKED_CAST")
    private fun createFieldComponent(field: TaskLauncherService.TaskParamField): JComponent {
        val currentValue = field.value ?: field.default

        return when {
            // 布尔类型
            field.type?.get("type") == "bool" || currentValue is Boolean -> {
                JCheckBox("", currentValue as? Boolean ?: false)
            }

            // 下拉选择
            field.type?.get("type") == "drop_down" -> {
                val options = field.type?.get("options") as? List<*> ?: emptyList<Any>()
                val comboBox = JComboBox(options.toTypedArray())
                comboBox.selectedItem = currentValue
                comboBox
            }

            // 多选
            field.type?.get("type") == "multi_selection" -> {
                val options = field.type?.get("options") as? List<*> ?: emptyList<Any>()
                val list = JList(options.toTypedArray())
                list.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
                if (currentValue is List<*>) {
                    val selectedIndices = currentValue.mapNotNull { options.indexOf(it).takeIf { i -> i >= 0 } }
                    list.selectedIndices = selectedIndices.toIntArray()
                }
                JBScrollPane(list)
            }

            // 数字类型
            currentValue is Int -> {
                JSpinner(SpinnerNumberModel(currentValue, Int.MIN_VALUE, Int.MAX_VALUE, 1))
            }
            currentValue is Double -> {
                JSpinner(SpinnerNumberModel(currentValue, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, 0.1))
            }

            // 文本类型（默认）
            else -> {
                val textField = JTextField(currentValue?.toString() ?: "")
                textField.columns = 20
                textField
            }
        }
    }

    /**
     * 获取当前选中任务的参数覆盖。
     */
    private fun getParamOverrides(): Map<String, Any> {
        val overrides = mutableMapOf<String, Any>()
        for ((key, component) in paramFields) {
            when (component) {
                is JCheckBox -> overrides[key] = component.isSelected
                is JSpinner -> overrides[key] = component.value
                is JTextField -> if (component.text.isNotBlank()) overrides[key] = component.text
                is JComboBox<*> -> component.selectedItem?.let { overrides[key] = it }
                is JList<*> -> {
                    val selectedValues = component.selectedValuesList.toList()
                    if (selectedValues.isNotEmpty()) overrides[key] = selectedValues
                }
            }
        }
        return overrides
    }

    /**
     * 运行选中的任务。
     */
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
        val taskConfig = taskService.getTaskConfig(taskService.getProjectName(), "${task.module}::${task.className}")

        val command = taskService.buildRunTaskCommand(task, configModule)

        val env = mutableMapOf<String, String>()
        env["PYTHONIOENCODING"] = "utf-8"
        env["PYTHONUTF8"] = "1"
        taskConfig.env?.let { env.putAll(it) }

        val paramOverrides = getParamOverrides()
        if (paramOverrides.isNotEmpty()) {
            val injectKey = "${task.module}::${task.className}"
            val inject = mapOf(injectKey to paramOverrides)
            val injectJson = com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(inject)
            env["OK_LANG_HINTS_INJECT"] = injectJson
        }

        val extraArgs = taskService.parseExtraArgs(taskConfig.extraArgs)
        val fullCommand = listOf(pythonPath) + command + extraArgs

        statusLabel.text = "Running: ${task.displayName}..."
        runButton.isEnabled = false
        stopButton.isEnabled = true

        try {
            val processBuilder = ProcessBuilder(fullCommand)
                .directory(File(projectDir))
            val envMap = processBuilder.environment()
            env.forEach { (k, v) -> envMap[k] = v }

            val process = processBuilder.start()
            currentProcess = process
            currentTask = task

            // 异步读取输出（消费 stdout 防止管道阻塞）
            Thread {
                try {
                    process.inputStream.bufferedReader().use { reader ->
                        while (reader.readLine() != null) { /* consume */ }
                    }
                } catch (_: Exception) { }
            }.start()

            // 监听进程结束
            Thread {
                try {
                    val exitCode = process.waitFor()
                    SwingUtilities.invokeLater {
                        statusLabel.text = if (exitCode == 0) {
                            OkScriptToolkitBundle.message("taskLauncher.taskCompleted")
                        } else {
                            OkScriptToolkitBundle.message("taskLauncher.taskFailed") + " (exit code $exitCode)"
                        }
                        runButton.isEnabled = true
                        stopButton.isEnabled = false
                        currentProcess = null
                        currentTask = null
                    }
                } catch (e: InterruptedException) {
                    SwingUtilities.invokeLater {
                        statusLabel.text = OkScriptToolkitBundle.message("taskLauncher.taskStopped")
                        runButton.isEnabled = true
                        stopButton.isEnabled = false
                        currentProcess = null
                        currentTask = null
                    }
                }
            }.start()
        } catch (e: Exception) {
            LOG.error("Failed to run task", e)
            statusLabel.text = "Failed: ${e.message}"
            runButton.isEnabled = true
            stopButton.isEnabled = false
            JOptionPane.showMessageDialog(
                mainPanel,
                "Failed to run task: ${e.message}",
                "Error",
                JOptionPane.ERROR_MESSAGE,
            )
        }
    }

    /**
     * 停止当前任务。
     */
    private fun stopCurrentTask() {
        currentProcess?.let { process ->
            if (process.isAlive) {
                process.destroyForcibly()
                statusLabel.text = OkScriptToolkitBundle.message("taskLauncher.taskStopped")
                runButton.isEnabled = true
                stopButton.isEnabled = false
                currentProcess = null
                currentTask = null
            }
        }
    }

    /**
     * 面板销毁时调用。
     */
    fun onDispose() {
        stopCurrentTask()
    }
}
