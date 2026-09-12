package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.alicejump.okscripttoolkit.core.CocoAnnotation
import com.alicejump.okscripttoolkit.core.CocoCategory
import com.alicejump.okscripttoolkit.core.TemplateAssetDataService
import com.alicejump.okscripttoolkit.core.TemplateImage
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.BasicStroke
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.Point
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

/**
 * COCO 标注编辑器（对齐 VSCode 版 annotationPanel 的强化编辑能力）：
 * - 画框模式（拖拽 / 两次点击）与删除模式（R/D 或工具栏切换）
 * - 点击选中、拖拽移动，8 向边缘手柄调整大小（边界钳制在图像内）
 * - 撤销/重做（100 层；无实际改动自动回退）
 * - Ctrl+C/V 复制粘贴框（粘贴偏移 10px）、右键复制分类名到系统剪贴板
 * - 滚轮以鼠标为中心缩放（上限 50x），放大后拖拽空白处平移
 * - 像素信息条：RGB + 绝对坐标 + 相对比例坐标（ok-script 框选取需要的 Rel 坐标）
 * - 双击编辑框（分类 + x/y/w/h 数值微调），分类名全项目唯一性校验
 * - ←/→ 跨图导航（imageList）：每张图的编辑保留在会话内，
 *   OK 一次性把全部改动图写回 coco_annotations.json，Cancel 全部放弃。
 * 与 VSCode 版（逐操作自动落盘的常驻面板）不同，这里遵循 IDE 模态对话框的
 * OK/Cancel 语义，落盘时机收敛到 OK。
 */
