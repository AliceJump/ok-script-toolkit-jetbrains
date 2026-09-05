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
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.BasicStroke
import java.awt.Color
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.imageio.ImageIO
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.ImageIcon
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
    companion object {
        private val LOG = Logger.getInstance(TemplateGalleryPanel::class.java)
        private const val THUMB_HEIGHT = 48
        private const val ANNOTATION_MARGIN = 200
    }

    private val data = project.service<OkProjectDataService>()
    private var templates = emptyList<FeatureTemplate>()
    private val visibleModel = DefaultListModel<FeatureTemplate>()
    private val list = JBList<FeatureTemplate>(visibleModel)
    private val search = SearchTextField(false)
    private val count = JBLabel()
    private val thumbs = ConcurrentHashMap<String, Icon?>()
    private val requestedThumbs = ConcurrentHashMap.newKeySet<String>()
    private val thumbExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ok-script-template-thumb").apply { isDaemon = true }
    }
    val component: JComponent

    init {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.emptyText.text = OkScriptToolkitBundle.message("gallery.empty")
        list.cellRenderer = object : com.intellij.ui.ColoredListCellRenderer<FeatureTemplate>() {
            override fun customizeCellRenderer(
                list: javax.swing.JList<out FeatureTemplate>,
                value: FeatureTemplate,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                if (!requestedThumbs.contains(value.name)) requestThumb(value)
                icon = thumbs[value.name]
                append(value.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                append("  ${value.width}×${value.height}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
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
                    list.selectedValue?.let(::openAnnotatedSource)
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
        // 数据刷新含全量目录扫描与文件 IO，移出 EDT
        CompletableFuture.runAsync {
            data.refresh(force)
            val features = data.features()
            javax.swing.SwingUtilities.invokeLater {
                templates = features
                applyFilter()
            }
        }
    }

    private fun applyFilter() {
        val query = search.text.trim().lowercase()
        visibleModel.clear()
        templates
            .filter { query.isEmpty() || it.name.lowercase().contains(query) }
            .forEach(visibleModel::addElement)
        count.text = OkScriptToolkitBundle.message("gallery.count", visibleModel.size)
    }

    /** 异步生成列表缩略图（bbox 裁剪），完成后重绘列表。 */
    private fun requestThumb(template: FeatureTemplate) {
        requestedThumbs.add(template.name)
        thumbExecutor.submit {
            val icon = loadThumb(template)
            thumbs[template.name] = icon
            javax.swing.SwingUtilities.invokeLater { list.repaint() }
        }
    }

    private fun loadThumb(template: FeatureTemplate): Icon? {
        return try {
            val file = template.imagePath.toFile()
            if (!file.exists()) return null
            val original = ImageIO.read(file) ?: return null
            val x = template.bbox[0].coerceIn(0, original.width - 1)
            val y = template.bbox[1].coerceIn(0, original.height - 1)
            val w = template.bbox[2].coerceAtMost(original.width - x)
            val h = template.bbox[3].coerceAtMost(original.height - y)
            if (w <= 0 || h <= 0) return null
            val crop = original.getSubimage(x, y, w, h)
            val targetW = (w * THUMB_HEIGHT.toDouble() / h).toInt().coerceIn(1, 160)
            val thumb = BufferedImage(targetW, THUMB_HEIGHT, BufferedImage.TYPE_INT_ARGB)
            val g = thumb.createGraphics()
            g.drawImage(crop, 0, 0, targetW, THUMB_HEIGHT, null)
            g.dispose()
            ImageIcon(thumb)
        } catch (e: Exception) {
            LOG.warn("Failed to render thumbnail for ${template.name}", e)
            null
        }
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

    /**
     * 对齐 VSCode 版 openAnnotatedImage：打开原图 bbox 外扩 200px 的裁剪，
     * 画红框 + 白色外圈标注后作为临时图片在 IDE 内打开。
     */
    private fun openAnnotatedSource(template: FeatureTemplate) {
        CompletableFuture.supplyAsync { renderAnnotatedImage(template) }.thenAccept { path ->
            if (path == null) {
                // 渲染失败回退为直接打开源图
                openRawSource(template)
                return@thenAccept
            }
            javax.swing.SwingUtilities.invokeLater {
                val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
                if (file != null) {
                    OpenFileDescriptor(project, file).navigate(true)
                } else {
                    openRawSource(template)
                }
            }
        }
    }

    private fun openRawSource(template: FeatureTemplate) {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(template.imagePath) ?: return
        OpenFileDescriptor(project, file).navigate(true)
    }

    private fun renderAnnotatedImage(template: FeatureTemplate): java.nio.file.Path? {
        return try {
            val file = template.imagePath.toFile()
            if (!file.exists()) return null
            val original = ImageIO.read(file) ?: return null
            val (bx, by, bw, bh) = template.bbox.toList()
            val margin = ANNOTATION_MARGIN
            val x0 = (bx - margin).coerceAtLeast(0)
            val y0 = (by - margin).coerceAtLeast(0)
            val x1 = (bx + bw + margin).coerceAtMost(original.width)
            val y1 = (by + bh + margin).coerceAtMost(original.height)
            if (x1 - x0 <= 0 || y1 - y0 <= 0) return null
            val crop = original.getSubimage(x0, y0, x1 - x0, y1 - y0)

            val stroke = maxOf(2, minOf(bw, bh) / 100).coerceAtMost(12)
            val g = crop.createGraphics()
            // 白色外圈 halo + 红色边框，与 VSCode 版一致
            g.stroke = BasicStroke((stroke * 2).toFloat())
            g.color = Color.WHITE
            g.drawRect(bx - x0 - stroke, by - y0 - stroke, bw + stroke * 2, bh + stroke * 2)
            g.stroke = BasicStroke(stroke.toFloat())
            g.color = Color(255, 40, 40)
            g.drawRect(bx - x0, by - y0, bw, bh)
            g.dispose()

            val outDir = Files.createTempDirectory("ok-script-toolkit")
            val out = outDir.resolve("annotated_${template.name}.png")
            ImageIO.write(crop, "png", out.toFile())
            out
        } catch (e: Exception) {
            LOG.warn("Failed to render annotated image for ${template.name}", e)
            null
        }
    }

    private fun notify(content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("okScriptToolkit")
            .createNotification(content, type)
            .notify(project)
    }

    override fun dispose() {
        thumbExecutor.shutdownNow()
    }
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
