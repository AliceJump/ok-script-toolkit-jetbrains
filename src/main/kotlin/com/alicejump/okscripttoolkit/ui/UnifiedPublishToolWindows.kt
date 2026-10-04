package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.core.BoxCatalogService
import com.alicejump.okscripttoolkit.core.OkProjectDataService
import com.alicejump.okscripttoolkit.core.PointCatalogService
import com.alicejump.okscripttoolkit.core.PositionPublisherService
import com.alicejump.okscripttoolkit.core.ScreenshotCapture
import com.alicejump.okscripttoolkit.core.TemplateAssetDataService
import com.alicejump.okscripttoolkit.core.TemplateImage
import com.alicejump.okscripttoolkit.core.isPathInsideRoot
import com.alicejump.okscripttoolkit.core.labelEnumPathInputError
import com.alicejump.okscripttoolkit.core.normalizeLabelEnumFile
import com.alicejump.okscripttoolkit.settings.OkScriptToolkitSettings
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBList
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

private val PUBLISH_ANNOTATION_PANEL_KEY =
    Key.create<PublishingAnnotationManagerPanel>("okScriptToolkit.publishingAnnotationPanel")

private data class PublishSelection(
    val template: Boolean,
    val rect: Boolean,
    val point: Boolean,
) {
    val hasPositions: Boolean get() = rect || point
}

private class PublishSelectionDialog(project: Project) : DialogWrapper(project) {
    private val template = JCheckBox("Template", true)
    private val rect = JCheckBox("Rect", true)
    private val point = JCheckBox("Point", true)

