package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.alicejump.okscripttoolkit.core.LabelEnumGuard
import com.alicejump.okscripttoolkit.core.OkDataChangeService
import com.alicejump.okscripttoolkit.core.SaveToAssetsFlow
import com.alicejump.okscripttoolkit.core.ScreenshotCapture
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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import javax.swing.*
import javax.swing.border.EmptyBorder

/** 引用扫描时跳过的目录：这些不是项目代码，扫进去既慢又没意义。 */
private val SKIPPED_SCAN_DIRS = setOf("node_modules", ".venv", "venv", ".git", "__pycache__", ".idea")

/** 引用扫描的文件数上限。项目代码远小于这个数，设它只为兜住"项目根填错"这种情形。 */
private const val LABEL_ENUM_REFERENCE_SCAN_LIMIT = 2000

class TemplateAssetToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = TemplateAssetPanel(project)
        val content = ContentFactory.getInstance().createContent(panel.mainPanel, "", false)
        content.setDisposer(panel)
        // 供快捷键 Action 复用面板自己的截图动作（见 PANEL_KEY 的说明）
        content.putUserData(TemplateAssetPanel.PANEL_KEY, panel)
        toolWindow.contentManager.addContent(content)
    }
}

class TemplateAssetPanel(private val project: Project) : com.intellij.openapi.Disposable {

    val mainPanel: JPanel
    private val data = project.service<TemplateAssetDataService>()

    private val searchField = JBTextField()
    /** 「硬前台」：本次截图强制把游戏窗口切到前台再截（会抢焦点），状态按项目持久化 */
    private val hardForegroundCheck: JCheckBox = HardForegroundToggle.create(project)
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
    private var visibleImages = listOf<TemplateImage>()
    private var currentFilter = ""
    // loadData 在后台线程失效缓存，EDT 在渲染时读写，需要并发安全
    private val thumbCache = java.util.concurrent.ConcurrentHashMap<String, ImageIcon?>()
    // 进行中的缩略图解码（过滤输入会高频触发 renderGrid，按路径去重避免重复读盘解码）
    private val thumbInflight = java.util.concurrent.ConcurrentHashMap<String, CompletableFuture<ImageIcon?>>()

    companion object {
        /**
         * 工具窗口 id。必须与 `plugin.xml` 的 `<toolWindow id="...">` 逐字一致 ——
         * XML 里引用不了 Kotlin 常量，所以这里抽出来是为了让**代码内的多处引用**不漂移。
         */
        const val TOOL_WINDOW_ID = "ok-script Assets"

        /**
         * 面板实例在工具窗口 content 上的 Key。
         *
         * 快捷键 Action（[ScreenshotToTemplateAssetsAction]）要复用面板自己的截图动作，
         * 而 `content.component` 给到的是外层 JPanel、**拿不到面板对象** —— 所以建 content
         * 时把面板存进来。用 Key 而不是静态字段：同一个 IDE 可以开多个项目，静态引用会串。
         */
        val PANEL_KEY: com.intellij.openapi.util.Key<TemplateAssetPanel> =
            com.intellij.openapi.util.Key.create("okScriptToolkit.templateAssetPanel")

        private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(TemplateAssetPanel::class.java)
        private const val THUMB_HEIGHT = ThumbGridPolicy.THUMB_HEIGHT
        private val DROP_HINT_COLOR = JBColor(0x0078D4, 0x4A9EFF)
    }

