package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.AnnotationUiBundle
import com.alicejump.okscripttoolkit.core.TemplateThumbPipeline
import com.intellij.openapi.Disposable
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanelWithEmptyText
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
import java.awt.Toolkit
import java.awt.event.ActionEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.Icon
import javax.swing.KeyStroke
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.TransferHandler
import kotlin.math.max

/** 卡片上的直达动作：平台图标 + 本地化名称（同时作为 tooltip 与读屏名），不再用 Unicode 字符当图标。 */
internal data class ResourceCardAction(val icon: Icon, val label: String, val run: () -> Unit)

/**
 * 与 VS Code PR #41 相同的卡片手势：
 * 单击 / Space = 选中，双击 / Enter = 主操作，E = 编辑，Ctrl/Cmd+C = 复制，Delete = 删除，
 * 方向键 / Home / End 在卡片间移动。卡片可 Tab 聚焦，读屏名为资源名。
 * 操作按钮常驻右下角且直接绑定资源，不依赖选中项。
 */
internal class ResourceThumbnailGrid<T>(
    private val visual: (T) -> CardVisual,
    private val actions: (T) -> List<ResourceCardAction>,
    private val primary: (T) -> Unit,
    private val load: (List<TemplateThumbPipeline.Request<String>>, (String, ImageIcon?) -> Unit) -> Unit,
    private val edit: ((T) -> Unit)? = null,
    private val copy: ((T) -> Unit)? = null,
    private val delete: ((T) -> Unit)? = null,
    private val onSelect: ((T?) -> Unit)? = null,
) : JPanel(BorderLayout()), Disposable {
    companion object { const val THUMB_HEIGHT = 96 }
    val count = JBLabel()
    private var emptyAction: Pair<String, () -> Unit>? = null
    /** 空态画在网格自身上（平台 StatusText），可带一个链接动作，例如「打开标注编辑器」。 */
    private val grid = object : JBPanelWithEmptyText(ResourceGridLayout()), Scrollable {
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
    private val cardsByIndex = mutableListOf<JComponent>()
    private var selectedKey: String? = null

    /** 当前选中的资源（按 key 跨刷新保留）。 */
    val selected: T? get() = shown.firstOrNull { visual(it).key == selectedKey }

    /** 当前可见条目（测试与「已选中」详情条使用）。 */
    val items: List<T> get() = shown
    private var total = 0
    private var disposed = false

    init {
        isOpaque = false
        add(scroll, BorderLayout.CENTER)
        renderEmpty(AnnotationUiBundle.message("preview.empty"), error = false)
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
        cardsByIndex.clear()
        if (shown.none { visual(it).key == selectedKey }) selectedKey = null
        shown.forEachIndexed { index, item -> grid.add(createCard(item, index).also { cardsByIndex += it }) }
        renderEmpty(AnnotationUiBundle.message(if (total == 0) "preview.empty" else "preview.noMatch"), error = false)
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
        grid.removeAll(); cardsByIndex.clear(); shown = emptyList()
        renderEmpty(message, error = true)
        grid.revalidate(); grid.repaint()
    }

    /** 空网格的链接动作（只在“完全没有资源”时显示，搜索无结果时不显示）。 */
    fun setEmptyAction(label: String?, action: (() -> Unit)?) {
        emptyAction = if (label != null && action != null) label to action else null
        if (shown.isEmpty()) renderEmpty(grid.emptyText.text, error = false)
    }

    /** 当前空态文本（测试用）。 */
    val emptyMessage: String get() = grid.emptyText.text

    private fun renderEmpty(message: String, error: Boolean) {
        val text = grid.emptyText
        text.clear()
        text.appendText(message, if (error) SimpleTextAttributes.ERROR_ATTRIBUTES else SimpleTextAttributes.GRAYED_ATTRIBUTES)
        val action = emptyAction
        if (!error && action != null && total == 0) {
            text.appendLine(action.first, SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { action.second() }
        }
    }

    private fun updateCount() {
        count.text = AnnotationUiBundle.message("preview.count", shown.size, total)
        if (completed.isNotEmpty()) count.text += " · " + AnnotationUiBundle.message("preview.thumbnailCount", icons.size, completed.size - icons.size)
    }

    private fun createCard(item: T, index: Int): JComponent {
        val meta = visual(item)
        val image = ResourceThumbnailLabel(icons[meta.key]).apply {
            preferredSize = JBUI.size(118, THUMB_HEIGHT)
            text = if (icon != null) null else if (meta.key in completed) AnnotationUiBundle.message("preview.thumbnailFailed") else "…"
        }
        labels[meta.key] = image
        val buttons = actions(item).map { action ->
            JButton(action.icon).apply {
                toolTipText = action.label
                isFocusable = false
                addActionListener { action.run() }
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
            border = cardBorder(meta.key == selectedKey)
            toolTipText = meta.tooltip
            isFocusable = true
            accessibleContext.accessibleName = meta.name
            accessibleContext.accessibleDescription = meta.detail
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            add(preview, BorderLayout.CENTER); add(info, BorderLayout.SOUTH)
            preferredSize = Dimension(JBUI.scale(118), JBUI.scale(THUMB_HEIGHT) + info.preferredSize.height + JBUI.scale(4))
        }
        card.addFocusListener(object : FocusAdapter() {
            override fun focusGained(event: FocusEvent) = select(index)
        })
        val click = object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(event)) return
                if (event.clickCount >= 2) primary(item) else focusCard(index)
            }
        }
        (listOf(card, preview, image, info, name, detail) + info.components.filterIsInstance<JBLabel>()).distinct().forEach {
            it.addMouseListener(click)
            it.transferHandler = transferHandler
        }
        bindKeys(card, item, index)
        return card
    }

    private fun cardBorder(selected: Boolean) =
        if (selected) JBUI.Borders.customLine(JBUI.CurrentTheme.Focus.focusColor(), 2)
        else JBUI.Borders.compound(JBUI.Borders.empty(1), JBUI.Borders.customLine(JBColor.border(), 1))

    private fun bindKeys(card: JComponent, item: T, index: Int) {
        val menu = Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
        fun bind(id: String, stroke: KeyStroke, run: () -> Unit) {
            card.getInputMap(JComponent.WHEN_FOCUSED).put(stroke, id)
            card.actionMap.put(id, object : AbstractAction() { override fun actionPerformed(e: ActionEvent?) = run() })
        }
        bind("ok.primary", KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)) { primary(item) }
        bind("ok.select", KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0)) { select(index) }
        edit?.let { run -> bind("ok.edit", KeyStroke.getKeyStroke(KeyEvent.VK_E, 0)) { run(item) } }
        copy?.let { run -> bind("ok.copy", KeyStroke.getKeyStroke(KeyEvent.VK_C, menu)) { run(item) } }
        delete?.let { run -> bind("ok.delete", KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0)) { run(item) } }
        bind("ok.left", KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0)) { focusCard(index - 1) }
        bind("ok.right", KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0)) { focusCard(index + 1) }
        bind("ok.up", KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0)) { focusCard(index - columns()) }
        bind("ok.down", KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0)) { focusCard(index + columns()) }
        bind("ok.home", KeyStroke.getKeyStroke(KeyEvent.VK_HOME, 0)) { focusCard(0) }
        bind("ok.end", KeyStroke.getKeyStroke(KeyEvent.VK_END, 0)) { focusCard(cardsByIndex.size - 1) }
    }

    /** 当前布局的列数：与首卡同一行的卡片数。 */
    private fun columns(): Int {
        val firstY = cardsByIndex.firstOrNull()?.y ?: return 1
        return max(1, cardsByIndex.count { it.y == firstY })
    }

    private fun focusCard(index: Int) {
        val card = cardsByIndex.getOrNull(index) ?: return
        select(index)
        card.requestFocusInWindow()
        card.scrollRectToVisible(Rectangle(0, 0, card.width, card.height))
    }

    /** 选中第 [index] 张卡；只改选中态，不触发任何资源动作。 */
    fun select(index: Int) {
        val item = shown.getOrNull(index) ?: return
        val key = visual(item).key
        if (selectedKey == key) return
        selectedKey = key
        cardsByIndex.forEachIndexed { i, card -> card.border = cardBorder(i == index) }
        onSelect?.invoke(item)
    }

    /** 按 key 选中并滚动到可见（「在资源中定位」使用）。 */
    fun reveal(predicate: (T) -> Boolean): Boolean {
        val index = shown.indexOfFirst(predicate)
        if (index < 0) return false
        focusCard(index)
        return true
    }

    fun installDropHandler(handler: TransferHandler) {
        transferHandler = handler
        grid.transferHandler = handler
        scroll.transferHandler = handler
    }

    /** 单击只选中、不再延迟执行动作，所以没有待取消的计时器；保留给旧调用方。 */
    fun cancelPendingClicks() = Unit

    override fun dispose() {
        disposed = true; generation.invalidate()
        grid.removeAll(); cardsByIndex.clear(); labels.clear(); icons.clear(); completed.clear(); pending.clear()
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
