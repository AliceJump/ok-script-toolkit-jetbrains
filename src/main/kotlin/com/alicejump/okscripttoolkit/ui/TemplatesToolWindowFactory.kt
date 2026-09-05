package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.alicejump.okscripttoolkit.core.FeatureTemplate
import com.alicejump.okscripttoolkit.core.OkProjectDataService
import com.alicejump.okscripttoolkit.settings.OkScriptToolkitSettings
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel

class TemplatesToolWindowFactory : ToolWindowFactory, DumbAware {
    override suspend fun isApplicableAsync(project: Project): Boolean =
        OkScriptToolkitSettings.getInstance(project).state.enableTemplateGallery

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = TemplateGalleryPanel(project)
        val content = ContentFactory.getInstance().createContent(panel.component, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

private class TemplateGalleryPanel(private val project: Project) : com.intellij.openapi.Disposable {
    private val data = project.service<OkProjectDataService>()
    private var templates = emptyList<FeatureTemplate>()
    private val visibleModel = DefaultListModel<FeatureTemplate>()
    private val list = JBList<FeatureTemplate>(visibleModel)
    private val search = SearchTextField(false)
    private val count = JBLabel()
    val component: JComponent

    init {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.emptyText.text = OkScriptToolkitBundle.message("gallery.empty")
        list.cellRenderer = SimpleListCellRenderer.create("No template") { value ->
            "${value.name}    ${value.width}×${value.height}"
        }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2 && event.button == MouseEvent.BUTTON1) {
                    list.selectedValue?.let(::insertExpression)
                }
            }
        })
        search.textEditor.emptyText.text = OkScriptToolkitBundle.message("gallery.search")
        search.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = applyFilter()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = applyFilter()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = applyFilter()
        })

        val toolbar = ToolbarDecorator.createDecorator(list)
            .setAddAction(null)
            .setRemoveAction(null)
            .disableUpDownActions()
            .addExtraAction(object : AnAction(OkScriptToolkitBundle.message("gallery.insert")) {
                override fun actionPerformed(e: AnActionEvent) {
                    list.selectedValue?.let(::insertExpression)
                }
            })
            .addExtraAction(object : AnAction(OkScriptToolkitBundle.message("gallery.copy")) {
                override fun actionPerformed(e: AnActionEvent) {
                    list.selectedValue?.let(::copyExpression)
                }
            })
            .addExtraAction(object : AnAction(OkScriptToolkitBundle.message("gallery.open")) {
                override fun actionPerformed(e: AnActionEvent) {
                    list.selectedValue?.let(::openSource)
                }
            })
            .addExtraAction(object : AnAction(OkScriptToolkitBundle.message("gallery.refresh")) {
                override fun actionPerformed(e: AnActionEvent) = reload(true)
            })
            .createPanel()

        component = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(6)
            add(JPanel(BorderLayout(8, 0)).apply {
                add(search, BorderLayout.CENTER)
                add(count, BorderLayout.EAST)
            }, BorderLayout.NORTH)
            add(toolbar, BorderLayout.CENTER)
        }
        reload(false)
    }

    private fun reload(force: Boolean) {
        data.refresh(force)
        templates = data.features()
        applyFilter()
    }

    private fun applyFilter() {
        val query = search.text.trim().lowercase()
        visibleModel.clear()
        templates
            .filter { query.isEmpty() || it.name.lowercase().contains(query) }
            .forEach(visibleModel::addElement)
        count.text = OkScriptToolkitBundle.message("gallery.count", visibleModel.size)
    }

    private fun expression(template: FeatureTemplate): String {
        val alias = OkScriptToolkitSettings.getInstance(project).featureAliases().firstOrNull() ?: "fL"
        return "$alias.${template.name}"
    }

    private fun insertExpression(template: FeatureTemplate) {
        val text = expression(template)
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        if (editor == null || editor.virtualFile.extension?.lowercase() != "py") {
            CopyPasteManager.getInstance().setContents(StringSelection(text))
            notify(OkScriptToolkitBundle.message("gallery.noEditor"), NotificationType.WARNING)
            return
        }
        WriteCommandAction.runWriteCommandAction(project) {
            for (caret in editor.caretModel.allCarets.sortedByDescending { it.offset }) {
                editor.document.insertString(caret.offset, text)
                caret.moveToOffset(caret.offset + text.length)
            }
        }
    }

    private fun copyExpression(template: FeatureTemplate) {
        val text = expression(template)
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        notify(OkScriptToolkitBundle.message("gallery.copied", text), NotificationType.INFORMATION)
    }

    private fun openSource(template: FeatureTemplate) {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(template.imagePath) ?: return
        OpenFileDescriptor(project, file).navigate(true)
    }

    private fun notify(content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("okScriptToolkit")
            .createNotification(content, type)
            .notify(project)
    }

    override fun dispose() = Unit
}

class ShowTemplatesAction : AnAction(), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            .getToolWindow("ok-script Templates")
            ?.show()
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
