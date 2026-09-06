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
    private val progressBar = JProgressBar()
    private var images = listOf<TemplateImage>()
    private var currentFilter = ""
    // loadData 在后台线程失效缓存，EDT 在渲染时读写，需要并发安全
    private val thumbCache = java.util.concurrent.ConcurrentHashMap<String, ImageIcon?>()
    // 进行中的缩略图解码（过滤输入会高频触发 renderGrid，按路径去重避免重复读盘解码）
    private val thumbInflight = java.util.concurrent.ConcurrentHashMap<String, CompletableFuture<ImageIcon?>>()

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
        val screenshotAction = ToolbarAction(AllIcons.Actions.Preview, OkScriptToolkitBundle.message("templateAsset.screenshot")) { handleScreenshot() }
        val exportAction = ToolbarAction(AllIcons.Actions.Upload, OkScriptToolkitBundle.message("templateAsset.export")) { handleSaveToAssets() }
        val refreshAction = ToolbarAction(AllIcons.Actions.Refresh, OkScriptToolkitBundle.message("templateAsset.refresh")) { loadData() }
        val actionGroup = com.intellij.openapi.actionSystem.DefaultActionGroup(importAction, screenshotAction, exportAction, refreshAction)
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
        progressBar.preferredSize = Dimension(200, 16)
        progressBar.isVisible = false
        statusPanel.add(progressBar, BorderLayout.SOUTH)

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

        // 缩略图缓存命中即显示；未命中的在后台线程解码（大图 ImageIO.read 可达数秒，
        // 此前在 EDT 同步解码导致 IDE 冻结，见 next_error 的 EDT 冻结转储），就绪后回填
        val thumbLabel = JBLabel().apply {
            horizontalAlignment = SwingConstants.CENTER
            verticalAlignment = SwingConstants.CENTER
            preferredSize = Dimension(0, THUMB_HEIGHT)
        }
        thumbCache[img.file.absolutePath]?.let { thumbLabel.icon = it } ?: requestThumb(img.file, thumbLabel)

        val annText = if (img.annotations.isNotEmpty()) " [${img.annotations.size} ann]" else ""
        val sizeText = "${img.width}×${img.height}$annText"
        // 长文件名中段截断，避免把卡片/网格撑宽；全名放 tooltip
        val displayName = if (img.name.length > 22) {
            img.name.take(12) + "…" + img.name.takeLast(9)
        } else {
            img.name
        }
        val infoText = "<html><div style=\"text-align:center;\"><b>$displayName</b><br>" +
            "<span style=\"color:#8a8a8a\">$sizeText</span></div></html>"
        val infoLabel = JBLabel(infoText)
        infoLabel.horizontalAlignment = SwingConstants.CENTER
        infoLabel.font = infoLabel.font.deriveFont(10f)

        card.add(thumbLabel, BorderLayout.CENTER)
        card.add(infoLabel, BorderLayout.SOUTH)
        card.toolTipText = img.name

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

    /** 缩略图解码只在后台线程做，完成后回填到仍显示中的卡片（网格重渲染会换新 label）；
     *  解码结果写回缓存供后续渲染复用，进行中的解码按路径去重。 */
    private fun requestThumb(file: File, label: JBLabel) {
        val future = thumbInflight.computeIfAbsent(file.absolutePath) {
            CompletableFuture.supplyAsync {
                val icon = loadThumbIcon(file)
                if (icon != null) thumbCache[file.absolutePath] = icon
                icon
            }.whenComplete { _, _ -> thumbInflight.remove(file.absolutePath) }
        }
        future.thenAccept { icon ->
            SwingUtilities.invokeLater {
                if (label.isShowing) {
                    label.icon = icon
                    label.repaint()
                }
            }
        }
    }

    private fun loadThumbIcon(file: File): ImageIcon? {
        return try {
            val bi = javax.imageio.ImageIO.read(file) ?: return null
            // 等比适配预览框（高 72、宽不超 120），绝不拉伸
            val scale = minOf(THUMB_HEIGHT.toDouble() / bi.height, 120.0 / bi.width)
            val w = (bi.width * scale).toInt().coerceAtLeast(1)
            val h = (bi.height * scale).toInt().coerceAtLeast(1)
            val thumb = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            val g = thumb.createGraphics()
            g.drawImage(bi, 0, 0, w, h, null)
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

    /**
     * 截图采集（对齐 VSCode 版 handleScreenshot）：自动探测窗口配置，
     * 失败回退手输标题正则；截图落盘 ok_templates 并自动注册进 COCO。
     */
    private fun handleScreenshot() {
        val capture = com.alicejump.okscripttoolkit.core.ScreenshotCapture(project)
        val projectDir = com.alicejump.okscripttoolkit.core.ScreenshotCapture.detectProjectDir(project)
        val pythonPath = com.alicejump.okscripttoolkit.core.ScreenshotCapture.detectPythonPath(projectDir, project)

        statusLabel.text = OkScriptToolkitBundle.message("templateAsset.screenshotProbing")
        CompletableFuture.supplyAsync { capture.probeWindowConfig(projectDir, pythonPath) }
            .thenAccept { windowConfig ->
                javax.swing.SwingUtilities.invokeLater {
                    var titleRegex: String? = null
                    var config: com.alicejump.okscripttoolkit.core.WindowConfig? = windowConfig
                    if (windowConfig != null &&
                        (!windowConfig.exe.isNullOrEmpty() || !windowConfig.title.isNullOrBlank() || !windowConfig.hwndClass.isNullOrBlank())
                    ) {
                        statusLabel.text = OkScriptToolkitBundle.message("templateAsset.screenshotDetected", windowConfig.describe())
                    } else {
                        config = null
                        val input = com.intellij.openapi.ui.Messages.showInputDialog(
                            project,
                            OkScriptToolkitBundle.message("templateAsset.screenshotPrompt"),
                            OkScriptToolkitBundle.message("templateAsset.screenshot"),
                            com.intellij.openapi.ui.Messages.getInformationIcon(),
                            "",
                            null,
                        ) ?: return@invokeLater
                        titleRegex = input.trim()
                        statusLabel.text = OkScriptToolkitBundle.message("templateAsset.screenshotCapturing")
                    }
                    doCapture(capture, projectDir, pythonPath, config, titleRegex)
                }
            }
            .exceptionally { throwable ->
                SwingUtilities.invokeLater {
                    statusLabel.text = "Error: ${throwable.message}"
                }
                null
            }
    }

    private fun doCapture(
        capture: com.alicejump.okscripttoolkit.core.ScreenshotCapture,
        projectDir: String,
        pythonPath: String,
        windowConfig: com.alicejump.okscripttoolkit.core.WindowConfig?,
        titleRegex: String?,
    ) {
        val templatesDirName = OkScriptToolkitSettings.getInstance(project).okTemplatesDirectory()
        CompletableFuture.supplyAsync {
            val projectRoot = projectDir.ifBlank { project.basePath ?: "" }
            if (projectRoot.isBlank()) return@supplyAsync null to "no project dir"
            val outputDir = java.nio.file.Paths.get(
                if (projectRoot.isNotBlank() &&
                    java.nio.file.Files.exists(java.nio.file.Paths.get(projectRoot, templatesDirName))
                ) projectRoot else (project.basePath ?: projectRoot),
                templatesDirName,
            )
            java.nio.file.Files.createDirectories(outputDir)
            val ts = java.time.LocalDateTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"),
            )
            val outputPath = outputDir.resolve("screenshot_$ts.png")
            val error = StringBuilder()
            val result = capture.capture(projectRoot, pythonPath, outputPath, windowConfig, titleRegex, error)
            result to error.toString()
        }.thenAccept { (result, err) ->
            SwingUtilities.invokeLater {
                if (result == null) {
                    statusLabel.text = OkScriptToolkitBundle.message("templateAsset.screenshotFailed", err)
                    com.intellij.notification.NotificationGroupManager.getInstance()
                        .getNotificationGroup("okScriptToolkit")
                        .createNotification(
                            OkScriptToolkitBundle.message("templateAsset.screenshotFailed", err),
                            com.intellij.notification.NotificationType.ERROR,
                        )
                        .notify(project)
                    return@invokeLater
                }
                try {
                    data.load(
                        OkScriptToolkitSettings.getInstance(project).okScriptProjectPath().ifBlank { project.basePath ?: "" },
                        OkScriptToolkitSettings.getInstance(project).okTemplatesDirectory(),
                    )
                    val cocoImage = data.getImageEntryForFile(result.toFile().name)
                        ?: data.addImageEntry(result.toFile().name, 0, 0)
                    val (w, h) = data.readImageDimensions(result.toFile())
                    if (w > 0 && cocoImage.width == 0) {
                        data.removeImageEntry(cocoImage.id)
                        data.addImageEntry(result.toFile().name, w, h)
                    }
                    data.save()
                    statusLabel.text = OkScriptToolkitBundle.message("templateAsset.screenshotSaved", result.toFile().name)
                    notify(OkScriptToolkitBundle.message("templateAsset.screenshotSaved", result.toFile().name), NotificationType.INFORMATION)
                    loadData()
                } catch (e: Exception) {
                    statusLabel.text = "Error: ${e.message}"
                }
            }
        }
    }

    /**
     * saveToAssets 导出（对齐 VSCode 版）：选择目标（assets / ok_tasks/assets）、
     * 可选生成 LabelEnum.py，后台 bin-packing 合成 pages 并重写目标 COCO。
     */
    private fun handleSaveToAssets() {
        val annotatedCount = images.count { it.annotations.isNotEmpty() }
        if (annotatedCount == 0) {
            notify(OkScriptToolkitBundle.message("templateAsset.exportNoAnnotations"), NotificationType.WARNING)
            return
        }
        val projectDir = OkScriptToolkitSettings.getInstance(project).okScriptProjectPath().ifBlank {
            project.basePath ?: ""
        }
        if (projectDir.isBlank()) {
            notify(OkScriptToolkitBundle.message("taskLauncher.noProject"), NotificationType.WARNING)
            return
        }

        val options = arrayOf("assets", "ok_tasks/assets")
        val chosenIndex = com.intellij.openapi.ui.Messages.showChooseDialog(
            project,
            OkScriptToolkitBundle.message("templateAsset.exportTargetPrompt", annotatedCount),
            OkScriptToolkitBundle.message("templateAsset.export"),
            com.intellij.icons.AllIcons.General.Information,
            options,
            options[0],
        )
        if (chosenIndex < 0) return
        val selectedTarget = options[chosenIndex]
        val targetFolder = java.nio.file.Paths.get(projectDir, selectedTarget.replace("/", java.io.File.separator)).toString()

        val generateEnum = com.intellij.openapi.ui.Messages.showYesNoDialog(
            project,
            OkScriptToolkitBundle.message("templateAsset.exportEnumPrompt"),
            OkScriptToolkitBundle.message("templateAsset.export"),
            com.intellij.openapi.ui.Messages.getQuestionIcon(),
        ) == com.intellij.openapi.ui.Messages.YES

        var enumPath: String? = null
        if (generateEnum) {
            val defaultPath = java.nio.file.Paths.get(targetFolder, "LabelEnum.py").toString()
            val input = com.intellij.openapi.ui.Messages.showInputDialog(
                project,
                OkScriptToolkitBundle.message("templateAsset.exportEnumPathPrompt"),
                OkScriptToolkitBundle.message("templateAsset.exportEnumTitle"),
                com.intellij.openapi.ui.Messages.getInformationIcon(),
                defaultPath,
                null,
            ) ?: return
            enumPath = input.trim()
        }

        statusLabel.text = OkScriptToolkitBundle.message("templateAsset.exportRunning")
        progressBar.isIndeterminate = true
        progressBar.isVisible = true

        com.intellij.openapi.progress.ProgressManager.getInstance().run(
            object : com.intellij.openapi.progress.Task.Backgroundable(
                project,
                OkScriptToolkitBundle.message("templateAsset.export"),
                false,
            ) {
                override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                    try {
                        data.load(
                            projectDir,
                            OkScriptToolkitSettings.getInstance(project).okTemplatesDirectory(),
                        )
                        data.saveToAssets(targetFolder, generateEnum, enumPath) { done, total ->
                            indicator.fraction = if (total > 0) done.toDouble() / total else 0.0
                            indicator.text = OkScriptToolkitBundle.message(
                                "templateAsset.exportProgress", done, total,
                            )
                        }
                        com.intellij.notification.NotificationGroupManager.getInstance()
                            .getNotificationGroup("okScriptToolkit")
                            .createNotification(
                                OkScriptToolkitBundle.message("templateAsset.exportDone", targetFolder),
                                NotificationType.INFORMATION,
                            )
                            .notify(project)
                    } catch (e: Exception) {
                        com.intellij.notification.NotificationGroupManager.getInstance()
                            .getNotificationGroup("okScriptToolkit")
                            .createNotification(
                                OkScriptToolkitBundle.message("templateAsset.exportFailed", e.message ?: e.toString()),
                                NotificationType.ERROR,
                            )
                            .notify(project)
                    } finally {
                        javax.swing.SwingUtilities.invokeLater {
                            progressBar.isIndeterminate = false
                            progressBar.isVisible = false
                            loadData()
                        }
                    }
                }
            },
        )
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
