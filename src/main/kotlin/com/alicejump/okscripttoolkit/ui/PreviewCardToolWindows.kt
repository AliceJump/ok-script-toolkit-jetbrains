package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.AnnotationUiBundle
import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.alicejump.okscripttoolkit.core.AnnotatedSourcePreview
import com.alicejump.okscripttoolkit.core.FeatureTemplate
import com.alicejump.okscripttoolkit.core.OkDataChangeService
import com.alicejump.okscripttoolkit.core.BoxCatalogService
import com.alicejump.okscripttoolkit.core.OkProjectDataService
import com.alicejump.okscripttoolkit.core.PointCatalogService
import com.alicejump.okscripttoolkit.core.TemplateImage
import com.alicejump.okscripttoolkit.core.TemplateThumbPipeline
import com.alicejump.okscripttoolkit.settings.OkScriptToolkitSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.util.Disposer
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
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
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionListener
import java.beans.PropertyChangeListener
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.BorderFactory
import javax.swing.ImageIcon
import javax.swing.JComboBox
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ListSelectionModel
import javax.swing.ListCellRenderer
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Unified source-image cards and runtime resource previews.
 * Annotation editing and publishing stay with the existing management panel; the resource window restores
 * insertion, copying and source navigation while sharing the established thumbnail and editor services.
 */
class CardPublishingAnnotationToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        PublishingAnnotationToolWindowFactory().createToolWindowContent(project, toolWindow)
        val content = toolWindow.contentManager.contents.lastOrNull() ?: return
        val list = findFirst(content.component) { it is JBList<*> } as? JBList<*> ?: return
        val panel = content.getUserData(PUBLISH_ANNOTATION_PANEL_KEY) ?: return
        Disposer.register(panel, AnnotationCardDecorator(project, list))
    }
}

class CardResourcePreviewToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val preview = UnifiedResourcePreview(project)
        val content = ContentFactory.getInstance().createContent(preview.component, "", false)
        content.setDisposer(preview)
        toolWindow.contentManager.addContent(content)
    }
}

internal class UnifiedResourcePreview(project: Project) : Disposable {
    val component: JPanel
    private val decorator: ResourceCardDecorator
    init {
        val list = JBList<String>().apply { selectionMode = ListSelectionModel.SINGLE_SELECTION }
        val mode = JComboBox(arrayOf(
            AnnotationUiBundle.message("mode.template"),
            AnnotationUiBundle.message("mode.rect"),
            AnnotationUiBundle.message("mode.point"),
        ))
        val search = SearchTextField()
        search.textEditor.emptyText.text = AnnotationUiBundle.message("preview.search")
        decorator = ResourceCardDecorator(project, list, mode, search)
        val toolbar = JPanel(WrappingToolbarLayout()).apply {
            addComponentListener(object : java.awt.event.ComponentAdapter() {
                override fun componentResized(event: java.awt.event.ComponentEvent?) { revalidate() }
            })
            add(mode)
            add(JButton(OkScriptToolkitBundle.message("gallery.insert")).apply {
                addActionListener { decorator.insertSelected() }
            })
            add(JButton(OkScriptToolkitBundle.message("gallery.copy")).apply {
                addActionListener { decorator.copySelected() }
            })
            add(JButton(OkScriptToolkitBundle.message("gallery.open")).apply {
                addActionListener { decorator.openSelected() }
            })
            add(JButton(OkScriptToolkitBundle.message("gallery.refresh")).apply {
                addActionListener { decorator.refreshVisuals() }
            })
        }
        component = JPanel(BorderLayout()).apply {
            add(JPanel(BorderLayout()).apply { add(toolbar, BorderLayout.NORTH); add(search, BorderLayout.SOUTH) }, BorderLayout.NORTH)
            add(JScrollPane(list), BorderLayout.CENTER)
        }
    }
    fun refresh() = decorator.refreshVisuals()
    override fun dispose() { Disposer.dispose(decorator) }
}

