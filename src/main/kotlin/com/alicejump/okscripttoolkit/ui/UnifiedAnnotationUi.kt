package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.AnnotationUiBundle
import com.alicejump.okscripttoolkit.core.AnnotationConflictChoice
import com.alicejump.okscripttoolkit.core.AnnotationDataChanges
import com.alicejump.okscripttoolkit.core.AnnotationMergeMode
import com.alicejump.okscripttoolkit.core.AnnotationSessionSyncState
import com.alicejump.okscripttoolkit.core.BoxCatalogService
import com.alicejump.okscripttoolkit.core.CocoAnnotationEdit
import com.alicejump.okscripttoolkit.core.CoordinateTuple
import com.alicejump.okscripttoolkit.core.CoordinateTupleFormat
import com.alicejump.okscripttoolkit.core.MergeShape
import com.alicejump.okscripttoolkit.core.NormalizedAnnotationRect
import com.alicejump.okscripttoolkit.core.OkProjectDataService
import com.alicejump.okscripttoolkit.core.PointCatalogService
import com.alicejump.okscripttoolkit.core.PositionPublisherService
import com.alicejump.okscripttoolkit.core.ResourceFileTransaction
import com.alicejump.okscripttoolkit.core.TemplateAssetDataService
import com.alicejump.okscripttoolkit.core.TemplateImage
import com.alicejump.okscripttoolkit.core.nextAnnotationId
import com.alicejump.okscripttoolkit.core.reconcileAnnotationSession
import com.alicejump.okscripttoolkit.core.resolveAnnotationSessionConflicts
import com.alicejump.okscripttoolkit.settings.OkScriptToolkitSettings
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.event.MouseWheelEvent
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import javax.imageio.ImageIO
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JToggleButton
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal const val UNIFIED_ANNOTATION_TOOL_WINDOW_ID = "ok-script Annotation Management"
internal const val UNIFIED_RESOURCE_PREVIEW_TOOL_WINDOW_ID = "ok-script Resource Preview"
internal val UNIFIED_ANNOTATION_PANEL_KEY =
    com.intellij.openapi.util.Key.create<AnnotationManagerPanel>("okScriptToolkit.unifiedAnnotationPanel")

private fun ui(key: String, vararg params: Any): String = AnnotationUiBundle.message(key, *params)

internal enum class AnnotationKind { TEMPLATE, RECT, POINT }

internal fun AnnotationKind.nextAnnotationKind(): AnnotationKind = when (this) {
    AnnotationKind.TEMPLATE -> AnnotationKind.RECT
    AnnotationKind.RECT -> AnnotationKind.POINT
    AnnotationKind.POINT -> AnnotationKind.TEMPLATE
}

private data class UnifiedShape(
    val id: Int,
    val name: String,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
)

private fun UnifiedShape.toMergeShape() = MergeShape(id, name, x, y, w, h)
private fun MergeShape.toUnifiedShape() = UnifiedShape(id, name, x, y, w, h)
private fun List<UnifiedShape>.toMergeShapes() = map(UnifiedShape::toMergeShape)
private fun List<MergeShape>.toUnifiedShapes() = map(MergeShape::toUnifiedShape)
private fun AnnotationKind.mergeMode(): AnnotationMergeMode = when (this) {
    AnnotationKind.TEMPLATE -> AnnotationMergeMode.TEMPLATE
    AnnotationKind.RECT -> AnnotationMergeMode.RECT
    AnnotationKind.POINT -> AnnotationMergeMode.POINT
}

private data class SessionKey(val file: String, val kind: AnnotationKind)
private data class ShapeSession(
    val key: SessionKey,
    var shapes: MutableList<UnifiedShape>,
    var dirty: Boolean = false,
    var nextId: Int = 1,
    var sync: AnnotationSessionSyncState,
)
private data class EditTransaction(
    val id: Long,
    val key: SessionKey,
    val before: List<UnifiedShape>,
    val after: List<UnifiedShape>,
    var applied: Boolean = true,
)

/**
 * JetBrains 统一标注器。宿主仍采用 OK/Cancel 批量提交，但模板/框/点共享同一个画布和交互协议。
 */