    init {
        title = "Publish"
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel().apply {
        layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
        add(JLabel("Select resources to publish:"))
        add(template)
        add(rect)
        add(point)
    }

    override fun doOKAction() {
        if (!template.isSelected && !rect.isSelected && !point.isSelected) {
            Messages.showWarningDialog("Select at least one resource.", "Publish")
            return
        }
        super.doOKAction()
    }

    fun selection(): PublishSelection = PublishSelection(
        template = template.isSelected,
        rect = rect.isSelected,
        point = point.isSelected,
    )
}

private data class TemplatePublishPlan(
    val target: java.nio.file.Path,
    val generateEnum: Boolean,
    val enumFile: String?,
)

private object UnifiedPublishController {
    fun publish(project: Project, onComplete: () -> Unit = {}) {
        val selectionDialog = PublishSelectionDialog(project)
        if (!selectionDialog.showAndGet()) return
        val selection = selectionDialog.selection()

        val positionPlan = if (selection.hasPositions) PositionPublishFlow.configure(project) ?: return else null
        val templatePlan = if (selection.template) configureTemplate(project) ?: return else null

        val publishPositions = {
            if (positionPlan != null) PositionPublishFlow.publish(project, selection.rect, selection.point, positionPlan)
            onComplete()
        }

        if (templatePlan == null) {
            publishPositions()
            return
        }

        val templateData = project.service<TemplateAssetDataService>()
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Publish Template resources", true) {
            override fun run(indicator: ProgressIndicator) {
                templateData.saveToAssets(
                    templatePlan.target.toString(),
                    templatePlan.generateEnum,
                    templatePlan.enumFile,
                ) { done, total ->
                    indicator.checkCanceled()
                    indicator.fraction = if (total > 0) done.toDouble() / total else 0.0
                    indicator.text = "Packing pages $done/$total…"
                }
            }

            override fun onSuccess() {
                notify(project, "Published Template resources to ${templatePlan.target}", NotificationType.INFORMATION)
                publishPositions()
            }

            override fun onThrowable(error: Throwable) {
                notify(project, "Template publish failed: ${error.message ?: error}", NotificationType.ERROR)
            }
        })
    }

    private fun choosePositionFormat(project: Project): PositionPublisherService.Format? {
        val choice = ChooseDialog.show(
            project,
            "Export selected Rect / Point resources as:",
            "Publish",
            listOf("JSON — src/scene/positions.json", "Python — src/scene/ScreenRatio.py + PositionMap.py"),
        ) ?: return null
        return if (choice == 0) PositionPublisherService.Format.JSON else PositionPublisherService.Format.PYTHON
    }

    private fun configureTemplate(project: Project): TemplatePublishPlan? {
        val root = project.service<OkProjectDataService>().rootPath()
        if (root == null) {
            Messages.showErrorDialog(project, "Project root is unavailable.", "Publish")
            return null
        }
        val settings = OkScriptToolkitSettings.getInstance(project)
        val data = project.service<TemplateAssetDataService>()
        data.load(root.toString(), settings.okTemplatesDirectory())
        if (data.readErrors.isNotEmpty()) {
            Messages.showErrorDialog(project, "The template annotation source is invalid.", "Publish")
            return null
        }
        if (data.listImages().none { it.annotations.isNotEmpty() }) {
            Messages.showWarningDialog(project, "There are no Template annotations to publish.", "Publish")
            return null
        }

        val targets = listOf("assets", "ok_tasks/assets")
        val targetIndex = ChooseDialog.show(
            project,
            "Publish Template resources to:",
            "Publish",
            targets,
        ) ?: return null
        val targetName = targets[targetIndex]
        val target = root.resolve(targetName)

        val wantsEnum = Messages.showYesNoDialog(
            project,
            "Generate LabelEnum.py for Template resources?",
            "Publish",
            Messages.getQuestionIcon(),
        ) == Messages.YES
        if (!wantsEnum) return TemplatePublishPlan(target, false, null)

        val current = settings.labelEnumPath().ifBlank { "$targetName/LabelEnum.py" }
        val input = Messages.showInputDialog(
            project,
            "LabelEnum.py path relative to the project root:",
            "Publish",
            Messages.getQuestionIcon(),
            current,
            null,
        ) ?: return null
        if (labelEnumPathInputError(input) != null) {
            Messages.showWarningDialog(project, "LabelEnum.py path must stay inside the project.", "Publish")
            return null
        }
        val normalized = normalizeLabelEnumFile(input.trim()).orEmpty()
        if (normalized.isBlank()) return TemplatePublishPlan(target, false, null)
        settings.setLabelEnumPath(normalized)
        val enumFile = root.resolve(normalized).normalize().toString()
        if (!isPathInsideRoot(root.toString(), enumFile)) {
            Messages.showWarningDialog(project, "LabelEnum.py path must stay inside the project.", "Publish")
            return null
        }
        return TemplatePublishPlan(target, true, enumFile)
    }

    private fun publishPositions(
        project: Project,
        selection: PublishSelection,
        format: PositionPublisherService.Format,
    ) {
        val publisher = project.service<PositionPublisherService>()
        val positionSelection = PositionPublisherService.Selection(selection.rect, selection.point)
        if (positionSelection.rect != positionSelection.point) {
            val answer = Messages.showYesNoDialog(
                project,
                "Publishing only the selected position type replaces the complete Position output and removes unselected positions.\n\nContinue?",
                "Publish",
                Messages.getWarningIcon(),
            )
            if (answer != Messages.YES) return
        }
        var result = publisher.publish(format, positionSelection, false)
        if (result.conflicts.isNotEmpty()) {
            val answer = Messages.showYesNoDialog(
                project,
                "These files are hand-written and would be replaced:\n${result.conflicts.joinToString("\n")}\n\nOverwrite them?",
                "Publish",
                Messages.getWarningIcon(),
            )
            if (answer != Messages.YES) return
            result = publisher.publish(format, positionSelection, true)
        }
        if (result.errors.isNotEmpty()) {
            Messages.showErrorDialog(project, result.errors.joinToString("\n"), "Publish")
        } else {
            Messages.showInfoMessage(project, "Written:\n${result.written.joinToString("\n")}", "Publish")
        }
    }

    private fun notify(project: Project, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("okScriptToolkit")
            .createNotification(content, type)
            .notify(project)
    }
}

/** Annotation Management is the only user-facing Publish entry. */
class PublishingAnnotationToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = PublishingAnnotationManagerPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.putUserData(PUBLISH_ANNOTATION_PANEL_KEY, panel)
        toolWindow.contentManager.addContent(content)
    }
}

private class PublishingAnnotationManagerPanel(private val project: Project) : JPanel(BorderLayout(0, 4)) {
    private val data = project.service<TemplateAssetDataService>()
    private val list = JBList<TemplateImage>()
    private val hardForegroundCheck = HardForegroundToggle.create(project)
    private var images: List<TemplateImage> = emptyList()

