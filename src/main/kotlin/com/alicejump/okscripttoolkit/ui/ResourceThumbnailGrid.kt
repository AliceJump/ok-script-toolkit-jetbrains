package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.AnnotationUiBundle
import com.alicejump.okscripttoolkit.core.TemplateThumbPipeline
import com.intellij.openapi.Disposable
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.LayoutManager
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.TransferHandler
import kotlin.math.max

internal data class ResourceCardAction(val glyph: String, val label: String, val run: () -> Unit)

/** 与 VS Code 相同：等宽自适应卡片，操作按钮常驻右下角且直接绑定资源。 */
internal class ResourceThumbnailGrid<T>(
    private val visual: (T) -> CardVisual,
    private val actions: (T) -> List<ResourceCardAction>,
    private val onSingle: (T) -> Unit,
    private val onDouble: ((T) -> Unit)? = null,
    private val load: (List<TemplateThumbPipeline.Request<String>>, (String, ImageIcon?) -> Unit) -> Unit,
) : JPanel(BorderLayout()), Disposable {
    companion object { const val THUMB_HEIGHT = 96 }
    val count = JBLabel()
    private val empty = JBLabel(AnnotationUiBundle.message("preview.empty"), SwingConstants.CENTER)
    private val grid = object : JPanel(ResourceGridLayout()), Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(r: Rectangle, orientation: Int, direction: Int) = JBUI.scale(24)
        override fun getScrollableBlockIncrement(r: Rectangle, orientation: Int, direction: Int) = max(JBUI.scale(24), r.height - JBUI.scale(24))
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
    }.apply { isOpaque = false; border = JBUI.Borders.empty(6) }
    private val scroll = JScrollPane(grid).apply { horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER }
    private val generation = PreviewThumbGeneration()
    private val icons = mutableMapOf<String, ImageIcon>()
    private val completed = mutableSetOf<String>()
    private val pending = mutableSetOf<String>()
    private val labels = mutableMapOf<String, JBLabel>()
    private var shown = emptyList<T>()
    private var total = 0
    private var disposed = false

    init {
        isOpaque = false
        empty.border = JBUI.Borders.empty(12)
        add(scroll, BorderLayout.CENTER)
        add(empty, BorderLayout.SOUTH)
    }

    fun setItems(items: List<T>, totalCount: Int = items.size, invalidate: Boolean = false) {
        cancelPendingClicks()
        if (invalidate) {
            generation.invalidate()
            icons.clear(); completed.clear(); pending.clear()
        }
        shown = items.toList()
        total = totalCount
        labels.clear()
        grid.removeAll()
        for (item in shown) grid.add(createCard(item))
        empty.text = AnnotationUiBundle.message(if (total == 0) "preview.empty" else "preview.noMatch")
        empty.isVisible = shown.isEmpty()
        updateCount()
        grid.revalidate(); grid.repaint()
        val requests = shown.map(visual).filter { it.key !in completed && pending.add(it.key) }
            .map { TemplateThumbPipeline.Request(it.key, it.imagePath, it.bbox) }
        val expected = generation.current()
        load(requests) { key, icon ->
            if (disposed || !generation.isCurrent(expected)) return@load
            pending.remove(key); completed.add(key)
            if (icon != null) icons[key] = icon
            labels[key]?.let { label ->
                label.icon = icon
                label.text = if (icon == null) AnnotationUiBundle.message("preview.thumbnailFailed") else null
            }
            updateCount()
        }
    }

    fun showError(message: String) {
        empty.text = message
        empty.isVisible = true
    }

    private fun updateCount() {
        count.text = AnnotationUiBundle.message("preview.count", shown.size, total)
        if (completed.isNotEmpty()) count.text += " · " + AnnotationUiBundle.message("preview.thumbnailCount", icons.size, completed.size - icons.size)
    }

    private fun createCard(item: T): JComponent {
        val meta = visual(item)
        val image = ResourceThumbnailLabel(icons[meta.key]).apply {
            preferredSize = JBUI.size(118, THUMB_HEIGHT)
            text = if (icon != null) null else if (meta.key in completed) AnnotationUiBundle.message("preview.thumbnailFailed") else "…"
        }
        labels[meta.key] = image
        val buttons = actions(item).map { action ->
            JButton(action.glyph).apply {
                toolTipText = action.label
                addActionListener { cancelPendingClicks(); action.run() }
            }
        }
        val preview = ThumbnailActions(image, *buttons.toTypedArray())
        val name = JBLabel(meta.name).apply { toolTipText = meta.tooltip }
        val detail = JBLabel(meta.detail).apply { foreground = UIUtil.getContextHelpForeground() }
        val info = JPanel(BorderLayout(0, JBUI.scale(2))).apply {
            isOpaque = false
            border = JBUI.Borders.empty(5, 7, 6, 7)
            meta.categories?.let { add(JBLabel(it.ifBlank { " " }).apply { toolTipText = it }, BorderLayout.CENTER) }
            add(name, BorderLayout.NORTH); add(detail, BorderLayout.SOUTH)
        }
        val card = JPanel(BorderLayout()).apply {
            border = BorderFactory.createLineBorder(JBColor.border())
            toolTipText = meta.tooltip
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            add(preview, BorderLayout.CENTER); add(info, BorderLayout.SOUTH)
            preferredSize = Dimension(JBUI.scale(118), JBUI.scale(THUMB_HEIGHT) + info.preferredSize.height + 2)
        }
        val click = if (onDouble == null) object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(event) && event.clickCount == 1) onSingle(item)
            }
        } else ThumbnailClicks(card, { onSingle(item) }, { onDouble.invoke(item) }, clickDelay = 500)
        (listOf(card, preview, image, info, name, detail) + info.components.filterIsInstance<JBLabel>()).distinct().forEach {
            it.addMouseListener(click)
            it.transferHandler = transferHandler
        }
        return card
    }

    fun installDropHandler(handler: TransferHandler) {
        transferHandler = handler
        grid.transferHandler = handler
        scroll.transferHandler = handler
    }

    fun cancelPendingClicks() {
        fun cancel(component: Component) {
            ((component as? JComponent)?.getClientProperty("thumbnailClickTimer") as? Timer)?.stop()
            if (component is Container) component.components.forEach(::cancel)
        }
        cancel(grid)
    }

    override fun dispose() {
        disposed = true; generation.invalidate(); cancelPendingClicks()
        grid.removeAll(); labels.clear(); icons.clear(); completed.clear(); pending.clear()
    }
}