class UnifiedAnnotationDialog(
    private val project: Project,
    private val images: List<TemplateImage>,
    startIndex: Int,
) : DialogWrapper(project) {
    companion object {
        private const val PREF_SHARED_UNDO = "okScriptToolkit.annotation.sharedUndo"
        private const val PREF_COORD_XYWH = "okScriptToolkit.annotation.coordPreferXywh"
        private const val MAX_HISTORY = 100
        private const val POINT_RADIUS = 6
        private const val HANDLE_MARGIN = 8
        private const val MIN_RECT = 3
        private val NORMAL = JBColor(0xE53935, 0xFF5252)
        private val SELECTED = JBColor(0x0078D4, 0x4A9EFF)
        private val HOVER = JBColor(0xE08700, 0xFFA02E)
        private val COORD = JBColor(0x7A5AF8, 0xA48BFF)
        private val MAPPER = ObjectMapper()
    }

    private val templateData = project.service<TemplateAssetDataService>()
    private val boxCatalog = project.service<BoxCatalogService>()
    private val pointCatalog = project.service<PointCatalogService>()
    private val prefs = PropertiesComponent.getInstance()

    private val canvas = UnifiedCanvas()
    private val conflictPanel = AnnotationConflictPanel()
    private val templateMode = JRadioButton(ui("mode.template"))
    private val rectMode = JRadioButton(ui("mode.rect"))
    private val pointMode = JRadioButton(ui("mode.point"))
    private val sharedUndo = JCheckBox(ui("sharedUndo"), prefs.getBoolean(PREF_SHARED_UNDO, true))
    private val preferXywh = JCheckBox("XYWH", prefs.getBoolean(PREF_COORD_XYWH, false))
    private val drawToggle = JToggleButton(ui("tool.draw"))
    private val coordToggle = JToggleButton(ui("tool.coords"))
    private val deleteButton = JButton(ui("tool.delete"))
    private val undoButton = JButton("↩")
    private val redoButton = JButton("↪")
    private val prevButton = JButton("◀")
    private val nextButton = JButton("▶")
    private val navLabel = JBLabel()
    private val statusLabel = JBLabel(" ")
    private val rows = JPanel()

    private var currentIndex = startIndex.coerceIn(0, (images.size - 1).coerceAtLeast(0))
    private var kind = AnnotationKind.TEMPLATE
    private val sessions = mutableMapOf<SessionKey, ShapeSession>()
    private val globalUndo = ArrayDeque<EditTransaction>()
    private val globalRedo = ArrayDeque<EditTransaction>()
    private val kindUndo = AnnotationKind.entries.associateWith { ArrayDeque<EditTransaction>() }.toMutableMap()
    private val kindRedo = AnnotationKind.entries.associateWith { ArrayDeque<EditTransaction>() }.toMutableMap()
    private var nextTransactionId = 1L
    private var annotationSubscription: AutoCloseable? = null
    private var saving = false

    init {
        title = ui("manager.title")
        setOKButtonText(ui("manager.save"))
        init()
        boxCatalog.readAuthoring()
        loadImage(currentIndex, preserveViewport = false)
        annotationSubscription = AnnotationDataChanges.subscribe { changed ->
            if (!saving) UIUtil.invokeLaterIfNeeded { if (!saving) handleExternalChange(changed) }
        }
    }

    private val currentImage: TemplateImage get() = images[currentIndex]
    private val currentKey: SessionKey get() = SessionKey(currentImage.file.name, kind)

    override fun createCenterPanel(): JComponent {
        val modes = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        val group = ButtonGroup()
        listOf(templateMode, rectMode, pointMode).forEach { group.add(it); modes.add(it) }
        templateMode.isSelected = true
        templateMode.addActionListener { switchKind(AnnotationKind.TEMPLATE) }
        rectMode.addActionListener { switchKind(AnnotationKind.RECT) }
        pointMode.addActionListener { switchKind(AnnotationKind.POINT) }
        sharedUndo.addActionListener {
            prefs.setValue(PREF_SHARED_UNDO, sharedUndo.isSelected, true)
            refreshHistoryButtons()
        }
        preferXywh.toolTipText = ui("coord.preferenceTooltip")
        preferXywh.addActionListener {
            prefs.setValue(PREF_COORD_XYWH, preferXywh.isSelected, false)
            canvas.refreshCoordinateReadout()
        }
        modes.add(sharedUndo)
        modes.add(preferXywh)

        drawToggle.addActionListener { canvas.setTool(if (drawToggle.isSelected) Tool.DRAW else Tool.NONE) }
        coordToggle.addActionListener { canvas.setTool(if (coordToggle.isSelected) Tool.COORD else Tool.NONE) }
        deleteButton.addActionListener { canvas.deleteSelected() }
        undoButton.addActionListener { undo() }
        redoButton.addActionListener { redo() }
        prevButton.addActionListener { navigate(-1) }
        nextButton.addActionListener { navigate(1) }
        listOf(drawToggle, coordToggle, deleteButton, undoButton, redoButton, prevButton, nextButton).forEach { it.isFocusable = false }

        val tools = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        tools.add(drawToggle); tools.add(coordToggle); tools.add(deleteButton); tools.add(undoButton); tools.add(redoButton)
        tools.add(prevButton); tools.add(nextButton); tools.add(navLabel)

        val north = JPanel(BorderLayout())
        north.add(modes, BorderLayout.NORTH)
        north.add(tools, BorderLayout.SOUTH)

        rows.layout = BoxLayout(rows, BoxLayout.Y_AXIS)
        val listPanel = JPanel(BorderLayout(0, 4))
        listPanel.preferredSize = Dimension(300, 560)
        listPanel.border = BorderFactory.createEmptyBorder(0, 8, 0, 0)
        listPanel.add(JBLabel(ui("list.title")), BorderLayout.NORTH)
        listPanel.add(JScrollPane(rows), BorderLayout.CENTER)
        listPanel.add(conflictPanel, BorderLayout.SOUTH)

        canvas.preferredSize = Dimension(900, 560)
        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, canvas, listPanel)
        split.resizeWeight = 1.0
        split.dividerLocation = 900

        val root = JPanel(BorderLayout(0, 4))
        root.add(north, BorderLayout.NORTH)
        root.add(split, BorderLayout.CENTER)
        statusLabel.foreground = UIUtil.getContextHelpForeground()
        root.add(statusLabel, BorderLayout.SOUTH)
        return root
    }

    private fun switchKind(next: AnnotationKind) {
        if (next == kind) return
        canvas.finishTransientEdit()
        kind = next
        drawToggle.text = if (kind == AnnotationKind.POINT) ui("tool.point") else ui("tool.draw")
        canvas.applySession(session(currentKey), keepViewport = true)
        syncRows()
        refreshHistoryButtons()
        updateConflictStatus()
    }

    private fun cycleKind() {
        when (kind.nextAnnotationKind()) {
            AnnotationKind.TEMPLATE -> templateMode.doClick()
            AnnotationKind.RECT -> rectMode.doClick()
            AnnotationKind.POINT -> pointMode.doClick()
        }
    }

    private fun navigate(delta: Int) {
        if (images.isEmpty()) return
        val next = (currentIndex + delta).coerceIn(0, images.lastIndex)
        if (next == currentIndex) return
        canvas.finishTransientEdit()
        currentIndex = next
        loadImage(currentIndex, preserveViewport = false)
    }

    private fun loadImage(index: Int, preserveViewport: Boolean) {
        if (images.isEmpty()) return
        currentIndex = index.coerceIn(0, images.lastIndex)
        val buffered = runCatching { ImageIO.read(currentImage.file) }.getOrNull()
        canvas.setImage(buffered, session(currentKey), preserveViewport)
        navLabel.text = "${currentIndex + 1}/${images.size}  ${currentImage.file.name}"
        prevButton.isEnabled = currentIndex > 0
        nextButton.isEnabled = currentIndex < images.lastIndex
        syncRows()
        refreshHistoryButtons()
        updateConflictStatus()
    }

    private fun sourcePath(targetKind: AnnotationKind): Path? = when (targetKind) {
        AnnotationKind.TEMPLATE -> templateData.annotationFile
        AnnotationKind.RECT -> boxCatalog.authoringPath()
        AnnotationKind.POINT -> pointCatalog.authoringPath()
    }

    private fun sourceRevision(targetKind: AnnotationKind): String? {
        val source = sourcePath(targetKind) ?: return null
        return try { Files.readString(source) } catch (_: java.nio.file.NoSuchFileException) { null } catch (_: Exception) { null }
    }

    private fun sameSource(left: Path?, right: Path): Boolean {
        left ?: return false
        val a = left.toAbsolutePath().normalize()
        val b = right.toAbsolutePath().normalize()
        return if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            a.toString().equals(b.toString(), ignoreCase = true)
        } else a == b
    }

    private fun readSessionShapes(key: SessionKey): List<UnifiedShape> = when (key.kind) {
        AnnotationKind.TEMPLATE -> {
            val image = templateData.getImageEntryForFile(key.file)
            val names = templateData.categories().associate { it.id to it.name }
            image?.let { templateData.getAnnotationsForImage(it.id) }.orEmpty().mapNotNull { ann ->
                val name = names[ann.categoryId] ?: return@mapNotNull null
                UnifiedShape(ann.id, name, ann.bbox[0], ann.bbox[1], ann.bbox[2], ann.bbox[3])
            }
        }
        AnnotationKind.RECT -> boxCatalog.boxesForImage(key.file).mapIndexed { index, box ->
            UnifiedShape(index + 1, box.path, box.bbox[0], box.bbox[1], box.bbox[2], box.bbox[3])
        }
        AnnotationKind.POINT -> pointCatalog.pointsForImage(key.file).mapIndexed { index, point ->
            UnifiedShape(index + 1, point.path, point.x, point.y, 0, 0)
        }
    }

    private fun session(key: SessionKey): ShapeSession = sessions.getOrPut(key) {
        val revision = sourceRevision(key.kind)
        val sourceValid = reloadSource(key.kind)
        val items = readSessionShapes(key)
        val acceptedRevision = if (sourceValid) revision else null
        ShapeSession(
            key = key,
            shapes = items.toMutableList(),
            nextId = nextAnnotationId(1, items.toMergeShapes(), items.toMergeShapes()),
            sync = AnnotationSessionSyncState(items.toMergeShapes(), items.toMergeShapes(), acceptedRevision, dirty = false),
        )
    }

    private fun reloadSource(targetKind: AnnotationKind): Boolean = when (targetKind) {
        AnnotationKind.TEMPLATE -> {
            templateData.reload()
            templateData.readErrors.isEmpty()
        }
        AnnotationKind.RECT -> {
            boxCatalog.annotations.reload()
            boxCatalog.annotations.readErrors.isEmpty()
        }
        AnnotationKind.POINT -> pointCatalog.read().errors.isEmpty()
    }

    private fun clearHistoryFor(key: SessionKey) {
        globalUndo.removeAll { it.key == key }
        globalRedo.removeAll { it.key == key }
        kindUndo.getValue(key.kind).removeAll { it.key == key }
        kindRedo.getValue(key.kind).removeAll { it.key == key }
    }

    private fun reconcileKindFromDisk(targetKind: AnnotationKind): String? {
        val revision = sourceRevision(targetKind)
        if (!reloadSource(targetKind)) return "parse"
        for (session in sessions.values.filter { it.key.kind == targetKind }) {
            val external = readSessionShapes(session.key)
            val state = if (session.sync.pending == null) {
                session.sync.copy(local = session.shapes.toMergeShapes(), dirty = session.dirty)
            } else session.sync
            val next = reconcileAnnotationSession(targetKind.mergeMode(), state, external.toMergeShapes(), revision)
            session.sync = next
            session.shapes = next.displayShapes.toUnifiedShapes().toMutableList()
            session.dirty = next.dirty
            session.nextId = nextAnnotationId(session.nextId, session.shapes.toMergeShapes(), next.base)
            clearHistoryFor(session.key)
        }
        if (currentKey.kind == targetKind && sessions.containsKey(currentKey)) {
            canvas.applySession(session(currentKey), keepViewport = true)
            syncRows()
            refreshHistoryButtons()
            updateConflictStatus()
        }
        return null
    }

    private fun handleExternalChange(changed: Path) {
        val changedKind = AnnotationKind.entries.firstOrNull { sameSource(sourcePath(it), changed) } ?: return
        val error = reconcileKindFromDisk(changedKind)
        if (error != null) {
            if (kind == changedKind) statusLabel.text = ui("external.invalid")
            return
        }
        if (kind == changedKind) updateConflictStatus(externalChanged = true)
    }

    private fun updateConflictStatus(externalChanged: Boolean = false) {
        val current = sessions[currentKey]
        val conflicts = current?.sync?.pending?.result?.conflicts.orEmpty()
        if (conflicts.isNotEmpty()) {
            conflictPanel.showConflicts(conflicts, ::applyConflictChoices)
            statusLabel.text = ui("external.conflict")
            return
        }
        conflictPanel.clearConflicts()
        if (externalChanged) statusLabel.text = ui("external.synced")
    }

    private fun applyConflictChoices(choices: Map<String, AnnotationConflictChoice>) {
        val current = sessions[currentKey] ?: return
        val pending = current.sync.pending ?: return
        val diskRevision = sourceRevision(kind)
        if (diskRevision != pending.externalRevision) {
            val error = reconcileKindFromDisk(kind)
            statusLabel.text = if (error == null) ui("external.changedAgain") else ui("external.changedAgainInvalid")
            updateConflictStatus()
            return
        }
        val resolved = resolveAnnotationSessionConflicts(kind.mergeMode(), current.sync, choices, diskRevision)
        if (resolved == null) {
            statusLabel.text = ui("external.choiceStale")
            updateConflictStatus()
            return
        }
        current.sync = resolved
        current.shapes = resolved.local.toUnifiedShapes().toMutableList()
        current.dirty = true
        current.nextId = nextAnnotationId(current.nextId, current.shapes.toMergeShapes(), resolved.base)
        clearHistoryFor(current.key)
        canvas.applySession(current, keepViewport = true)
        syncRows()
        refreshHistoryButtons()
        conflictPanel.clearConflicts()
        statusLabel.text = ui("external.resolved")
    }

    private fun ensureExternalMergedBeforeSave(): String? {
        val dirtyKinds = sessions.values.filter { it.dirty }.mapTo(linkedSetOf()) { it.key.kind }
        for (targetKind in dirtyKinds) {
            val accepted = sessions.values.firstOrNull { it.key.kind == targetKind }?.let { session ->
                session.sync.pending?.externalRevision ?: session.sync.revision
            }
            if (sourceRevision(targetKind) != accepted) {
                reconcileKindFromDisk(targetKind)?.let { return "$targetKind:$it" }
            }
        }
        return if (sessions.values.any { it.sync.hasConflicts }) "conflict" else null
    }

    private fun saveAll(): String? {
        ensureExternalMergedBeforeSave()?.let { return it }
        val dirty = sessions.values.filter { it.dirty }
        if (dirty.isEmpty()) return null

        fun fileFor(session: ShapeSession) =
            images.firstOrNull { PointCatalogService.sameImage(it.file.name, session.key.file) }?.file

        val templateEdits = dirty.filter { it.key.kind == AnnotationKind.TEMPLATE }.map { session ->
            val file = fileFor(session) ?: return "template:image"
            val size = templateData.readImageHeaderSize(file) ?: return "template:image"
            CocoAnnotationEdit(file.name, size, session.shapes.map { it.name to intArrayOf(it.x, it.y, it.w, it.h) })
        }
        val rectEdits = dirty.filter { it.key.kind == AnnotationKind.RECT }.map { session ->
            val file = fileFor(session) ?: return "rect:image"
            val size = templateData.readImageHeaderSize(file) ?: return "rect:image"
            CocoAnnotationEdit(file.name, size, session.shapes.map { it.name to intArrayOf(it.x, it.y, it.w, it.h) })
        }
        val pointEdits = linkedMapOf<Path, List<Pair<String, Point>>>()
        dirty.filter { it.key.kind == AnnotationKind.POINT }.forEach { session ->
            val file = fileFor(session) ?: return "point:image"
            pointEdits[file.toPath()] = session.shapes.map { it.name to Point(it.x, it.y) }
        }

        val resourcePaths = mutableListOf<Path>()
        if (pointEdits.isNotEmpty()) resourcePaths.add(pointCatalog.authoringPath() ?: return "point:path")
        if (templateEdits.isNotEmpty()) resourcePaths.add(templateData.annotationFile ?: return "template:path")
        if (rectEdits.isNotEmpty()) resourcePaths.add(boxCatalog.authoringPath() ?: return "rect:path")
        val transaction = ResourceFileTransaction.capture(resourcePaths) ?: return "snapshot"

        fun rollback(error: String): String {
            val restored = transaction.rollback()
            if (templateEdits.isNotEmpty()) templateData.reload()
            if (rectEdits.isNotEmpty()) boxCatalog.annotations.reload()
            return if (restored) error else "$error:rollback"
        }

        pointCatalog.savePoints(pointEdits)?.let { return rollback("point:$it") }
        if (templateEdits.isNotEmpty() && !templateData.saveAnnotationEdits(templateEdits)) {
            return rollback("template:${templateData.lastError ?: "write"}")
        }
        if (rectEdits.isNotEmpty() && !boxCatalog.annotations.saveAnnotationEdits(rectEdits)) {
            return rollback("rect:${boxCatalog.annotations.lastError ?: "write"}")
        }
        return null
    }

    override fun doOKAction() {
        canvas.finishTransientEdit()
        saving = true
        val error = try { saveAll() } finally { saving = false }
        if (error != null) {
            val message = if (error == "conflict") ui("external.unresolvedSave") else ui("manager.saveFailed", error)
            Messages.showErrorDialog(project, message, ui("manager.title"))
            return
        }
        annotationSubscription?.close()
        annotationSubscription = null
        super.doOKAction()
    }

    override fun doCancelAction() {
        annotationSubscription?.close()
        annotationSubscription = null
        super.doCancelAction()
    }

    private fun occupiedNames(targetKind: AnnotationKind, currentFile: String): MutableSet<String> {
        val names = when (targetKind) {
            AnnotationKind.TEMPLATE -> {
                val categoryNames = templateData.categories().associate { it.id to it.name }
                val openFiles = sessions.keys.filter { it.kind == AnnotationKind.TEMPLATE }
                    .mapTo(mutableSetOf()) { it.file.lowercase() }
                templateData.listImages()
                    .filter { it.file.name.lowercase() !in openFiles }
                    .flatMap { image -> image.annotations.mapNotNull { categoryNames[it.categoryId] } }
                    .toMutableSet()
            }
            AnnotationKind.RECT -> boxCatalog.pathOwners().keys.toMutableSet()
            AnnotationKind.POINT -> pointCatalog.pathOwners().keys.toMutableSet()
        }
        sessions.filterKeys { it.kind == targetKind }.values.forEach { open ->
            when (targetKind) {
                AnnotationKind.TEMPLATE -> Unit
                AnnotationKind.RECT -> boxCatalog.boxesForImage(open.key.file).forEach { names.remove(it.path) }
                AnnotationKind.POINT -> pointCatalog.pointsForImage(open.key.file).forEach { names.remove(it.path) }
            }
            open.shapes.forEach { names += it.name }
        }
        session(SessionKey(currentFile, targetKind)).shapes.forEach { names += it.name }
        return names
    }

    private fun uniqueName(raw: String): String {
        val base = raw.trim()
        val names = occupiedNames(kind, currentImage.file.name)
        if (base !in names) return base
        var suffix = 2
        while ("$base$suffix" in names) suffix++
        return "$base$suffix"
    }

    private fun validName(value: String): Boolean {
        val name = value.trim()
        if (name.isEmpty()) return false
        if (kind == AnnotationKind.TEMPLATE) return true
        return PointCatalogService.positionPathError(name) == null
    }

    private fun promptName(initial: String = ""): String? {
        if (sessions[currentKey]?.sync?.hasConflicts == true) return null
        var value = initial
        while (true) {
            val answer = Messages.showInputDialog(project, ui("name.prompt"), ui("name.title"), null, value, null) ?: return null
            val name = answer.trim()
            if (validName(name)) return name
            Messages.showErrorDialog(project, ui("name.invalid"), ui("name.invalidTitle"))
            value = name
        }
    }

    private fun recordEdit(key: SessionKey, before: List<UnifiedShape>, after: List<UnifiedShape>) {
        if (before == after) return
        val session = sessions[key] ?: return
        if (session.sync.hasConflicts) {
            session.shapes = session.sync.displayShapes.toUnifiedShapes().toMutableList()
            if (key == currentKey) canvas.applySession(session, keepViewport = true)
            return
        }
        val tx = EditTransaction(nextTransactionId++, key, before, after)
        globalUndo.addLast(tx)
        kindUndo.getValue(key.kind).addLast(tx)
        while (globalUndo.size > MAX_HISTORY) globalUndo.removeFirst()
        while (kindUndo.getValue(key.kind).size > MAX_HISTORY) kindUndo.getValue(key.kind).removeFirst()
        globalRedo.clear()
        kindRedo.getValue(key.kind).clear()
        session.dirty = true
        session.sync = session.sync.copy(local = after.toMergeShapes(), dirty = true, pending = null)
        refreshHistoryButtons()
    }

    private fun popMatching(stack: ArrayDeque<EditTransaction>, wantApplied: Boolean, targetKind: AnnotationKind?): EditTransaction? {
        while (stack.isNotEmpty()) {
            val tx = stack.removeLast()
            if (tx.applied == wantApplied && (targetKind == null || tx.key.kind == targetKind)) return tx
        }
        return null
    }

    private fun undo() {
        if (sessions[currentKey]?.sync?.hasConflicts == true) return
        val tx = if (sharedUndo.isSelected) popMatching(globalUndo, true, null)
        else popMatching(kindUndo.getValue(kind), true, kind) ?: return
        if (tx == null) return
        tx.applied = false
        globalRedo.addLast(tx)
        kindRedo.getValue(tx.key.kind).addLast(tx)
        applyTransaction(tx, tx.before)
    }

    private fun redo() {
        if (sessions[currentKey]?.sync?.hasConflicts == true) return
        val tx = if (sharedUndo.isSelected) popMatching(globalRedo, false, null)
        else popMatching(kindRedo.getValue(kind), false, kind) ?: return
        if (tx == null) return
        tx.applied = true
        globalUndo.addLast(tx)
        kindUndo.getValue(tx.key.kind).addLast(tx)
        applyTransaction(tx, tx.after)
    }

    private fun applyTransaction(tx: EditTransaction, snapshot: List<UnifiedShape>) {
        val s = session(tx.key)
        s.shapes = snapshot.toMutableList()
        s.dirty = true
        s.sync = s.sync.copy(local = snapshot.toMergeShapes(), dirty = true, pending = null)
        s.nextId = nextAnnotationId(s.nextId, snapshot.toMergeShapes(), s.sync.base)
        if (tx.key == currentKey) canvas.applySession(s, keepViewport = true)
        refreshHistoryButtons()
        syncRows()
    }

    private fun refreshHistoryButtons() {
        val blocked = sessions[currentKey]?.sync?.hasConflicts == true
        undoButton.isEnabled = !blocked && if (sharedUndo.isSelected) globalUndo.any { it.applied } else kindUndo.getValue(kind).any { it.applied }
        redoButton.isEnabled = !blocked && if (sharedUndo.isSelected) globalRedo.any { !it.applied } else kindRedo.getValue(kind).any { !it.applied }
    }

    private fun syncRows() {
        rows.removeAll()
        val selected = canvas.selectedIds()
        for (shape in session(currentKey).shapes) {
            val button = JButton(shape.name)
            button.horizontalAlignment = javax.swing.SwingConstants.LEFT
            button.isBorderPainted = shape.id in selected
            button.addActionListener {
                canvas.selectOnly(shape.id)
                syncRows()
            }
            button.maximumSize = Dimension(Int.MAX_VALUE, button.preferredSize.height)
            rows.add(button)
        }
        rows.revalidate(); rows.repaint()
    }

    private enum class Tool { NONE, DRAW, COORD }

    private inner class UnifiedCanvas : JComponent() {
        private var image: BufferedImage? = null
        private var sessionRef: ShapeSession? = null
        private var scale = 1.0
        private var fitScale = 1.0
        private var offsetX = 0.0
        private var offsetY = 0.0
        private var tool = Tool.NONE
        private val selected = linkedSetOf<Int>()
        private var hovered = -1
        private var drawStart: Point? = null
        private var drawPreview: Point? = null
        private var dragId: Int? = null
        private var dragStart: Point? = null
        private var dragOriginal: UnifiedShape? = null
        private var resizeId: Int? = null
        private var resizeHandle: String? = null
        private var resizeStart: Point? = null
        private var resizeOriginal: UnifiedShape? = null
        private var coordRect: Rectangle? = null
        private var panning = false
        private var panStart: Point? = null
        private var panOffset: Pair<Double, Double>? = null

        init {
            isFocusable = true
            isOpaque = true
            background = UIUtil.getPanelBackground()
            installKeys()
            installMouse()
        }

        private fun editingBlocked(): Boolean = sessionRef?.sync?.hasConflicts == true

        private fun clearTransientInteraction() {
            drawStart = null
            drawPreview = null
            dragId = null
            dragStart = null
            dragOriginal = null
            resizeId = null
            resizeHandle = null
            resizeStart = null
            resizeOriginal = null
            panning = false
            panStart = null
            panOffset = null
        }

        fun setImage(buffered: BufferedImage?, s: ShapeSession, preserveViewport: Boolean) {
            image = buffered
            if (!preserveViewport) {
                recalcFit()
                scale = fitScale
                recalcOffset()
            }
            applySession(s, keepViewport = true)
        }

        fun applySession(s: ShapeSession, keepViewport: Boolean) {
            sessionRef = s
            selected.clear(); hovered = -1
            tool = Tool.NONE
            clearTransientInteraction()
            coordRect = null
            drawToggle.isSelected = false; coordToggle.isSelected = false
            drawToggle.isEnabled = !s.sync.hasConflicts
            coordToggle.isEnabled = !s.sync.hasConflicts
            deleteButton.isEnabled = !s.sync.hasConflicts
            cursor = Cursor.getDefaultCursor()
            if (!keepViewport) { recalcFit(); scale = fitScale; recalcOffset() }
            repaint()
        }

        fun selectedIds(): Set<Int> = selected.toSet()
        fun selectOnly(id: Int) { selected.clear(); selected += id; repaint() }
        fun finishTransientEdit() { clearTransientInteraction() }

        fun setTool(next: Tool) {
            if (editingBlocked()) return
            tool = next
            drawStart = null; drawPreview = null
            if (next != Tool.COORD) { coordRect = null; statusLabel.text = " " }
            drawToggle.isSelected = next == Tool.DRAW
            coordToggle.isSelected = next == Tool.COORD
            cursor = if (next == Tool.DRAW || next == Tool.COORD) Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR) else Cursor.getDefaultCursor()
            repaint()
        }

        private fun shapes(): MutableList<UnifiedShape> = sessionRef?.shapes ?: mutableListOf()

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val source = image ?: return
            val g2 = g.create() as Graphics2D
            g2.drawImage(source, offsetX.roundToInt(), offsetY.roundToInt(), (source.width * scale).roundToInt(), (source.height * scale).roundToInt(), null)
            shapes().forEach { shape -> paintShape(g2, shape) }
            paintConflictCandidates(g2)
            coordRect?.let { rect ->
                val r = toScreen(rect)
                g2.color = COORD; g2.stroke = BasicStroke(2f); g2.drawRect(r.x, r.y, r.width, r.height)
            }
            if (drawStart != null && drawPreview != null && kind != AnnotationKind.POINT) {
                val a = toImage(drawStart!!); val b = toImage(drawPreview!!)
                val rect = Rectangle(min(a.x,b.x), min(a.y,b.y), abs(a.x-b.x), abs(a.y-b.y))
                val r = toScreen(rect); g2.color = JBColor.GREEN; g2.drawRect(r.x,r.y,r.width,r.height)
            }
            g2.dispose()
        }

        private fun paintShape(g2: Graphics2D, shape: UnifiedShape) {
            val isSelected = shape.id in selected
            val color = if (isSelected) SELECTED else if (shape.id == hovered) HOVER else NORMAL
            g2.color = color
            g2.stroke = BasicStroke(if (isSelected) 2.5f else 2f)
            if (kind == AnnotationKind.POINT) {
                val p = toScreenPoint(Point(shape.x, shape.y))
                g2.drawOval(p.x - 4, p.y - 4, 8, 8)
                g2.drawLine(p.x - POINT_RADIUS, p.y, p.x + POINT_RADIUS, p.y)
                g2.drawLine(p.x, p.y - POINT_RADIUS, p.x, p.y + POINT_RADIUS)
            } else {
                val r = toScreen(Rectangle(shape.x, shape.y, shape.w, shape.h))
                g2.drawRect(r.x, r.y, r.width, r.height)
                if (isSelected && selected.size == 1) handlePoints(r).values.forEach { p -> g2.fillRect(p.x - 3, p.y - 3, 6, 6) }
            }
        }

        private fun paintConflictCandidates(g2: Graphics2D) {
            val conflicts = sessionRef?.sync?.pending?.result?.conflicts.orEmpty()
            for (conflict in conflicts) {
                conflict.local?.let { paintConflictCandidate(g2, it, external = false) }
                conflict.external?.let { paintConflictCandidate(g2, it, external = true) }
            }
        }

        private fun paintConflictCandidate(g2: Graphics2D, shape: MergeShape, external: Boolean) {
            g2.color = if (external) HOVER else SELECTED
            g2.stroke = if (external) {
                BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(7f, 4f), 0f)
            } else BasicStroke(2f)
            if (kind == AnnotationKind.POINT || (shape.w == 0 && shape.h == 0)) {
                val p = toScreenPoint(Point(shape.x, shape.y))
                g2.drawOval(p.x - 5, p.y - 5, 10, 10)
                g2.drawLine(p.x - 8, p.y, p.x + 8, p.y)
                g2.drawLine(p.x, p.y - 8, p.x, p.y + 8)
            } else {
                val r = toScreen(Rectangle(shape.x, shape.y, shape.w, shape.h))
                g2.drawRect(r.x, r.y, r.width, r.height)
            }
        }

        private fun installKeys() {
            fun bind(stroke: KeyStroke, name: String, action: () -> Unit) {
                getInputMap(WHEN_FOCUSED).put(stroke, name)
                getActionMap().put(name, object : AbstractAction() { override fun actionPerformed(e: ActionEvent?) = action() })
            }
            bind(KeyStroke.getKeyStroke(KeyEvent.VK_M, 0), "cycleMode") { this@UnifiedAnnotationDialog.cycleKind() }
            bind(KeyStroke.getKeyStroke(KeyEvent.VK_R, 0), "draw") { setTool(if (tool == Tool.DRAW) Tool.NONE else Tool.DRAW) }
            bind(KeyStroke.getKeyStroke(KeyEvent.VK_C, 0), "coord") { setTool(if (tool == Tool.COORD) Tool.NONE else Tool.COORD) }
            bind(KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK), "undo") { this@UnifiedAnnotationDialog.undo() }
            bind(KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK), "redo") { this@UnifiedAnnotationDialog.redo() }
            bind(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK), "copy") { copySelected() }
            bind(KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK), "paste") { pasteClipboard() }
            bind(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "delete") { deleteSelected() }
        }

        private fun installMouse() {
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    requestFocusInWindow()
                    if (editingBlocked()) return
                    if (SwingUtilities.isRightMouseButton(e)) return
                    if (tool == Tool.DRAW) {
                        if (kind == AnnotationKind.POINT) createPointAt(e.point) else { drawStart = e.point; drawPreview = e.point }
                        return
                    }
                    if (tool == Tool.COORD) { drawStart = e.point; drawPreview = e.point; return }
                    val resize = if (selected.size == 1 && kind != AnnotationKind.POINT) findHandle(e.point) else null
                    if (resize != null) {
                        val shape = shapes().first { it.id == selected.first() }
                        resizeId = shape.id; resizeHandle = resize; resizeStart = e.point; resizeOriginal = shape
                        return
                    }
                    val id = hit(e.point)
                    if (id != null) {
                        if (e.isControlDown || e.isShiftDown || e.isMetaDown) {
                            if (!selected.add(id)) selected.remove(id)
                            syncRows(); repaint(); return
                        }
                        if (id !in selected || selected.size > 1) { selected.clear(); selected += id; syncRows() }
                        if (selected.size == 1) {
                            dragId = id; dragStart = e.point; dragOriginal = shapes().firstOrNull { it.id == id }
                        }
                    } else {
                        selected.clear(); syncRows(); repaint()
                        if (scale > fitScale + 1e-9) { panning = true; panStart = e.point; panOffset = offsetX to offsetY }
                    }
                }

                override fun mouseReleased(e: MouseEvent) {
                    if (editingBlocked()) return
                    if (tool == Tool.DRAW && kind != AnnotationKind.POINT && drawStart != null) { finishRect(e.point); return }
                    if (tool == Tool.COORD && drawStart != null) { finishCoord(e.point); return }
                    finishDragOrResize()
                    panning = false; panStart = null; panOffset = null
                }

                override fun mouseClicked(e: MouseEvent) {
                    if (!editingBlocked() && e.clickCount == 2 && tool == Tool.NONE) hit(e.point)?.let(::editName)
                }
            })
            addMouseMotionListener(object : MouseMotionAdapter() {
                override fun mouseDragged(e: MouseEvent) {
                    if (editingBlocked()) return
                    when {
                        drawStart != null && (tool == Tool.DRAW || tool == Tool.COORD) -> { drawPreview = e.point; repaint() }
                        resizeId != null -> resizeTo(e.point)
                        dragId != null -> dragTo(e.point)
                        panning && panStart != null && panOffset != null -> {
                            offsetX = panOffset!!.first + e.x - panStart!!.x; offsetY = panOffset!!.second + e.y - panStart!!.y; recalcOffset(); repaint()
                        }
                    }
                }
                override fun mouseMoved(e: MouseEvent) { hovered = hit(e.point) ?: -1; repaint() }
            })
            addMouseWheelListener { e: MouseWheelEvent ->
                image ?: return@addMouseWheelListener
                val old = scale
                val next = (if (e.wheelRotation < 0) scale * 1.1 else scale / 1.1).coerceIn(fitScale, fitScale * 50.0)
                if (abs(next - old) < 1e-9) return@addMouseWheelListener
                val ix = (e.x - offsetX) / old; val iy = (e.y - offsetY) / old
                scale = next; offsetX = e.x - ix * next; offsetY = e.y - iy * next; recalcOffset(); repaint()
            }
        }

        private fun createPointAt(screen: Point) {
            if (editingBlocked()) return
            val source = image ?: return
            val p = toImage(screen)
            val x = p.x.coerceIn(0, source.width); val y = p.y.coerceIn(0, source.height)
            val name = promptName() ?: return
            addShape(name, x, y, 0, 0)
            setTool(Tool.NONE)
        }

        private fun finishRect(screen: Point) {
            if (editingBlocked()) return
            val source = image ?: return
            val start = drawStart ?: return
            drawStart = null; drawPreview = null
            val a = toImage(start); val b = toImage(screen)
            val x = min(a.x,b.x).coerceIn(0, source.width)
            val y = min(a.y,b.y).coerceIn(0, source.height)
            val x2 = max(a.x,b.x).coerceIn(0, source.width)
            val y2 = max(a.y,b.y).coerceIn(0, source.height)
            if (x2 - x < MIN_RECT || y2 - y < MIN_RECT) { repaint(); return }
            val name = promptName() ?: return
            addShape(name, x, y, x2-x, y2-y)
            setTool(Tool.NONE)
        }

        private fun addShape(rawName: String, x: Int, y: Int, w: Int, h: Int, autoRename: Boolean = false) {
            if (editingBlocked()) return
            val s = sessionRef ?: return
            val name = if (autoRename) uniqueName(rawName) else rawName.trim()
            if (!autoRename && name in occupiedNames(kind, currentImage.file.name)) {
                Messages.showErrorDialog(project, ui("name.duplicate"), ui("name.duplicateTitle"))
                return
            }
            val before = s.shapes.toList()
            val shape = UnifiedShape(s.nextId++, name, x, y, if (kind == AnnotationKind.POINT) 0 else w, if (kind == AnnotationKind.POINT) 0 else h)
            s.shapes.add(shape)
            selected.clear(); selected += shape.id
            recordEdit(s.key, before, s.shapes.toList())
            syncRows(); repaint()
        }

        private fun editName(id: Int) {
            if (editingBlocked()) return
            val s = sessionRef ?: return
            val index = s.shapes.indexOfFirst { it.id == id }
            if (index < 0) return
            val old = s.shapes[index]
            val proposed = promptName(old.name) ?: return
            if (proposed != old.name && proposed in occupiedNames(kind, currentImage.file.name)) {
                Messages.showErrorDialog(project, ui("name.duplicate"), ui("name.duplicateTitle"))
                return
            }
            val before = s.shapes.toList()
            s.shapes[index] = old.copy(name = proposed)
            recordEdit(s.key, before, s.shapes.toList()); syncRows(); repaint()
        }

        fun deleteSelected() {
            if (editingBlocked()) return
            val s = sessionRef ?: return
            if (selected.isEmpty()) return
            val before = s.shapes.toList()
            s.shapes.removeAll { it.id in selected }
            selected.clear(); hovered = -1
            recordEdit(s.key, before, s.shapes.toList()); syncRows(); repaint()
        }

        private fun copySelected() {
            val source = image ?: return
            val selectedShapes = shapes().filter { it.id in selected }
            if (selectedShapes.isEmpty()) return
            val text = if (selectedShapes.size == 1) {
                val s = selectedShapes.single()
                val rect = NormalizedAnnotationRect(s.x.toDouble()/source.width, s.y.toDouble()/source.height,
                    if (kind == AnnotationKind.POINT) 0.0 else s.w.toDouble()/source.width,
                    if (kind == AnnotationKind.POINT) 0.0 else s.h.toDouble()/source.height)
                val node = MAPPER.createObjectNode().put("name", s.name)
                val bbox = MAPPER.createArrayNode().add(rect.x).add(rect.y).add(rect.w).add(rect.h)
                node.set<com.fasterxml.jackson.databind.JsonNode>("bbox", bbox)
                MAPPER.writeValueAsString(node)
            } else {
                val x1 = selectedShapes.minOf { it.x }
                val y1 = selectedShapes.minOf { it.y }
                val x2 = selectedShapes.maxOf { it.x + if (kind == AnnotationKind.POINT) 0 else it.w }
                val y2 = selectedShapes.maxOf { it.y + if (kind == AnnotationKind.POINT) 0 else it.h }
                val rect = NormalizedAnnotationRect(x1.toDouble()/source.width, y1.toDouble()/source.height,
                    (x2-x1).toDouble()/source.width, (y2-y1).toDouble()/source.height)
                CoordinateTuple.format(rect, coordinatePreference(), separator = ", ")
            }
            CopyPasteManager.getInstance().setContents(StringSelection(text))
            statusLabel.text = text
        }

        private fun pasteClipboard() {
            if (editingBlocked() || tool != Tool.NONE) return
            val text = runCatching { CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor) as? String }.getOrNull()?.trim().orEmpty()
            if (text.isEmpty()) return
            val payload = parseClipboard(text)
            if (payload == null) { statusLabel.text = ui("clipboard.invalid"); return }
            var rect = payload.second
            if (rect.isPoint && kind != AnnotationKind.POINT) {
                statusLabel.text = ui("clipboard.pointOnly")
                return
            }
            if (kind == AnnotationKind.POINT && !rect.isPoint) rect = rect.centerPoint()
            val source = image ?: return
            val x = (rect.x * source.width).roundToInt().coerceIn(0, source.width)
            val y = (rect.y * source.height).roundToInt().coerceIn(0, source.height)
            val w = if (kind == AnnotationKind.POINT) 0 else max(1, (rect.w * source.width).roundToInt())
            val h = if (kind == AnnotationKind.POINT) 0 else max(1, (rect.h * source.height).roundToInt())
            val named = payload.first
            if (named != null) {
                val candidate = uniqueName(named)
                val name = if (validName(candidate)) candidate else promptName(candidate) ?: return
                addShape(name, x, y, w, h, autoRename = false)
            } else {
                val name = promptName() ?: return
                addShape(name, x, y, w, h, autoRename = false)
            }
        }

        private fun parseClipboard(text: String): Pair<String?, NormalizedAnnotationRect>? {
            runCatching {
                val json = MAPPER.readTree(text)
                if (json.isObject && json.path("bbox").isArray && json.path("bbox").size() == 4) {
                    val values = json.path("bbox").map { it.asDouble(Double.NaN) }
                    val rect = CoordinateTuple.asXywh(values) ?: return null
                    return json.path("name").takeIf { it.isTextual && it.asText().trim().isNotEmpty() }?.asText()?.trim() to rect
                }
                if (json.isArray && json.size() == 4) {
                    val values = json.map { it.asDouble(Double.NaN) }
                    return null to (CoordinateTuple.interpret(values, coordinatePreference())?.rect ?: return null)
                }
            }
            val values = text.replace(Regex("[\\[\\](){}]"), " ").split(Regex("[\\s,;]+"))
                .filter { it.isNotBlank() }.mapNotNull { it.toDoubleOrNull() }
            if (values.size != 4) return null
            return null to (CoordinateTuple.interpret(values, coordinatePreference())?.rect ?: return null)
        }

        private fun coordinatePreference(): CoordinateTupleFormat = if (preferXywh.isSelected) CoordinateTupleFormat.XYWH else CoordinateTupleFormat.XYXY

        private fun finishCoord(screen: Point) {
            val source = image ?: return
            val start = drawStart ?: return
            drawStart = null; drawPreview = null
            val a = toImage(start); val b = toImage(screen)
            val x = min(a.x,b.x).coerceIn(0, source.width); val y = min(a.y,b.y).coerceIn(0, source.height)
            val x2 = max(a.x,b.x).coerceIn(0, source.width); val y2 = max(a.y,b.y).coerceIn(0, source.height)
            if (x2-x < MIN_RECT || y2-y < MIN_RECT) { coordRect = null; repaint(); return }
            coordRect = Rectangle(x,y,x2-x,y2-y)
            copyCoord()
        }

        private fun copyCoord() {
            val source = image ?: return
            val r = coordRect ?: return
            val rect = NormalizedAnnotationRect(r.x.toDouble()/source.width, r.y.toDouble()/source.height, r.width.toDouble()/source.width, r.height.toDouble()/source.height)
            val text = CoordinateTuple.format(rect, coordinatePreference(), separator = ", ")
            CopyPasteManager.getInstance().setContents(StringSelection(text)); statusLabel.text = text; repaint()
        }

        fun refreshCoordinateReadout() { if (coordRect != null) copyCoord() }

        private fun dragTo(p: Point) {
            if (editingBlocked()) return
            val source = image ?: return
            val id = dragId ?: return
            val start = dragStart ?: return
            val original = dragOriginal ?: return
            val s = sessionRef ?: return
            val dx = ((p.x-start.x)/scale).roundToInt(); val dy = ((p.y-start.y)/scale).roundToInt()
            val maxX = if (kind == AnnotationKind.POINT) source.width else source.width-original.w
            val maxY = if (kind == AnnotationKind.POINT) source.height else source.height-original.h
            val updated = original.copy(x=(original.x+dx).coerceIn(0,maxX.coerceAtLeast(0)), y=(original.y+dy).coerceIn(0,maxY.coerceAtLeast(0)))
            val index = s.shapes.indexOfFirst { it.id == id }; if (index >= 0) s.shapes[index] = updated
            repaint()
        }

        private fun resizeTo(p: Point) {
            if (editingBlocked() || kind == AnnotationKind.POINT) return
            val source = image ?: return
            val id = resizeId ?: return
            val start = resizeStart ?: return
            val o = resizeOriginal ?: return
            val h = resizeHandle ?: return
            val dx = (p.x-start.x)/scale; val dy = (p.y-start.y)/scale
            var x=o.x.toDouble(); var y=o.y.toDouble(); var w=o.w.toDouble(); var height=o.h.toDouble()
            if (h in listOf("left","tl","bl")) { x=o.x+dx; w=o.w-dx }
            if (h in listOf("right","tr","br")) w=o.w+dx
            if (h in listOf("top","tl","tr")) { y=o.y+dy; height=o.h-dy }
            if (h in listOf("bottom","bl","br")) height=o.h+dy
            w=max(5.0,w); height=max(5.0,height); x=x.coerceIn(0.0,source.width-w); y=y.coerceIn(0.0,source.height-height)
            val updated=o.copy(x=x.roundToInt(),y=y.roundToInt(),w=w.roundToInt().coerceAtMost(source.width-x.roundToInt()),h=height.roundToInt().coerceAtMost(source.height-y.roundToInt()))
            val s=sessionRef?:return; val index=s.shapes.indexOfFirst{it.id==id}; if(index>=0)s.shapes[index]=updated; repaint()
        }

        private fun finishDragOrResize() {
            if (editingBlocked()) return
            val s = sessionRef ?: return
            val original = dragOriginal ?: resizeOriginal
            val id = dragId ?: resizeId
            if (original != null && id != null) {
                val now = s.shapes.firstOrNull { it.id == id }
                if (now != null && now != original) {
                    val after = s.shapes.toList()
                    val before = after.map { if (it.id == id) original else it }
                    recordEdit(s.key, before, after)
                }
            }
            dragId=null; dragStart=null; dragOriginal=null; resizeId=null; resizeHandle=null; resizeStart=null; resizeOriginal=null
        }

        private fun hit(p: Point): Int? {
            for (shape in shapes().asReversed()) {
                if (kind == AnnotationKind.POINT) {
                    val sp = toScreenPoint(Point(shape.x,shape.y)); if (abs(p.x-sp.x)<=POINT_RADIUS+3 && abs(p.y-sp.y)<=POINT_RADIUS+3) return shape.id
                } else if (toScreen(Rectangle(shape.x,shape.y,shape.w,shape.h)).contains(p)) return shape.id
            }
            return null
        }

        private fun findHandle(p: Point): String? {
            val id = selected.singleOrNull() ?: return null
            val shape = shapes().firstOrNull { it.id == id } ?: return null
            val r = toScreen(Rectangle(shape.x,shape.y,shape.w,shape.h))
            val left=abs(p.x-r.x)<=HANDLE_MARGIN; val right=abs(p.x-(r.x+r.width))<=HANDLE_MARGIN
            val top=abs(p.y-r.y)<=HANDLE_MARGIN; val bottom=abs(p.y-(r.y+r.height))<=HANDLE_MARGIN
            return when { top&&left->"tl";top&&right->"tr";bottom&&left->"bl";bottom&&right->"br";top->"top";bottom->"bottom";left->"left";right->"right";else->null }
        }

        private fun handlePoints(r: Rectangle): Map<String,Point> = mapOf(
            "tl" to Point(r.x,r.y), "tr" to Point(r.x+r.width,r.y), "bl" to Point(r.x,r.y+r.height), "br" to Point(r.x+r.width,r.y+r.height),
            "top" to Point(r.x+r.width/2,r.y), "bottom" to Point(r.x+r.width/2,r.y+r.height), "left" to Point(r.x,r.y+r.height/2), "right" to Point(r.x+r.width,r.y+r.height/2),
        )

        private fun recalcFit() {
            val source=image?:return
            val w=if(width>0)width else 900; val h=if(height>0)height else 560
            fitScale=min(w.toDouble()/source.width,h.toDouble()/source.height).coerceAtLeast(0.01)
        }
        private fun recalcOffset() {
            val source=image?:return
            val shownW=source.width*scale; val shownH=source.height*scale
            if(shownW<=width) offsetX=(width-shownW)/2 else offsetX=offsetX.coerceIn(width-shownW,0.0)
            if(shownH<=height) offsetY=(height-shownH)/2 else offsetY=offsetY.coerceIn(height-shownH,0.0)
        }
        private fun toImage(p:Point)=Point(((p.x-offsetX)/scale).roundToInt(),((p.y-offsetY)/scale).roundToInt())
        private fun toScreenPoint(p:Point)=Point((p.x*scale+offsetX).roundToInt(),(p.y*scale+offsetY).roundToInt())
        private fun toScreen(r:Rectangle)=Rectangle((r.x*scale+offsetX).roundToInt(),(r.y*scale+offsetY).roundToInt(),(r.width*scale).roundToInt(),(r.height*scale).roundToInt())
    }
}

class UnifiedAnnotationToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = AnnotationManagerPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.putUserData(UNIFIED_ANNOTATION_PANEL_KEY, panel)
        toolWindow.contentManager.addContent(content)
    }
}

internal class AnnotationManagerPanel(private val project: Project) : JPanel(BorderLayout(0, 4)) {
    private val data = project.service<TemplateAssetDataService>()
    private val list = JBList<TemplateImage>()
    private val hardForegroundCheck = HardForegroundToggle.create(project)
    private var images: List<TemplateImage> = emptyList()

    init {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT,4,2))
        val refresh = JButton(ui("manager.refresh"))
        val import = JButton(ui("manager.import"))
        val screenshot = JButton(ui("manager.screenshot"))
        toolbar.add(hardForegroundCheck); toolbar.add(refresh); toolbar.add(import); toolbar.add(screenshot)
        add(toolbar, BorderLayout.NORTH)
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = javax.swing.DefaultListCellRenderer()
        add(JScrollPane(list), BorderLayout.CENTER)
        refresh.addActionListener { reload() }
        import.addActionListener { importImages() }
        screenshot.addActionListener { screenshotNow() }
        list.addMouseListener(object:MouseAdapter(){ override fun mouseClicked(e:MouseEvent){ if(e.clickCount==2)openSelected() } })
        list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "openSelected")
        list.actionMap.put("openSelected", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) = openSelected()
        })
        reload()
    }

    private fun reload() {
        val root=project.service<OkProjectDataService>().rootPath()?:return
        val dir=OkScriptToolkitSettings.getInstance(project).okTemplatesDirectory()
        data.load(root.toString(),dir)
        images=data.listImages()
        list.setListData(images.toTypedArray())
        list.cellRenderer=object:javax.swing.DefaultListCellRenderer(){
            override fun getListCellRendererComponent(l:javax.swing.JList<*>?,value:Any?,index:Int,isSelected:Boolean,cellHasFocus:Boolean):java.awt.Component{
                val c=super.getListCellRendererComponent(l,value,index,isSelected,cellHasFocus) as javax.swing.JLabel
                val image=value as? TemplateImage; c.text=image?.file?.name ?: ""; return c
            }
        }
    }

    private fun openSelected() {
        val index=list.selectedIndex
        if(index<0||index>=images.size)return
        UnifiedAnnotationDialog(project,images,index).show()
        reload()
    }

    private fun importImages() {
        val root=project.service<OkProjectDataService>().rootPath()?:return
        val chooser=JFileChooser().apply { isMultiSelectionEnabled=true; fileSelectionMode=JFileChooser.FILES_ONLY }
        if(chooser.showOpenDialog(this)!=JFileChooser.APPROVE_OPTION)return
        val target=root.resolve(OkScriptToolkitSettings.getInstance(project).okTemplatesDirectory()).toFile()
        data.importImages(chooser.selectedFiles.toList(),target)
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
                    error != null -> Messages.showErrorDialog(project, ui("manager.screenshotFailed", error.message ?: ""), ui("manager.title"))
                    result?.second == null -> reload()
                    result?.second != ScreenshotCapture.CANCELLED -> Messages.showErrorDialog(project, ui("manager.screenshotFailed", result?.second ?: ""), ui("manager.title"))
                }
            }
        }
    }
}

class UnifiedResourcePreviewToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ResourcePreviewPanel(project)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel,"",false))
    }
}

private class ResourcePreviewPanel(private val project: Project) : JPanel(BorderLayout(0,4)) {
    private val mode = javax.swing.JComboBox(arrayOf(ui("mode.template"),ui("mode.rect"),ui("mode.point")))
    private val list = JBList<String>()
    private val copy = JButton(ui("preview.copy"))
    private val publish = JButton(ui("preview.publish"))

    init {
        val top=JPanel(FlowLayout(FlowLayout.LEFT,4,2)); top.add(mode); top.add(copy); top.add(publish); add(top,BorderLayout.NORTH); add(JScrollPane(list),BorderLayout.CENTER)
        mode.addActionListener { reload() }; copy.addActionListener { copySelected() }; publish.addActionListener { publish() }
        list.addMouseListener(object:MouseAdapter(){override fun mouseClicked(e:MouseEvent){if(e.clickCount==2)copySelected()}})
        reload()
    }

    private fun reload() {
        val root=project.service<OkProjectDataService>().rootPath()
        root?.let { project.service<TemplateAssetDataService>().load(it.toString(),OkScriptToolkitSettings.getInstance(project).okTemplatesDirectory()) }
        val values=when(mode.selectedIndex){
            0->project.service<TemplateAssetDataService>().categories().map{it.name}.distinct().sorted()
            1->project.service<BoxCatalogService>().readAuthoring().boxes.map{it.path}.distinct().sorted()
            else->project.service<PointCatalogService>().read().file.points.map{it.path}.distinct().sorted()
        }
        list.setListData(values.toTypedArray()); publish.isVisible=mode.selectedIndex!=0
    }

