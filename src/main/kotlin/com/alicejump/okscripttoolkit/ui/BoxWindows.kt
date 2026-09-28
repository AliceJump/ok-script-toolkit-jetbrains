package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.alicejump.okscripttoolkit.core.BoxCatalogService
import com.alicejump.okscripttoolkit.core.OkDataChangeService
import com.alicejump.okscripttoolkit.core.TemplateAssetDataService
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
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/** 框资源管理：同一批模板原图，编辑 `<模板目录>/boxes.json`。 */
class BoxAssetToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = BoxAssetPanel(project)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, "", false))
    }
}

/** 框管理：浏览运行时框，单击插入 `self.pos.<path>.to_box()`。 */
class BoxGalleryToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = BoxGalleryPanel(project)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, "", false))
    }
}

private class BoxAssetPanel(private val project: Project) : JPanel(BorderLayout(0, 4)), com.intellij.openapi.Disposable {
    private val model = DefaultListModel<String>()
    private val list = JBList(model)
    private val images = mutableListOf<com.alicejump.okscripttoolkit.core.TemplateImage>()

    init {
        val refresh = JButton(OkScriptToolkitBundle.message("boxAssets.refresh"))
        val publish = JButton(OkScriptToolkitBundle.message("boxAssets.publish"))
        refresh.addActionListener { reload() }
        publish.addActionListener { publishBoxes() }
        val bar = JPanel()
        bar.add(refresh)
        bar.add(publish)
        add(bar, BorderLayout.NORTH)
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) openEditor()
            }
        })
        add(JBScrollPane(list), BorderLayout.CENTER)
        project.messageBus.connect(this).subscribe(OkDataChangeService.TOPIC) { reload() }
        reload()
    }

    private fun reload() {
        val data = project.service<TemplateAssetDataService>()
        data.load()
        val catalog = project.service<BoxCatalogService>()
        images.clear()
        images.addAll(data.listImages())
        model.clear()
        if (images.isEmpty()) {
            model.addElement(OkScriptToolkitBundle.message("boxAssets.empty"))
            return
        }
        images.forEach { image ->
            val count = catalog.boxesForImage(image.file.name).size
            model.addElement(OkScriptToolkitBundle.message("boxAssets.count", image.file.name, count))
        }
    }

    private fun openEditor() {
        val image = images.getOrNull(list.selectedIndex) ?: return
        val data = project.service<TemplateAssetDataService>()
        val dialog = AnnotationDialog(project, data, image, images, images.indexOf(image).coerceAtLeast(0), editingBoxes = true)
        dialog.show()
        reload()
    }

    private fun publishBoxes() {
        val catalog = project.service<BoxCatalogService>()
        val dropped = catalog.runtimeOnlyPaths()
        if (dropped.isNotEmpty()) {
            val answer = Messages.showYesNoDialog(
                project,
                OkScriptToolkitBundle.message("boxAssets.publishDrop", dropped.joinToString("\n")),
                OkScriptToolkitBundle.message("boxAssets.publish"),
                Messages.getYesButton(),
                Messages.getNoButton(),
                null,
            )
            if (answer != Messages.YES) return
        }
        if (catalog.publish()) {
            Messages.showInfoMessage(project, OkScriptToolkitBundle.message("boxGallery.published", catalog.readRuntime().boxes.size),
                OkScriptToolkitBundle.message("boxAssets.publish"))
        } else {
            Messages.showErrorDialog(project, OkScriptToolkitBundle.message("annotation.saveFailed"),
                OkScriptToolkitBundle.message("boxAssets.publish"))
        }
    }

    override fun dispose() = Unit
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
        project.messageBus.connect(this).subscribe(OkDataChangeService.TOPIC) { reload() }
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
