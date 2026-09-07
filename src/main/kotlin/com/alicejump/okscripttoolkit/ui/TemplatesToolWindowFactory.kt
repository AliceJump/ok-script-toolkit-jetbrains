package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.alicejump.okscripttoolkit.core.FeatureTemplate
import com.alicejump.okscripttoolkit.core.OkDataChangeService
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
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.Graphics
import java.awt.datatransfer.StringSelection
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.imageio.ImageIO
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.ImageIcon
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

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
        private const val THUMB_HEIGHT = ThumbGridPolicy.THUMB_HEIGHT
        private const val CARD_WIDTH = ThumbGridPolicy.CELL_WIDTH
        private const val ANNOTATION_MARGIN = 200
    }

    private val data = project.service<OkProjectDataService>()
    private var templates = emptyList<FeatureTemplate>()
    private val gridPanel = JBPanel<JBPanel<*>>(GridLayout(0, 5, ThumbGridPolicy.HGAP_VALUE, ThumbGridPolicy.HGAP_VALUE))
    private val gridWrap = JPanel(BorderLayout()).apply { isOpaque = false }
    private val scrollPane = JBScrollPane(gridWrap)
    private val search = SearchTextField(false)
    private val count = JBLabel()
    private val emptyLabel = JBLabel(OkScriptToolkitBundle.message("gallery.empty"), SwingConstants.CENTER)
    private val thumbs = ConcurrentHashMap<String, Icon?>()
    private val requestedThumbs = ConcurrentHashMap.newKeySet<String>()
    private val thumbExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ok-script-template-thumb").apply { isDaemon = true }
    }
    private val renderGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    private var gridCols = 5

    /** 最近活动的 Python 编辑器（对齐 VSCode 版 lastPythonEditor：插入表达式优先落到最近编辑过的编辑器） */
    private var lastPythonEditor: com.intellij.openapi.editor.Editor? = null
    // 缩略图异步加载完成后回填到已渲染的卡片图标
    private val pendingThumbLabels = ConcurrentHashMap<String, MutableList<JLabel>>()
    val component: JComponent

    init {
        gridPanel.border = JBUI.Borders.empty(8)
        gridPanel.isOpaque = false
        gridWrap.add(gridPanel, BorderLayout.NORTH)
        emptyLabel.isVisible = false
        // 列数按视口宽度自适应，横向不允许滚动（避免卡线溢出产生水平条）
        scrollPane.horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER

        search.textEditor.emptyText.text = OkScriptToolkitBundle.message("gallery.search")
        search.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = applyFilter()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = applyFilter()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = applyFilter()
        })

        val header = JBPanel<JBPanel<*>>(BorderLayout(8, 0)).apply {
            border = JBUI.Borders.empty(6, 8)
            add(search, BorderLayout.CENTER)
            add(count, BorderLayout.EAST)
        }

        component = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
        }

        // 视口宽度变化时重算列数，保证网格铺满且不出横向滚动条
        scrollPane.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) {
                applyGridLayout()
            }
        })

        reload(false)

        // 数据文件变化自动刷新（对齐 VSCode 版 watcher 派发）
        project.messageBus.connect(this).subscribe(
            OkDataChangeService.TOPIC,
            com.alicejump.okscripttoolkit.core.OkDataChangeListener { reload(true) },
        )

        // 跟踪最近活动的 Python 编辑器（对齐 VSCode ensureEditorTracker）
        lastPythonEditor = FileEditorManager.getInstance(project).selectedTextEditor
            ?.takeIf { it.virtualFile?.extension?.lowercase() == "py" }
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
                    if (!editor.isDisposed && editor.virtualFile?.extension?.lowercase() == "py") {
                        lastPythonEditor = editor
                    }
                }
            },
        )
    }

    private fun reload(force: Boolean) {
        // 数据刷新含全量目录扫描与文件 IO，移出 EDT
        CompletableFuture.runAsync {
            data.refresh(force)
            val features = data.features()
            SwingUtilities.invokeLater {
                templates = features
                renderGrid()
            }
        }
    }

    private fun applyFilter() = renderGrid()

    private fun applyGridLayout() {
        val viewportWidth = scrollPane.width.takeIf { it > 0 } ?: scrollPane.viewport.width
        if (viewportWidth <= 0) return
        val cols = ThumbGridPolicy.columnsFor(viewportWidth)
        if (cols != gridCols) {
            gridCols = cols
            gridPanel.layout = GridLayout(0, cols, ThumbGridPolicy.HGAP_VALUE, ThumbGridPolicy.HGAP_VALUE)
            gridPanel.revalidate()
            gridPanel.repaint()
        }
    }

    private fun renderGrid() {
        val generation = renderGeneration.incrementAndGet()
        val query = search.text.trim().lowercase()
        val filtered = templates
            .filter { query.isEmpty() || it.name.lowercase().contains(query) }
        count.text = OkScriptToolkitBundle.message("gallery.count", filtered.size)

        applyGridLayout()
        pendingThumbLabels.clear()
        gridPanel.removeAll()
        gridPanel.layout = GridLayout(0, gridCols, ThumbGridPolicy.HGAP_VALUE, ThumbGridPolicy.HGAP_VALUE)
        for (img in filtered) {
            gridPanel.add(createCard(img, generation))
        }
        emptyLabel.isVisible = filtered.isEmpty()
        gridPanel.add(emptyLabel)
        gridPanel.revalidate()
        gridPanel.repaint()
    }

    private fun createCard(template: FeatureTemplate, generation: Int): JComponent {
        val card = JBPanel<JBPanel<*>>(BorderLayout())
        card.isOpaque = false

        val icon = thumbs[template.name]
        val imageArea = JBLabel(icon, SwingConstants.CENTER)
        imageArea.isOpaque = false
        imageArea.verticalAlignment = SwingConstants.CENTER
        imageArea.preferredSize = Dimension(CARD_WIDTH - 16, THUMB_HEIGHT + 4)
        if (icon == null && !requestedThumbs.contains(template.name)) {
            requestThumb(template, generation)
        }
        pendingThumbLabels.getOrPut(template.name) { java.util.Collections.synchronizedList(mutableListOf()) }.add(imageArea)

        val sizeText = "${template.width}×${template.height}"
        val nameLabel = JBLabel(
            "<html><div style=\"text-align:center;width:${CARD_WIDTH - 20}px;\">" +
                escapeHtml(template.name) +
                "<span style=\"color:#8a8a8a\">&nbsp;$sizeText</span></div></html>",
            SwingConstants.CENTER,
        )
        nameLabel.verticalAlignment = SwingConstants.TOP
        nameLabel.isOpaque = false

        card.add(imageArea, BorderLayout.CENTER)
        card.add(nameLabel, BorderLayout.SOUTH)
        card.toolTipText = "${expression(template)}  ($sizeText)"
        card.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        card.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                when {
                    e.clickCount == 2 -> copyExpression(template)
                    SwingUtilities.isRightMouseButton(e) -> showCardMenu(e, template)
                    e.clickCount == 1 -> insertExpression(template)
                }
            }

            override fun mousePressed(e: MouseEvent) {
                if (SwingUtilities.isRightMouseButton(e)) showCardMenu(e, template)
            }
        })
        return card
    }

    private fun showCardMenu(e: MouseEvent, template: FeatureTemplate) {
        val popup = JPopupMenu()
        val insertItem = JMenuItem(OkScriptToolkitBundle.message("gallery.insert"))
        insertItem.addActionListener { insertExpression(template) }
        popup.add(insertItem)
        val copyItem = JMenuItem(OkScriptToolkitBundle.message("gallery.copy"))
        copyItem.addActionListener { copyExpression(template) }
        popup.add(copyItem)
        popup.addSeparator()
        val openItem = JMenuItem(OkScriptToolkitBundle.message("gallery.open"))
        openItem.addActionListener { openAnnotatedSource(template) }
        popup.add(openItem)
        popup.show(e.component, e.x, e.y)
    }

    /** 异步生成网格缩略图（bbox 裁剪），完成后回填到已渲染的卡片，避免旧数据回流。 */
    private fun requestThumb(template: FeatureTemplate, generation: Int) {
        requestedThumbs.add(template.name)
        thumbExecutor.submit {
            val icon = loadThumb(template)
            thumbs[template.name] = icon
            SwingUtilities.invokeLater {
                if (renderGeneration.get() != generation) return@invokeLater
                pendingThumbLabels.remove(template.name)?.forEach { label ->
                    label.icon = icon
                    label.repaint()
                }
            }
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
            // 等比适配预览框（高 72、宽不超格子内区），绝不拉伸
            val scale = minOf(THUMB_HEIGHT.toDouble() / h, (CARD_WIDTH - 16).toDouble() / w)
            val targetW = (w * scale).toInt().coerceAtLeast(1)
            val targetH = (h * scale).toInt().coerceAtLeast(1)
            val thumb = BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB)
            val g = thumb.createGraphics()
            g.drawImage(crop, 0, 0, targetW, targetH, null)
            g.dispose()
            ImageIcon(thumb)
        } catch (e: Exception) {
            LOG.warn("Failed to render thumbnail for ${template.name}", e)
            null
        }
    }

    private fun escapeHtml(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun expression(template: FeatureTemplate): String {
        val alias = OkScriptToolkitSettings.getInstance(project).featureAliases().firstOrNull() ?: "fL"
        return "$alias.${template.name}"
    }

    private fun insertExpression(template: FeatureTemplate) {
        val text = expression(template)
        // 优先使用最近活动的 Python 编辑器，其次当前选中编辑器（对齐 VSCode insertIntoPythonEditor）
        val editor = lastPythonEditor
            ?.takeIf { !it.isDisposed && it.virtualFile?.extension?.lowercase() == "py" }
            ?: FileEditorManager.getInstance(project).selectedTextEditor
                ?.takeIf { it.virtualFile?.extension?.lowercase() == "py" }
        if (editor == null) {
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
     * 对齐 VSCode 版 openAnnotatedImage：优先从 ok_templates 素材库 COCO
     * 反查原图与原始 bbox（30s TTL），查不到回退画廊数据；
     * bbox 外扩 200px 裁剪，画红框 + 白色外圈标注后在 IDE 内打开。
     */
    private fun openAnnotatedSource(template: FeatureTemplate) {
        CompletableFuture.supplyAsync {
            val (imagePath, bbox) = data.findOkTemplateCocoEntry(template.name)
                ?: (template.imagePath to template.bbox)
            renderAnnotatedImage(template.name, imagePath, bbox.toList())
        }.thenAccept { path ->
            if (path == null) {
                openRawSource(template)
                return@thenAccept
            }
            SwingUtilities.invokeLater {
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

    private fun renderAnnotatedImage(
        templateName: String,
        imagePath: java.nio.file.Path,
        bbox: List<Int>,
    ): java.nio.file.Path? {
        return try {
            val file = imagePath.toFile()
            if (!file.exists()) return null
            val original = ImageIO.read(file) ?: return null
            val (bx, by, bw, bh) = bbox
            val margin = ANNOTATION_MARGIN
            val x0 = (bx - margin).coerceAtLeast(0)
            val y0 = (by - margin).coerceAtLeast(0)
            val x1 = (bx + bw + margin).coerceAtMost(original.width)
            val y1 = (by + bh + margin).coerceAtMost(original.height)
            if (x1 - x0 <= 0 || y1 - y0 <= 0) return null
            val cropW = x1 - x0
            val cropH = y1 - y0
            val crop = original.getSubimage(x0, y0, cropW, cropH)

            // 归一化：缩放到目标分辨率内，保证不同原图输出视觉效果一致
            val TARGET = 400
            val scale = minOf(1.0, TARGET.toDouble() / maxOf(cropW, cropH))
            val outW = (cropW * scale).toInt()
            val outH = (cropH * scale).toInt()
            val scaled = java.awt.image.BufferedImage(outW, outH, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            val g = scaled.createGraphics()
            g.drawImage(crop, 0, 0, outW, outH, null)

            // 在缩放后的图上画标注框
            val stroke = maxOf(2, (2 * scale).toInt())
            // 白色外圈 halo + 红色边框
            g.stroke = java.awt.BasicStroke((stroke * 2).toFloat())
            g.color = java.awt.Color.WHITE
            g.drawRect(
                ((bx - x0) * scale).toInt() - stroke,
                ((by - y0) * scale).toInt() - stroke,
                (bw * scale).toInt() + stroke * 2,
                (bh * scale).toInt() + stroke * 2,
            )
            g.stroke = java.awt.BasicStroke(stroke.toFloat())
            g.color = java.awt.Color(255, 40, 40)
            g.drawRect(
                ((bx - x0) * scale).toInt(),
                ((by - y0) * scale).toInt(),
                (bw * scale).toInt(),
                (bh * scale).toInt(),
            )
            g.dispose()

            val outDir = Files.createTempDirectory("ok-script-toolkit")
            val out = outDir.resolve("annotated_${templateName}.png")
            ImageIO.write(scaled, "png", out.toFile())
            out
        } catch (e: Exception) {
            LOG.warn("Failed to render annotated image for $templateName", e)
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