    private fun copySelected() {
        val value=list.selectedValue?:return
        val text=when(mode.selectedIndex){0->"fL.$value";1->"self.pos.$value.to_box()";else->"self.pos.$value"}
        CopyPasteManager.getInstance().setContents(StringSelection(text))
    }

    private fun publish() {
        val publisher=project.service<PositionPublisherService>()
        val choice=Messages.showChooseDialog(project,ui("preview.exportPrompt"),ui("preview.publish"),null,arrayOf(ui("preview.json"),ui("preview.python")),ui("preview.json"))
        if(choice<0)return
        val format=if(choice==0)PositionPublisherService.Format.JSON else PositionPublisherService.Format.PYTHON
        var result=publisher.publish(format,false)
        if(result.conflicts.isNotEmpty()){
            val answer=Messages.showYesNoDialog(project,ui("preview.overwrite",result.conflicts.joinToString("\n")),ui("preview.publish"),null)
            if(answer!=Messages.YES)return
            result=publisher.publish(format,true)
        }
        if(result.errors.isNotEmpty()) Messages.showErrorDialog(project,result.errors.joinToString("\n"),ui("preview.publish"))
        else Messages.showInfoMessage(project,ui("preview.written",result.written.joinToString("\n")),ui("preview.publish"))
    }
}

class ShowUnifiedAnnotationsAction : AnAction(), DumbAware {
    override fun actionPerformed(e: AnActionEvent) { e.project?.let { ToolWindowManager.getInstance(it).getToolWindow(UNIFIED_ANNOTATION_TOOL_WINDOW_ID)?.show() } }
}
class ShowUnifiedResourcesAction : AnAction(), DumbAware {
    override fun actionPerformed(e: AnActionEvent) { e.project?.let { ToolWindowManager.getInstance(it).getToolWindow(UNIFIED_RESOURCE_PREVIEW_TOOL_WINDOW_ID)?.show() } }
}
