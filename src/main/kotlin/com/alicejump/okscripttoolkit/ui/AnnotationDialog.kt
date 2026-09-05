package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.alicejump.okscripttoolkit.core.CocoAnnotation
import com.alicejump.okscripttoolkit.core.TemplateAssetDataService
import com.alicejump.okscripttoolkit.core.TemplateImage
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import java.util.Vector

/**
 * COCO 标注编辑器（对齐 VSCode 版 AnnotationPanel 的基础能力）：
 * 拖拽画框、点选、Delete 删除、双击改分类，确认后写回 coco_annotations.json。
 * 不做 undo/redo、复制粘贴与跨图导航（后续迭代）。
 */
class AnnotationDialog(
    private val project: Project,
    private val data: TemplateAssetDataService,
    private val image: TemplateImage,
) : DialogWrapper(project) {

    companion object {
        private val LOG = Logger.getInstance(AnnotationDialog::class.java)
    }

    private val canvas = AnnotationCanvas()

    private data class BoxItem(
        val categoryId: Int,
        val categoryName: String,
        // 相对原图像素的坐标
        val rect: Rectangle,
    )
    private val categoryPanel = JPanel(GridLayout(1, 2, 6, 0))
    private var cocoImageId: Int = -1

    init {
        title = OkScriptToolkitBundle.message("annotation.title", image.name)
        setOKButtonText(OkScriptToolkitBundle.message("annotation.save"))
        setCancelButtonText(OkScriptToolkitBundle.message("annotation.cancel"))
        init()
        loadImage()
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, 8))
        canvas.preferredSize = Dimension(900, 560)
        root.add(canvas, BorderLayout.CENTER)

        categoryPanel.border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
        val hint = JLabel(OkScriptToolkitBundle.message("annotation.hint"))
        categoryPanel.add(hint)
        root.add(categoryPanel, BorderLayout.SOUTH)
        return root
    }

    private fun loadImage() {
        Thread {
            try {
                val buffered = ImageIO.read(image.file)
                val cocoImage = data.getImageEntryForFile(image.file.name)
                val annotations = cocoImage?.let { data.getAnnotationsForImage(it.id) } ?: emptyList()
                SwingUtilities.invokeLater {
                    if (buffered != null && cocoImage != null) {
                        cocoImageId = cocoImage.id
                        canvas.setImage(buffered, annotations, data.categories())
                    } else {
                        close(CANCEL_EXIT_CODE)
                    }
                }
            } catch (e: Exception) {
                LOG.warn("Failed to open annotation editor for ${image.name}", e)
                SwingUtilities.invokeLater { close(CANCEL_EXIT_CODE) }
            }
        }.start()
    }

    override fun doOKAction() {
        if (cocoImageId >= 0) {
            val items = canvas.exportAnnotations()
            try {
                data.replaceAnnotationsForImage(cocoImageId, items)
                data.save()
            } catch (e: Exception) {
                LOG.error("Failed to save annotations for ${image.name}", e)
            }
        }
        super.doOKAction()
    }

    private inner class AnnotationCanvas : JComponent() {

        private var source: BufferedImage? = null
        private var drawScale = 1.0
        private var drawOffset = Point(0, 0)
        private val boxes = mutableListOf<BoxItem>()
        private val categoryNames = mutableListOf<String>()
        private var selected: BoxItem? = null
        private var dragStart: Point? = null
        private var dragCurrent: Point? = null

        init {
            isFocusable = true
            val inputMap = getInputMap(WHEN_FOCUSED)
            val actionMap = getActionMap()
            inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "delete-selected")
            inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "delete-selected")
            actionMap.put("delete-selected", object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) {
                    selected?.let {
                        boxes.remove(it)
                        selected = null
                        repaint()
                    }
                }
            })

            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    requestFocusInWindow()
                    val p = toImage(e.point)
                    selected = boxes.lastOrNull { it.rect.contains(p) }
                    if (selected == null) dragStart = e.point
                    repaint()
                }

                override fun mouseReleased(e: MouseEvent) {
                    val start = dragStart
                    dragStart = null
                    if (start != null) {
                        val rect = normalizeRect(start, e.point)
                        val imgRect = toImageRect(rect)
                        if (imgRect.width >= 4 && imgRect.height >= 4) {
                            promptCategory { categoryName ->
                                if (categoryName != null) {
                                    val category = data.getOrCreateCategory(categoryName)
                                    if (categoryNames.none { it == categoryName }) {
                                        categoryNames.add(categoryName)
                                    }
                                    boxes.add(BoxItem(category.id, categoryName, imgRect))
                                    selected = boxes.last()
                                    repaint()
                                }
                            }
                        }
                    }
                    dragCurrent = null
                    repaint()
                }

                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) {
                        val p = toImage(e.point)
                        val hit = boxes.lastOrNull { it.rect.contains(p) }
                        if (hit != null) {
                            promptCategory(hit.categoryName) { newName ->
                                if (newName != null) {
                                    val category = data.getOrCreateCategory(newName)
                                    val index = boxes.indexOf(hit)
                                    boxes[index] = hit.copy(categoryId = category.id, categoryName = newName)
                                    if (categoryNames.none { it == newName }) categoryNames.add(newName)
                                    repaint()
                                }
                            }
                        }
                    }
                }
            })
            addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
                override fun mouseDragged(e: MouseEvent) {
                    dragCurrent = e.point
                    cursor = Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
                    repaint()
                }

                override fun mouseMoved(e: MouseEvent?) {
                    cursor = Cursor.getDefaultCursor()
                }
            })
        }

        fun setImage(buffered: BufferedImage, annotations: List<CocoAnnotation>, categories: List<com.alicejump.okscripttoolkit.core.CocoCategory>) {
            source = buffered
            categoryNames.clear()
            categoryNames.addAll(categories.map { it.name })
            boxes.clear()
            annotations.forEach { ann ->
                val name = categories.firstOrNull { it.id == ann.categoryId }?.name ?: "#${ann.categoryId}"
                boxes.add(
                    BoxItem(
                        ann.categoryId,
                        name,
                        Rectangle(ann.bbox[0], ann.bbox[1], ann.bbox[2], ann.bbox[3]),
                    ),
                )
            }
            repaint()
        }

        fun exportAnnotations(): List<Pair<Int, IntArray>> =
            boxes.map { it.categoryId to intArrayOf(it.rect.x, it.rect.y, it.rect.width, it.rect.height) }

        private fun promptCategory(initial: String? = null, onDone: (String?) -> Unit) {
            val combo = JComboBox(Vector(categoryNames))
            combo.isEditable = true
            initial?.let { combo.selectedItem = it }
            val option = JOptionPaneOption(combo, OkScriptToolkitBundle.message("annotation.categoryPrompt")) { result ->
                val value = combo.selectedItem as? String
                onDone(if (result && !value.isNullOrBlank()) value.trim() else null)
            }
            option.show()
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val img = source ?: return
            val g2 = g as Graphics2D
            g2.color = Color.DARK_GRAY
            g2.fillRect(0, 0, width, height)

            val scale = minOf(width.toDouble() / img.width, (height - 40).toDouble() / img.height).coerceAtMost(1.0)
            drawScale = scale
            val drawW = (img.width * scale).toInt()
            val drawH = (img.height * scale).toInt()
            drawOffset = Point((width - drawW) / 2, (height - drawH) / 2)
            g2.drawImage(img, drawOffset.x, drawOffset.y, drawW, drawH, null)

            for (box in boxes) {
                val r = toScreenRect(box.rect)
                val isSelected = box === selected
                g2.color = if (isSelected) Color(255, 210, 40) else Color(255, 40, 40)
                g2.stroke = java.awt.BasicStroke(if (isSelected) 2.5f else 1.8f)
                g2.drawRect(r.x, r.y, r.width, r.height)
                g2.color = Color.WHITE
                g2.background = Color(0, 0, 0, 140)
                val label = box.categoryName
                val labelX = r.x
                val labelY = r.y - 4
                g2.drawString(label, labelX, labelY)
            }

            val start = dragStart
            val current = dragCurrent
            if (start != null && current != null) {
                val r = normalizeRect(start, current)
                g2.color = Color(255, 210, 40)
                g2.stroke = java.awt.BasicStroke(1.5f, 0, 0, 8f, floatArrayOf(6f, 6f), 0f)
                g2.drawRect(r.x, r.y, r.width, r.height)
            }
        }

        private fun toImage(p: Point): Point = Point(
            ((p.x - drawOffset.x) / drawScale).toInt(),
            ((p.y - drawOffset.y) / drawScale).toInt(),
        )

        private fun toImageRect(r: Rectangle): Rectangle {
            val x = (r.x / drawScale).toInt().coerceAtLeast(0)
            val y = (r.y / drawScale).toInt().coerceAtLeast(0)
            val w = (r.width / drawScale).toInt()
            val h = (r.height / drawScale).toInt()
            return Rectangle(x, y, w, h)
        }

        private fun toScreenRect(r: Rectangle): Rectangle = Rectangle(
            drawOffset.x + (r.x * drawScale).toInt(),
            drawOffset.y + (r.y * drawScale).toInt(),
            (r.width * drawScale).toInt(),
            (r.height * drawScale).toInt(),
        )

        private fun normalizeRect(a: Point, b: Point): Rectangle =
            Rectangle(minOf(a.x, b.x), minOf(a.y, b.y), Math.abs(a.x - b.x), Math.abs(a.y - b.y))
    }

    /** 极简可复用确认弹窗（editable combo + OK/Cancel）。 */
    private class JOptionPaneOption(
        content: JComponent,
        titleText: String,
        private val onDone: (Boolean) -> Unit,
    ) {
        private val dialog: JDialog

        init {
            val dialogHolder = arrayOfNulls<JDialog>(1)
            val ok = JButton(OkScriptToolkitBundle.message("annotation.ok"))
            val cancel = JButton(OkScriptToolkitBundle.message("annotation.cancel"))
            fun finish(result: Boolean) {
                dialogHolder[0]?.dispose()
                onDone(result)
            }
            ok.addActionListener { finish(true) }
            cancel.addActionListener { finish(false) }

            dialog = JDialog().apply {
                title = titleText
                isModal = true
                layout = BorderLayout(8, 8)
                add(content, BorderLayout.CENTER)
                val buttons = JPanel(GridLayout(1, 2, 6, 0))
                buttons.add(ok)
                buttons.add(cancel)
                add(buttons, BorderLayout.SOUTH)
                rootPane.defaultButton = ok
                pack()
                size = Dimension(320, preferredSize.height)
                setLocationRelativeTo(null)
            }
            dialogHolder[0] = dialog
        }

        fun show() {
            dialog.isVisible = true
        }
    }
}