class AnnotationDialog(
    private val project: Project,
    private val data: TemplateAssetDataService,
    private val image: TemplateImage,
    /** ←/→ 可切换的图片集合（通常是素材面板当前过滤结果） */
    private val imageList: List<TemplateImage> = listOf(image),
    startIndex: Int = 0,
) : DialogWrapper(project) {

    companion object {
        private val LOG = Logger.getInstance(AnnotationDialog::class.java)
        private const val MAX_UNDO = 100
        private const val ZOOM_MAX = 50.0
        private const val ZOOM_STEP = 1.1
        private const val EDGE_MARGIN = 8.0
        private const val MIN_RESIZE = 5
        private const val MIN_DRAW = 3

        // 语义色：红=普通框、蓝=选中、橙=悬停、绿=手柄/预览；深浅主题分别取对比度合适的值
        private val BOX_COLOR = JBColor(0xE53935, 0xFF5252)
        private val SELECTED_COLOR = JBColor(0x0078D4, 0x4A9EFF)
        private         val HOVER_COLOR = JBColor(0xE08700, 0xFFA02E)
        private val HANDLE_COLOR = JBColor(0x009900, 0x00C800)
        // 坐标复制模式的预览框（与画框模式的绿色区分）
        private val COORD_COLOR = JBColor(0xE8A33D, 0xFFB454)
    }

    private val canvas = AnnotationCanvas()
    private val modeDrawToggle = JToggleButton(OkScriptToolkitBundle.message("annotation.mode.draw"))
    private val modeCoordToggle = JToggleButton(OkScriptToolkitBundle.message("annotation.mode.coords"))
    private val modeDeleteToggle = JToggleButton(OkScriptToolkitBundle.message("annotation.mode.delete"))
    private val undoButton = JButton(OkScriptToolkitBundle.message("annotation.undo"))
    private val redoButton = JButton(OkScriptToolkitBundle.message("annotation.redo"))
    private val prevButton = JButton(OkScriptToolkitBundle.message("annotation.prev"))
    private val nextButton = JButton(OkScriptToolkitBundle.message("annotation.next"))
    private val navLabel = JBLabel()
    private val colorLabel = JBLabel(" ")
    private val hintLabel = JBLabel(OkScriptToolkitBundle.message("annotation.hint"))

    /** 每张图一份编辑会话（导航后保留，OK 时统一写回改动过的图） */
    private inner class ImageSession(
        val fileName: String,
        val boxes: MutableList<BoxItem>,
        var cocoImageId: Int,
        var newSize: Pair<Int, Int>?,
        val undo: ArrayDeque<List<BoxItem>> = ArrayDeque(),
        val redo: ArrayDeque<List<BoxItem>> = ArrayDeque(),
        var dirty: Boolean = false,
    )

    private var session: ImageSession? = null
    private val sessionByFile = mutableMapOf<String, ImageSession>()
    private var currentIndex = startIndex.coerceIn(0, (imageList.size - 1).coerceAtLeast(0))
    private var loading = false

    init {
        title = OkScriptToolkitBundle.message("annotation.title", currentImage.name)
        setOKButtonText(OkScriptToolkitBundle.message("annotation.save"))
        setCancelButtonText(OkScriptToolkitBundle.message("annotation.cancel"))
        init()
        loadImage(currentIndex)
        SwingUtilities.invokeLater { canvas.requestFocusInWindow() }
    }

    private val currentImage: TemplateImage
        get() = imageList.getOrNull(currentIndex) ?: image

    override fun createCenterPanel(): JComponent {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        modeDrawToggle.toolTipText = OkScriptToolkitBundle.message("annotation.mode.drawTooltip")
        modeCoordToggle.toolTipText = OkScriptToolkitBundle.message("annotation.mode.coordsTooltip")
        modeDeleteToggle.toolTipText = OkScriptToolkitBundle.message("annotation.mode.deleteTooltip")
        modeDrawToggle.isFocusable = false
        modeCoordToggle.isFocusable = false
        modeDeleteToggle.isFocusable = false
        modeDrawToggle.addActionListener { canvas.setMode(if (modeDrawToggle.isSelected) CanvasMode.DRAW else CanvasMode.NONE) }
        modeCoordToggle.addActionListener { canvas.setMode(if (modeCoordToggle.isSelected) CanvasMode.COPYCOORD else CanvasMode.NONE) }
        modeDeleteToggle.addActionListener { canvas.setMode(if (modeDeleteToggle.isSelected) CanvasMode.DELETE else CanvasMode.NONE) }
        toolbar.add(modeDrawToggle)
        toolbar.add(modeCoordToggle)
        toolbar.add(modeDeleteToggle)
        undoButton.isFocusable = false
        redoButton.isFocusable = false
        prevButton.isFocusable = false
        nextButton.isFocusable = false
        undoButton.addActionListener { canvas.undo() }
        redoButton.addActionListener { canvas.redo() }
        prevButton.addActionListener { navigate(-1) }
        nextButton.addActionListener { navigate(1) }
        toolbar.add(undoButton)
        toolbar.add(redoButton)
        toolbar.add(prevButton)
        toolbar.add(nextButton)
        toolbar.add(navLabel)

        val canvasWrap = JPanel(BorderLayout())
        canvasWrap.add(canvas, BorderLayout.CENTER)
        canvas.preferredSize = Dimension(900, 560)
        canvas.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) = canvas.onCanvasResized()
        })

        val root = JPanel(BorderLayout(0, 4))
        root.add(toolbar, BorderLayout.NORTH)
        root.add(canvasWrap, BorderLayout.CENTER)

        colorLabel.font = colorLabel.font.deriveFont(Font.PLAIN, 11f)
        hintLabel.font = hintLabel.font.deriveFont(Font.PLAIN, 10f)
        hintLabel.foreground = UIUtil.getContextHelpForeground()
        // 长文案用 HTML 固定宽度换行，避免把对话框撑宽
        hintLabel.text = "<html><body style='width:660px'>" +
            OkScriptToolkitBundle.message("annotation.hint") + "</body></html>"
        val footer = JPanel(GridLayout(0, 1))
        footer.border = BorderFactory.createEmptyBorder(4, 4, 0, 4)
        footer.add(colorLabel)
        footer.add(hintLabel)
        root.add(footer, BorderLayout.SOUTH)
        return root
    }

    private fun loadImage(index: Int) {
        if (loading) return
        stashCurrent()
        currentIndex = index
        val target = currentImage
        title = OkScriptToolkitBundle.message("annotation.title", target.name)
        loading = true
        updateNav()
        colorLabel.text = " "
        Thread {
            // 简单 map 查找，放在 try 外供 catch 分支复用
            val cocoImage = data.getImageEntryForFile(target.file.name)
            try {
                val buffered = ImageIO.read(target.file)
                SwingUtilities.invokeLater {
                    loading = false
                    if (isDisposed) return@invokeLater
                    val fresh = ImageSession(
                        fileName = target.file.name,
                        boxes = mutableListOf<BoxItem>().also { list ->
                            val annotations: List<CocoAnnotation> =
                                cocoImage?.let { data.getAnnotationsForImage(it.id) } ?: emptyList()
                            val categories: List<CocoCategory> = data.categories()
                            annotations.forEach { ann ->
                                val name = categories.firstOrNull { it.id == ann.categoryId }?.name
                                    ?: "#${ann.categoryId}"
                                list.add(BoxItem(name, Rect(ann.bbox[0], ann.bbox[1], ann.bbox[2], ann.bbox[3])))
                            }
                        },
                        cocoImageId = cocoImage?.id ?: -1,
                        // 尚未注册进 COCO 的图（如新截图）也可标注：记住尺寸，保存时自动注册
                        newSize = buffered?.let { it.width to it.height },
                    )
                    // 已有会话（本对话框内编辑过/切回的图）优先于磁盘状态
                    val active = sessionByFile.getOrPut(target.file.name) { fresh }
                    canvas.applySession(active, buffered)
                    updateNav()
                }
            } catch (e: Exception) {
                LOG.warn("Failed to open annotation editor for ${target.name}", e)
                SwingUtilities.invokeLater {
                    loading = false
                    canvas.applySession(sessionByFile.getOrPut(target.file.name) {
                        ImageSession(target.file.name, mutableListOf(), cocoImage?.id ?: -1, null)
                    }, null)
                    updateNav()
                }
            }
        }.start()
    }

    private fun stashCurrent() {
        val s = session ?: return
        sessionByFile[s.fileName] = s
    }

    private fun navigate(delta: Int) {
        val next = currentIndex + delta
        if (next < 0 || next >= imageList.size) return
        loadImage(next)
    }

    private fun updateNav() {
        navLabel.text = "${currentImage.name} (${currentIndex + 1}/${imageList.size})"
        prevButton.isEnabled = currentIndex > 0 && !loading
        nextButton.isEnabled = currentIndex < imageList.size - 1 && !loading
    }

    override fun doOKAction() {
        try {
            stashCurrent()
            var changed = false
            for (s in sessionByFile.values) {
                if (!s.dirty) continue
                val id = if (s.cocoImageId < 0) {
                    val (w, h) = s.newSize ?: continue
                    data.addImageEntry(s.fileName, w, h).id.also { s.cocoImageId = it }
                } else {
                    s.cocoImageId
                }
                data.replaceAnnotationsForImage(
                    id,
                    s.boxes.map { box ->
                        data.getOrCreateCategory(box.categoryName).id to
                            intArrayOf(box.rect.x, box.rect.y, box.rect.w, box.rect.h)
                    },
                )
                changed = true
            }
            if (changed) data.save()
        } catch (e: Exception) {
            LOG.error("Failed to save annotations for ${currentImage.name}", e)
        }
        super.doOKAction()
    }

    /** 全项目分类名唯一性索引：分类名 -> 已占用它的文件名（不含当前图，对齐 VSCode 版校验语义） */
    private fun buildTakenCategories(currentFile: String): Map<String, String> {
        val categories = data.categories()
        val taken = mutableMapOf<String, String>()
        for (img in data.listImages()) {
            val name = img.file.name
            if (name == currentFile) continue
            val coco = data.getImageEntryForFile(name) ?: continue
            data.getAnnotationsForImage(coco.id).forEach { ann ->
                val categoryName = categories.firstOrNull { it.id == ann.categoryId }?.name ?: return@forEach
                taken.putIfAbsent(categoryName, name)
            }
        }
        return taken
    }

    // ── 数据模型 ──────────────────────────────────────────────────────

    /** 不可变矩形（undo 栈按值快照） */
    private data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {
        fun contains(px: Int, py: Int) = px >= x && px <= x + w && py >= y && py <= y + h
    }

    private data class BoxItem(val categoryName: String, val rect: Rect)

    /** 坐标框的调整拖拽：handle 为空表示整体移动 */
    private data class CoordDrag(val handle: String?, val start: Point, val orig: Rect)

    private enum class CanvasMode { NONE, DRAW, DELETE, COPYCOORD }

    // ── 画布 ──────────────────────────────────────────────────────────

    private inner class AnnotationCanvas : JComponent() {

        private var source: BufferedImage? = null
        private var fitScale = 1.0
        private var scale = 1.0
        private var offsetX = 0.0
        private var offsetY = 0.0

        private var mode = CanvasMode.NONE
        private var boxes: MutableList<BoxItem> = mutableListOf()
        private var sessionRef: ImageSession? = null

        private var selected = -1
        private var hovered = -1

        // 画框（拖拽 / 两次点击共用）
        private var drawStart: Point? = null
        private var drawPreview: Point? = null
        private var drawDragging = false

        // 坐标复制模式的临时框：只用于取坐标，不进 boxes、不落盘；
        // 创建与每次调整结束都会重新复制，点击非交互区域即清除
        private var coordBox: Rect? = null
        private var coordDrag: CoordDrag? = null

        // 拖拽移动
        private var dragging = false
        private var dragStartPos: Point? = null
        private var dragOrigRect: Rect? = null

        // 手柄调整大小
        private var resizing = false
        private var resizeHandle: String? = null
        private var resizeStartPos: Point? = null
        private var resizeOrigRect: Rect? = null

        // 平移
        private var panning = false
        private var panStartPos: Point? = null
        private var panStartOffset: Pair<Double, Double>? = null

        init {
            isFocusable = true
            isOpaque = true
            background = UIUtil.getPanelBackground()
            registerKeys()
            installMouseListeners()
        }

        fun applySession(newSession: ImageSession, buffered: BufferedImage?) {
            sessionRef = newSession
            session = newSession
            boxes = newSession.boxes
            source = buffered
            selected = -1
            hovered = -1
            setMode(CanvasMode.NONE, syncToggleOnly = true)
            if (buffered != null) {
                recalcFit()
                scale = fitScale
                recalcOffset()
            }
            syncButtons()
            cursor = Cursor.getDefaultCursor()
            repaint()
        }

        fun showLoadFailed() {
            source = null
            boxes = mutableListOf()
            repaint()
        }

        // ── 模式 ──

        fun setMode(m: CanvasMode, syncToggleOnly: Boolean = false) {
            mode = m
            drawStart = null
            drawPreview = null
            drawDragging = false
            // 坐标框只在坐标模式内存在，切换走即丢弃（它不落盘，无需保留）
            if (m != CanvasMode.COPYCOORD && (coordBox != null || coordDrag != null)) {
                coordBox = null
                coordDrag = null
                colorLabel.text = " "
            }
            if (!syncToggleOnly) {
                modeDrawToggle.isSelected = m == CanvasMode.DRAW
                modeCoordToggle.isSelected = m == CanvasMode.COPYCOORD
                modeDeleteToggle.isSelected = m == CanvasMode.DELETE
            } else {
                modeDrawToggle.isSelected = false
                modeCoordToggle.isSelected = false
                modeDeleteToggle.isSelected = false
            }
            cursor = when {
                m == CanvasMode.DRAW -> Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
                m == CanvasMode.COPYCOORD -> Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
                m == CanvasMode.DELETE -> Cursor.getDefaultCursor()
                isZoomed() -> Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                else -> Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
            }
            repaint()
        }

        // ── 撤销/重做 ──

        private fun pushUndo() {
            val s = sessionRef ?: return
            s.undo.addLast(boxes.toList())
            if (s.undo.size > MAX_UNDO) s.undo.removeFirst()
            s.redo.clear()
            s.dirty = true
        }

        private fun popUndo() {
            sessionRef?.undo?.removeLastOrNull()
        }

        fun undo() {
            val s = sessionRef ?: return
            val prev = s.undo.removeLastOrNull() ?: return
            s.redo.addLast(boxes.toList())
            boxes.clear()
            boxes.addAll(prev)
            selected = -1
            hovered = -1
            syncButtons()
            repaint()
        }

        fun redo() {
            val s = sessionRef ?: return
            val next = s.redo.removeLastOrNull() ?: return
            s.undo.addLast(boxes.toList())
            boxes.clear()
            boxes.addAll(next)
            selected = -1
            hovered = -1
            syncButtons()
            repaint()
        }

        private fun syncButtons() {
            val s = sessionRef
            undoButton.isEnabled = (s?.undo?.isNotEmpty() == true)
            redoButton.isEnabled = (s?.redo?.isNotEmpty() == true)
        }

        // ── 键盘 ──

        private fun registerKeys() {
            val inputMap = getInputMap(WHEN_FOCUSED)
            val actionMap = getActionMap()
            fun bind(stroke: KeyStroke, name: String, action: () -> Unit) {
                inputMap.put(stroke, name)
                actionMap.put(name, object : AbstractAction() {
                    override fun actionPerformed(e: ActionEvent?) = action()
                })
            }
            bind(KeyStroke.getKeyStroke(KeyEvent.VK_R, 0), "mode-draw") {
                setMode(if (mode == CanvasMode.DRAW) CanvasMode.NONE else CanvasMode.DRAW)
            }
            bind(KeyStroke.getKeyStroke(KeyEvent.VK_D, 0), "mode-delete") {
                setMode(if (mode == CanvasMode.DELETE) CanvasMode.NONE else CanvasMode.DELETE)
            }
            bind(KeyStroke.getKeyStroke(KeyEvent.VK_C, 0), "mode-coords") {
                setMode(if (mode == CanvasMode.COPYCOORD) CanvasMode.NONE else CanvasMode.COPYCOORD)
            }
            bind(KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK), "undo") { undo() }
            bind(KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK), "redo") { redo() }
            bind(
                KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK),
                "redo2",
            ) { redo() }
            bind(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK), "copy-box") { copySelected() }
            bind(KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK), "paste-box") { pasteClipboard() }
            bind(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "delete-selected") { deleteSelected() }
            bind(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "delete-selected2") { deleteSelected() }
            bind(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "prev-image") { navigate(-1) }
            bind(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "next-image") { navigate(1) }
        }

        // ── 鼠标 ──

        private fun installMouseListeners() {
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    requestFocusInWindow()
                    val p = e.point
                    if (SwingUtilities.isRightMouseButton(e)) {
                        val idx = hitBox(p)
                        if (idx >= 0) {
                            selected = idx
                            copyCategoryToClipboard(boxes[idx].categoryName)
                            repaint()
                        }
                        return
                    }
                    if (!SwingUtilities.isLeftMouseButton(e)) return

                    if (mode == CanvasMode.DRAW) {
                        if (drawStart == null) {
                            drawStart = p
                            drawDragging = true
                        } else {
                            finishDraw(p)
                        }
                        return
                    }
                    if (mode == CanvasMode.COPYCOORD) {
                        // 与标注框一致：先命中手柄，再命中框体，否则起手画新框
                        val handle = findCoordHandleAt(p)
                        when {
                            handle != null -> {
                                coordDrag = CoordDrag(handle, p, coordBox!!)
                                cursor = handleCursor(handle)
                                return
                            }
                            coordContains(p) -> {
                                coordDrag = CoordDrag(null, p, coordBox!!)
                                cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                                return
                            }
                            else -> {
                                drawStart = p
                                drawDragging = true
                            }
                        }
                        return
                    }
                    if (mode == CanvasMode.DELETE) {
                        val idx = hitBox(p)
                        if (idx >= 0) {
                            selected = idx
                            deleteSelected()
                        }
                        return
                    }

                    val (handleIdx, handle) = findHandleAt(p)
                    if (handle != null && handleIdx >= 0) {
                        selected = handleIdx
                        pushUndo()
                        resizing = true
                        resizeHandle = handle
                        resizeStartPos = p
                        resizeOrigRect = boxes[handleIdx].rect
                        repaint()
                        return
                    }

                    val idx = hitBox(p)
                    selected = idx
                    if (idx >= 0) {
                        pushUndo()
                        dragging = true
                        dragStartPos = p
                        dragOrigRect = boxes[idx].rect
                    } else if (isZoomed()) {
                        panning = true
                        panStartPos = p
                        panStartOffset = offsetX to offsetY
                        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    }
                    repaint()
                }

                override fun mouseReleased(e: MouseEvent) {
                    // 结束坐标框的移动 / 缩放：有变化才复制
                    val coordDragging = coordDrag
                    if (coordDragging != null) {
                        val changed = coordBox != coordDragging.orig
                        coordDrag = null
                        if (changed) copyCoordBox()
                        cursor = Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
                        repaint()
                        return
                    }
                    if (mode == CanvasMode.DRAW && drawDragging && drawStart != null) {
                        val start = drawStart!!
                        val distSq = (e.point.x - start.x).toDouble() * (e.point.x - start.x) +
                            (e.point.y - start.y).toDouble() * (e.point.y - start.y)
                        if (distSq > EDGE_MARGIN * EDGE_MARGIN) {
                            finishDraw(e.point)
                        }
                        drawDragging = false
                        return
                    }
                    if (mode == CanvasMode.COPYCOORD && drawDragging && drawStart != null) {
                        val start = drawStart!!
                        val distSq = (e.point.x - start.x).toDouble() * (e.point.x - start.x) +
                            (e.point.y - start.y).toDouble() * (e.point.y - start.y)
                        drawDragging = false
                        if (distSq > EDGE_MARGIN * EDGE_MARGIN) {
                            finishCoord(e.point)
                        } else {
                            // 几乎没移动 = 点了图片的非交互部分：清除坐标框
                            drawStart = null
                            drawPreview = null
                            clearCoordBox()
                        }
                        return
                    }
                    drawDragging = false
                    if (dragging && selected >= 0) {
                        val moved = dragOrigRect != null && boxes[selected].rect != dragOrigRect
                        dragging = false
                        dragStartPos = null
                        dragOrigRect = null
                        if (!moved) popUndo()
                    }
                    if (resizing && selected >= 0) {
                        val changed = resizeOrigRect != null && boxes[selected].rect != resizeOrigRect
                        resizing = false
                        resizeStartPos = null
                        resizeOrigRect = null
                        resizeHandle = null
                        if (!changed) popUndo()
                    }
                    if (panning) {
                        panning = false
                        panStartPos = null
                        panStartOffset = null
                        cursor = if (isZoomed()) {
                            Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        } else {
                            Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
                        }
                    }
                    repaint()
                }

                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount != 2 || SwingUtilities.isRightMouseButton(e)) return
                    val idx = hitBox(e.point)
                    if (idx >= 0) {
                        selected = idx
                        showEditDialog(idx)
                    }
                }
            })

            addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
                override fun mouseDragged(e: MouseEvent) {
                    val p = e.point
                    // 调整坐标框：过程中只更新读数，松手时才复制
                    if (coordDrag != null) {
                        applyCoordDrag(p)
                        repaint()
                        return
                    }
                    if (mode == CanvasMode.DRAW && drawStart != null) {
                        drawPreview = p
                        repaint()
                        updateColorAt(p)
                        return
                    }
                    if (mode == CanvasMode.COPYCOORD && drawStart != null) {
                        drawPreview = p
                        updateCoordPreview(p)
                        repaint()
                        return
                    }
                    if (resizing && selected >= 0 && resizeStartPos != null) {
                        doResize(p)
                        repaint()
                        updateColorAt(p)
                        return
                    }
                    if (dragging && selected >= 0 && dragStartPos != null) {
                        val ann = boxes[selected]
                        val orig = dragOrigRect ?: ann.rect
                        val dx = (p.x - dragStartPos!!.x) / scale
                        val dy = (p.y - dragStartPos!!.y) / scale
                        var nx = orig.x + dx
                        var ny = orig.y + dy
                        source?.let {
                            nx = nx.coerceIn(0.0, (it.width - ann.rect.w).coerceAtLeast(0).toDouble())
                            ny = ny.coerceIn(0.0, (it.height - ann.rect.h).coerceAtLeast(0).toDouble())
                        }
                        boxes[selected] = ann.copy(rect = Rect(nx.toInt(), ny.toInt(), ann.rect.w, ann.rect.h))
                        repaint()
                        updateColorAt(p)
                        return
                    }
                    if (panning && panStartPos != null && panStartOffset != null) {
                        offsetX = panStartOffset!!.first + (p.x - panStartPos!!.x)
                        offsetY = panStartOffset!!.second + (p.y - panStartPos!!.y)
                        recalcOffset()
                        repaint()
                        return
                    }
                }

                override fun mouseMoved(e: MouseEvent) {
                    val p = e.point
                    if (mode == CanvasMode.NONE) {
                        val (handleIdx, handle) = findHandleAt(p)
                        if (handle != null && handleIdx >= 0) {
                            hovered = handleIdx
                            cursor = handleCursor(handle)
                        } else {
                            val idx = hitBox(p)
                            hovered = idx
                            cursor = when {
                                idx >= 0 -> Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                                isZoomed() -> Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                                else -> Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
                            }
                        }
                        repaint()
                    } else if (mode == CanvasMode.DELETE) {
                        hovered = hitBox(p)
                        cursor = if (hovered >= 0) {
                            Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        } else {
                            Cursor.getDefaultCursor()
                        }
                        repaint()
                    } else if (mode == CanvasMode.COPYCOORD) {
                        val handle = findCoordHandleAt(p)
                        cursor = when {
                            handle != null -> handleCursor(handle)
                            coordContains(p) -> Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                            else -> Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
                        }
                        repaint()
                    }
                    updateColorAt(p)
                }
            })

            addMouseWheelListener { e -> onWheel(e) }
        }

        // ── 复制/粘贴/删除 ──

        private var clipboardBox: BoxItem? = null

        private fun copySelected() {
            if (selected >= 0 && mode == CanvasMode.NONE) {
                clipboardBox = boxes[selected]
            }
        }

        private fun pasteClipboard() {
            val box = clipboardBox ?: return
            if (mode != CanvasMode.NONE) return
            pushUndo()
            val img = source
            var x = box.rect.x + 10
            var y = box.rect.y + 10
            if (img != null) {
                x = x.coerceIn(0, (img.width - box.rect.w).coerceAtLeast(0))
                y = y.coerceIn(0, (img.height - box.rect.h).coerceAtLeast(0))
            }
            boxes.add(BoxItem(box.categoryName, Rect(x, y, box.rect.w, box.rect.h)))
            selected = boxes.size - 1
            syncButtons()
            repaint()
        }

        private fun deleteSelected() {
            if (selected < 0) return
            pushUndo()
            boxes.removeAt(selected)
            selected = -1
            hovered = -1
            syncButtons()
            repaint()
        }

        private fun copyCategoryToClipboard(category: String) {
            CopyPasteManager.getInstance().setContents(StringSelection(category))
            colorLabel.text = OkScriptToolkitBundle.message("annotation.copied", category)
        }

        // ── 画框 ──

        private fun finishDraw(p: Point) {
            val start = drawStart ?: return
            val img = source
            drawStart = null
            drawPreview = null
            val a = toImage(start)
            val b = toImage(p)
            val rect = Rect(
                Math.min(a.x, b.x),
                Math.min(a.y, b.y),
                Math.abs(b.x - a.x),
                Math.abs(b.y - a.y),
            )
            if (rect.w < MIN_DRAW || rect.h < MIN_DRAW) {
                repaint()
                return
            }
            if (img != null) {
                val clamped = clampRect(rect, img.width, img.height)
                showBBoxDialog(null, clamped) { category, finalRect ->
                    if (category != null) {
                        pushUndo()
                        boxes.add(BoxItem(category, finalRect))
                        selected = boxes.size - 1
                        syncButtons()
                    }
                    setMode(CanvasMode.NONE)
                }
            }
        }

        /**
         * 坐标复制模式：框选结束后建立**可继续调整**的坐标框，并把归一化
         * x,y,tox,toy（左上 / 右下，0..1）写入系统剪贴板。
         * 该框不进入 boxes、不改动 COCO，只是一个取坐标的尺子。
         */
        private fun finishCoord(p: Point) {
            val img = source ?: return
            val start = drawStart ?: return
            drawStart = null
            drawPreview = null
            val a = toImage(start)
            val b = toImage(p)
            val rect = Rect(
                minOf(a.x, b.x),
                minOf(a.y, b.y),
                Math.abs(b.x - a.x),
                Math.abs(b.y - a.y),
            ).let { clampRect(it, img.width, img.height) }
            if (rect.w < MIN_DRAW || rect.h < MIN_DRAW) {
                clearCoordBox()
                return
            }
            coordBox = rect
            copyCoordBox()
        }

        /** 把坐标框当前的归一化坐标写入剪贴板（创建与每次调整结束都会调用） */
        private fun copyCoordBox() {
            val img = source ?: return
            val box = coordBox ?: return
            val text = NormalizedBox.format(
                box.x.toDouble(), box.y.toDouble(),
                (box.x + box.w).toDouble(), (box.y + box.h).toDouble(),
                img.width, img.height,
            )
            if (text.isEmpty()) return
            CopyPasteManager.getInstance().setContents(StringSelection(text))
            colorLabel.text = "${OkScriptToolkitBundle.message("annotation.coordLabel")} $text"
            repaint()
        }

        private fun clearCoordBox() {
            coordBox = null
            coordDrag = null
            colorLabel.text = " "
            repaint()
        }

        private fun coordScreenRect(): java.awt.Rectangle? = coordBox?.let { toScreenRect(it) }

        private fun findCoordHandleAt(p: Point): String? {
            val r = coordScreenRect() ?: return null
            return detectHandle(p.x.toDouble(), p.y.toDouble(), r)
        }

        private fun coordContains(p: Point): Boolean = coordScreenRect()?.contains(p) == true

        /** 按当前拖拽（移动或缩放）算出新的图像坐标矩形 */
        private fun applyCoordDrag(p: Point) {
            val img = source ?: return
            val d = coordDrag ?: return
            val dx = (p.x - d.start.x) / scale
            val dy = (p.y - d.start.y) / scale
            val o = d.orig
            var nx = o.x.toDouble()
            var ny = o.y.toDouble()
            var nw = o.w.toDouble()
            var nh = o.h.toDouble()
            val h = d.handle
            if (h == null) {
                nx = o.x + dx
                ny = o.y + dy
            } else {
                // 手柄名是 tl/tr/bl/br/top/bottom/left/right，必须精确匹配
                if (h == "left" || h == "tl" || h == "bl") { nx = o.x + dx; nw = o.w - dx }
                if (h == "right" || h == "tr" || h == "br") { nw = o.w + dx }
                if (h == "top" || h == "tl" || h == "tr") { ny = o.y + dy; nh = o.h - dy }
                if (h == "bottom" || h == "bl" || h == "br") { nh = o.h + dy }
                if (nw < MIN_RESIZE) { if (h == "left" || h == "tl" || h == "bl") nx = (o.x + o.w - MIN_RESIZE).toDouble(); nw = MIN_RESIZE.toDouble() }
                if (nh < MIN_RESIZE) { if (h == "top" || h == "tl" || h == "tr") ny = (o.y + o.h - MIN_RESIZE).toDouble(); nh = MIN_RESIZE.toDouble() }
            }
            if (img.width > 0) nx = nx.coerceIn(0.0, (img.width - nw).coerceAtLeast(0.0))
            if (img.height > 0) ny = ny.coerceIn(0.0, (img.height - nh).coerceAtLeast(0.0))
            nw = nw.coerceAtMost(img.width - nx).coerceAtLeast(1.0)
            nh = nh.coerceAtMost(img.height - ny).coerceAtLeast(1.0)
            coordBox = Rect(nx.toInt(), ny.toInt(), nw.toInt(), nh.toInt())
            // 拖动过程中只更新读数（不写剪贴板），松手时才复制
            val box = coordBox ?: return
            val text = NormalizedBox.format(
                box.x.toDouble(), box.y.toDouble(),
                (box.x + box.w).toDouble(), (box.y + box.h).toDouble(),
                img.width, img.height,
            )
            if (text.isNotEmpty()) {
                colorLabel.text = "${OkScriptToolkitBundle.message("annotation.coordLabel")} $text"
            }
        }

        /** 拖拽画新框时在信息条实时预览即将复制的坐标 */
        private fun updateCoordPreview(p: Point) {
            val img = source ?: return
            val start = drawStart ?: return
            val a = toImageDouble(start)
            val b = toImageDouble(p)
            val text = NormalizedBox.format(a.first, a.second, b.first, b.second, img.width, img.height)
            if (text.isNotEmpty()) {
                colorLabel.text = "${OkScriptToolkitBundle.message("annotation.coordLabel")} $text"
            }
        }

        /** 绘制坐标框：框体 + 8 向手柄 + 当前归一化坐标 */
        private fun paintCoordBox(g2: Graphics2D) {
            val box = coordBox ?: return
            val r = toScreenRect(box)
            g2.color = COORD_COLOR
            g2.stroke = BasicStroke(2f)
            g2.drawRect(r.x, r.y, r.width, r.height)

            g2.color = HANDLE_COLOR
            for ((hx, hy) in handlePoints(r).values) {
                g2.fillOval(hx - 4, hy - 4, 8, 8)
            }

            val text = colorLabel.text.trim()
            if (text.isNotEmpty()) {
                g2.color = COORD_COLOR
                val metrics = g2.fontMetrics
                val labelY = if (r.y - 4 < metrics.height) r.y + metrics.height + 2 else r.y - 4
                g2.drawString(text, r.x, labelY)
            }
        }

        /** 框的 8 个手柄锚点（四角 + 四边中点） */
        private fun handlePoints(r: java.awt.Rectangle): Map<String, Pair<Int, Int>> {
            val mx = r.x + r.width / 2
            val my = r.y + r.height / 2
            return mapOf(
                "tl" to (r.x to r.y),
                "top" to (mx to r.y),
                "tr" to (r.x + r.width to r.y),
                "right" to (r.x + r.width to my),
                "br" to (r.x + r.width to r.y + r.height),
                "bottom" to (mx to r.y + r.height),
                "bl" to (r.x to r.y + r.height),
                "left" to (r.x to my),
            )
        }

        private fun clampRect(rect: Rect, imgW: Int, imgH: Int): Rect {
            val x = rect.x.coerceIn(0, (imgW - MIN_DRAW).coerceAtLeast(0))
            val y = rect.y.coerceIn(0, (imgH - MIN_DRAW).coerceAtLeast(0))
            val w = rect.w.coerceAtMost(imgW - x).coerceAtLeast(1)
            val h = rect.h.coerceAtMost(imgH - y).coerceAtLeast(1)
            return Rect(x, y, w, h)
        }

        private fun showBBoxDialog(initial: String?, rect: Rect, onDone: (String?, Rect) -> Unit) {
            val dialog = BBoxDialog(project, initial, rect, buildTakenCategories(currentImage.file.name))
            dialog.show()
            if (dialog.exitCode == OK_EXIT_CODE && dialog.acceptedRect != null) {
                onDone(dialog.acceptedCategory, dialog.acceptedRect!!)
            } else {
                onDone(null, rect)
            }
        }

        private fun showEditDialog(idx: Int) {
            val box = boxes.getOrNull(idx) ?: return
            showBBoxDialog(box.categoryName, box.rect) { category, rect ->
                if (category != null) {
                    pushUndo()
                    boxes[idx] = BoxItem(category, rect)
                    syncButtons()
                    repaint()
                }
            }
        }

        // ── 缩放 ──

        private fun onWheel(e: MouseWheelEvent) {
            val img = source ?: return
            val p = e.point
            val (ix, iy) = toImageDouble(p)
            val factor = if (e.wheelRotation < 0) ZOOM_STEP else 1 / ZOOM_STEP
            scale = (scale * factor).coerceAtMost(ZOOM_MAX).coerceAtLeast(fitScale)
            offsetX = p.x - ix * scale
            offsetY = p.y - iy * scale
            recalcOffset()
            repaint()
        }

        private fun recalcFit() {
            val img = source
            if (img == null || width <= 0 || height <= 0) {
                fitScale = 1.0
                return
            }
            fitScale = minOf(width.toDouble() / img.width, height.toDouble() / img.height).coerceAtLeast(0.001)
        }

        private fun recalcOffset() {
            val img = source ?: return
            val sw = img.width * scale
            val sh = img.height * scale
            offsetX = if (sw <= width) (width - sw) / 2 else offsetX.coerceIn((width - sw).coerceAtMost(0.0), 0.0)
            offsetY = if (sh <= height) (height - sh) / 2 else offsetY.coerceIn((height - sh).coerceAtMost(0.0), 0.0)
        }

        private fun isZoomed(): Boolean {
            val img = source ?: return false
            return img.width * scale > width || img.height * scale > height
        }

        fun onCanvasResized() {
            if (source != null) {
                recalcFit()
                if (scale < fitScale) scale = fitScale
                recalcOffset()
            }
            repaint()
        }

        // ── 手柄 ──

        private fun detectHandle(px: Double, py: Double, r: java.awt.Rectangle): String? {
            val m = EDGE_MARGIN
            val nearL = Math.abs(px - r.x) <= m && py >= r.y - m && py <= r.y + r.height + m
            val nearR = Math.abs(px - (r.x + r.width)) <= m && py >= r.y - m && py <= r.y + r.height + m
            val nearT = Math.abs(py - r.y) <= m && px >= r.x - m && px <= r.x + r.width + m
            val nearB = Math.abs(py - (r.y + r.height)) <= m && px >= r.x - m && px <= r.x + r.width + m
            if (nearT && nearL) return "tl"
            if (nearT && nearR) return "tr"
            if (nearB && nearL) return "bl"
            if (nearB && nearR) return "br"
            if (nearT) return "top"
            if (nearB) return "bottom"
            if (nearL) return "left"
            if (nearR) return "right"
            return null
        }

        private fun handleCursor(h: String): Cursor = when (h) {
            "tl", "br" -> Cursor.getPredefinedCursor(Cursor.NW_RESIZE_CURSOR)
            "tr", "bl" -> Cursor.getPredefinedCursor(Cursor.NE_RESIZE_CURSOR)
            "top", "bottom" -> Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)
            "left", "right" -> Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR)
            else -> Cursor.getDefaultCursor()
        }

        private fun findHandleAt(p: Point): Pair<Int, String?> {
            for (i in boxes.indices.reversed()) {
                val handle = detectHandle(p.x.toDouble(), p.y.toDouble(), toScreenRect(boxes[i].rect))
                if (handle != null) return i to handle
            }
            return -1 to null
        }

        private fun hitBox(p: Point): Int {
            for (i in boxes.indices.reversed()) {
                val screen = toScreenRect(boxes[i].rect)
                if (screen.contains(p)) return i
            }
            return -1
        }

        private fun doResize(p: Point) {
            if (selected < 0) return
            val orig = resizeOrigRect ?: return
            val start = resizeStartPos ?: return
            val handle = resizeHandle ?: return
            val img = source
            val dx = (p.x - start.x) / scale
            val dy = (p.y - start.y) / scale
            var nx = orig.x.toDouble()
            var ny = orig.y.toDouble()
            var nw = orig.w.toDouble()
            var nh = orig.h.toDouble()
            if (handle.contains("left")) {
                nx = orig.x + dx
                nw = orig.w - dx
            }
            if (handle.contains("right")) {
                nw = orig.w + dx
            }
            if (handle.contains("top")) {
                ny = orig.y + dy
                nh = orig.h - dy
            }
            if (handle.contains("bottom")) {
                nh = orig.h + dy
            }
            if (nw < MIN_RESIZE) {
                if (handle.contains("left")) nx = (orig.x + orig.w - MIN_RESIZE).toDouble()
                nw = MIN_RESIZE.toDouble()
            }
            if (nh < MIN_RESIZE) {
                if (handle.contains("top")) ny = (orig.y + orig.h - MIN_RESIZE).toDouble()
                nh = MIN_RESIZE.toDouble()
            }
            if (img != null) {
                nx = nx.coerceAtLeast(0.0)
                ny = ny.coerceAtLeast(0.0)
                if (nx + nw > img.width) nw = img.width - nx
                if (ny + nh > img.height) nh = img.height - ny
            }
            boxes[selected] = BoxItem(boxes[selected].categoryName, Rect(nx.toInt(), ny.toInt(), nw.toInt(), nh.toInt()))
        }

        // ── 坐标转换 ──

        private fun toImage(p: Point): Point = Point(
            ((p.x - offsetX) / scale).toInt(),
            ((p.y - offsetY) / scale).toInt(),
        )

        private fun toImageDouble(p: Point): Pair<Double, Double> = Pair(
            (p.x - offsetX) / scale,
            (p.y - offsetY) / scale,
        )

        private fun toScreenRect(r: Rect): java.awt.Rectangle = java.awt.Rectangle(
            (offsetX + r.x * scale).toInt(),
            (offsetY + r.y * scale).toInt(),
            Math.max(1, (r.w * scale).toInt()),
            Math.max(1, (r.h * scale).toInt()),
        )

        // ── 像素信息 ──

        private fun updateColorAt(p: Point) {
            val img = source ?: return
            val (ix, iy) = toImageDouble(p)
            val px = ix.toInt()
            val py = iy.toInt()
            if (px < 0 || py < 0 || px >= img.width || py >= img.height) {
                colorLabel.text = "Abs: ($px, $py)"
                return
            }
            val rgb = img.getRGB(px.coerceIn(0, img.width - 1), py.coerceIn(0, img.height - 1))
            val r = (rgb shr 16) and 0xFF
            val g = (rgb shr 8) and 0xFF
            val b = rgb and 0xFF
            val relX = ix / img.width
            val relY = iy / img.height
            colorLabel.text = "R:$r G:$g B:$b  Abs:($px,$py) Rel:(%.3f,%.3f)".format(relX, relY)
        }

        // ── 绘制 ──

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val img = source
            val g2 = g as Graphics2D
            g2.background = background
            g2.clearRect(0, 0, width, height)

            if (img == null) {
                g2.color = JBColor.foreground()
                val text = OkScriptToolkitBundle.message("annotation.loadFailed")
                g2.drawString(text, (width - g2.fontMetrics.stringWidth(text)) / 2, height / 2)
                return
            }

            val drawW = (img.width * scale).toInt()
            val drawH = (img.height * scale).toInt()
            g2.drawImage(img, offsetX.toInt(), offsetY.toInt(), drawW, drawH, null)

            boxes.forEachIndexed { i, box ->
                val r = toScreenRect(box.rect)
                val isSel = i == selected
                val isHov = i == hovered
                g2.stroke = BasicStroke(if (isSel) 2.5f else 1.8f)
                g2.color = when {
                    isSel -> SELECTED_COLOR
                    isHov -> HOVER_COLOR
                    else -> BOX_COLOR
                }
                g2.drawRect(r.x, r.y, r.width, r.height)

                if (isHov) {
                    g2.color = HANDLE_COLOR
                    for (corner in listOf(
                        r.x to r.y,
                        r.x + r.width to r.y,
                        r.x to r.y + r.height,
                        r.x + r.width to r.y + r.height,
                    )) {
                        g2.fillOval(corner.first - 4, corner.second - 4, 8, 8)
                    }
                }

                val label = box.categoryName
                val metrics = g2.fontMetrics
                val labelX = r.x
                val labelY = (r.y - 4).coerceAtLeast(metrics.height)
                // 标签底色半透明，深浅主题下都可读
                g2.color = Color(0, 0, 0, 110)
                g2.fillRect(labelX - 1, labelY - metrics.height + 2, metrics.stringWidth(label) + 3, metrics.height)
                g2.color = JBColor.foreground()
                g2.drawString(label, labelX, labelY)
            }

            // 画框 / 坐标复制 预览（虚线框，图像坐标归一化后按当前缩放绘制）
            val start = drawStart
            val preview = drawPreview
            if ((mode == CanvasMode.DRAW || mode == CanvasMode.COPYCOORD) && start != null && preview != null) {
                val a = toImage(start)
                val b = toImage(preview)
                val x = Math.min(a.x, b.x)
                val y = Math.min(a.y, b.y)
                val w = Math.abs(b.x - a.x)
                val h = Math.abs(b.y - a.y)
                g2.color = if (mode == CanvasMode.COPYCOORD) COORD_COLOR else HANDLE_COLOR
                g2.stroke = BasicStroke(1.5f, 0, 0, 8f, floatArrayOf(6f, 6f), 0f)
                g2.drawRect(
                    (offsetX + x * scale).toInt(),
                    (offsetY + y * scale).toInt(),
                    (w * scale).toInt(),
                    (h * scale).toInt(),
                )
            }

            // 坐标复制模式的可调框（画在最上层，便于看到手柄与读数）
            if (mode == CanvasMode.COPYCOORD) paintCoordBox(g2)
        }
    }

    // ── 框属性对话框（新建 / 编辑）──

    private class BBoxDialog(
        project: Project,
        private val initialCategory: String?,
        initialRect: Rect,
        private val taken: Map<String, String>,
    ) : DialogWrapper(project) {

        val acceptedCategory: String? get() = if (exitCode == OK_EXIT_CODE) categoryField.text.trim() else null
        val acceptedRect: Rect? get() = if (exitCode == OK_EXIT_CODE) parseRectOrNull() else null

        private val categoryField = JBTextField(initialCategory ?: "", 16)
        private val xField = JBTextField(initialRect.x.toString(), 6)
        private val yField = JBTextField(initialRect.y.toString(), 6)
        private val wField = JBTextField(initialRect.w.toString(), 6)
        private val hField = JBTextField(initialRect.h.toString(), 6)

        init {
            title = if (initialCategory == null) {
                OkScriptToolkitBundle.message("annotation.newBoxTitle")
            } else {
                OkScriptToolkitBundle.message("annotation.editBoxTitle")
            }
            setOKButtonText(OkScriptToolkitBundle.message("annotation.ok"))
            setCancelButtonText(OkScriptToolkitBundle.message("annotation.cancel"))
            init()
        }

        private fun parseField(field: JBTextField): Int? = field.text.trim().toIntOrNull()

        private fun parseRectOrNull(): Rect? {
            val x = parseField(xField) ?: return null
            val y = parseField(yField) ?: return null
            val w = parseField(wField) ?: return null
            val h = parseField(hField) ?: return null
            return Rect(x, y, w, h)
        }

        private fun categoryError(): String? {
            val name = categoryField.text.trim()
            if (name.isEmpty()) return OkScriptToolkitBundle.message("annotation.categoryRequired")
            // 编辑且未改名时不算冲突；其余情况与其他图片的分类比对（排除当前图，对齐 VSCode 版）
            if (name == initialCategory) return null
            val owner = taken[name] ?: return null
            return OkScriptToolkitBundle.message("annotation.categoryExists", owner)
        }

        override fun doValidate(): ValidationInfo? {
            categoryError()?.let { return ValidationInfo(it, categoryField) }
            for (field in listOf(xField, yField, wField, hField)) {
                if (parseField(field) == null) {
                    return ValidationInfo(OkScriptToolkitBundle.message("annotation.numberRequired"), field)
                }
            }
            val rect = parseRectOrNull()!!
            if (rect.w <= 0 || rect.h <= 0) {
                return ValidationInfo(OkScriptToolkitBundle.message("annotation.numberRequired"), wField)
            }
            return null
        }

        override fun createCenterPanel(): JComponent {
            val form = JPanel(GridLayout(0, 2, 6, 4))
            fun addRow(labelKey: String, field: JComponent) {
                form.add(JLabel(OkScriptToolkitBundle.message(labelKey)))
                form.add(field)
            }
            addRow("annotation.category", categoryField)
            addRow("annotation.x", xField)
            addRow("annotation.y", yField)
            addRow("annotation.w", wField)
            addRow("annotation.h", hField)
            return form
        }
    }
}