    init {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        val refresh = JButton("Refresh")
        val import = JButton("Import")
        val screenshot = JButton("Screenshot")
        val open = JButton("Open")
        val publish = JButton("Publish")
        toolbar.add(hardForegroundCheck)
        toolbar.add(refresh)
        toolbar.add(import)
        toolbar.add(screenshot)
        toolbar.add(open)
        toolbar.add(publish)
        add(toolbar, BorderLayout.NORTH)

        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        add(JScrollPane(list), BorderLayout.CENTER)
        refresh.addActionListener { reload() }
        import.addActionListener { importImages() }
        screenshot.addActionListener { screenshotNow() }
        open.addActionListener { openSelected() }
        publish.addActionListener { UnifiedPublishController.publish(project) { reload() } }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) openSelected()
            }
        })
        reload()
    }

    private fun reload() {
        val root = project.service<OkProjectDataService>().rootPath() ?: return
        val dir = OkScriptToolkitSettings.getInstance(project).okTemplatesDirectory()
        data.load(root.toString(), dir)
        images = data.listImages()
        list.setListData(images.toTypedArray())
        list.cellRenderer = object : javax.swing.DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                l: javax.swing.JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): java.awt.Component {
                val component = super.getListCellRendererComponent(l, value, index, isSelected, cellHasFocus) as JLabel
                component.text = (value as? TemplateImage)?.file?.name ?: ""
                return component
            }
        }
    }

    private fun openSelected() {
        val index = list.selectedIndex
        if (index < 0 || index >= images.size) return
        UnifiedAnnotationDialog(project, images, index).show()
        reload()
    }

    private fun importImages() {
        val root = project.service<OkProjectDataService>().rootPath() ?: return
        val chooser = JFileChooser().apply {
            isMultiSelectionEnabled = true
            fileSelectionMode = JFileChooser.FILES_ONLY
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        val target = root.resolve(OkScriptToolkitSettings.getInstance(project).okTemplatesDirectory()).toFile()
        data.importImages(chooser.selectedFiles.toList(), target)
        reload()
    }

    fun screenshotNow() {
        val root = project.service<OkProjectDataService>().rootPath() ?: return
        val outputDir = root.resolve(OkScriptToolkitSettings.getInstance(project).okTemplatesDirectory())
        val methodOverride = HardForegroundToggle.methodOverride(hardForegroundCheck)
        CompletableFuture.supplyAsync {
            Files.createDirectories(outputDir)
            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
            val output = outputDir.resolve("screenshot_$stamp.png")
            output to ScreenshotCapture(project).captureInteractive(output, methodOverride) { }
        }.whenComplete { result, error ->
            SwingUtilities.invokeLater {
                when {
                    error != null -> Messages.showErrorDialog(project, error.message ?: "Screenshot failed", "Annotation Management")
                    result?.second == null -> reload()
                    result?.second != ScreenshotCapture.CANCELLED -> Messages.showErrorDialog(project, result?.second ?: "Screenshot failed", "Annotation Management")
                }
            }
        }
    }
}

/** Resource Preview is deliberately preview/copy-only; publishing lives in Annotation Management. */
class PreviewOnlyResourceToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = PreviewOnlyResourcePanel(project)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, "", false))
    }
}

private class PreviewOnlyResourcePanel(private val project: Project) : JPanel(BorderLayout(0, 4)) {
    private val mode = javax.swing.JComboBox(arrayOf("Template", "Box", "Point"))
    private val list = JBList<String>()
    private val copy = JButton("Copy")

    init {
        val top = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        top.add(mode)
        top.add(copy)
        add(top, BorderLayout.NORTH)
        add(JScrollPane(list), BorderLayout.CENTER)
        mode.addActionListener { reload() }
        copy.addActionListener { copySelected() }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) copySelected()
            }
        })
        reload()
    }

    private fun reload() {
        val root = project.service<OkProjectDataService>().rootPath()
        root?.let {
            project.service<TemplateAssetDataService>().load(
                it.toString(),
                OkScriptToolkitSettings.getInstance(project).okTemplatesDirectory(),
            )
        }
        val values = when (mode.selectedIndex) {
            0 -> project.service<TemplateAssetDataService>().categories().map { it.name }.distinct().sorted()
            1 -> project.service<BoxCatalogService>().readAuthoring().boxes.map { it.path }.distinct().sorted()
            else -> project.service<PointCatalogService>().read().file.points.map { it.path }.distinct().sorted()
        }
        list.setListData(values.toTypedArray())
    }

    private fun copySelected() {
        val value = list.selectedValue ?: return
        val text = when (mode.selectedIndex) {
            0 -> "fL.$value"
            1 -> "self.pos.$value.to_box()"
            else -> "self.pos.$value"
        }
        com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(StringSelection(text))
    }
}

class ScreenshotToPublishingAnnotationsAction : AnAction(), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(UNIFIED_ANNOTATION_TOOL_WINDOW_ID) ?: return
        toolWindow.show()
        val content = toolWindow.contentManager.contents.firstOrNull() ?: return
        content.getUserData(PUBLISH_ANNOTATION_PANEL_KEY)?.screenshotNow()
    }
}
