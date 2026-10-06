package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.GettingStartedBundle
import com.alicejump.okscripttoolkit.core.ScreenshotCapture
import com.alicejump.okscripttoolkit.settings.OkScriptToolkitConfigurable
import com.intellij.ide.BrowserUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import java.beans.PropertyChangeListener
import java.nio.file.Files
import java.nio.file.Paths
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane

private const val GETTING_STARTED_VERSION = "1"
private const val GETTING_STARTED_STATE_KEY = "okScriptToolkit.onboardingVersion"
private const val TASK_TOOL_WINDOW_ID = "ok-script Tasks"
private const val REPOSITORY_URL = "https://github.com/AliceJump/ok-script-toolkit"
private const val ISSUES_URL = "$REPOSITORY_URL/issues"
private const val RELEASES_URL = "$REPOSITORY_URL/releases"

private fun gs(key: String, vararg params: Any): String = GettingStartedBundle.message(key, *params)

internal object GettingStartedFileType : FileType {
    private val icon = IconLoader.getIcon("/icons/task.svg", GettingStartedFileType::class.java)

    override fun getName(): String = "ok-script Getting Started"
    override fun getDisplayName(): String = gs("page.title")
    override fun getDescription(): String = gs("page.subtitle")
    override fun getDefaultExtension(): String = "okgettingstarted"
    override fun getIcon() = icon
    override fun isBinary(): Boolean = true
    override fun isReadOnly(): Boolean = true
}

class GettingStartedFile : LightVirtualFile(gs("page.title"), GettingStartedFileType, "") {
    init { isWritable = false }
}

class GettingStartedEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean = file is GettingStartedFile

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        GettingStartedEditor(project, file as GettingStartedFile)

    override fun getEditorTypeId(): String = "ok-script-getting-started"
    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

class GettingStartedEditor(project: Project, private val file: GettingStartedFile) : FileEditor {
    private val panel = GettingStartedPanel(project)

    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusedComponent(): JComponent = panel
    override fun getName(): String = gs("page.title")
    override fun setState(state: FileEditorState) = Unit
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = true
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun <T : Any> getUserData(key: com.intellij.openapi.util.Key<T>): T? = null
    override fun <T : Any> putUserData(key: com.intellij.openapi.util.Key<T>, value: T?) = Unit
    override fun getFile(): VirtualFile = file
    override fun dispose() = Unit
}

fun openGettingStarted(project: Project) {
    val manager = FileEditorManager.getInstance(project)
    val existing = manager.openFiles.filterIsInstance<GettingStartedFile>().firstOrNull()
    manager.openFile(existing ?: GettingStartedFile(), true)
}

class ShowGettingStartedAction : AnAction(), DumbAware {
    init {
        templatePresentation.text = gs("action.open.text")
        templatePresentation.description = gs("action.open.description")
    }

    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let(::openGettingStarted)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

class GettingStartedProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val state = PropertiesComponent.getInstance()
        if (state.getValue(GETTING_STARTED_STATE_KEY) == GETTING_STARTED_VERSION) return
        state.setValue(GETTING_STARTED_STATE_KEY, GETTING_STARTED_VERSION)
        UIUtil.invokeLaterIfNeeded {
            if (!project.isDisposed) openGettingStarted(project)
        }
    }
}

