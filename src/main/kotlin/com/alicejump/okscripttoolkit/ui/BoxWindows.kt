package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.alicejump.okscripttoolkit.core.BoxCatalogService
import com.alicejump.okscripttoolkit.core.OkDataChangeService
import com.alicejump.okscripttoolkit.core.OkDataChangeListener
import com.alicejump.okscripttoolkit.core.OkProjectDataService
import com.alicejump.okscripttoolkit.settings.OkScriptToolkitSettings
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
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.ImageIcon
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

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
        toolWindow.contentManager.addContent(com.intellij.ui.content.ContentFactory.getInstance().createContent(panel, "", false))
    }
}

/**
 * 框管理对标模板管理：每个 box path 一张 **bbox 裁剪后的资源缩略图**
 * （标注管理 ↔ 框资源管理是原图缩略图；模板管理 ↔ 框管理是裁剪缩略图）。
 * 来源是 authoring 的 Pixel bbox + 原图；裁剪与缓存在这里实现，
 * 不复用整图缩略图管线 —— 那是"原图卡"的缓存键，没有 bbox 维度。
 */
private class BoxGalleryPanel(private val project: Project) : JPanel(BorderLayout()), com.intellij.openapi.Disposable {
    private data class Item(val path: String, val imagePath: Path?, val bbox: IntArray?)

    private val model = DefaultListModel<Item>()
    private var items: List<Item> = emptyList()
    private val list = JBList(model)
    private val thumbs = ConcurrentHashMap<String, ImageIcon?>()
    private val inflight = ConcurrentHashMap<String, Boolean>()

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
                if (item != null) label.icon = thumbs[thumbKey(item)]
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
        add(JBScrollPane(list), BorderLayout.CENTER)
        add(JBLabel(OkScriptToolkitBundle.message("boxGallery.hint")), BorderLayout.SOUTH)
        project.messageBus.connect(this).subscribe(OkDataChangeService.TOPIC, OkDataChangeListener { reload() })
        reload()
    }

    private fun reload() {
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
        items.forEach { requestThumb(it) }
        list.repaint()
    }

    private fun thumbKey(item: Item): String? {
        val file = item.imagePath?.toFile() ?: return null
        if (!file.isFile || item.bbox == null || item.bbox.size < 4) return null
        return "${file.absolutePath}|${file.lastModified()}|${file.length()}|${item.bbox.joinToString(",")}"
    }

    /** 裁剪缩略图只在后台线程解码，完成后回 UI 重绘；进行中的解码按 key 去重。 */
    private fun requestThumb(item: Item) {
        val key = thumbKey(item) ?: return
        if (thumbs.containsKey(key)) return
        if (inflight.putIfAbsent(key, true) == true) return
        CompletableFuture.supplyAsync {
            thumbs[key] = runCatching {
                val file = item.imagePath!!.toFile()
                val image = ImageIO.read(file) ?: return@runCatching null
                val b = item.bbox!!
                val x = b[0].coerceIn(0, image.width - 1)
                val y = b[1].coerceIn(0, image.height - 1)
                val w = b[2].coerceAtMost(image.width - x)
                val h = b[3].coerceAtMost(image.height - y)
                if (w <= 0 || h <= 0) return@runCatching null
                val crop = image.getSubimage(x, y, w, h)
                val targetH = 48
                val scale = targetH.toDouble() / crop.height
                val scaled = BufferedImage(
                    (crop.width * scale).toInt().coerceAtLeast(1),
                    targetH,
                    BufferedImage.TYPE_INT_RGB,
                )
                val g = scaled.createGraphics()
                g.drawImage(crop, 0, 0, scaled.width, scaled.height, null)
                g.dispose()
                ImageIcon(scaled)
            }.getOrNull()
            inflight.remove(key)
            SwingUtilities.invokeLater { list.repaint() }
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
