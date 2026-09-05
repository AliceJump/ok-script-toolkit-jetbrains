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
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
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

    init {
        mainPanel = JPanel(BorderLayout())
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

        toolbar.add(refreshButton)
        toolbar.add(Box.createHorizontalStrut(4))
        toolbar.add(runButton)
        toolbar.add(Box.createHorizontalStrut(4))
        toolbar.add(stopButton)
        toolbar.add(Box.createHorizontalStrut(4))
        toolbar.add(pauseButton)
        toolbar.add(Box.createHorizontalStrut(4))
        toolbar.add(resumeButton)

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

        mainPanel.add(toolbar, BorderLayout.NORTH)
        mainPanel.add(splitPane, BorderLayout.CENTER)
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
            val probeResult = taskService.probeTaskSchemas(pythonPath, locale)
            if (probeResult.ok && probeResult.schemas != null) {
                taskService.saveSchemaCache(projectDir, locale, probeResult)
            }
            probeResult
        }.thenAccept { result ->
            SwingUtilities.invokeLater {
                progressBar.isIndeterminate = false
                progressBar.isVisible = false

                if (result.ok && result.schemas != null) {
                    schemas = result.schemas
                    if (result.projectDir != null) {
                        configModule = "src.config"
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

            for (field in schema.fields) {
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
                row++
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
        val taskKey = "${task.module}::${task.className}"
        val config = buildTaskConfig(task)
        taskService.saveTaskConfig(taskKey, config)
    }

    private fun buildTaskConfig(task: TaskLauncherService.TaskInfo): TaskLauncherService.TaskConfig {
        val params = mutableMapOf<String, Any>()
        for ((key, component) in paramFields) {
            when (component) {
                is JCheckBox -> params[key] = component.isSelected
                is JSpinner -> params[key] = component.value
                is JTextField -> if (component.text.isNotBlank()) params[key] = component.text
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
        runButton.isEnabled = false
        stopButton.isEnabled = true
        pauseButton.isEnabled = true
        paused = false
        stdoutRemainder = ""
        stopping.set(false)

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
                        scanControlMarkers(line)
                    }
                } catch (_: Exception) { }
            }.start()

            Thread {
                try {
                    stderrReader.lineSequence().forEach { _ -> }
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

    fun onDispose() {
        stopCurrentTask()
    }
}