private class GettingStartedPanel(private val project: Project) : JPanel(BorderLayout()) {
    init {
        border = JBUI.Borders.empty(24)
        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        val title = JBLabel(gs("page.title")).apply {
            font = font.deriveFont(Font.BOLD, font.size2D + 10f)
            alignmentX = LEFT_ALIGNMENT
        }
        val subtitle = bodyText(gs("page.subtitle")).apply { alignmentX = LEFT_ALIGNMENT }
        content.add(title)
        content.add(Box.createVerticalStrut(6))
        content.add(subtitle)
        content.add(Box.createVerticalStrut(18))
        content.add(projectStatusPanel())
        content.add(Box.createVerticalStrut(22))

        content.add(sectionTitle(gs("goals.title")))
        content.add(Box.createVerticalStrut(10))
        content.add(goalGrid())
        content.add(Box.createVerticalStrut(22))

        content.add(sectionTitle(gs("workflow.title")))
        content.add(Box.createVerticalStrut(8))
        content.add(bodyText(gs("workflow.visual")))
        content.add(Box.createVerticalStrut(8))
        content.add(bodyText(gs("workflow.full")))
        content.add(Box.createVerticalStrut(22))
        content.add(footerLinks())

        add(JScrollPane(content).apply {
            border = null
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            viewport.isOpaque = false
            isOpaque = false
        }, BorderLayout.CENTER)
    }

    private fun projectStatusPanel(): JComponent {
        val projectDir = ScreenshotCapture.detectProjectDir(project)
        val detected = projectDir.isNotBlank()
        val conventionFile = if (detected) Paths.get(projectDir, "ok-script-toolkit.json") else null
        val conventionFound = conventionFile?.let(Files::exists) == true

        val panel = JPanel(BorderLayout(12, 8)).apply {
            alignmentX = LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 110)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border()),
                JBUI.Borders.empty(12),
            )
            isOpaque = false
        }
        val text = when {
            detected && conventionFound -> gs("project.detectedWithConfig", projectDir)
            detected -> gs("project.detected", projectDir)
            else -> gs("project.missing")
        }
        panel.add(bodyText(text), BorderLayout.CENTER)
        panel.add(JButton(gs("project.settings")).apply {
            addActionListener {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, OkScriptToolkitConfigurable::class.java)
            }
        }, BorderLayout.EAST)
        return panel
    }

    private fun goalGrid(): JComponent = JPanel(GridLayout(2, 2, 12, 12)).apply {
        alignmentX = LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, 300)
        isOpaque = false
        add(goalCard("task", TASK_TOOL_WINDOW_ID))
        add(goalCard("visual", UNIFIED_ANNOTATION_TOOL_WINDOW_ID))
        add(goalCard("publish", UNIFIED_ANNOTATION_TOOL_WINDOW_ID))
        add(goalCard("run", TASK_TOOL_WINDOW_ID))
    }

    private fun goalCard(key: String, toolWindowId: String): JComponent = JPanel(BorderLayout(8, 8)).apply {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor.border()),
            JBUI.Borders.empty(12),
        )
        isOpaque = false
        val heading = JBLabel(gs("goal.$key.title")).apply {
            font = font.deriveFont(Font.BOLD, font.size2D + 1f)
        }
        add(heading, BorderLayout.NORTH)
        add(bodyText(gs("goal.$key.body")), BorderLayout.CENTER)
        add(JButton(gs("goal.$key.button")).apply {
            addActionListener { ToolWindowManager.getInstance(project).getToolWindow(toolWindowId)?.show() }
        }, BorderLayout.SOUTH)
    }

    private fun footerLinks(): JComponent = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
        alignmentX = LEFT_ALIGNMENT
        isOpaque = false
        add(linkButton("footer.docs", "$REPOSITORY_URL#readme"))
        add(linkButton("footer.github", REPOSITORY_URL))
        add(linkButton("footer.issues", ISSUES_URL))
        add(linkButton("footer.releases", RELEASES_URL))
    }

    private fun linkButton(key: String, url: String) = JButton(gs(key)).apply {
        addActionListener { BrowserUtil.browse(url) }
    }

    private fun sectionTitle(text: String) = JBLabel(text).apply {
        font = font.deriveFont(Font.BOLD, font.size2D + 3f)
        alignmentX = LEFT_ALIGNMENT
    }

    private fun bodyText(text: String) = JBTextArea(text).apply {
        lineWrap = true
        wrapStyleWord = true
        isEditable = false
        isFocusable = false
        isOpaque = false
        border = null
        background = UIUtil.getPanelBackground()
    }
}