    init {
        mainPanel = JPanel(BorderLayout())
        initUI()
        installDropTarget()
        loadData()

        // 数据文件变化自动刷新（对齐 VSCode 版 watcher 派发）
        project.messageBus.connect(this).subscribe(
            OkDataChangeService.TOPIC,
            com.alicejump.okscripttoolkit.core.OkDataChangeListener { loadData() },
        )

        // 索引构建完成后再刷一次，确保缩略图正常加载
        com.intellij.openapi.project.DumbService.getInstance(project).runWhenSmart {
            loadData()
        }
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
        toolbar.add(hardForegroundCheck, BorderLayout.WEST)

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
        // 标注编辑器的 ←/→ 导航跟随当前过滤结果
        visibleImages = filtered

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

    /** 双击打开 COCO 标注编辑器（对齐 VSCode 版标注编辑器入口），关闭后刷新网格。
     *  传入当前过滤列表，编辑器内 ←/→ 可在列表内连续标注。 */
    private fun openAnnotator(img: TemplateImage) {
        val list = visibleImages.ifEmpty { listOf(img) }
        val dialog = AnnotationDialog(project, data, img, list, list.indexOf(img).coerceAtLeast(0))
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
        // 对齐 VSCode 版：多选导入 PNG/JPEG/BMP（裁剪/打包管线支持的格式）
        val descriptor = com.intellij.openapi.fileChooser.FileChooserDescriptor(
            /* chooseFiles = */ true,
            /* chooseFolders = */ false,
            /* chooseJars = */ false,
            /* chooseJarsAsFiles = */ false,
            /* chooseJarContents = */ false,
            /* chooseMultiple = */ true,
        )
            .withTitle(OkScriptToolkitBundle.message("templateAsset.importTitle"))
            .withFileFilter { file ->
                (file.extension ?: "").lowercase() in setOf("png", "jpg", "jpeg", "bmp")
            }
        com.intellij.openapi.fileChooser.FileChooser.chooseFiles(descriptor, project, null) { files ->
            val settings = OkScriptToolkitSettings.getInstance(project)
            val projectDir = project.basePath ?: return@chooseFiles
            val targetDir = File(projectDir, settings.okTemplatesDirectory())
            val imported = data.importImages(files.map { File(it.path) }, targetDir)
            if (imported > 0) {
                notify(
                    OkScriptToolkitBundle.message("templateAsset.imported", imported),
                    NotificationType.INFORMATION,
                )
                loadData()
            }
        }
    }

    /**
     * 面板**外部**的截图入口（快捷键 Action 用）。
     *
     * 直接转发到面板自己的 [handleScreenshot] —— **不新增截图实现**，
     * 否则两条路径的截图行为（落盘位置、COCO 登记）迟早漂移。
     * 与点工具栏那个截图按钮走的是同一段代码。
     *
     * 调用方需在 EDT 上（`AnAction.actionPerformed` 天然满足）。
     */
    fun screenshotNow() {
        handleScreenshot()
    }

    /**
     * 截图采集（对齐 VSCode 版 handleScreenshot）：自动探测窗口配置，
     * 失败回退手输标题正则；截图落盘 ok_templates 并自动注册进 COCO。
     *
     * 探测 / 回退输入 / 采集这一整段由 [ScreenshotCapture.captureInteractive]
     * 统一提供，与临时截图工具窗口共用同一套逻辑。
     */
    private fun handleScreenshot() {
        val templatesDirName = OkScriptToolkitSettings.getInstance(project).okTemplatesDirectory()
        val projectDir = ScreenshotCapture.detectProjectDir(project)

        statusLabel.text = OkScriptToolkitBundle.message("templateAsset.screenshotProbing")
        val methodOverride = HardForegroundToggle.methodOverride(hardForegroundCheck)
        CompletableFuture.supplyAsync<Pair<Path?, String?>> {
            val projectRoot = projectDir.ifBlank { project.basePath.orEmpty() }
                .ifBlank { return@supplyAsync null to "no project dir" }
            val base = Paths.get(projectRoot)
            val outputDir = if (Files.isDirectory(base.resolve(templatesDirName))) {
                base.resolve(templatesDirName)
            } else {
                Paths.get(project.basePath ?: projectRoot).resolve(templatesDirName)
            }
            Files.createDirectories(outputDir)
            val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
            val outputPath = outputDir.resolve("screenshot_$ts.png")
            val error = ScreenshotCapture(project).captureInteractive(outputPath, methodOverride) { config ->
                statusLabel.text = OkScriptToolkitBundle.message("templateAsset.screenshotDetected", config.describe())
            }
            outputPath to error
        }.thenAccept { (outputPath, error) ->
            SwingUtilities.invokeLater {
                when {
                    outputPath == null -> {
                        statusLabel.text = OkScriptToolkitBundle.message("templateAsset.screenshotFailed", error ?: "")
                        notify(statusLabel.text, NotificationType.ERROR)
                    }
                    error == null -> registerCapturedImage(outputPath)
                    error == ScreenshotCapture.CANCELLED -> statusLabel.text = " "
                    else -> {
                        statusLabel.text = OkScriptToolkitBundle.message("templateAsset.screenshotFailed", error)
                        notify(statusLabel.text, NotificationType.ERROR)
                    }
                }
            }
        }
    }

    /** 把刚落盘的截图登记进 COCO：已存在则补齐尺寸，不存在则新增条目 */
    private fun registerCapturedImage(outputPath: Path) {
        val file = outputPath.toFile()
        try {
            val settings = OkScriptToolkitSettings.getInstance(project)
            val projectDir = settings.okScriptProjectPath().ifBlank { project.basePath ?: "" }
            data.load(projectDir, settings.okTemplatesDirectory())
            val existingImage = data.getImageEntryForFile(file.name)
            if (existingImage != null) {
                // Image already in COCO, update dimensions if needed
                if (existingImage.width == 0 || existingImage.height == 0) {
                    val (w, h) = data.readImageHeaderSize(file) ?: (0 to 0)
                    if (w > 0 && h > 0) {
                        data.removeImageEntry(existingImage.id)
                        data.addImageEntry(file.name, w, h)
                    }
                }
            } else {
                // New image: read dimensions from file BEFORE adding to COCO
                val (w, h) = data.readImageHeaderSize(file) ?: (0 to 0)
                data.addImageEntry(file.name, w, h)
            }
            data.save()
            val text = OkScriptToolkitBundle.message("templateAsset.screenshotSaved", file.name)
            statusLabel.text = text
            notify(text, NotificationType.INFORMATION)
            loadData()
        } catch (e: Exception) {
            statusLabel.text = "Error: ${e.message}"
        }
    }

    /**
     * 接收「临时截图」工具窗口拖入的图片（对齐 VSCode 版的 dropTemp）。
     * JetBrains 端两个工具窗口同处一个 JVM，可直接用自定义 DataFlavor 传递文件路径。
     */
    private fun handleDropTemp(file: File) {
        val settings = OkScriptToolkitSettings.getInstance(project)
        val projectDir = project.basePath ?: return
        val targetDir = File(projectDir, settings.okTemplatesDirectory())
        CompletableFuture.supplyAsync {
            data.importImages(listOf(file), targetDir)
        }.thenAccept { imported ->
            SwingUtilities.invokeLater {
                if (imported <= 0) {
                    notify(
                        OkScriptToolkitBundle.message("templateAsset.dropFailed", file.name),
                        NotificationType.ERROR,
                    )
                } else {
                    notify(
                        OkScriptToolkitBundle.message("templateAsset.dropped", file.name),
                        NotificationType.INFORMATION,
                    )
                }
                loadData()
            }
        }
    }

    /** 拖入高亮：canImport 会随拖拽移动反复触发，用防抖计时器复位 */
    private fun installDropTarget() {
        var resetTimer: javax.swing.Timer? = null
        val normalBorder = mainPanel.border
        mainPanel.transferHandler = object : TransferHandler() {
            override fun canImport(support: TransferSupport): Boolean {
                val ok = support.isDrop && support.isDataFlavorSupported(TempShotTransferable.FLAVOR)
                if (ok) {
                    resetTimer?.stop()
                    mainPanel.border = BorderFactory.createLineBorder(DROP_HINT_COLOR, 2)
                    statusLabel.text = OkScriptToolkitBundle.message("templateAsset.dropHint")
                    resetTimer = javax.swing.Timer(400) { clearDropHint(normalBorder) }.also {
                        it.isRepeats = false
                        it.start()
                    }
                }
                return ok
            }

            override fun importData(support: TransferSupport): Boolean {
                resetTimer?.stop()
                clearDropHint(normalBorder)
                if (!support.isDrop) return false
                val file = TempShotTransferable.fileOf(support.transferable) ?: return false
                handleDropTemp(file)
                return true
            }
        }
    }

    /**
     * 覆盖已有枚举文件、且**类名会变**时先问一句。
     *
     * 为什么这道闸在 UI 层而不是 `TemplateAssetDataService.generateLabelEnum` 里：
     * 那是个同步的数据写入，里面弹模态框会让它没法被测、也把 UI 决策塞进了数据层。
     * 判据本身是纯对象（[LabelEnumGuard]），IO 与弹窗留在这里。
     *
     * 失败一律放行：读不了文件、扫不了项目都只影响"提示的完整度"，不能反过来阻断导出。
     *
     * @return `false` = 用户选择放弃这次导出（**整个**导出，不只是枚举 —— 半导出状态更难解释）
     */
    private fun confirmLabelEnumRename(project: Project, projectDir: String, enumAbsolutePath: String): Boolean {
        val existing = try {
            File(enumAbsolutePath).takeIf { it.isFile }?.readText()
        } catch (_: Exception) {
            null
        } ?: return true // 文件不存在（或读不了）→ 全新生成，没有旧名字可废
        // 用**将要写入的那个类名**（`writableClassName` 会把非法标识符退回兜底名）去比 ——
        // 直接拿用户填的原始值比，会为"填了个非法名字、实际什么都没变"的情况报警。
        val newClassName = LabelEnumGuard.writableClassName(
            OkScriptToolkitSettings.getInstance(project).labelEnumClassName(enumAbsolutePath),
        )
        val impact = LabelEnumGuard.renameImpact(existing, newClassName) ?: return true
        val references = findLabelEnumReferences(projectDir, impact.existingClassName)
        val choice = com.intellij.openapi.ui.Messages.showYesNoDialog(
            project,
            LabelEnumGuard.renameMessage(impact, references),
            OkScriptToolkitBundle.message("labelEnum.rename.title"),
            com.intellij.openapi.ui.Messages.getWarningIcon(),
        )
        return choice == com.intellij.openapi.ui.Messages.YES
    }

    /**
     * 扫项目里的 `.py`，找出**按旧类名 import** 的文件（相对项目根）。
     *
     * 只在"类名真的变了"时才调用（罕见），所以不做缓存、也不做增量。
     * 排除目录是常见的"不是项目代码"的地方 —— 扫进虚拟环境里的几千个文件既慢又没意义。
     *
     * 返回**全部**命中；文案里只列前几个，但总数照实报。
     */
    private fun findLabelEnumReferences(projectDir: String, className: String): List<String> {
        if (className.isEmpty()) return emptyList()
        val root = File(projectDir)
        if (!root.isDirectory) return emptyList()
        val collected = ArrayList<Pair<String, String>>()
        root.walkTopDown()
            .onEnter { it == root || it.name !in SKIPPED_SCAN_DIRS }
            .filter { it.isFile && it.extension == "py" }
            .take(LABEL_ENUM_REFERENCE_SCAN_LIMIT)
            .forEach { file ->
                try {
                    val relative = root.toPath().relativize(file.toPath()).toString().replace('\\', '/')
                    collected += relative to file.readText()
                } catch (_: Exception) {
                    // 读不了就跳过 —— 这是"提示"不是"校验"，不能因为一个文件读不了就少报或误报
                }
            }
        return LabelEnumGuard.referencingFiles(collected, className)
    }

    private fun clearDropHint(border: javax.swing.border.Border?) {
        mainPanel.border = border
        statusLabel.text = " "
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

        val settings = OkScriptToolkitSettings.getInstance(project)

        /**
         * 当前**生效**的枚举文件绝对路径；`null` = 没指定（这次不生成）。
         *
         * 取值链：**个人偏好（IDE 设置 `labelEnumPath`）> 项目约定文件的 `labelEnum.path` > 空**。
         * 个人偏好层由下面的「修改路径…」写入设置（`setLabelEnumPath`），所以"填过一次就记住"
         * 与 VS Code 侧一致，而且那个值在设置界面能看到、在溯源面板能溯源、也能一键恢复。
         *
         * 注意必须走 `labelEnumPath()` 而不是直接拿 `labelEnum.path`：后者是**模块路径**
         * （`src/data/FeatureList`，不带 .py，与 config.py 的 label_enum_relative_path 同形），
         * 而这里要的是**文件路径** —— 直接塞进去会生成一个没有扩展名的文件，Python import 不到。
         */
        fun effectiveEnumPath(): String? = settings.labelEnumPath()
            .takeIf { it.isNotEmpty() }
            ?.let { SaveToAssetsFlow.toAbsolute(projectDir, it) }

        val targets = listOf("assets", "ok_tasks/assets")
        val changePathLabel = OkScriptToolkitBundle.message("templateAsset.exportEnumChangePath")

        /** 用户是否已经在「修改路径」里做过决定 —— 决定过就不再追问，哪怕他清空了 */
        var enumPathDecided = false

        // 目标选择与「修改路径」共用一轮循环：改完路径要回到目标选择，
        // 所以候选列表每次都重新构造（有生效路径时才多出那一项，见 `SaveToAssetsFlow`）。
        var selectedTarget = ""
        while (true) {
            val chosenIndex = ChooseDialog.show(
                project,
                OkScriptToolkitBundle.message("templateAsset.exportTargetPrompt", annotatedCount),
                OkScriptToolkitBundle.message("templateAsset.export"),
                SaveToAssetsFlow.options(targets, effectiveEnumPath(), changePathLabel),
            ) ?: return
            if (!SaveToAssetsFlow.isChangePathChoice(chosenIndex, targets.size)) {
                selectedTarget = targets[chosenIndex]
                break
            }
            val input = com.intellij.openapi.ui.Messages.showInputDialog(
                project,
                OkScriptToolkitBundle.message("templateAsset.exportEnumPathPrompt"),
                OkScriptToolkitBundle.message("templateAsset.exportEnumTitle"),
                com.intellij.openapi.ui.Messages.getInformationIcon(),
                effectiveEnumPath() ?: "",
                null,
            ) ?: continue // 取消改路径 → 回到目标选择
            // 写进**个人偏好**（IDE 设置）。留空 = **撤销覆盖、回到项目约定**
            // （`setLabelEnumPath` 传空会取消记账，而不是钉死为空）—— 与 aliases 同一条规则。
            // 存的是**相对项目根**的路径，设置里的值才能跟"项目在哪"无关。
            settings.setLabelEnumPath(SaveToAssetsFlow.toProjectRelative(projectDir, input.trim()))
            enumPathDecided = true
        }
        val targetFolder = java.nio.file.Paths.get(projectDir, selectedTarget.replace("/", java.io.File.separator)).toString()

        val wantsEnum = com.intellij.openapi.ui.Messages.showYesNoDialog(
            project,
            OkScriptToolkitBundle.message("templateAsset.exportEnumPrompt"),
            OkScriptToolkitBundle.message("templateAsset.export"),
            com.intellij.openapi.ui.Messages.getQuestionIcon(),
        ) == com.intellij.openapi.ui.Messages.YES

        var enumPath: String? = null
        if (wantsEnum) {
            enumPath = effectiveEnumPath()
            // **已经有生效路径就不再问**（那是用户自己定的、或团队约定好的值，
            // 每次导出都确认一遍是纯噪音）。要改的话走目标列表里的「修改路径」项 ——
            // 那个入口只在跳过弹框时出现，所以"跳过"与"还能改"这两件事永远同时成立。
            if (SaveToAssetsFlow.needsEnumPathPrompt(enumPath, enumPathDecided)) {
                val input = com.intellij.openapi.ui.Messages.showInputDialog(
                    project,
                    OkScriptToolkitBundle.message("templateAsset.exportEnumPathPrompt"),
                    OkScriptToolkitBundle.message("templateAsset.exportEnumTitle"),
                    com.intellij.openapi.ui.Messages.getInformationIcon(),
                    java.nio.file.Paths.get(targetFolder, "LabelEnum.py").toString(),
                    null,
                ) ?: return
                val trimmed = input.trim()
                if (trimmed.isNotEmpty()) {
                    settings.setLabelEnumPath(SaveToAssetsFlow.toProjectRelative(projectDir, trimmed))
                    enumPath = trimmed
                }
            }
        }
        // 路径为空 = **不生成枚举**（用户留空跳过）。必须显式判断：
        // `saveToAssets` 内部是 `enumPath ?: 默认路径`，空串不是 null，
        // 会一路传到 `File("")` 上 —— 那是个 FileNotFoundException，报错还看不出原因。
        val generateEnum = wantsEnum && !enumPath.isNullOrBlank()
        // 覆盖已有枚举文件、且**类名会变**时先问一句。这是唯一一处"个人覆盖能把项目弄坏"的地方：
        // 项目的代码按类名 import（`from src.data.feature_list import FeatureList`），
        // 改名之后那些 import 全部 ImportError，而导出成功的提示照样会弹出来。
        if (generateEnum && !confirmLabelEnumRename(project, projectDir, enumPath!!)) return

        statusLabel.text = OkScriptToolkitBundle.message("templateAsset.exportRunning")
        progressBar.isIndeterminate = true
        progressBar.isVisible = true

        com.intellij.openapi.progress.ProgressManager.getInstance().run(
            object : com.intellij.openapi.progress.Task.Backgroundable(
                project,
                OkScriptToolkitBundle.message("templateAsset.export"),
                // 对齐 VSCode 版：导出可取消（已完成分页保留，仅中止后续合成与 COCO 重写）
                true,
            ) {
                override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                    try {
                        data.load(
                            projectDir,
                            OkScriptToolkitSettings.getInstance(project).okTemplatesDirectory(),
                        )
                        data.saveToAssets(targetFolder, generateEnum, enumPath) { done, total ->
                            indicator.checkCanceled()
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
                    } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
                        throw e
                    } catch (e: CancellationException) {
                        com.intellij.notification.NotificationGroupManager.getInstance()
                            .getNotificationGroup("okScriptToolkit")
                            .createNotification(
                                OkScriptToolkitBundle.message("templateAsset.exportCancelled"),
                                NotificationType.WARNING,
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
            .getToolWindow(TemplateAssetPanel.TOOL_WINDOW_ID)
            ?.show()
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

/**
 * 快捷键入口：打开标注模板管理面板并**立即截图**（默认 `Ctrl+Alt+S` / macOS `Cmd+Alt+S`）。
 *
 * 与 [ShowTemplateAssetsAction] 的区别只在"顺手截图"这一步 —— 复用的是面板自己的
 * [TemplateAssetPanel.screenshotNow]，**不新增截图实现**。
 *
 * 键位在 `plugin.xml` 里给默认值，用户可在「设置 → 按键映射」里改：
 * 键位是**个人偏好**，不进项目约定文件（见 `docs/project-config.md` §6.4）。
 */
class ScreenshotToTemplateAssetsAction : AnAction() {
    init {
        // 文案走 bundle（i18n 铁律：不硬编码在业务代码里）；plugin.xml 只声明 id / class / 键位
        templatePresentation.text = OkScriptToolkitBundle.message("action.screenshotToTemplate.text")
        templatePresentation.description = OkScriptToolkitBundle.message("action.screenshotToTemplate.description")
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindow = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            .getToolWindow(TemplateAssetPanel.TOOL_WINDOW_ID) ?: return
        toolWindow.show()
        // 面板还没被创建过时 content 为空 —— show() 之后由 ToolWindowFactory 建好，所以这时能拿到
        val content = toolWindow.contentManager.contents.firstOrNull() ?: return
        content.getUserData(TemplateAssetPanel.PANEL_KEY)?.screenshotNow()
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
