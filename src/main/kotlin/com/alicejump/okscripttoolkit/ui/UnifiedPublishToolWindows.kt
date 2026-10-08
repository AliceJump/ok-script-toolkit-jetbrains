package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.AnnotationUiBundle
import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.alicejump.okscripttoolkit.core.AnnotationSwap
import com.alicejump.okscripttoolkit.core.BoxCatalogService
import com.alicejump.okscripttoolkit.core.PointCatalogService
import com.alicejump.okscripttoolkit.core.deleteAnnotationImage
import com.alicejump.okscripttoolkit.core.CocoAnnotationData
import com.alicejump.okscripttoolkit.core.OkDataChangeService
import com.alicejump.okscripttoolkit.core.OkProjectDataService
import com.alicejump.okscripttoolkit.core.ScreenshotCapture
import com.alicejump.okscripttoolkit.core.TemplateAssetDataService
import com.alicejump.okscripttoolkit.core.TemplateImage
import com.alicejump.okscripttoolkit.settings.OkScriptToolkitSettings
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.TransferHandler
import javax.swing.filechooser.FileNameExtensionFilter
import com.intellij.ui.SearchTextField

internal val PUBLISH_ANNOTATION_PANEL_KEY =
    Key.create<PublishingAnnotationManagerPanel>("okScriptToolkit.publishingAnnotationPanel")

private fun publishingMessage(key: String, vararg values: Any) = AnnotationUiBundle.message(key, *values)
private fun assetMessage(key: String, vararg values: Any) = OkScriptToolkitBundle.message(key, *values)

private data class PublishSelection(val template: Boolean, val rect: Boolean, val point: Boolean) {
    val hasPositions: Boolean get() = rect || point
}

internal data class PublishAvailability(val template: Boolean, val rect: Boolean, val point: Boolean) {
    val any: Boolean get() = template || rect || point
}

internal fun publishAvailability(templateAnnotations: Int, rectAnnotations: Int, pointAnnotations: Int) = PublishAvailability(
    template = templateAnnotations > 0,
    rect = rectAnnotations > 0,
    point = pointAnnotations > 0,
)

private class PublishSelectionDialog(
    project: Project,
    private val availability: PublishAvailability,
) : DialogWrapper(project) {
    private val template = JCheckBox(publishingMessage("mode.template"), availability.template)
    private val rect = JCheckBox(publishingMessage("mode.rect"), availability.rect)
    private val point = JCheckBox(publishingMessage("mode.point"), availability.point)
    init { title = publishingMessage("publish.title"); init() }
    override fun createCenterPanel(): JComponent = JPanel().apply {
        layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
        add(JLabel(publishingMessage("publish.select")))
        if (availability.template) add(template)
        if (availability.rect) add(rect)
        if (availability.point) add(point)
    }
    override fun doOKAction() {
        if (!template.isSelected && !rect.isSelected && !point.isSelected) {
            Messages.showWarningDialog(publishingMessage("publish.selectOne"), title)
        } else super.doOKAction()
    }
    fun selection() = PublishSelection(template.isSelected, rect.isSelected, point.isSelected)
}

private object UnifiedPublishController {
    fun publish(project: Project, onComplete: () -> Unit) {
        val availability = availability(project)
        if (!availability.any) {
            notify(project, publishingMessage("publish.empty"), NotificationType.INFORMATION)
            return
        }
        val dialog = PublishSelectionDialog(project, availability)
        if (!dialog.showAndGet()) return
        val selection = dialog.selection()
        publishSelectedResources(selection.template, selection.hasPositions,
            { TemplatePublishFlow.configure(project) },
            { PositionPublishFlow.configure(project, selection.rect, selection.point) },
            { PositionPublishFlow.publish(project, selection.rect, selection.point, it) },
            { publishTemplate(project, it, onComplete) }, onComplete)
    }

    private fun availability(project: Project): PublishAvailability {
        val root = project.service<OkProjectDataService>().rootPath()
        val templateData = project.service<TemplateAssetDataService>()
        val templateAnnotations = if (root == null) {
            0
        } else {
            templateData.load(root.toString(), OkScriptToolkitSettings.getInstance(project).okTemplatesDirectory())
            if (templateData.readErrors.isEmpty()) templateData.listImages().sumOf { it.annotations.size } else 0
        }

        val boxes = project.service<BoxCatalogService>()
        val rectAnnotations = if (boxes.authoringErrors().isEmpty()) boxes.readAuthoring().boxes.size else 0

        val points = project.service<PointCatalogService>().read()
        val pointAnnotations = if (points.errors.isEmpty()) points.file.points.size else 0

        return publishAvailability(templateAnnotations, rectAnnotations, pointAnnotations)
    }

