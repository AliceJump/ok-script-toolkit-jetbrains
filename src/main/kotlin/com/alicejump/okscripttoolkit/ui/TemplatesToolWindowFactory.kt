package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.alicejump.okscripttoolkit.core.AnnotatedSourcePreview
import com.alicejump.okscripttoolkit.core.FeatureTemplate
import com.alicejump.okscripttoolkit.core.OkDataChangeService
import com.alicejump.okscripttoolkit.core.OkProjectDataService
import com.alicejump.okscripttoolkit.core.TemplateThumbPipeline
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
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
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

internal class TemplateGalleryPanel(private val project: Project) : com.intellij.openapi.Disposable {
    companion object {
        private val LOG = Logger.getInstance(TemplateGalleryPanel::class.java)
        private const val THUMB_HEIGHT = ThumbGridPolicy.THUMB_HEIGHT
        private const val CARD_WIDTH = ThumbGridPolicy.CELL_WIDTH
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
    private val renderGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    private val reloadGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    private var gridCols = 5
    @Volatile
    private var disposed = false

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
        val generation = reloadGeneration.incrementAndGet()
        // 数据刷新含全量目录扫描与文件 IO，移出 EDT
        CompletableFuture.runAsync {
            data.refresh(force)
            val features = data.features()
            SwingUtilities.invokeLater {
                if (disposed || generation != reloadGeneration.get()) return@invokeLater
                thumbs.clear()
                templates = features
                renderGrid()
            }
        }
    }

    fun refresh() = reload(true)

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
        // 先收齐"这一轮真正要加载的"，再按源图分组一次性提交 ——
        // 逐个提交会让同一张原图被反复解码（见 `requestThumbs` 的说明）。
        val missing = filtered.filter { thumbs[it.name] == null && !requestedThumbs.contains(it.name) }
        for (img in filtered) {
            gridPanel.add(createCard(img))
        }
        emptyLabel.isVisible = filtered.isEmpty()
        gridPanel.add(emptyLabel)
        gridPanel.revalidate()
        gridPanel.repaint()
        requestThumbs(missing, generation)
    }

    private fun createCard(template: FeatureTemplate): JComponent {
        val card = JBPanel<JBPanel<*>>(BorderLayout())
        card.isOpaque = false

        val icon = thumbs[template.name]
        val imageArea = JBLabel(icon, SwingConstants.CENTER)
        imageArea.isOpaque = false
        imageArea.verticalAlignment = SwingConstants.CENTER
        imageArea.preferredSize = Dimension(CARD_WIDTH - 16, THUMB_HEIGHT + 4)
        // 缩略图**不在这里请求** —— 由 `renderGrid` 收齐后按源图分组提交（见 `requestThumbs`）。
        // 在这里逐个请求会让同一张原图被反复解码（实测最高 17 个模板共用一张图）。
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
                if (e.isPopupTrigger || SwingUtilities.isRightMouseButton(e)) return
                when {
                    e.clickCount == 2 -> copyExpression(template)
                    e.clickCount == 1 -> insertExpression(template)
                }
            }

            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) showCardMenu(e, template)
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) showCardMenu(e, template)
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

    /**
     * 为一批模板请求缩略图。重活（磁盘缓存 / 按源图分组懒解码 / 裁剪缩放 / 存储）
     * 全部在公共管线 [TemplateThumbPipeline.loadThumbs] 里 —— 模板管理是"原图整卡缩略图"，
     * 框管理是"bbox 裁剪缩略图"，两者共用同一条管线，谁都不许自己手搓缓存。
     *
     * 这里只保留面板自己的簿记：generation 过期判断、双击重试与待填标签的回填。
     */
    private fun requestThumbs(batch: List<FeatureTemplate>, generation: Int) {
        if (disposed || batch.isEmpty()) return
        val requests = batch.map { template ->
            com.alicejump.okscripttoolkit.core.TemplateThumbPipeline.Request(template.name, template.imagePath, template.bbox)
        }
        for (request in requests) requestedThumbs.add(request.key)
        TemplateThumbPipeline.loadThumbs(project, requests, THUMB_HEIGHT) { name, icon ->
            if (disposed) return@loadThumbs
            // 清除请求标记，允许后续渲染重新请求
            requestedThumbs.remove(name)
            // 过期请求（reload 已 thumbs.clear()）：不能把旧缩略图写回新网格；
            // 卡片还在的话按当前 generation 重排一次
            if (generation != renderGeneration.get()) {
                if (pendingThumbLabels.containsKey(name)) {
                    templates.firstOrNull { current -> current.name == name }
                        ?.let { retry -> requestThumbs(listOf(retry), renderGeneration.get()) }
                }
                return@loadThumbs
            }
            if (icon != null) thumbs[name] = icon
            pendingThumbLabels.remove(name)?.forEach { label ->
                label.icon = icon
                label.repaint()
            }
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
            AnnotatedSourcePreview.fileFor(project, imagePath, bbox)
        }.whenComplete { path, error ->
            if (error != null) LOG.warn("Failed to open annotated image for ${template.name}", error)
            SwingUtilities.invokeLater {
                if (project.isDisposed) return@invokeLater
                val file = path?.let { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it) }
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

    private fun notify(content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("okScriptToolkit")
            .createNotification(content, type)
            .notify(project)
    }

    override fun dispose() {
        disposed = true
    }
}

class ShowTemplatesAction : AnAction(), DumbAware {
    init {
        templatePresentation.text = OkScriptToolkitBundle.message("action.showTemplates.text")
        templatePresentation.description = OkScriptToolkitBundle.message("action.showTemplates.description")
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            .getToolWindow("ok-script Templates")
            ?.show()
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
