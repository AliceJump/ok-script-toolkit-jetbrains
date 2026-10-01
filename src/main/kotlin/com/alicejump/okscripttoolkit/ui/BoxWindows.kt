package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.alicejump.okscripttoolkit.core.BoxCatalogService
import com.alicejump.okscripttoolkit.core.AnnotatedSourcePreview
import com.alicejump.okscripttoolkit.core.OkDataChangeService
import com.alicejump.okscripttoolkit.core.OkDataChangeListener
import com.alicejump.okscripttoolkit.core.OkProjectDataService
import com.alicejump.okscripttoolkit.core.TemplateThumbPipeline
import com.alicejump.okscripttoolkit.settings.OkScriptToolkitSettings
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JPanel
import javax.swing.JButton
import javax.swing.SwingUtilities
import java.util.concurrent.CompletableFuture

/** 框资源管理：同一批模板原图，编辑 `<模板目录>/boxes.json`。 */
class BoxAssetToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = TemplateAssetPanel(project, editingBoxes = true)
        val content = com.intellij.ui.content.ContentFactory.getInstance().createContent(panel.mainPanel, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

/** 框管理：浏览运行时框，单击插入 `self.pos.<path>.to_box()`。 */
class BoxGalleryToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = BoxGalleryPanel(project)
        val content = com.intellij.ui.content.ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

/**
 * 每个 box path 展示 authoring 原图中 bbox 外扩后的上下文和红框。
 * 与模板查看原图共用标注图缓存，并通过 [TemplateThumbPipeline] 后台加载。
 */
private class BoxGalleryPanel(private val project: Project) : JPanel(BorderLayout()), com.intellij.openapi.Disposable {
    private data class Item(val path: String, val imagePath: java.nio.file.Path?, val bbox: IntArray?)
    private val grid = JPanel(java.awt.GridLayout(0, 1, ThumbGridPolicy.HGAP_VALUE, ThumbGridPolicy.HGAP_VALUE))
    private val scroll = JBScrollPane(grid)
    private val thumbs = ConcurrentHashMap<String, JBLabel>()
    private var generation = 0
    private var disposed = false

    init {
        grid.border = JBUI.Borders.empty(8)
        add(scroll, BorderLayout.CENTER)
        add(JBLabel(OkScriptToolkitBundle.message("boxGallery.hint")), BorderLayout.SOUTH)
        scroll.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) {
                grid.layout = java.awt.GridLayout(0, ThumbGridPolicy.columnsFor(scroll.width),
                    ThumbGridPolicy.HGAP_VALUE, ThumbGridPolicy.HGAP_VALUE)
                grid.revalidate()
            }
        })
        project.messageBus.connect(this).subscribe(OkDataChangeService.TOPIC, OkDataChangeListener { reload() })
        reload()
    }

    private fun reload() {
        if (disposed || project.isDisposed) return
        val gen = ++generation
        thumbs.clear()
        val catalog = project.service<BoxCatalogService>()
        val runtime = catalog.readRuntime().boxes
        val authoring = catalog.readAuthoring().boxes.associateBy { it.path }
        val settings = OkScriptToolkitSettings.getInstance(project)
        val templates = project.service<OkProjectDataService>().rootPath()?.resolve(settings.okTemplatesDirectory())
        val items = runtime.map { box ->
            val source = authoring[box.path]
            Item(box.path, source?.let { templates?.resolve(it.image) }, source?.bbox)
        }
        grid.removeAll()
        if (items.isEmpty()) grid.add(JBLabel(OkScriptToolkitBundle.message("boxGallery.empty")))
        items.forEach { grid.add(createCard(it)) }
        grid.revalidate()
        grid.repaint()
        val requests = items.filter { it.imagePath != null && it.bbox != null }
            .map { TemplateThumbPipeline.Request(it.path, it.imagePath!!, it.bbox!!) }
        TemplateThumbPipeline.loadThumbs(project, requests, ThumbGridPolicy.THUMB_HEIGHT, annotatedSource = true) { path, icon ->
            if (disposed || gen != generation) return@loadThumbs
            thumbs[path]?.apply { this.icon = icon; repaint() }
        }
    }

    private fun createCard(item: Item): JPanel {
        val image = JBLabel().apply {
            horizontalAlignment = javax.swing.SwingConstants.CENTER
            preferredSize = java.awt.Dimension(ThumbGridPolicy.CELL_WIDTH, ThumbGridPolicy.THUMB_HEIGHT)
        }
        thumbs[item.path] = image
        val preview = ThumbnailActions(image,
            JButton(OkScriptToolkitBundle.message("gallery.insert"), AllIcons.Actions.AddFile).apply {
                addActionListener { insert("self.pos." + item.path + ".to_box()") }
            },
            JButton(OkScriptToolkitBundle.message("gallery.copy"), AllIcons.Actions.Copy).apply {
                addActionListener { CopyPasteManager.getInstance().setContents(StringSelection("self.pos." + item.path)) }
            },
            JButton(OkScriptToolkitBundle.message("gallery.open"), AllIcons.Actions.Preview).apply {
                isEnabled = item.imagePath != null && item.bbox != null
                addActionListener { openSource(item) }
            },
        )
        val displayName = if (item.path.length > 22) item.path.take(12) + "…" + item.path.takeLast(9) else item.path
        val name = JBLabel(displayName, javax.swing.SwingConstants.CENTER)
        val card = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4)
            add(preview, BorderLayout.CENTER)
            add(name, BorderLayout.SOUTH)
        }
        val mouse = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                if (e.clickCount >= 2) CopyPasteManager.getInstance().setContents(StringSelection("self.pos." + item.path))
                else insert("self.pos." + item.path + ".to_box()")
            }
        }
        listOf(card, preview, image, name).forEach {
            it.toolTipText = "self.pos." + item.path + ".to_box()"
            it.addMouseListener(mouse)
        }
        return card
    }

    private fun openSource(item: Item) {
        val imagePath = item.imagePath ?: return
        val bbox = item.bbox ?: return
        CompletableFuture.supplyAsync { AnnotatedSourcePreview.fileFor(project, imagePath, bbox) }
            .whenComplete { path, _ ->
                SwingUtilities.invokeLater {
                    if (disposed || project.isDisposed) return@invokeLater
                    val file = path?.let { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it) }
                    if (file != null) OpenFileDescriptor(project, file).navigate(true)
                }
            }
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

    override fun dispose() { disposed = true; generation++ }
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
