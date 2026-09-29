package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.alicejump.okscripttoolkit.core.BoxCatalogService
import com.alicejump.okscripttoolkit.core.OkDataChangeListener
import com.alicejump.okscripttoolkit.core.OkDataChangeService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/** 框资源管理：同一批模板原图，编辑 `<模板目录>/boxes.json`。 */
class BoxAssetToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = TemplateAssetPanel(project, editingBoxes = true)
        val content = ContentFactory.getInstance().createContent(panel.mainPanel, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

/** 框管理：浏览运行时框，单击插入 `self.pos.<path>.to_box()`。 */
class BoxGalleryToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = BoxGalleryPanel(project)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, "", false))
    }
}

private class BoxGalleryPanel(private val project: Project) : JPanel(BorderLayout()), com.intellij.openapi.Disposable {
    private val model = DefaultListModel<String>()
    private val list = JBList(model)
    private var paths = emptyList<String>()

    init {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val path = paths.getOrNull(list.selectedIndex) ?: return
                if (e.clickCount >= 2) {
                    CopyPasteManager.getInstance().setContents(StringSelection("self.pos.$path"))
                } else {
                    insert("self.pos.$path.to_box()")
                }
            }
        })
        add(JBScrollPane(list), BorderLayout.CENTER)
        add(JBLabel(OkScriptToolkitBundle.message("boxGallery.hint")), BorderLayout.SOUTH)
        project.messageBus.connect(this).subscribe(OkDataChangeService.TOPIC, OkDataChangeListener { reload() })
        reload()
    }

    private fun reload() {
        paths = project.service<BoxCatalogService>().readRuntime().boxes.map { it.path }
        model.clear()
        if (paths.isEmpty()) {
            model.addElement(OkScriptToolkitBundle.message("boxGallery.empty"))
            return
        }
        paths.forEach { model.addElement("self.pos.$it.to_box()") }
    }

    private fun insert(text: String) {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
            ?.takeIf { it.virtualFile?.extension?.equals("py", true) == true }
        if (editor == null) {
            CopyPasteManager.getInstance().setContents(StringSelection(text))
            return
        }
        WriteCommandAction.runWriteCommandAction(project) {
            val caret = editor.caretModel.primaryCaret
            editor.document.insertString(caret.offset, text)
            caret.moveToOffset(caret.offset + text.length)
        }
    }

    override fun dispose() = Unit
}

class ShowBoxAssetsAction : AnAction(), DumbAware {
    init {
        templatePresentation.text = OkScriptToolkitBundle.message("action.showBoxAssets.text")
        templatePresentation.description = OkScriptToolkitBundle.message("action.showBoxAssets.description")
    }

    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let { com.intellij.openapi.wm.ToolWindowManager.getInstance(it).getToolWindow("ok-script Box Assets")?.show() }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

class ShowBoxGalleryAction : AnAction(), DumbAware {
    init {
        templatePresentation.text = OkScriptToolkitBundle.message("action.showBoxGallery.text")
        templatePresentation.description = OkScriptToolkitBundle.message("action.showBoxGallery.description")
    }

    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let { com.intellij.openapi.wm.ToolWindowManager.getInstance(it).getToolWindow("ok-script Boxes")?.show() }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