/** 缩略图按可见卡片宽高完整显示，窄列也不裁掉宽图两侧。 */
internal class ResourceThumbnailLabel(icon: ImageIcon?) : JBLabel(icon, SwingConstants.CENTER) {
    override fun paintComponent(graphics: Graphics) {
        val thumbnail = icon as? ImageIcon
        if (thumbnail == null || thumbnail.iconWidth <= 0 || thumbnail.iconHeight <= 0) {
            super.paintComponent(graphics)
            return
        }
        val ratio = minOf(1.0, width.toDouble() / thumbnail.iconWidth, height.toDouble() / thumbnail.iconHeight)
        val shownWidth = max(1, (thumbnail.iconWidth * ratio).toInt())
        val shownHeight = max(1, (thumbnail.iconHeight * ratio).toInt())
        graphics.drawImage(thumbnail.image, (width - shownWidth) / 2, (height - shownHeight) / 2, shownWidth, shownHeight, null)
    }
}

/** 列数由可用宽度决定，最后一行不会因为元素较少而把卡片拉宽。 */
internal class ResourceGridLayout : LayoutManager {
    private fun columns(parent: Container) = max(1, (parent.width - parent.insets.left - parent.insets.right + JBUI.scale(6)) / JBUI.scale(124))
    private fun cardHeight(parent: Container) = max(JBUI.scale(142), parent.components.maxOfOrNull { it.preferredSize.height } ?: 0)
    override fun addLayoutComponent(name: String?, component: Component?) = Unit
    override fun removeLayoutComponent(component: Component?) = Unit
    override fun minimumLayoutSize(parent: Container) = JBUI.size(118, 1)
    override fun preferredLayoutSize(parent: Container): Dimension {
        val rows = (parent.componentCount + columns(parent) - 1) / columns(parent)
        return Dimension(max(JBUI.scale(118), parent.width), parent.insets.top + parent.insets.bottom + rows * (cardHeight(parent) + JBUI.scale(6)))
    }
    override fun layoutContainer(parent: Container) {
        val n = columns(parent)
        val gap = JBUI.scale(6)
        val height = cardHeight(parent)
        val width = max(1, (parent.width - parent.insets.left - parent.insets.right - (n - 1) * gap) / n)
        parent.components.forEachIndexed { index, card ->
            card.setBounds(parent.insets.left + index % n * (width + gap), parent.insets.top + index / n * (height + gap), width, height)
        }
    }
}
