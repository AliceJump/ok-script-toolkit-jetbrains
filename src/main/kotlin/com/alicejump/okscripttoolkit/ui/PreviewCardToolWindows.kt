package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.AnnotationUiBundle
import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.alicejump.okscripttoolkit.core.AnnotatedSourcePreview
import com.alicejump.okscripttoolkit.core.BoxCatalogService
import com.alicejump.okscripttoolkit.core.FeatureTemplate
import com.alicejump.okscripttoolkit.core.OkDataChangeService
import com.alicejump.okscripttoolkit.core.OkProjectDataService
import com.alicejump.okscripttoolkit.core.PointCatalogService
import com.alicejump.okscripttoolkit.core.TemplateThumbPipeline
import com.alicejump.okscripttoolkit.settings.OkScriptToolkitSettings
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.ButtonGroup
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import kotlin.math.max
import kotlin.math.roundToInt

class CardPublishingAnnotationToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) =
        PublishingAnnotationToolWindowFactory().createToolWindowContent(project, toolWindow)
}

class CardResourcePreviewToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val preview = UnifiedResourcePreview(project)
        val content = ContentFactory.getInstance().createContent(preview.component, "", false)
        content.setDisposer(preview)
        toolWindow.contentManager.addContent(content)
    }
}

internal data class CardVisual(val key: String, val name: String, val detail: String, val tooltip: String, val imagePath: Path, val bbox: IntArray)

internal class PreviewThumbGeneration {
    private val value = AtomicInteger(0)
    fun current(): Int = value.get()
    fun invalidate() { value.incrementAndGet() }
    fun isCurrent(generation: Int): Boolean = generation == value.get()
}

internal fun runtimeTemplatePreviews(templates: List<FeatureTemplate>, alias: String): Map<String, CardVisual> =
    templates.associate { template ->
        template.name to CardVisual("template:${template.name}:${template.imagePath}", template.name,
            "${template.width}×${template.height}", "$alias.${template.name}", template.imagePath, template.bbox.copyOf())
    }

private enum class PreviewMode(val labelKey: String) { TEMPLATE("mode.template"), RECT("mode.rect"), POINT("mode.point") }

/** 侧栏与宽屏预览共用真实卡片按钮，不依赖列表选中项。 */
internal class UnifiedResourcePreview(private val project: Project) : Disposable {
    val component: JPanel
    private val search = SearchTextField()
    private val generation = AtomicInteger(0)
    private var mode = PreviewMode.TEMPLATE
    private var visuals = emptyList<CardVisual>()
    private var disposed = false
    private val pythonEditor = PythonEditorTarget(project, this)
    private val cards = ResourceThumbnailGrid<CardVisual>(
        visual = { it },
        actions = { item -> listOf(
            ResourceCardAction("＋", OkScriptToolkitBundle.message("gallery.insert")) { insert(item) },
            ResourceCardAction("⧉", OkScriptToolkitBundle.message("gallery.copy")) { copy(item) },
            ResourceCardAction("👁", OkScriptToolkitBundle.message("gallery.open")) { open(item) },
        ) },
        onSingle = ::insert,
        onDouble = ::copy,
        load = { requests, callback -> TemplateThumbPipeline.loadThumbs(project, requests, ResourceThumbnailGrid.THUMB_HEIGHT, onThumb = callback) },
    )

