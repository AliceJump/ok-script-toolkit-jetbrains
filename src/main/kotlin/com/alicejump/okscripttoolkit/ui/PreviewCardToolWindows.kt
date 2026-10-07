package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.core.BoxCatalogService
import com.alicejump.okscripttoolkit.core.OkProjectDataService
import com.alicejump.okscripttoolkit.core.PointCatalogService
import com.alicejump.okscripttoolkit.core.TemplateAssetDataService
import com.alicejump.okscripttoolkit.core.TemplateImage
import com.alicejump.okscripttoolkit.core.TemplateThumbPipeline
import com.alicejump.okscripttoolkit.settings.OkScriptToolkitSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.Font
import java.awt.GridLayout
import java.beans.PropertyChangeListener
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.BorderFactory
import javax.swing.ImageIcon
import javax.swing.JComboBox
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Visual parity wrapper for the two unified resource windows.
 *
 * The unified workflow/publishing classes keep owning behavior and state. These wrappers only restore the
 * thumbnail-card presentation that was lost when the old Template/Box windows were collapsed into two lists.
 */
class CardPublishingAnnotationToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        PublishingAnnotationToolWindowFactory().createToolWindowContent(project, toolWindow)
        val content = toolWindow.contentManager.contents.lastOrNull() ?: return
        val list = findFirst<JBList<*>>(content.component) ?: return
        content.setDisposer(AnnotationCardDecorator(project, list))
    }
}

class CardResourcePreviewToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        PreviewOnlyResourceToolWindowFactory().createToolWindowContent(project, toolWindow)
        val content = toolWindow.contentManager.contents.lastOrNull() ?: return
        val list = findFirst<JBList<*>>(content.component) ?: return
        val mode = findFirst<JComboBox<*>>(content.component) ?: return
        content.setDisposer(ResourceCardDecorator(project, list, mode))
    }
}

private inline fun <reified T : Component> findFirst(root: Component): T? {
    if (root is T) return root
    if (root !is Container) return null
    for (child in root.components) findFirst<T>(child)?.let { return it }
    return null
}

private data class CardVisual(
    val key: String,
    val name: String,
    val detail: String,
    val tooltip: String,
    val imagePath: Path,
    val bbox: IntArray,
)

private class PreviewCardRenderer(
    private val visual: (Any?) -> CardVisual?,
    private val icon: (CardVisual) -> ImageIcon?,
) : ListCellRenderer<Any?> {
    override fun getListCellRendererComponent(
        list: JList<out Any?>,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val item = visual(value)
        if (item == null) return JBLabel(value?.toString().orEmpty())

        val image = JBLabel(icon(item), SwingConstants.CENTER).apply {
            preferredSize = Dimension(ThumbGridPolicy.CELL_WIDTH, ThumbGridPolicy.THUMB_HEIGHT)
            verticalAlignment = SwingConstants.CENTER
        }
        val name = JBLabel(shortName(item.name), SwingConstants.CENTER).apply {
            font = font.deriveFont(Font.BOLD, 10f)
        }
        val detail = JBLabel(item.detail, SwingConstants.CENTER).apply {
            font = font.deriveFont(10f)
            foreground = if (isSelected) list.selectionForeground else UIUtil.getContextHelpForeground()
        }
        val info = JPanel(GridLayout(2, 1)).apply {
            isOpaque = false
            add(name)
            add(detail)
        }
        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = if (isSelected) list.selectionBackground else list.background
            foreground = if (isSelected) list.selectionForeground else list.foreground
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(if (isSelected) list.selectionForeground else JBColor.border()),
                JBUI.Borders.empty(4),
            )
            toolTipText = item.tooltip
            add(image, BorderLayout.CENTER)
            add(info, BorderLayout.SOUTH)
        }
    }
}

private fun shortName(value: String): String =
    if (value.length > 24) value.take(12) + "…" + value.takeLast(11) else value

private fun configureCardList(list: JBList<*>) {
    list.layoutOrientation = JList.HORIZONTAL_WRAP
    list.visibleRowCount = -1
    list.fixedCellWidth = ThumbGridPolicy.CELL_WIDTH + JBUI.scale(18)
    list.fixedCellHeight = ThumbGridPolicy.THUMB_HEIGHT + JBUI.scale(48)
}

private abstract class ThumbDecorator(
    protected val project: Project,
    list: JBList<*>,
) : Disposable {
    @Suppress("UNCHECKED_CAST")
    protected val list: JBList<Any?> = list as JBList<Any?>
    private val icons = ConcurrentHashMap<String, ImageIcon>()
    private val requested = ConcurrentHashMap.newKeySet<String>()
    private val finished = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var disposed = false

    protected fun iconFor(item: CardVisual): ImageIcon? {
        icons[item.key]?.let { return it }
        if (finished.contains(item.key)) return null
        if (requested.add(item.key)) {
            TemplateThumbPipeline.loadThumbs(
                project,
                listOf(TemplateThumbPipeline.Request(item.key, item.imagePath, item.bbox)),
                ThumbGridPolicy.THUMB_HEIGHT,
            ) { key, icon ->
                requested.remove(key)
                finished.add(key)
                if (icon != null) icons[key] = icon
                if (!disposed) list.repaint()
            }
        }
        return null
    }

    protected fun invalidateThumbs() {
        icons.clear()
        requested.clear()
        finished.clear()
    }

    override fun dispose() {
        disposed = true
        invalidateThumbs()
    }
}