    private fun publishTemplate(project: Project, template: TemplatePublishPlan, onComplete: () -> Unit) {
        val data = project.service<TemplateAssetDataService>()
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, publishingMessage("publish.templateProgress"), true) {
            override fun run(indicator: ProgressIndicator) {
                data.saveToAssets(template.target.toString(), template.enumFile != null, template.enumFile) { done, total ->
                    indicator.checkCanceled()
                    indicator.fraction = if (total > 0) done.toDouble() / total else 0.0
                    indicator.text = publishingMessage("publish.packing", done, total)
                }
            }
            override fun onSuccess() {
                notify(project, publishingMessage("publish.templateDone", template.target), NotificationType.INFORMATION)
                onComplete()
            }
            override fun onThrowable(error: Throwable) {
                notify(project, publishingMessage("publish.templateFailed", error.message ?: error), NotificationType.ERROR)
            }
        })
    }
}

class PublishingAnnotationToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = PublishingAnnotationManagerPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.putUserData(PUBLISH_ANNOTATION_PANEL_KEY, panel)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

/** 工作原图、图片动作和刷新始终通过同一个面板快照调用。 */
internal class PublishingAnnotationManagerPanel(private val project: Project) : JPanel(BorderLayout(0, 4)), Disposable {
    private val data = project.service<TemplateAssetDataService>()
    private val cards = ResourceThumbnailGrid<TemplateImage>(
        visual = { image ->
            val names = image.annotations.mapNotNull { categoryNames[it.categoryId] }.distinct().joinToString(", ")
            CardVisual(image.file.absolutePath, image.file.name, "${image.width}×${image.height}",
                "${image.file.absolutePath}\n${image.width}×${image.height}\n$names", image.file.toPath(),
                intArrayOf(0, 0, image.width, image.height), names)
        },
        actions = { image -> listOf(
            ResourceCardAction("👁", assetMessage("templateAsset.open")) { viewSource(image) },
            ResourceCardAction("⇄", assetMessage("templateAsset.swap")) { swapImage(image) },
            ResourceCardAction("×", assetMessage("templateAsset.delete")) { deleteImage(image) },
        ) },
        onSingle = ::openImage,
        load = { requests, callback -> com.alicejump.okscripttoolkit.core.TemplateThumbPipeline.loadThumbs(
            project, requests, ResourceThumbnailGrid.THUMB_HEIGHT, onThumb = callback) },
    )
    private val hardForeground = HardForegroundToggle.create(project)
    private val generation = AtomicInteger(0)
    private val swapThumbs = ConcurrentHashMap<String, javax.swing.ImageIcon?>()
    @Volatile private var disposed = false
    private var readFailed = false
    private val search = SearchTextField()
    private var images: List<TemplateImage> = emptyList()
    private var categoryNames: Map<Int, String> = emptyMap()
    init {
        val toolbar = JPanel(WrappingToolbarLayout())
        toolbar.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(event: java.awt.event.ComponentEvent?) { toolbar.revalidate() }
        })
        toolbar.add(hardForeground)
        fun button(label: String, action: () -> Unit) = toolbar.add(JButton(label).apply { addActionListener { action() } })
        button(publishingMessage("manager.import"), ::importImages)
        button(publishingMessage("manager.screenshot"), ::screenshotNow)
        button(publishingMessage("publish.title")) { UnifiedPublishController.publish(project, ::reload) }
        search.textEditor.emptyText.text = publishingMessage("preview.search")
        search.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(event: javax.swing.event.DocumentEvent?) = applyFilter()
            override fun removeUpdate(event: javax.swing.event.DocumentEvent?) = applyFilter()
            override fun changedUpdate(event: javax.swing.event.DocumentEvent?) = applyFilter()
        })
        toolbar.add(search)
        toolbar.add(cards.count)
        add(toolbar, BorderLayout.NORTH)
        add(cards, BorderLayout.CENTER)
        cards.installDropHandler(object : TransferHandler() {
            override fun canImport(support: TransferSupport): Boolean = support.isDataFlavorSupported(TempShotTransferable.FLAVOR)
                || support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
            override fun importData(support: TransferSupport): Boolean {
                if (!canImport(support)) return false
                val files = TempShotTransferable.fileOf(support.transferable)?.let(::listOf)
                    ?: runCatching { (support.transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)?.filterIsInstance<File>() }.getOrNull()
                    ?: return false
                importFiles(files)
                return true
            }
        })
        project.service<OkDataChangeService>()
        project.messageBus.connect(this).subscribe(OkDataChangeService.TOPIC,
            com.alicejump.okscripttoolkit.core.OkDataChangeListener { reload() })
        reload()
    }

    private fun root() = project.service<OkProjectDataService>().rootPath()
    private fun directory() = OkScriptToolkitSettings.getInstance(project).okTemplatesDirectory()
    fun reload() {
        if (disposed || project.isDisposed) return
        val sequence = generation.incrementAndGet()
        val root = root()
        if (root == null) {
            images = emptyList()
            categoryNames = emptyMap()
            applyFilter()
            return
        }
        CompletableFuture.supplyAsync {
            synchronized(data) {
                data.load(root.toString(), directory())
                Triple(data.listImages(), data.readErrors.isNotEmpty(), data.categories().associate { it.id to it.name })
            }
        }.whenComplete { result, error -> SwingUtilities.invokeLater {
            if (disposed || project.isDisposed || sequence != generation.get()) return@invokeLater
            if (error != null) {
                notify(project, publishingMessage("manager.reloadFailed", error.message ?: error), NotificationType.ERROR)
                return@invokeLater
            }
            val (loadedImages, invalid, names) = result
            if (invalid && !readFailed) notify(project, assetMessage("templateAsset.sourceInvalid"), NotificationType.ERROR)
            readFailed = invalid
            images = loadedImages
            categoryNames = names
            applyFilter(invalidate = true)
        } }
    }

    private fun applyFilter(invalidate: Boolean = false) {
        val query = search.text.trim()
        val filtered = images.filter { image ->
            image.file.name.contains(query, ignoreCase = true) || image.annotations.any {
                categoryNames[it.categoryId]?.contains(query, ignoreCase = true) == true
            }
        }
        cards.setItems(filtered, images.size, invalidate)
    }

    private fun openImage(image: TemplateImage) {
        openUnifiedAnnotationEditor(project, images, images.indexOf(image))
    }

    private fun viewSource(image: TemplateImage) {
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(image.file)?.let { OpenFileDescriptor(project, it).navigate(true) }
    }

    private fun deleteImage(image: TemplateImage) {
        if (Messages.showYesNoDialog(project, assetMessage("templateAsset.deleteConfirm", image.file.name),
                assetMessage("templateAsset.delete"), Messages.getWarningIcon()) != Messages.YES) return
        data.reload()
        if (data.readErrors.isNotEmpty()) {
            notify(project, assetMessage("templateAsset.sourceInvalid"), NotificationType.ERROR)
            return
        }
        val boxes = project.service<BoxCatalogService>()
        val points = project.service<PointCatalogService>()
        val sources = listOfNotNull(boxes.authoringPath(), points.authoringPath())
        val deleted = sources.size == 2 && deleteAnnotationImage(image.file, sources,
            { boxes.removeImage(image.file.name) && points.removeImage(image.file.name) },
            { data.deleteImage(image.file) })
        if (!deleted) notify(project, assetMessage("templateAsset.deleteFailed", image.file.name), NotificationType.ERROR)
        reload()
    }

    private fun swapImage(source: TemplateImage) {
        data.reload()
        val candidates = AnnotationSwap.swapCandidates(source, data.listImages())
        if (candidates.isEmpty()) { notify(project, assetMessage("templateAsset.swapNoTarget"), NotificationType.INFORMATION); return }
        val chooser = SwapTargetDialog(project, source, candidates, swapThumbs)
        if (!chooser.showAndGet()) return
        val target = chooser.selected ?: return
        data.reload()
        val sourceSize = data.swapImageSize(source.file)
        val targetSize = data.swapImageSize(target.file)
        if (sourceSize == null || targetSize == null) { notify(project, assetMessage("templateAsset.swapSizeUnknown"), NotificationType.ERROR); return }
        val names = data.categories().associate { it.id to it.name }
        fun boxes(file: File) = data.getImageEntryForFile(file.name)?.let {
            AnnotationSwap.namedBoxes(data.getAnnotationsForImage(it.id), names)?.map { (name, bbox) -> name to bbox.copyOf() }
        } ?: emptyList()
        val sourceBoxes = boxes(source.file)
        val targetBoxes = boxes(target.file)
        if (sourceBoxes.isEmpty() && targetBoxes.isEmpty()) { notify(project, assetMessage("templateAsset.swapNothing"), NotificationType.INFORMATION); return }
        val from = AnnotationSwap.Size(sourceSize.first, sourceSize.second)
        val to = AnnotationSwap.Size(targetSize.first, targetSize.second)
        val detail = buildList {
            add(assetMessage("templateAsset.swapQuestion", source.file.name, target.file.name))
            if (!AnnotationSwap.isSameSize(from, to)) add(assetMessage("templateAsset.swapScaled", "${from.width}×${from.height}", "${to.width}×${to.height}"))
            add(assetMessage("templateAsset.swapCounts", source.file.name, sourceBoxes.size, target.file.name, targetBoxes.size))
        }.joinToString("\n")
        if (Messages.showYesNoDialog(project, detail, assetMessage("templateAsset.swapTitle"), Messages.getQuestionIcon()) != Messages.YES) return
        val result = data.saveSwapEdits(mapOf(source.file.name to sourceBoxes, target.file.name to targetBoxes),
            mapOf(source.file to sourceSize, target.file to targetSize),
            AnnotationSwap.editsForSwap(source.file.name, from, sourceBoxes, target.file.name, to, targetBoxes))
        when (result) {
            CocoAnnotationData.SwapSaveResult.SAVED -> notify(project, assetMessage("templateAsset.swapDone", source.file.name, target.file.name), NotificationType.INFORMATION)
            CocoAnnotationData.SwapSaveResult.CHANGED -> notify(project, assetMessage("templateAsset.swapChanged"), NotificationType.WARNING)
            CocoAnnotationData.SwapSaveResult.FAILED -> notify(project, assetMessage("templateAsset.swapFailed"), NotificationType.ERROR)
        }
        reload()
    }

    private fun importImages() {
        val chooser = JFileChooser().apply {
            isMultiSelectionEnabled = true
            fileSelectionMode = JFileChooser.FILES_ONLY
            fileFilter = FileNameExtensionFilter(assetMessage("templateAsset.import"), "png", "jpg", "jpeg", "bmp")
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) importFiles(chooser.selectedFiles.toList())
    }

    private fun importFiles(files: List<File>) {
        val target = root()?.resolve(directory())?.toFile() ?: return
        CompletableFuture.supplyAsync { data.importImages(files, target) }.whenComplete { count, error -> SwingUtilities.invokeLater {
            if (disposed || project.isDisposed) return@invokeLater
            if (error != null || count == null || count <= 0) notify(project, assetMessage("templateAsset.importFailed"), NotificationType.ERROR)
            else notify(project, assetMessage("templateAsset.imported", count), NotificationType.INFORMATION)
            reload()
        } }
    }

    fun screenshotNow() {
        val root = root() ?: return
        val directory = directory()
        val outputDirectory = root.resolve(directory)
        val method = HardForegroundToggle.methodOverride(hardForeground)
        CompletableFuture.supplyAsync {
            Files.createDirectories(outputDirectory)
            data.load(root.toString(), directory)
            val output = outputDirectory.resolve(data.nextImageName() + ".png")
            ScreenshotCapture(project).captureInteractive(output, method) { }
        }.whenComplete { result, error -> SwingUtilities.invokeLater {
            if (disposed || project.isDisposed) return@invokeLater
            if (error != null || (result != null && result != ScreenshotCapture.CANCELLED)) {
                notify(project, publishingMessage("manager.screenshotFailed", error?.message ?: result.orEmpty()), NotificationType.ERROR)
            }
            reload()
        } }
    }

    override fun dispose() { disposed = true; generation.incrementAndGet(); cards.dispose() }
}

private fun notify(project: Project, message: String, type: NotificationType) {
    NotificationGroupManager.getInstance().getNotificationGroup("okScriptToolkit").createNotification(message, type).notify(project)
}

class ScreenshotToPublishingAnnotationsAction : AnAction(), DumbAware {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val window = ToolWindowManager.getInstance(project).getToolWindow(UNIFIED_ANNOTATION_TOOL_WINDOW_ID) ?: return
        window.show { window.contentManager.contents.firstOrNull()?.getUserData(PUBLISH_ANNOTATION_PANEL_KEY)?.screenshotNow() }
    }
}