    init {
        search.textEditor.emptyText.text = AnnotationUiBundle.message("preview.search")
        search.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(event: DocumentEvent?) = applyFilter()
            override fun removeUpdate(event: DocumentEvent?) = applyFilter()
            override fun changedUpdate(event: DocumentEvent?) = applyFilter()
        })
        val group = ButtonGroup()
        val toolbar = JPanel(WrappingToolbarLayout()).apply {
            for (target in PreviewMode.entries) add(JToggleButton(AnnotationUiBundle.message(target.labelKey)).apply {
                group.add(this)
                isSelected = target == mode
                addActionListener { if (mode != target) { mode = target; refresh() } }
            })
            add(search)
            add(cards.count)
        }
        component = JPanel(BorderLayout()).apply { add(toolbar, BorderLayout.NORTH); add(cards, BorderLayout.CENTER) }
        project.service<OkDataChangeService>()
        project.messageBus.connect(this).subscribe(OkDataChangeService.TOPIC,
            com.alicejump.okscripttoolkit.core.OkDataChangeListener { refresh() })
        refresh()
    }

    fun refresh() {
        if (disposed || project.isDisposed) return
        val current = generation.incrementAndGet()
        val requestedMode = mode
        visuals = emptyList()
        cards.setItems(emptyList(), invalidate = true)
        cards.showError(AnnotationUiBundle.message("preview.loading"))
        CompletableFuture.supplyAsync { loadVisuals(requestedMode) }.whenComplete { result, error ->
            SwingUtilities.invokeLater {
                if (disposed || project.isDisposed || current != generation.get()) return@invokeLater
                visuals = result.orEmpty()
                applyFilter(invalidate = true)
                if (error != null) cards.showError(AnnotationUiBundle.message("manager.reloadFailed", (error.cause ?: error).message.orEmpty()))
            }
        }
    }

    private fun applyFilter(invalidate: Boolean = false) {
        val query = search.text.trim()
        cards.setItems(visuals.filter { it.name.contains(query, true) || it.tooltip.contains(query, true) }, visuals.size, invalidate)
    }

    private fun loadVisuals(target: PreviewMode): List<CardVisual> {
        val root = project.service<OkProjectDataService>().rootPath() ?: return emptyList()
        val settings = OkScriptToolkitSettings.getInstance(project)
        val directory = root.resolve(settings.okTemplatesDirectory())
        return when (target) {
            PreviewMode.TEMPLATE -> {
                val data = project.service<OkProjectDataService>()
                data.refresh(true)
                runtimeTemplatePreviews(data.features(), settings.featureAliases().firstOrNull() ?: "fL").values.toList()
            }
            PreviewMode.RECT -> {
                val catalog = project.service<BoxCatalogService>()
                val source = catalog.readAuthoring()
                check(catalog.authoringErrors().isEmpty()) { catalog.authoringErrors().joinToString("; ") }
                source.boxes.map { box -> CardVisual("rect:${box.path}:${box.image}", box.path, "${box.bbox[2]}×${box.bbox[3]}",
                    "self.pos.${box.path}.to_box()", directory.resolve(box.image), box.bbox.copyOf()) }
            }
            PreviewMode.POINT -> {
                val source = project.service<PointCatalogService>().read()
                check(source.errors.isEmpty()) { source.errors.joinToString("; ") }
                val sizes = source.file.images.associateBy { it.file.lowercase() }
                source.file.points.mapNotNull { point ->
                    val image = sizes[point.image.lowercase()] ?: return@mapNotNull null
                    CardVisual("point:${point.path}:${point.image}", point.path, "${image.width}×${image.height}",
                        "self.pos.${point.path}", directory.resolve(point.image), pointPreviewBbox(point.x, point.y, image.width, image.height))
                }
            }
        }.sortedBy { it.name }
    }

    private fun insert(item: CardVisual) {
        if (disposed) return
        val text = item.tooltip
        val editor = pythonEditor.editor()
        if (editor == null) {
            copy(item)
            NotificationGroupManager.getInstance().getNotificationGroup("okScriptToolkit")
                .createNotification(OkScriptToolkitBundle.message("gallery.noEditor"), NotificationType.WARNING).notify(project)
            return
        }
        WriteCommandAction.runWriteCommandAction(project) {
            for (caret in editor.caretModel.allCarets.sortedByDescending { it.offset }) {
                editor.document.insertString(caret.offset, text)
                caret.moveToOffset(caret.offset + text.length)
            }
        }
    }

    private fun copy(item: CardVisual) {
        if (!disposed) CopyPasteManager.getInstance().setContents(StringSelection(item.tooltip))
    }

    private fun open(item: CardVisual) {
        val templateMode = mode == PreviewMode.TEMPLATE
        CompletableFuture.supplyAsync {
            val (path, bbox) = if (templateMode) project.service<OkProjectDataService>().findOkTemplateCocoEntry(item.name)
                ?: (item.imagePath to item.bbox) else item.imagePath to item.bbox
            AnnotatedSourcePreview.fileFor(project, path, bbox) ?: path
        }.whenComplete { path, _ -> SwingUtilities.invokeLater {
            if (disposed || project.isDisposed) return@invokeLater
            val file = path?.let { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it) } ?: return@invokeLater
            OpenFileDescriptor(project, file).navigate(true)
        } }
    }

    override fun dispose() { disposed = true; generation.incrementAndGet(); cards.dispose() }
}

internal fun pointPreviewBbox(x: Int, y: Int, width: Int, height: Int): IntArray {
    if (width <= 0 || height <= 0) return intArrayOf(0, 0, 0, 0)
    val contextW = max(1, max(64, (width * 0.12).roundToInt()).coerceAtMost(width))
    val contextH = max(1, max(48, (height * 0.12).roundToInt()).coerceAtMost(height))
    return intArrayOf((x - contextW / 2).coerceIn(0, width - contextW), (y - contextH / 2).coerceIn(0, height - contextH), contextW, contextH)
}