private class AnnotationCardDecorator(project: Project, list: JBList<*>) : ThumbDecorator(project, list) {
    private val renderer = PreviewCardRenderer(::visual, ::iconFor)
    private var reinstalling = false
    private val rendererListener = PropertyChangeListener { event ->
        if (event.propertyName == "cellRenderer" && event.newValue !== renderer && !reinstalling) installRenderer()
    }

    init {
        configureCardList(list)
        installRenderer()
        this.list.addPropertyChangeListener(rendererListener)
    }

    private fun installRenderer() {
        reinstalling = true
        try {
            list.cellRenderer = renderer
        } finally {
            reinstalling = false
        }
    }

    private fun visual(value: Any?): CardVisual? {
        val image = value as? TemplateImage ?: return null
        return CardVisual(
            key = image.file.absolutePath,
            name = image.file.name,
            detail = "${image.width}×${image.height} · ${image.annotations.size}",
            tooltip = image.file.name,
            imagePath = image.file.toPath(),
            bbox = intArrayOf(0, 0, image.width, image.height),
        )
    }

    override fun dispose() {
        list.removePropertyChangeListener(rendererListener)
        super.dispose()
    }
}

private enum class PreviewMode { TEMPLATE, RECT, POINT }

private class ResourceCardDecorator(
    project: Project,
    list: JBList<*>,
    private val mode: JComboBox<*>,
) : ThumbDecorator(project, list) {
    private val generation = AtomicInteger(0)
    @Volatile private var visuals: Map<String, CardVisual> = emptyMap()
    private val renderer = PreviewCardRenderer(
        visual = { value -> visuals[value as? String] },
        icon = ::iconFor,
    )

    init {
        configureCardList(list)
        this.list.cellRenderer = renderer
        mode.addActionListener { refreshVisuals() }
        refreshVisuals()
    }

    private fun refreshVisuals() {
        val current = generation.incrementAndGet()
        val selected = PreviewMode.entries[mode.selectedIndex.coerceIn(0, PreviewMode.entries.lastIndex)]
        CompletableFuture.supplyAsync { loadVisuals(selected) }.whenComplete { result, _ ->
            SwingUtilities.invokeLater {
                if (current != generation.get() || project.isDisposed) return@invokeLater
                visuals = result.orEmpty()
                invalidateThumbs()
                list.repaint()
            }
        }
    }

    private fun loadVisuals(selected: PreviewMode): Map<String, CardVisual> {
        val root = project.service<OkProjectDataService>().rootPath() ?: return emptyMap()
        val settings = OkScriptToolkitSettings.getInstance(project)
        val templateDir = root.resolve(settings.okTemplatesDirectory())
        val templateData = project.service<TemplateAssetDataService>()
        templateData.load(root.toString(), settings.okTemplatesDirectory())
        return when (selected) {
            PreviewMode.TEMPLATE -> {
                val categories = templateData.categories().associate { it.id to it.name }
                buildMap {
                    for (image in templateData.listImages()) {
                        for (annotation in image.annotations) {
                            val name = categories[annotation.categoryId] ?: continue
                            putIfAbsent(
                                name,
                                CardVisual(
                                    key = "template:$name:${image.file.absolutePath}",
                                    name = name,
                                    detail = "${annotation.bbox[2]}×${annotation.bbox[3]}",
                                    tooltip = name,
                                    imagePath = image.file.toPath(),
                                    bbox = annotation.bbox.copyOf(),
                                ),
                            )
                        }
                    }
                }
            }
            PreviewMode.RECT -> {
                val source = project.service<BoxCatalogService>().readAuthoring()
                val sizes = source.images.associateBy { it.file.lowercase() }
                source.boxes.associate { box ->
                    val image = sizes[box.image.lowercase()]
                    box.path to CardVisual(
                        key = "rect:${box.path}:${box.image}",
                        name = box.path,
                        detail = image?.let { "${it.width}×${it.height}" } ?: "rect",
                        tooltip = "self.pos.${box.path}.to_box()",
                        imagePath = templateDir.resolve(box.image),
                        bbox = box.bbox.copyOf(),
                    )
                }
            }
            PreviewMode.POINT -> {
                val source = project.service<PointCatalogService>().read()
                if (source.errors.isNotEmpty()) return emptyMap()
                val sizes = source.file.images.associateBy { it.file.lowercase() }
                source.file.points.mapNotNull { point ->
                    val image = sizes[point.image.lowercase()] ?: return@mapNotNull null
                    point.path to CardVisual(
                        key = "point:${point.path}:${point.image}",
                        name = point.path,
                        detail = "point",
                        tooltip = "self.pos.${point.path}",
                        imagePath = templateDir.resolve(point.image),
                        bbox = pointPreviewBbox(point.x, point.y, image.width, image.height),
                    )
                }.toMap()
            }
        }
    }

    override fun dispose() {
        generation.incrementAndGet()
        super.dispose()
    }
}

internal fun pointPreviewBbox(x: Int, y: Int, width: Int, height: Int): IntArray {
    if (width <= 0 || height <= 0) return intArrayOf(0, 0, 0, 0)
    val contextW = max(1, max(64, (width * 0.12).roundToInt()).coerceAtMost(width))
    val contextH = max(1, max(48, (height * 0.12).roundToInt()).coerceAtMost(height))
    val left = (x - contextW / 2).coerceIn(0, width - contextW)
    val top = (y - contextH / 2).coerceIn(0, height - contextH)
    return intArrayOf(left, top, contextW, contextH)
}