private fun findFirst(root: Component, predicate: (Component) -> Boolean): Component? {
    if (predicate(root)) return root
    if (root !is Container) return null
    for (child in root.components) findFirst(child, predicate)?.let { return it }
    return null
}

internal data class CardVisual(
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

internal class PreviewThumbGeneration {
    private val value = AtomicInteger(0)

    fun current(): Int = value.get()

    fun invalidate() {
        value.incrementAndGet()
    }

    fun isCurrent(generation: Int): Boolean = generation == value.get()
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
    private val thumbGeneration = PreviewThumbGeneration()
    @Volatile private var disposed = false

    protected fun iconFor(item: CardVisual): ImageIcon? {
        icons[item.key]?.let { return it }
        if (finished.contains(item.key)) return null
        if (requested.add(item.key)) {
            val requestGeneration = thumbGeneration.current()
            TemplateThumbPipeline.loadThumbs(
                project,
                listOf(TemplateThumbPipeline.Request(item.key, item.imagePath, item.bbox)),
                ThumbGridPolicy.THUMB_HEIGHT,
            ) { key, icon ->
                if (!thumbGeneration.isCurrent(requestGeneration)) return@loadThumbs
                requested.remove(key)
                finished.add(key)
                if (icon != null) icons[key] = icon
                if (!disposed) list.repaint()
            }
        }
        return null
    }

    protected fun invalidateThumbs() {
        thumbGeneration.invalidate()
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
        if (event.propertyName == "cellRenderer" && event.newValue !== renderer && !reinstalling) {
            invalidateThumbs()
            installRenderer()
        }
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

internal fun runtimeTemplatePreviews(templates: List<FeatureTemplate>, alias: String): Map<String, CardVisual> =
    templates.associate { template ->
        template.name to CardVisual(
            key = "template:${template.name}:${template.imagePath}",
            name = template.name,
            detail = "${template.width}×${template.height}",
            tooltip = "$alias.${template.name}",
            imagePath = template.imagePath,
            bbox = template.bbox.copyOf(),
        )
    }

private class ResourceCardDecorator(
    project: Project,
    list: JBList<*>,
    private val mode: JComboBox<*>,
    private val search: SearchTextField,
) : ThumbDecorator(project, list) {
    private val generation = AtomicInteger(0)
    @Volatile private var visuals: Map<String, CardVisual> = emptyMap()
    private val renderer = PreviewCardRenderer(
        visual = { value -> visuals[value as? String] },
        icon = ::iconFor,
    )
    private val modeListener = ActionListener { refreshVisuals() }
    private val pythonEditor = PythonEditorTarget(project, this)
    private var clickSelection: String? = null
    private val clicks = ThumbnailClicks(this.list, {
        if (list.selectedValue == clickSelection) insertSelected()
    }, { copySelected() })

    init {
        configureCardList(list)
        this.list.cellRenderer = renderer
        mode.addActionListener(modeListener)
        search.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(event: javax.swing.event.DocumentEvent?) = applyFilter()
            override fun removeUpdate(event: javax.swing.event.DocumentEvent?) = applyFilter()
            override fun changedUpdate(event: javax.swing.event.DocumentEvent?) = applyFilter()
        })
        this.list.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(event: java.awt.event.MouseEvent) {
                val index = list.locationToIndex(event.point)
                if (index < 0 || list.getCellBounds(index, index)?.contains(event.point) != true) return
                clickSelection = list.model.getElementAt(index) as? String
                clicks.mouseClicked(event)
            }
        })
        project.service<OkDataChangeService>()
        project.messageBus.connect(this).subscribe(OkDataChangeService.TOPIC,
            com.alicejump.okscripttoolkit.core.OkDataChangeListener { refreshVisuals() },
        )
        refreshVisuals()
    }

    fun refreshVisuals() {
        cancelPendingClick()
        val current = generation.incrementAndGet()
        val selected = PreviewMode.entries[mode.selectedIndex.coerceIn(0, PreviewMode.entries.lastIndex)]
        val selectedName = list.selectedValue
        visuals = emptyMap()
        invalidateThumbs()
        list.setListData(emptyArray())
        CompletableFuture.supplyAsync { loadVisuals(selected) }.whenComplete { result, _ ->
            SwingUtilities.invokeLater {
                if (current != generation.get() || project.isDisposed) return@invokeLater
                visuals = result.orEmpty()
                invalidateThumbs()
                applyFilter()
                if (selectedName in visuals) list.setSelectedValue(selectedName, true)
                list.repaint()
            }
        }
    }

    private fun loadVisuals(selected: PreviewMode): Map<String, CardVisual> {
        val root = project.service<OkProjectDataService>().rootPath() ?: return emptyMap()
        val settings = OkScriptToolkitSettings.getInstance(project)
        val templateDir = root.resolve(settings.okTemplatesDirectory())
        return when (selected) {
            PreviewMode.TEMPLATE -> {
                val data = project.service<OkProjectDataService>()
                data.refresh(true)
                runtimeTemplatePreviews(data.features(), settings.featureAliases().firstOrNull() ?: "fL")
            }
            PreviewMode.RECT -> {
                val source = project.service<BoxCatalogService>().readAuthoring()
                source.boxes.associate { box ->
                    box.path to CardVisual(
                        key = "rect:${box.path}:${box.image}",
                        name = box.path,
                        detail = "${box.bbox[2]}×${box.bbox[3]}",
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
                        detail = AnnotationUiBundle.message("mode.point"),
                        tooltip = "self.pos.${point.path}",
                        imagePath = templateDir.resolve(point.image),
                        bbox = pointPreviewBbox(point.x, point.y, image.width, image.height),
                    )
                }.toMap()
            }
        }
    }

    private fun applyFilter() {
        cancelPendingClick()
        val selected = list.selectedValue
        val query = search.text.trim()
        list.setListData(visuals.values.filter {
            it.name.contains(query, ignoreCase = true) || it.tooltip.contains(query, ignoreCase = true)
        }.map { it.name }.sorted().toTypedArray())
        if (selected != null) list.setSelectedValue(selected, true)
    }

    private fun cancelPendingClick() {
        (list.getClientProperty("thumbnailClickTimer") as? javax.swing.Timer)?.stop()
    }

    private fun selected(): CardVisual? = visuals[list.selectedValue as? String]

    fun insertSelected() {
        cancelPendingClick()
        val text = selected()?.tooltip ?: return
        val editor = pythonEditor.editor()
        if (editor == null) {
            CopyPasteManager.getInstance().setContents(StringSelection(text))
            com.intellij.notification.NotificationGroupManager.getInstance()
                .getNotificationGroup("okScriptToolkit")
                .createNotification(OkScriptToolkitBundle.message("gallery.noEditor"),
                    com.intellij.notification.NotificationType.WARNING).notify(project)
            return
        }
        WriteCommandAction.runWriteCommandAction(project) {
            for (caret in editor.caretModel.allCarets.sortedByDescending { it.offset }) {
                editor.document.insertString(caret.offset, text)
                caret.moveToOffset(caret.offset + text.length)
            }
        }
    }

    fun copySelected() {
        cancelPendingClick()
        val text = selected()?.tooltip ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(text))
    }

    fun openSelected() {
        cancelPendingClick()
        val item = selected() ?: return
        val templateMode = mode.selectedIndex == PreviewMode.TEMPLATE.ordinal
        CompletableFuture.supplyAsync {
            val (path, bbox) = if (templateMode) {
                project.service<OkProjectDataService>().findOkTemplateCocoEntry(item.name)
                    ?: (item.imagePath to item.bbox)
            } else item.imagePath to item.bbox
            AnnotatedSourcePreview.fileFor(project, path, bbox) ?: path
        }.whenComplete { path, _ ->
            SwingUtilities.invokeLater {
                if (project.isDisposed) return@invokeLater
                val file = path?.let { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it) }
                    ?: return@invokeLater
                OpenFileDescriptor(project, file).navigate(true)
            }
        }
    }

    override fun dispose() {
        cancelPendingClick()
        generation.incrementAndGet()
        mode.removeActionListener(modeListener)
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
