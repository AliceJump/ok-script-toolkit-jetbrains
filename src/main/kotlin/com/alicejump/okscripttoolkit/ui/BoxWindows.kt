package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.alicejump.okscripttoolkit.core.BoxCatalogService
import com.alicejump.okscripttoolkit.core.AnnotatedSourcePreview
import com.alicejump.okscripttoolkit.core.OkDataChangeService
import com.alicejump.okscripttoolkit.core.OkDataChangeListener
import com.alicejump.okscripttoolkit.core.OkProjectDataService
import com.alicejump.okscripttoolkit.core.TemplateThumbPipeline
import com.alicejump.okscripttoolkit.settings.OkScriptToolkitSettings
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
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.ConcurrentHashMap
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JButton
import javax.swing.SwingUtilities
import java.util.concurrent.CompletableFuture
import javax.swing.ListSelectionModel

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

    /** 缩略图目标高度。列表行比卡片矮，48px 足够辨认裁剪区域。 */
    private val thumbHeight = 48

    private val model = DefaultListModel<Item>()
    private var items: List<Item> = emptyList()
    private val list = JBList(model)
    private var generation = 0
    private var disposed = false
    private val openSource = JButton(OkScriptToolkitBundle.message("gallery.open"))

    /** UI 侧的"当前图标"表（按 path 索引），真正的缓存/解码在 [TemplateThumbPipeline]。 */
    private val icons = ConcurrentHashMap<String, Icon>()

    init {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>,
                value: Any?,
                index: Int,
                selected: Boolean,
                focused: Boolean,
            ): Component {
                val item = value as? Item
                val label = JBLabel()
                label.text = if (item == null || item.path.isEmpty()) {
                    OkScriptToolkitBundle.message("boxGallery.empty")
                } else {
                    "self.pos.${item.path}.to_box()"
                }
                label.iconTextGap = 8
                label.border = JBUI.Borders.empty(4, 8)
                label.isOpaque = true
                if (selected) {
                    label.background = list.selectionBackground
                    label.foreground = list.selectionForeground
                } else {
                    label.background = list.background
                    label.foreground = list.foreground
                }
                if (item != null) label.icon = icons[item.path]
                return label
            }
        }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val item = items.getOrNull(list.selectedIndex) ?: return
                if (item.path.isEmpty()) return
                if (e.clickCount >= 2) {
                    CopyPasteManager.getInstance().setContents(StringSelection("self.pos.${item.path}"))
                } else {
                    insert("self.pos.${item.path}.to_box()")
                }
            }
        })
        openSource.isEnabled = false
        openSource.addActionListener { openSelectedSource() }
        list.addListSelectionListener {
            val item = items.getOrNull(list.selectedIndex)
            openSource.isEnabled = item?.imagePath != null && item.bbox != null
        }
        add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT)).apply { add(openSource) }, BorderLayout.NORTH)
        add(JBScrollPane(list), BorderLayout.CENTER)
        add(JBLabel(OkScriptToolkitBundle.message("boxGallery.hint")), BorderLayout.SOUTH)
        project.messageBus.connect(this).subscribe(OkDataChangeService.TOPIC, OkDataChangeListener { reload() })
        reload()
    }

    private fun reload() {
        if (disposed || project.isDisposed) return
        val gen = ++generation
        icons.clear()
        val catalog = project.service<BoxCatalogService>()
        val runtime = catalog.readRuntime().boxes
        val authoring = catalog.readAuthoring().boxes.associateBy { it.path }
        val settings = OkScriptToolkitSettings.getInstance(project)
        val templates = project.service<OkProjectDataService>().rootPath()
            ?.resolve(settings.okTemplatesDirectory())
        items = runtime.map { box ->
            val source = authoring[box.path]
            Item(box.path, source?.let { templates?.resolve(it.image) }, source?.bbox)
        }
        model.clear()
        if (items.isEmpty()) model.addElement(Item("", null, null)) else items.forEach { model.addElement(it) }
        // 缺 authoring 来源的 path（运行时独有）拿不到 bbox，保持无图占位 —— 与 VS Code 画廊一致
        val requests = items.filter { it.imagePath != null && it.bbox != null }
            .map { TemplateThumbPipeline.Request(it.path, it.imagePath!!, it.bbox!!) }
        TemplateThumbPipeline.loadThumbs(project, requests, thumbHeight, annotatedSource = true) { path, icon ->
            if (disposed || gen != generation) return@loadThumbs
            if (icon != null) icons[path] = icon else icons.remove(path)
            list.repaint()
        }
    }

    private fun openSelectedSource() {
        val item = items.getOrNull(list.selectedIndex) ?: return
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
