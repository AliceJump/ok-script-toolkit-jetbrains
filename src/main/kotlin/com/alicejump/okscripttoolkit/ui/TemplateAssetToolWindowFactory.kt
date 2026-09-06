package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.alicejump.okscripttoolkit.core.TemplateAssetDataService
import com.alicejump.okscripttoolkit.core.TemplateImage
import com.alicejump.okscripttoolkit.settings.OkScriptToolkitSettings
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.util.concurrent.CompletableFuture
import javax.swing.*
import javax.swing.border.EmptyBorder

class TemplateAssetToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = TemplateAssetPanel(project)
        val content = ContentFactory.getInstance().createContent(panel.mainPanel, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

class TemplateAssetPanel(private val project: Project) : com.intellij.openapi.Disposable {

    val mainPanel: JPanel
    private val data = project.service<TemplateAssetDataService>()

    private val searchField = JBTextField()
    private val gridPanel = JPanel(GridLayout(0, ThumbGridPolicy.columnsFor(540), ThumbGridPolicy.HGAP_VALUE, ThumbGridPolicy.HGAP_VALUE)).apply {
        isOpaque = false
    }
    private val gridWrap = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(gridPanel, BorderLayout.NORTH)
    }
    private val scrollPane = JBScrollPane(gridWrap)
    private var gridCols = ThumbGridPolicy.columnsFor(540)
    private val statusLabel = JBLabel()
    private val countLabel = JBLabel()
    private var images = listOf<TemplateImage>()
    private var currentFilter = ""
    // loadData 在后台线程失效缓存，EDT 在渲染时读写，需要并发安全
    private val thumbCache = java.util.concurrent.ConcurrentHashMap<String, ImageIcon?>()

    companion object {
        private const val THUMB_HEIGHT = ThumbGridPolicy.THUMB_HEIGHT
    }

    init {
        mainPanel = JPanel(BorderLayout())
        initUI()
        loadData()
    }

    private fun initUI() {
        val toolbar = JPanel(BorderLayout(4, 0))
        toolbar.border = JBUI.Borders.empty(4)

        searchField.emptyText.text = OkScriptToolkitBundle.message("templateAsset.search")
        searchField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = applyFilter()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = applyFilter()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = applyFilter()
        })
        toolbar.add(searchField, BorderLayout.CENTER)

        val importAction = ToolbarAction(AllIcons.Actions.AddFile, OkScriptToolkitBundle.message("templateAsset.import")) { handleImport() }
        val refreshAction = ToolbarAction(AllIcons.Actions.Refresh, OkScriptToolkitBundle.message("templateAsset.refresh")) { loadData() }
        val actionGroup = com.intellij.openapi.actionSystem.DefaultActionGroup(importAction, refreshAction)
        val actionToolbar = com.intellij.openapi.actionSystem.ActionManager.getInstance()
            .createActionToolbar("ok-script-assets", actionGroup, true)
        actionToolbar.targetComponent = mainPanel
        val btnPanel = actionToolbar.component
        btnPanel.border = javax.swing.BorderFactory.createEmptyBorder(0, 4, 0, 0)

        toolbar.add(btnPanel, BorderLayout.EAST)

        gridPanel.border = EmptyBorder(8, 8, 8, 8)
        // 视口宽度变化时按统一规则重算列数（与模板画廊一致）
        scrollPane.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent?) {
                applyGridLayout()
            }
        })

        val statusPanel = JPanel(BorderLayout())
        statusPanel.border = JBUI.Borders.empty(2, 4)
        statusPanel.add(statusLabel, BorderLayout.CENTER)
        statusPanel.add(countLabel, BorderLayout.EAST)

        mainPanel.add(toolbar, BorderLayout.NORTH)
        mainPanel.add(scrollPane, BorderLayout.CENTER)
        mainPanel.add(statusPanel, BorderLayout.SOUTH)
    }

    private fun loadData() {
        statusLabel.text = OkScriptToolkitBundle.message("templateAsset.loading")
        CompletableFuture.supplyAsync {
            val settings = OkScriptToolkitSettings.getInstance(project)
            val projectDir = project.basePath ?: ""
            data.load(projectDir, settings.okTemplatesDirectory())
            val result = data.listImages()
            // 图片集合可能已变化，按路径失效缩略图缓存
            val validPaths = result.map { it.file.absolutePath }.toSet()
            thumbCache.keys.retainAll(validPaths)
            result
        }.thenAccept { result ->
            SwingUtilities.invokeLater {
                images = result
                renderGrid()
            }
        }.exceptionally { throwable ->
            SwingUtilities.invokeLater {
                statusLabel.text = "Error: ${throwable.message}"
            }
            null
        }
    }

    private fun renderGrid() {
        applyGridLayout()
        gridPanel.removeAll()

        val filtered = if (currentFilter.isEmpty()) images else {
            images.filter { it.name.lowercase().contains(currentFilter) }
        }

        for (img in filtered) {
            gridPanel.add(createImageCard(img))
        }

        if (filtered.isEmpty()) {
            gridPanel.add(JBLabel(OkScriptToolkitBundle.message("templateAsset.empty")))
        }

        countLabel.text = OkScriptToolkitBundle.message("templateAsset.count", filtered.size)
        statusLabel.text = "Loaded ${images.size} images"
        gridPanel.revalidate()
        gridPanel.repaint()
    }

    /** 与模板画廊一致：按视口宽等分铺满，动态调整列数 */
    private fun applyGridLayout() {
        val viewportWidth = scrollPane.width.takeIf { it > 0 } ?: return
        val cols = ThumbGridPolicy.columnsFor(viewportWidth)
        if (cols != gridCols) {
            gridCols = cols
            gridPanel.layout = GridLayout(0, cols, ThumbGridPolicy.HGAP_VALUE, ThumbGridPolicy.HGAP_VALUE)
            gridPanel.revalidate()
            gridPanel.repaint()
        }
    }

    private fun createImageCard(img: TemplateImage): JPanel {
        val card = JPanel(BorderLayout())
        card.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor.border()),
            BorderFactory.createEmptyBorder(4, 4, 4, 4),
        )

        // 缩略图只读盘一次并缓存（此前每次 paint 都重新 ImageIO.read）
        val thumbIcon = thumbCache[img.file.absolutePath] ?: loadThumbIcon(img.file).also {
            if (it != null) thumbCache[img.file.absolutePath] = it
        }
        val thumbLabel = JBLabel(thumbIcon).apply {
            horizontalAlignment = SwingConstants.CENTER
            verticalAlignment = SwingConstants.CENTER
            preferredSize = Dimension(0, THUMB_HEIGHT)
        }

        val annText = if (img.annotations.isNotEmpty()) " [${img.annotations.size} ann]" else ""
        val infoText = "<html><center><b>${img.name}</b><br>${img.width}x${img.height}$annText</center></html>"
        val infoLabel = JBLabel(infoText)
        infoLabel.horizontalAlignment = SwingConstants.CENTER
        infoLabel.font = infoLabel.font.deriveFont(10f)

        card.add(thumbLabel, BorderLayout.CENTER)
        card.add(infoLabel, BorderLayout.SOUTH)

        card.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) openAnnotator(img)
            }
            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) showContextMenu(e, img)
            }
            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) showContextMenu(e, img)
            }
        })

        return card
    }

    /** 双击打开 COCO 标注编辑器（对齐 VSCode 版标注编辑器入口），关闭后刷新网格。 */
    private fun openAnnotator(img: TemplateImage) {
        val dialog = AnnotationDialog(project, data, img)
        dialog.show()
        loadData()
    }

    private fun loadThumbIcon(file: File): ImageIcon? {
        return try {
            val bi = javax.imageio.ImageIO.read(file) ?: return null
            val scale = THUMB_HEIGHT.toDouble() / bi.height
            val w = (bi.width * scale).toInt().coerceIn(1, 120)
            val thumb = java.awt.image.BufferedImage(w, THUMB_HEIGHT, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            val g = thumb.createGraphics()
            g.drawImage(bi, 0, 0, w, THUMB_HEIGHT, null)
            g.dispose()
            ImageIcon(thumb)
        } catch (_: Exception) {
            null
        }
    }

    private fun openInEditor(img: TemplateImage) {
        com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .refreshAndFindFileByNioFile(img.file.toPath())
            ?.let { file ->
                com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
                    .openFile(file, true)
            }
    }

    private fun showContextMenu(e: MouseEvent, img: TemplateImage) {
        val popup = JPopupMenu()

        val openItem = JMenuItem(OkScriptToolkitBundle.message("templateAsset.open"))
        openItem.addActionListener {
            openInEditor(img)
        }
        popup.add(openItem)

        popup.addSeparator()

        val deleteItem = JMenuItem(OkScriptToolkitBundle.message("templateAsset.delete"))
        deleteItem.addActionListener {
            val confirm = JOptionPane.showConfirmDialog(
                mainPanel,
                OkScriptToolkitBundle.message("templateAsset.deleteConfirm", img.name),
                OkScriptToolkitBundle.message("templateAsset.delete"),
                JOptionPane.YES_NO_OPTION,
            )
            if (confirm == JOptionPane.YES_OPTION) {
                // 文件删除 + COCO 写盘移出 EDT
                CompletableFuture.runAsync {
                    data.deleteImage(img.file)
                    data.save()
                }.thenRun {
                    SwingUtilities.invokeLater { loadData() }
                }
            }
        }
        popup.add(deleteItem)

        popup.show(e.component, e.x, e.y)
    }

    private fun handleImport() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()
            .withTitle("Import Template Image")
        com.intellij.openapi.fileChooser.FileChooser.chooseFile(descriptor, project, null) { file ->
            val settings = OkScriptToolkitSettings.getInstance(project)
            val projectDir = project.basePath ?: return@chooseFile
            val targetDir = File(projectDir, settings.okTemplatesDirectory())
            val imported = data.importImage(File(file.path), targetDir)
            if (imported != null) {
                notify("Imported: ${imported.name}", NotificationType.INFORMATION)
                loadData()
            } else {
                notify("Failed to import image", NotificationType.ERROR)
            }
        }
    }

    private fun applyFilter() {
        currentFilter = searchField.text.trim().lowercase()
        renderGrid()
    }

    private fun notify(content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("okScriptToolkit")
            .createNotification(content, type)
            .notify(project)
    }

    override fun dispose() {}
}

class ShowTemplateAssetsAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            .getToolWindow("ok-script Assets")
            ?.show()
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
