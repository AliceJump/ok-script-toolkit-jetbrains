package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.alicejump.okscripttoolkit.core.ScreenshotCapture
import com.alicejump.okscripttoolkit.core.TempShot
import com.alicejump.okscripttoolkit.core.TempShotFiles
import com.alicejump.okscripttoolkit.core.TempScreenshotStore
import com.alicejump.okscripttoolkit.settings.OkScriptToolkitSettings
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.Image
import java.awt.Point
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JToggleButton
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.TransferHandler

/**
 * 临时截图工具窗口（对齐 VSCode 版 tempScreenshots 侧边栏）：
 * - 最多保留 [TempShotFiles.MAX_SHOTS] 张，超出淘汰最早；落盘在 IDE system 目录按项目隔离
 * - 入列：粘贴系统剪贴板图片、截取游戏窗口、粘贴/拖入图片文件
 * - 舞台：适配显示 + 滚轮缩放 + 中键/空白拖拽平移 + 右键复位
 * - **0.1s 轮播**：[CAROUSEL_INTERVAL_MS] 毫秒切换帧。轮播与框选相互独立——框选期间
 *   轮播继续播放，选框松手后保留在原位，便于对着带移动界限的目标反复比对微调
 * - 框选复制归一化坐标 x,y,tox,toy（[NormalizedBox]）
 * - 拖拽源：缩略图可拖到「ok-script Assets」直接导入 ok_templates
 *
 * 舞台使用降采样预览（[PREVIEW_MAX_HEIGHT]）而非原图：多张 4K 截图全量解码可达数百 MB，
 * 而归一化坐标只取比例，精度不受影响。
 */
class TempScreenshotToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = TempScreenshotPanel(project)
        val content = ContentFactory.getInstance().createContent(panel.mainPanel, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

class TempScreenshotPanel(private val project: Project) : Disposable {

    companion object {
        private const val CAROUSEL_INTERVAL_MS = 100
        private const val PREVIEW_MAX_HEIGHT = 720
        private const val PREVIEW_MAX_WIDTH = 1280
        private const val THUMB_HEIGHT = 72
        private const val GRID_GAP = 4
        private const val GRID_COLS = 3
        private const val ZOOM_MAX = 20.0
        private const val ZOOM_STEP = 1.1

        private val COORD_COLOR = JBColor(0xE8A33D, 0xFFB454)
    }

    val mainPanel = JPanel(BorderLayout())

    private val store = TempScreenshotStore.getInstance(project)
    private val stage = TempShotStage()
    private val gridPanel = JPanel(GridLayout(0, GRID_COLS, GRID_GAP, GRID_GAP))
    private val statusLabel = JBLabel()
    private val carouselToggle = JToggleButton(msg("tempShots.carousel"))
    private val coordToggle = JToggleButton(msg("tempShots.coordMode"))

    private val pasteButton = JButton(msg("tempShots.paste"))
    private val captureButton = JButton(msg("tempShots.capture"))
    private val clearButton = JButton(msg("tempShots.clear"))

    /** 降采样预览缓存：id → 预览图（舞台与轮播使用，避免常驻 4K 原图） */
    private val previews = ConcurrentHashMap<String, BufferedImage>()
    private var shots: List<TempShot> = emptyList()
    private var generation = 0
    private var carouselTimer: Timer? = null

    private val storeSubscription: Disposable = store.onChange {
        UIUtil.invokeLaterIfNeeded { reloadAsync() }
    }

    init {
        buildUI()
        reloadAsync()
    }

    // ── UI ─────────────────────────────────────────────────────────

    private fun buildUI() {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        pasteButton.toolTipText = msg("tempShots.pasteTooltip")
        captureButton.toolTipText = msg("tempShots.captureTooltip")
        clearButton.toolTipText = msg("tempShots.clearTooltip")
        carouselToggle.toolTipText = msg("tempShots.carouselTooltip")
        coordToggle.toolTipText = msg("tempShots.coordTooltip")
        for (b in listOf(pasteButton, captureButton, clearButton, carouselToggle, coordToggle)) {
            b.isFocusable = false
            toolbar.add(b)
        }
        pasteButton.addActionListener { handlePaste() }
        captureButton.addActionListener { handleCapture() }
        clearButton.addActionListener { handleClear() }
        carouselToggle.addActionListener { setCarousel(carouselToggle.isSelected) }
        coordToggle.addActionListener { stage.setCoordMode(coordToggle.isSelected) }

        gridPanel.border = BorderFactory.createEmptyBorder(GRID_GAP, GRID_GAP, GRID_GAP, GRID_GAP)
        val gridScroll = JBScrollPane(gridPanel)
        gridScroll.preferredSize = Dimension(0, 200)

        statusLabel.border = BorderFactory.createEmptyBorder(2, 4, 2, 4)
        statusLabel.font = statusLabel.font.deriveFont(11f)

        // 网格与状态栏合并放在 SOUTH（BorderLayout 同一位置只能挂一个组件）
        val bottom = JPanel(BorderLayout())
        bottom.add(gridScroll, BorderLayout.CENTER)
        bottom.add(statusLabel, BorderLayout.SOUTH)

        stage.preferredSize = Dimension(0, 200)
        mainPanel.add(toolbar, BorderLayout.NORTH)
        mainPanel.add(stage, BorderLayout.CENTER)
        mainPanel.add(bottom, BorderLayout.SOUTH)
    }

    // ── 数据加载 ───────────────────────────────────────────────────

    private fun reloadAsync() {
        val gen = ++generation
        val current = store.list()
        shots = current
        if (current.isEmpty()) {
            previews.clear()
            stage.setFrames(emptyList())
            renderGrid()
            stage.showIndex(-1)
            statusLabel.text = msg("tempShots.count", 0, TempShotFiles.MAX_SHOTS)
            return
        }
        statusLabel.text = msg("tempShots.loading", current.size)
        CompletableFuture.supplyAsync {
            current.map { shot -> shot.id to loadPreview(shot.file) }
        }.thenAccept { loaded ->
            SwingUtilities.invokeLater {
                if (gen != generation) return@invokeLater
                val wanted = current.map { it.id }.toSet()
                previews.keys.retainAll(wanted)
                for ((id, image) in loaded) {
                    if (image != null) previews[id] = image
                }
                renderGrid()
                stage.setFrames(shots.map { previews[it.id] })
                stage.showIndex(shots.lastIndex)
                statusLabel.text = msg("tempShots.count", shots.size, TempShotFiles.MAX_SHOTS)
            }
        }
    }

    private fun loadPreview(file: File): BufferedImage? {
        val original: BufferedImage = try {
            ImageIO.read(file) ?: return null
        } catch (_: Exception) {
            return null
        }
        val scale = minOf(
            PREVIEW_MAX_HEIGHT.toDouble() / original.height,
            PREVIEW_MAX_WIDTH.toDouble() / original.width,
            1.0,
        )
        if (scale >= 1.0) return original
        val w = (original.width * scale).toInt().coerceAtLeast(1)
        val h = (original.height * scale).toInt().coerceAtLeast(1)
        val scaled = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = scaled.createGraphics()
        g.drawImage(original, 0, 0, w, h, null)
        g.dispose()
        return scaled
    }

    // ── 缩略图网格 ─────────────────────────────────────────────────

    private fun renderGrid() {
        gridPanel.removeAll()
        if (shots.isEmpty()) {
            val hint = JBLabel(msg("tempShots.empty"))
            hint.horizontalAlignment = SwingConstants.CENTER
            gridPanel.add(hint)
            gridPanel.revalidate()
            gridPanel.repaint()
            return
        }
        for ((index, shot) in shots.withIndex()) {
            gridPanel.add(createShotCard(shot, index))
        }
        gridPanel.revalidate()
        gridPanel.repaint()
    }

    private fun createShotCard(shot: TempShot, index: Int): JPanel {
        val card = JPanel(BorderLayout())
        card.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor.border()),
            BorderFactory.createEmptyBorder(2, 2, 2, 2),
        )
        card.toolTipText = "${shot.name}<br>${msg("tempShots.dragHint")}".let {
            "<html>$it</html>"
        }

        val preview = previews[shot.id]
        val iconLabel = JBLabel()
        iconLabel.horizontalAlignment = SwingConstants.CENTER
        iconLabel.preferredSize = Dimension(0, THUMB_HEIGHT)
        if (preview != null) {
            val scale = THUMB_HEIGHT.toDouble() / preview.height
            val w = (preview.width * scale).toInt().coerceAtLeast(1)
            iconLabel.icon = ImageIcon(preview.getScaledInstance(w, THUMB_HEIGHT, Image.SCALE_SMOOTH))
        }
        card.add(iconLabel, BorderLayout.CENTER)

        val nameLabel = JBLabel(shot.name)
        nameLabel.horizontalAlignment = SwingConstants.CENTER
        nameLabel.font = nameLabel.font.deriveFont(9f)
        card.add(nameLabel, BorderLayout.SOUTH)

        card.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 1) {
                    setCarousel(false)
                    stage.showIndex(index)
                }
            }
            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) showCardMenu(e, shot)
            }
            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) showCardMenu(e, shot)
            }
        })
        installDragSource(card, shot)
        return card
    }

    /** 拖拽源：把临时截图文件传给「ok-script Assets」工具窗口 */
    private fun installDragSource(card: JPanel, shot: TempShot) {
        card.transferHandler = object : TransferHandler() {
            override fun getSourceActions(c: JComponent?): Int = COPY
            override fun createTransferable(c: JComponent?): Transferable = TempShotTransferable(shot.file)
        }
        var armed = false
        card.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                armed = SwingUtilities.isLeftMouseButton(e)
            }
            override fun mouseReleased(e: MouseEvent) {
                armed = false
            }
        })
        card.addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                if (!armed) return
                armed = false
                card.transferHandler?.exportAsDrag(card, e, TransferHandler.COPY)
            }
        })
    }

    private fun showCardMenu(e: MouseEvent, shot: TempShot) {
        val popup = JPopupMenu()
        popup.add(JMenuItem(msg("tempShots.send")).apply {
            addActionListener { handleSendToAssets(shot) }
        })
        popup.add(JMenuItem(msg("tempShots.delete")).apply {
            addActionListener { store.remove(shot.id) }
        })
        popup.show(e.component, e.x, e.y)
    }

    // ── 动作 ───────────────────────────────────────────────────────

    private fun handlePaste() {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val image: java.awt.Image? = try {
            if (clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) {
                clipboard.getData(DataFlavor.imageFlavor) as? java.awt.Image
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
        if (image != null) {
            val added = store.writeImage(toBufferedImage(image))
            if (added == null) notify("tempShots.saveFailed", NotificationType.ERROR)
            return
        }
        // 回退：剪贴板里的文件列表
        val files: List<File>? = try {
            if (clipboard.isDataFlavorAvailable(DataFlavor.javaFileListFlavor)) {
                @Suppress("UNCHECKED_CAST")
                (clipboard.getData(DataFlavor.javaFileListFlavor) as? List<File>)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
        if (files != null) {
            for (file in files) importShotFile(file)
            return
        }
        notify("tempShots.pasteNoImage", NotificationType.WARNING)
    }

    private fun toBufferedImage(image: java.awt.Image): BufferedImage {
        if (image is BufferedImage) return image
        val w = image.getWidth(null).coerceAtLeast(1)
        val h = image.getHeight(null).coerceAtLeast(1)
        val buffered = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = buffered.createGraphics()
        g.drawImage(image, 0, 0, null)
        g.dispose()
        return buffered
    }

    /** 把外部图片文件复制进临时截图区 */
    private fun importShotFile(file: File) {
        if (!file.isFile) return
        val target = store.newFilePath()
        try {
            Files.copy(file.toPath(), target.toPath())
            store.register(target)
        } catch (_: Exception) {
            notify("tempShots.saveFailed", NotificationType.ERROR)
        }
    }

    private fun handleCapture() {
        captureButton.isEnabled = false
        statusLabel.text = msg("tempShots.capturing")
        val outputPath = store.newFilePath()
        CompletableFuture.supplyAsync {
            ScreenshotCapture(project).captureInteractive(outputPath.toPath()) { config ->
                statusLabel.text = msg("tempShots.detected", config.describe())
            }
        }.thenAccept { error ->
            SwingUtilities.invokeLater {
                captureButton.isEnabled = true
                when {
                    error == null -> {
                        store.register(outputPath)
                        statusLabel.text = msg("tempShots.captureSaved", outputPath.name)
                    }
                    error == ScreenshotCapture.CANCELLED -> statusLabel.text = " "
                    else -> notify("tempShots.captureFailed", NotificationType.ERROR, error)
                }
            }
        }
    }

    private fun handleClear() {
        if (shots.isEmpty()) return
        val confirm = JOptionPane.showConfirmDialog(
            mainPanel,
            msg("tempShots.clearConfirm"),
            msg("tempShots.clear"),
            JOptionPane.YES_NO_OPTION,
        )
        if (confirm != JOptionPane.YES_OPTION) return
        setCarousel(false)
        store.clear()
    }

    /** 与卡片上的「发送到标注管理」菜单等价：把临时截图复制进 ok_templates 并登记 COCO */
    private fun handleSendToAssets(shot: TempShot) {
        val settings = OkScriptToolkitSettings.getInstance(project)
        val projectDir = project.basePath ?: return
        val targetDir = File(projectDir, settings.okTemplatesDirectory())
        val data = project.service<com.alicejump.okscripttoolkit.core.TemplateAssetDataService>()
        CompletableFuture.supplyAsync {
            data.importImages(listOf(shot.file), targetDir)
        }.thenAccept { imported ->
            SwingUtilities.invokeLater {
                if (imported <= 0) {
                    notify("tempShots.sendFailed", NotificationType.ERROR, shot.name)
                } else {
                    notify("tempShots.sent", NotificationType.INFORMATION, shot.name)
                }
            }
        }
    }

    private fun setCarousel(enabled: Boolean) {
        carouselTimer?.stop()
        carouselTimer = null
        if (enabled && shots.isNotEmpty()) {
            carouselTimer = Timer(CAROUSEL_INTERVAL_MS) { stage.showNext() }.also { it.start() }
        }
        if (carouselToggle.isSelected != enabled) carouselToggle.isSelected = enabled
    }

    private fun notify(key: String, type: NotificationType, vararg args: Any) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("okScriptToolkit")
            .createNotification(msg(key, *args), type)
            .notify(project)
    }

    private fun msg(key: String, vararg args: Any): String =
        OkScriptToolkitBundle.message(key, *args)

    override fun dispose() {
        carouselTimer?.stop()
        carouselTimer = null
        storeSubscription.dispose()
    }

    // ── 舞台 ───────────────────────────────────────────────────────

    /** 图像坐标系下的矩形（用于保留选框，缩放/平移后重绘仍然一致） */
    private data class ImageRect(val x: Double, val y: Double, val w: Double, val h: Double)

    private inner class TempShotStage : JComponent() {

        private var frames: List<BufferedImage?> = emptyList()
        private var index = -1

        private var fitScale = 1.0
        private var scale = 1.0
        private var offsetX = 0.0
        private var offsetY = 0.0

        private var coordMode = false
        private var boxStart: Point? = null
        private var boxCurrent: Point? = null
        private var committed: ImageRect? = null

        private var panning = false
        private var panStart: Point? = null
        private var panStartOffset: Pair<Double, Double>? = null

        init {
            isOpaque = true
            background = UIUtil.getPanelBackground()
            cursor = Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
            installMouseListeners()
        }

        private fun current(): BufferedImage? = frames.getOrNull(index)

        fun setFrames(list: List<BufferedImage?>) {
            frames = list
            if (frames.isEmpty()) {
                index = -1
            } else if (index !in 0 until frames.size) {
                index = frames.lastIndex
            }
            committed?.let { rect ->
                val img = current()
                if (img != null && (rect.x + rect.w > img.width || rect.y + rect.h > img.height)) committed = null
            }
            recalcFit()
            scale = fitScale
            recalcOffset()
            repaint()
        }

        fun showIndex(next: Int) {
            index = if (frames.isEmpty()) -1 else next.coerceIn(0, frames.lastIndex)
            repaint()
        }

        fun showNext() {
            if (frames.isEmpty()) return
            showIndex((index + 1) % frames.size)
        }

        fun setCoordMode(enabled: Boolean) {
            coordMode = enabled
            boxStart = null
            boxCurrent = null
            if (!enabled) committed = null
            repaint()
        }

        private fun resetView() {
            recalcFit()
            scale = fitScale
            recalcOffset()
            repaint()
        }

        // ── 坐标 ──

        private fun toImage(p: Point): Pair<Double, Double> =
            (p.x - offsetX) / scale to (p.y - offsetY) / scale

        private fun recalcFit() {
            val img = current()
            if (img == null || width <= 0 || height <= 0) {
                fitScale = 1.0
                return
            }
            fitScale = minOf(width.toDouble() / img.width, height.toDouble() / img.height).coerceAtLeast(0.001)
        }

        private fun recalcOffset() {
            val img = current() ?: return
            val sw = img.width * scale
            val sh = img.height * scale
            offsetX = if (sw <= width) (width - sw) / 2 else offsetX.coerceIn((width - sw).coerceAtMost(0.0), 0.0)
            offsetY = if (sh <= height) (height - sh) / 2 else offsetY.coerceIn((height - sh).coerceAtMost(0.0), 0.0)
        }

        private fun isZoomed(): Boolean {
            val img = current() ?: return false
            return img.width * scale > width || img.height * scale > height
        }

        // ── 鼠标 ──

        private fun installMouseListeners() {
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    requestFocusInWindow()
                    when {
                        SwingUtilities.isRightMouseButton(e) -> resetView()
                        SwingUtilities.isMiddleMouseButton(e) -> startPan(e.point)
                        SwingUtilities.isLeftMouseButton(e) -> {
                            if (coordMode) {
                                boxStart = e.point
                                boxCurrent = e.point
                            } else if (isZoomed()) {
                                startPan(e.point)
                            }
                        }
                    }
                    repaint()
                }

                override fun mouseReleased(e: MouseEvent) {
                    if (panning) {
                        panning = false
                        panStart = null
                        panStartOffset = null
                        repaint()
                        return
                    }
                    if (coordMode && SwingUtilities.isLeftMouseButton(e)) {
                        val start = boxStart
                        if (start != null) finishBox(start, e.point)
                    }
                }
            })

            addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
                override fun mouseDragged(e: MouseEvent) {
                    if (panning) {
                        val start = panStart ?: return
                        val origin = panStartOffset ?: return
                        offsetX = origin.first + (e.point.x - start.x)
                        offsetY = origin.second + (e.point.y - start.y)
                        recalcOffset()
                        repaint()
                        return
                    }
                    if (coordMode && boxStart != null) {
                        boxCurrent = e.point
                        repaint()
                    }
                }
            })

            addMouseWheelListener { e: MouseWheelEvent -> onWheel(e) }
        }

        private fun startPan(p: Point) {
            panning = true
            panStart = p
            panStartOffset = offsetX to offsetY
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }

        private fun onWheel(e: MouseWheelEvent) {
            val img = current() ?: return
            val p = e.point
            val (ix, iy) = toImage(p)
            val factor = if (e.wheelRotation < 0) ZOOM_STEP else 1 / ZOOM_STEP
            scale = (scale * factor).coerceAtMost(ZOOM_MAX).coerceAtLeast(fitScale)
            offsetX = p.x - ix * scale
            offsetY = p.y - iy * scale
            recalcOffset()
            repaint()
        }

        /** 完成框选：写入剪贴板 + 保留选框（轮播继续，便于对照运动中的目标） */
        private fun finishBox(start: Point, end: Point) {
            val img = current()
            boxStart = null
            boxCurrent = null
            if (img == null) {
                repaint()
                return
            }
            val a = toImage(start)
            val b = toImage(end)
            val text = NormalizedBox.format(a.first, a.second, b.first, b.second, img.width, img.height)
            if (text.isEmpty()) {
                committed = null
                repaint()
                return
            }
            committed = ImageRect(
                minOf(a.first, b.first),
                minOf(a.second, b.second),
                Math.abs(b.first - a.first),
                Math.abs(b.second - a.second),
            )
            CopyPasteManager.getInstance().setContents(StringSelection(text))
            // 只在状态栏提示：连续微调时不弹气球通知（VSCode 版是 toast，这里是常驻状态栏）
            statusLabel.text = "${msg("tempShots.coordLabel")} $text"
            repaint()
        }

        // ── 绘制 ──

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g as Graphics2D
            g2.background = background
            g2.clearRect(0, 0, width, height)

            val img = current()
            if (img == null) {
                g2.color = JBColor.foreground()
                val text = msg("tempShots.noImage")
                g2.drawString(text, (width - g2.fontMetrics.stringWidth(text)) / 2, height / 2)
                return
            }

            val drawW = (img.width * scale).toInt()
            val drawH = (img.height * scale).toInt()
            g2.drawImage(img, offsetX.toInt(), offsetY.toInt(), drawW, drawH, null)

            // 保留的选框（图像坐标 → 屏幕坐标）
            committed?.let { rect ->
                g2.color = COORD_COLOR
                g2.stroke = BasicStroke(1.5f)
                drawImageRect(g2, rect)
            }

            // 拖拽中的预览框
            val start = boxStart
            val current = boxCurrent
            if (coordMode && start != null && current != null) {
                val a = toImage(start)
                val b = toImage(current)
                g2.color = COORD_COLOR
                g2.stroke = BasicStroke(1.5f, 0, 0, 8f, floatArrayOf(6f, 6f), 0f)
                drawImageRect(
                    g2,
                    ImageRect(
                        minOf(a.first, b.first),
                        minOf(a.second, b.second),
                        Math.abs(b.first - a.first),
                        Math.abs(b.second - a.second),
                    ),
                )
            }
        }

        private fun drawImageRect(g2: Graphics2D, rect: ImageRect) {
            g2.drawRect(
                (offsetX + rect.x * scale).toInt(),
                (offsetY + rect.y * scale).toInt(),
                (rect.w * scale).toInt(),
                (rect.h * scale).toInt(),
            )
        }
    }
}

class ShowTempShotsAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ToolWindowManager.getInstance(project)
            .getToolWindow("ok-script Temp Shots")
            ?.show()
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
