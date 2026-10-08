package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.AnnotationUiBundle
import com.alicejump.okscripttoolkit.core.AnnotationDraft
import com.alicejump.okscripttoolkit.core.acceptAnnotationSave
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
import com.alicejump.okscripttoolkit.core.PointCatalogService
import com.alicejump.okscripttoolkit.core.ResourceFileTransaction
import com.alicejump.okscripttoolkit.core.TemplateAssetDataService
import com.alicejump.okscripttoolkit.core.TemplateImage
import com.alicejump.okscripttoolkit.core.nextAnnotationId
import com.alicejump.okscripttoolkit.core.reconcileAnnotationSession
import com.alicejump.okscripttoolkit.core.resolveAnnotationSessionConflicts
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
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
import javax.imageio.ImageIO
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JToggleButton
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal const val UNIFIED_ANNOTATION_TOOL_WINDOW_ID = "ok-script Annotation Management"
internal const val UNIFIED_RESOURCE_PREVIEW_TOOL_WINDOW_ID = "ok-script Resource Preview"

private fun ui(key: String, vararg params: Any): String = AnnotationUiBundle.message(key, *params)

internal enum class AnnotationKind { TEMPLATE, RECT, POINT }

internal fun AnnotationKind.nextAnnotationKind(): AnnotationKind = when (this) {
    AnnotationKind.TEMPLATE -> AnnotationKind.RECT
    AnnotationKind.RECT -> AnnotationKind.POINT
    AnnotationKind.POINT -> AnnotationKind.TEMPLATE
}

internal data class AnnotationCanvasFit(val scale: Double, val width: Int, val height: Int)

internal fun annotationCanvasFit(sourceWidth: Int, sourceHeight: Int, availableWidth: Int, availableHeight: Int): AnnotationCanvasFit {
    if (sourceWidth <= 0 || sourceHeight <= 0 || availableWidth <= 0 || availableHeight <= 0) {
        return AnnotationCanvasFit(1.0, 1, 1)
    }
    val scale = min(availableWidth.toDouble() / sourceWidth, availableHeight.toDouble() / sourceHeight)
    return AnnotationCanvasFit(
        scale = scale,
        width = max(1, (sourceWidth * scale).roundToInt()),
        height = max(1, (sourceHeight * scale).roundToInt()),
    )
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
 * 编辑器页签内的统一标注器；每次已完成操作立即保存，冲突与失败保留在当前会话。
 */
class UnifiedAnnotationPanel(
    private val project: Project,
    private var images: List<TemplateImage>,
    startIndex: Int,
) : JPanel(BorderLayout()), com.intellij.openapi.Disposable {
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
    private val drafts = PropertiesComponent.getInstance(project)

    private val canvas = UnifiedCanvas()
    private val canvasHost = JPanel(java.awt.GridBagLayout())
    private val conflictPanel = AnnotationConflictPanel()
    private val templateMode = JToggleButton(ui("mode.template"))
    private val rectMode = JToggleButton(ui("mode.rect"))
    private val pointMode = JToggleButton(ui("mode.point"))
    private val sharedUndo = JCheckBox(ui("sharedUndo"), prefs.getBoolean(PREF_SHARED_UNDO, true))
    private val preferXywh = JCheckBox("XYWH", prefs.getBoolean(PREF_COORD_XYWH, false))
    private val drawToggle = JToggleButton(ui("tool.draw"))
    private val coordToggle = JToggleButton(ui("tool.coords"))
    private val deleteButton = JToggleButton(ui("tool.delete"))
    private val numericButton = JButton(ui("tool.numeric"))
    private val retryButton = JButton(ui("save.retry"))
    private val hidden = AnnotationKind.entries.associateWith { mutableSetOf<String>() }
    private var disposed = false
    private val undoButton = JButton("↩")
    private val redoButton = JButton("↪")
    private val prevButton = JButton("◀")
    private val nextButton = JButton("▶")
    private val navLabel = JBLabel()
    private val statusLabel = JBLabel(" ")
    private val swatch = JPanel().apply { preferredSize = Dimension(16, 16); isOpaque = true }
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
        add(createCenterPanel(), BorderLayout.CENTER)
        canvas.installKeys()
        boxCatalog.readAuthoring()
        loadImage(currentIndex, preserveViewport = false)
        if (sessions.values.any { it.dirty }) autoSave()
        annotationSubscription = AnnotationDataChanges.subscribe { changed ->
            if (!saving && !disposed) UIUtil.invokeLaterIfNeeded { if (!saving && !disposed) handleExternalChange(changed) }
        }
    }

    private val currentImage: TemplateImage get() = images[currentIndex]
    private val currentKey: SessionKey get() = SessionKey(currentImage.file.name, kind)

    private fun createCenterPanel(): JComponent {
        val modes = JPanel(WrappingToolbarLayout())
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
        deleteButton.addActionListener { canvas.setTool(if (deleteButton.isSelected) Tool.DELETE else Tool.NONE) }
        numericButton.addActionListener { canvas.editCoordinates() }
        retryButton.isVisible = false
        retryButton.addActionListener { autoSave() }
        undoButton.addActionListener { undo() }
        redoButton.addActionListener { redo() }
        prevButton.addActionListener { navigate(-1) }
        nextButton.addActionListener { navigate(1) }
        listOf(drawToggle, coordToggle, deleteButton, undoButton, redoButton, prevButton, nextButton).forEach { it.isFocusable = false }

        val tools = modes
        tools.add(drawToggle); tools.add(coordToggle); tools.add(numericButton); tools.add(deleteButton); tools.add(undoButton); tools.add(redoButton)
        tools.add(prevButton); tools.add(nextButton); tools.add(navLabel); tools.add(retryButton)
        tools.add(JButton(ui("keys.title")).apply { addActionListener {
            if (AnnotationKeybindingsDialog(project).showAndGet()) canvas.installKeys()
        } })

        val north = JPanel(BorderLayout())
        north.add(modes, BorderLayout.NORTH)

        rows.layout = BoxLayout(rows, BoxLayout.Y_AXIS)
        val listPanel = JPanel(BorderLayout(0, 4))
        listPanel.preferredSize = Dimension(300, 560)
        listPanel.border = BorderFactory.createEmptyBorder(0, 8, 0, 0)
        listPanel.add(JPanel(WrappingToolbarLayout()).apply {
            add(JBLabel(ui("list.title")))
            fun visibility(key: String, action: () -> Unit) { add(JButton(ui(key)).apply { addActionListener { action(); syncRows(); canvas.repaint() } }) }
            visibility("visibility.showAll") { hidden.getValue(kind).clear() }
            visibility("visibility.hideAll") { hidden.getValue(kind).addAll(session(currentKey).shapes.map { it.name }); canvas.clearSelection() }
            visibility("visibility.onlySelected") {
                val selected = canvas.selectedIds()
                hidden.getValue(kind).clear()
                hidden.getValue(kind).addAll(session(currentKey).shapes.filter { it.id !in selected }.map { it.name })
            }
        }, BorderLayout.NORTH)
        listPanel.add(JPanel(BorderLayout()).apply { add(conflictPanel, BorderLayout.NORTH); add(JScrollPane(rows), BorderLayout.CENTER) }, BorderLayout.CENTER)

        canvasHost.minimumSize = Dimension(1, 1)
        canvas.minimumSize = Dimension(1, 1)
        canvasHost.preferredSize = Dimension(900, 560)
        canvasHost.background = UIUtil.getPanelBackground()
        canvasHost.add(canvas)
        canvasHost.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent?) {
                canvas.refitToHost()
            }
        })
        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, canvasHost, listPanel)
        split.resizeWeight = 1.0
        split.dividerLocation = 900

        val root = JPanel(BorderLayout(0, 4))
        root.add(north, BorderLayout.NORTH)
        root.add(split, BorderLayout.CENTER)
        statusLabel.foreground = UIUtil.getContextHelpForeground()
        root.add(JPanel(BorderLayout(6, 0)).apply { add(swatch, BorderLayout.WEST); add(statusLabel, BorderLayout.CENTER) }, BorderLayout.SOUTH)
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
        val disk = items.toMergeShapes()
        val recovered = runCatching {
            val raw = drafts.getValue(draftKey(key)) ?: return@runCatching null
            val saved = AnnotationDraft.decode(raw) ?: return@runCatching null
            if (sourceValid) reconcileAnnotationSession(key.kind.mergeMode(), saved, disk, acceptedRevision) else saved
        }.getOrNull()
        val sync = recovered ?: AnnotationSessionSyncState(disk, disk, acceptedRevision, dirty = false)
        ShapeSession(key, sync.displayShapes.toUnifiedShapes().toMutableList(), sync.dirty,
            nextAnnotationId(1, sync.displayShapes, sync.base), sync)
    }

    private fun draftKey(key: SessionKey) = "okScriptToolkit.annotation.draft:${sourcePath(key.kind)?.toAbsolutePath()?.normalize()}:${key.file}:${key.kind}"

    private fun preserveDraft(session: ShapeSession) {
        drafts.setValue(draftKey(session.key), AnnotationDraft.encode(session.sync))
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
        var changed = false
        for (session in sessions.values.filter { it.key.kind == targetKind }) {
            if (session.sync.revision == revision && session.sync.pending == null) continue
            changed = true
            val external = readSessionShapes(session.key)
            val state = if (session.sync.pending == null) {
                session.sync.copy(local = session.shapes.toMergeShapes(), dirty = session.dirty)
            } else session.sync
            val next = reconcileAnnotationSession(targetKind.mergeMode(), state, external.toMergeShapes(), revision)
            session.sync = next
            session.shapes = next.displayShapes.toUnifiedShapes().toMutableList()
            session.dirty = next.dirty
            session.nextId = nextAnnotationId(session.nextId, session.shapes.toMergeShapes(), next.base)
            if (session.dirty) preserveDraft(session)
            clearHistoryFor(session.key)
        }
        if (changed && currentKey.kind == targetKind && sessions.containsKey(currentKey)) {
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
        if (sessions.values.any { it.dirty } && sessions.values.none { it.sync.hasConflicts }) autoSave()
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
        preserveDraft(current)
        current.nextId = nextAnnotationId(current.nextId, current.shapes.toMergeShapes(), resolved.base)
        clearHistoryFor(current.key)
        canvas.applySession(current, keepViewport = true)
        syncRows()
        refreshHistoryButtons()
        conflictPanel.clearConflicts()
        statusLabel.text = ui("external.resolved")
        autoSave()
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
        val savedKinds = dirty.mapTo(mutableSetOf()) { it.key.kind }
        for (open in sessions.values.filter { it.key.kind in savedKinds }) {
            val local = open.shapes.toMergeShapes()
            open.sync = acceptAnnotationSave(local, sourceRevision(open.key.kind))
            open.dirty = false
            drafts.unsetValue(draftKey(open.key))
        }
        return null
    }

    private fun autoSave() {
        if (saving || disposed) return
        saving = true
        val error = try { saveAll() } catch (error: Exception) { error.message ?: error.javaClass.simpleName } finally { saving = false }
        retryButton.isVisible = error != null
        statusLabel.text = when {
            error == "conflict" -> ui("external.unresolvedSave")
            error != null -> ui("manager.saveFailed", error)
            else -> ui("save.saved")
        }
        updateConflictStatus()
    }

    val hasUnsavedChanges: Boolean get() = sessions.values.any { it.dirty }
    val focusedComponent: JComponent get() = canvas

    fun showImage(nextImages: List<TemplateImage>, index: Int) {
        canvas.finishTransientEdit()
        images = nextImages + images.filter { old -> sessions.values.any { it.dirty && it.key.file == old.file.name } && nextImages.none { it.file == old.file } }
        hidden.values.forEach { it.clear() }
        loadImage(index, preserveViewport = false)
    }

    override fun dispose() {
        canvas.finishTransientEdit()
        autoSave()
        disposed = true
        annotationSubscription?.close()
        annotationSubscription = null
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
        preserveDraft(session)
        refreshHistoryButtons()
        autoSave()
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
        preserveDraft(s)
        s.nextId = nextAnnotationId(s.nextId, snapshot.toMergeShapes(), s.sync.base)
        if (tx.key == currentKey) canvas.applySession(s, keepViewport = true)
        refreshHistoryButtons()
        syncRows()
        autoSave()
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
            val row = JPanel(BorderLayout(4, 0))
            val visible = JCheckBox().apply {
                isSelected = shape.name !in hidden.getValue(kind)
                toolTipText = ui("visibility.toggle")
                addActionListener {
                    if (isSelected) hidden.getValue(kind).remove(shape.name) else { hidden.getValue(kind).add(shape.name); canvas.deselect(shape.id) }
                    canvas.repaint(); syncRows()
                }
            }
            val button = JButton(shape.name).apply {
                horizontalAlignment = javax.swing.SwingConstants.LEFT
                isBorderPainted = shape.id in selected
                addActionListener { event ->
                    canvas.selectFromList(shape.id, event.modifiers and (ActionEvent.CTRL_MASK or ActionEvent.SHIFT_MASK or ActionEvent.META_MASK) != 0)
                    syncRows()
                }
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(event: MouseEvent) { if (event.clickCount == 2) canvas.editCoordinates(shape.id) }
                })
            }
            row.add(visible, BorderLayout.WEST); row.add(button, BorderLayout.CENTER)
            row.maximumSize = Dimension(Int.MAX_VALUE, row.preferredSize.height)
            rows.add(row)
        }
        rows.revalidate(); rows.repaint()
    }

    private enum class Tool { NONE, DRAW, COORD, DELETE }

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
        private var coordOriginal: Rectangle? = null
        private var coordStart: Point? = null
        private var coordHandle: String? = null
        private var panning = false
        private var panStart: Point? = null
        private var panOffset: Pair<Double, Double>? = null

        init {
            isFocusable = true
            isOpaque = true
            isDoubleBuffered = false
            background = UIUtil.getPanelBackground()
            installMouse()
        }

        private fun editingBlocked(): Boolean = sessionRef?.sync?.hasConflicts == true

        private fun clearTransientInteraction() {
            drawStart = null
            drawPreview = null
            coordOriginal = null; coordStart = null; coordHandle = null
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
                offsetX = 0.0
                offsetY = 0.0
            } else {
                recalcFit()
                recalcOffset()
            }
            applySession(s, keepViewport = true)
        }

        fun refitToHost() {
            image ?: return
            val oldFit = fitScale
            val zoomRatio = if (oldFit > 0.0) scale / oldFit else 1.0
            recalcFit()
            scale = (fitScale * zoomRatio).coerceIn(fitScale, fitScale * 50.0)
            recalcOffset()
            repaint()
            SwingUtilities.invokeLater {
                recalcOffset()
                repaint()
            }
        }

        fun applySession(s: ShapeSession, keepViewport: Boolean) {
            sessionRef = s
            selected.clear(); hovered = -1
            tool = Tool.NONE
            clearTransientInteraction()
            coordRect = null
            drawToggle.isSelected = false; coordToggle.isSelected = false; deleteButton.isSelected = false
            drawToggle.isEnabled = !s.sync.hasConflicts
            coordToggle.isEnabled = !s.sync.hasConflicts
            deleteButton.isEnabled = !s.sync.hasConflicts
            cursor = Cursor.getDefaultCursor()
            if (!keepViewport) { recalcFit(); scale = fitScale; recalcOffset() }
            repaint()
        }

        fun selectedIds(): Set<Int> = selected.toSet()
        fun clearSelection() { selected.clear() }
        fun deselect(id: Int) { selected.remove(id) }
        fun selectFromList(id: Int, multiple: Boolean) {
            shapes().firstOrNull { it.id == id }?.let { hidden.getValue(kind).remove(it.name) }
            if (!multiple) selected.clear()
            if (!selected.add(id)) selected.remove(id)
            requestFocusInWindow(); repaint()
        }
        fun finishTransientEdit() { finishDragOrResize(); clearTransientInteraction() }

        fun setTool(next: Tool) {
            if (editingBlocked()) return
            finishDragOrResize()
            tool = next
            drawStart = null; drawPreview = null
            if (next != Tool.COORD) { coordRect = null; statusLabel.text = " " }
            drawToggle.isSelected = next == Tool.DRAW
            coordToggle.isSelected = next == Tool.COORD
            deleteButton.isSelected = next == Tool.DELETE
            cursor = if (next == Tool.DRAW || next == Tool.COORD) Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR) else Cursor.getDefaultCursor()
            repaint()
        }

        private fun shapes(): MutableList<UnifiedShape> = sessionRef?.shapes ?: mutableListOf()

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val source = image ?: return
            val g2 = g.create() as Graphics2D
            g2.clipRect(0, 0, width, height)
            g2.drawImage(source, offsetX.roundToInt(), offsetY.roundToInt(), (source.width * scale).roundToInt(), (source.height * scale).roundToInt(), null)
            shapes().filter { it.name !in hidden.getValue(kind) }.forEach { shape -> paintShape(g2, shape) }
            paintConflictCandidates(g2)
            coordRect?.let { rect ->
                val r = toScreen(rect)
                g2.color = COORD; g2.stroke = BasicStroke(2f); g2.drawRect(r.x, r.y, r.width, r.height)
                handlePoints(r).values.forEach { p -> g2.fillRect(p.x - 3, p.y - 3, 6, 6) }
            }
            if (drawStart != null && drawPreview != null && (kind != AnnotationKind.POINT || tool == Tool.COORD)) {
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
                paintShapeLabel(g2, shape.name, p.x + POINT_RADIUS + 4, p.y, color, preferAbove = true)
            } else {
                val r = toScreen(Rectangle(shape.x, shape.y, shape.w, shape.h))
                g2.drawRect(r.x, r.y, r.width, r.height)
                paintShapeLabel(g2, shape.name, r.x, r.y, color, preferAbove = true)
                if (isSelected && selected.size == 1) handlePoints(r).values.forEach { p -> g2.fillRect(p.x - 3, p.y - 3, 6, 6) }
            }
        }

        private fun paintShapeLabel(g2: Graphics2D, text: String, anchorX: Int, anchorY: Int, color: Color, preferAbove: Boolean) {
            if (text.isBlank() || width <= 0 || height <= 0) return
            val metrics = g2.fontMetrics
            val labelWidth = metrics.stringWidth(text) + 8
            val labelHeight = metrics.height + 4
            val x = anchorX.coerceIn(0, (width - labelWidth).coerceAtLeast(0))
            val rawY = if (preferAbove && anchorY >= labelHeight + 2) anchorY - labelHeight else anchorY + 2
            val y = rawY.coerceIn(0, (height - labelHeight).coerceAtLeast(0))
            val previous = g2.color
            g2.color = color
            g2.fillRoundRect(x, y, labelWidth, labelHeight, 6, 6)
            g2.color = Color.WHITE
            g2.drawString(text, x + 4, y + metrics.ascent + 2)
            g2.color = previous
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

        fun installKeys() {
            val configured = AnnotationKeybindings.current()
            drawToggle.toolTipText = configured.getValue("drawBbox")
            coordToggle.toolTipText = configured.getValue("copyCoords")
            deleteButton.toolTipText = configured.getValue("deleteMode")
            undoButton.toolTipText = ui("keys.undo") + " (" + configured.getValue("undo") + ")"
            redoButton.toolTipText = ui("keys.redo") + " (" + configured.getValue("redo") + ")"
            prevButton.toolTipText = ui("keys.prevImage") + " (" + configured.getValue("prevImage") + ")"
            nextButton.toolTipText = ui("keys.nextImage") + " (" + configured.getValue("nextImage") + ")"
            getInputMap(WHEN_FOCUSED).clear()
            getActionMap().clear()
            fun bind(stroke: KeyStroke, name: String, action: () -> Unit) {
                getInputMap(WHEN_FOCUSED).put(stroke, name)
                getActionMap().put(name, object : AbstractAction() { override fun actionPerformed(e: ActionEvent?) = action() })
            }
            val actions = mapOf<String, () -> Unit>(
                "cycleMode" to { this@UnifiedAnnotationPanel.cycleKind() },
                "modeTemplate" to { templateMode.doClick() }, "modeRect" to { rectMode.doClick() }, "modePoint" to { pointMode.doClick() },
                "drawBbox" to { setTool(if (tool == Tool.DRAW) Tool.NONE else Tool.DRAW) },
                "copyCoords" to { setTool(if (tool == Tool.COORD) Tool.NONE else Tool.COORD) },
                "deleteMode" to { setTool(if (tool == Tool.DELETE) Tool.NONE else Tool.DELETE) },
                "undo" to { this@UnifiedAnnotationPanel.undo() }, "redo" to { this@UnifiedAnnotationPanel.redo() },
                "copy" to { if (tool == Tool.NONE) copySelected() }, "paste" to { pasteClipboard() },
                "deleteSelected" to { if (tool == Tool.NONE) deleteSelected() },
                "prevImage" to { navigate(-1) }, "nextImage" to { navigate(1) },
            )
            for ((name, value) in AnnotationKeybindings.current()) {
                val action = actions.getValue(name)
                val stroke = AnnotationKeybindings.stroke(value) ?: continue
                bind(stroke, name, action)
                // VS Code accepts the platform's command modifier for ctrl bindings.
                if (stroke.modifiers and InputEvent.CTRL_DOWN_MASK != 0)
                    bind(KeyStroke.getKeyStroke(stroke.keyCode, (stroke.modifiers and InputEvent.CTRL_DOWN_MASK.inv() and InputEvent.CTRL_MASK.inv()) or InputEvent.META_DOWN_MASK), "$name.meta", action)
            }
            for ((key, direction) in mapOf(KeyEvent.VK_LEFT to Point(-1, 0), KeyEvent.VK_RIGHT to Point(1, 0), KeyEvent.VK_UP to Point(0, -1), KeyEvent.VK_DOWN to Point(0, 1))) {
                for (shift in listOf(false, true)) {
                    val stroke = KeyStroke.getKeyStroke(key, if (shift) InputEvent.SHIFT_DOWN_MASK else 0)
                    val fallback = getInputMap(WHEN_FOCUSED).get(stroke)?.let { getActionMap().get(it) }
                    bind(stroke, "nudge.$key.$shift") {
                        if (selected.isNotEmpty() && tool == Tool.NONE) nudge(direction.x * if (shift) 10 else 1, direction.y * if (shift) 10 else 1)
                        else fallback?.actionPerformed(ActionEvent(this, ActionEvent.ACTION_PERFORMED, "navigate"))
                    }
                }
            }
        }

        private fun nudge(dx: Int, dy: Int) {
            if (editingBlocked()) return
            val source = image ?: return
            val s = sessionRef ?: return
            val before = s.shapes.toList()
            s.shapes = s.shapes.map { shape ->
                if (shape.id !in selected || shape.name in hidden.getValue(kind)) shape else shape.copy(
                    x = (shape.x + dx).coerceIn(0, (source.width - shape.w).coerceAtLeast(0)),
                    y = (shape.y + dy).coerceIn(0, (source.height - shape.h).coerceAtLeast(0)))
            }.toMutableList()
            recordEdit(s.key, before, s.shapes.toList()); repaint()
        }

        private fun installMouse() {
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    requestFocusInWindow()
                    if (editingBlocked()) return
                    if (SwingUtilities.isRightMouseButton(e)) {
                        hit(e.point)?.let { id ->
                            selected.clear(); selected += id
                            shapes().firstOrNull { it.id == id }?.let { CopyPasteManager.getInstance().setContents(StringSelection(it.name)); statusLabel.text = it.name }
                            syncRows(); repaint()
                        }
                        return
                    }
                    if (!SwingUtilities.isLeftMouseButton(e)) return
                    if (tool == Tool.DELETE) { hit(e.point)?.let { selected.clear(); selected += it; deleteSelected() }; return }
                    if (tool == Tool.DRAW) {
                        if (kind == AnnotationKind.POINT) createPointAt(e.point) else { drawStart = e.point; drawPreview = e.point }
                        return
                    }
                    if (tool == Tool.COORD) {
                        val rect = coordRect
                        val handle = rect?.let { handleAt(toScreen(it), e.point) }
                        if (rect != null && (handle != null || toScreen(rect).contains(e.point))) {
                            coordOriginal = Rectangle(rect); coordStart = e.point; coordHandle = handle
                        } else { drawStart = e.point; drawPreview = e.point }
                        return
                    }
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
                    if (tool == Tool.COORD) {
                        if (drawStart != null) finishCoord(e.point)
                        else if (coordOriginal != null) { copyCoord(); coordOriginal = null; coordStart = null; coordHandle = null }
                        return
                    }
                    finishDragOrResize()
                    panning = false; panStart = null; panOffset = null
                }

                override fun mouseClicked(e: MouseEvent) {
                    if (!editingBlocked() && e.clickCount == 2 && tool == Tool.NONE) hit(e.point)?.let { editCoordinates(it) }
                }
            })
            addMouseMotionListener(object : MouseMotionAdapter() {
                override fun mouseDragged(e: MouseEvent) {
                    if (editingBlocked()) return
                    when {
                        drawStart != null && (tool == Tool.DRAW || tool == Tool.COORD) -> { drawPreview = e.point; repaint() }
                        coordOriginal != null -> dragCoord(e.point)
                        resizeId != null -> resizeTo(e.point)
                        dragId != null -> dragTo(e.point)
                        panning && panStart != null && panOffset != null -> {
                            offsetX = panOffset!!.first + e.x - panStart!!.x; offsetY = panOffset!!.second + e.y - panStart!!.y; recalcOffset(); repaint()
                        }
                    }
                }
                override fun mouseMoved(e: MouseEvent) {
                    hovered = hit(e.point) ?: -1
                    if (tool != Tool.COORD && !retryButton.isVisible && !editingBlocked()) {
                        val source = image
                        val point = toImage(e.point)
                        if (source != null && point.x in 0 until source.width && point.y in 0 until source.height) {
                            val color = Color(source.getRGB(point.x, point.y))
                            swatch.background = color
                            statusLabel.text = ui("pixel.readout", color.red, color.green, color.blue, point.x, point.y,
                                String.format(java.util.Locale.ROOT, "%.3f", point.x.toDouble() / source.width),
                                String.format(java.util.Locale.ROOT, "%.3f", point.y.toDouble() / source.height))
                        }
                    }
                    repaint()
                }
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
            val value = requestCoordinates(UnifiedShape(0, "", x, y, 0, 0)) ?: return
            addShape(value.name, value.x, value.y, value.w, value.h)
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
            val value = requestCoordinates(UnifiedShape(0, "", x, y, x2-x, y2-y)) ?: return
            addShape(value.name, value.x, value.y, value.w, value.h)
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

        fun editCoordinates(id: Int? = selected.singleOrNull()) {
            if (editingBlocked()) return
            val source = image ?: return
            val s = sessionRef ?: return
            val old = s.shapes.firstOrNull { it.id == id }
            val initial = old ?: UnifiedShape(0, "", 0, 0, if (kind == AnnotationKind.POINT) 0 else min(100, source.width), if (kind == AnnotationKind.POINT) 0 else min(100, source.height))
            val value = requestCoordinates(initial, old?.name) ?: return
            if (old == null) { addShape(value.name, value.x, value.y, value.w, value.h); return }
            val before = s.shapes.toList()
            val index = s.shapes.indexOfFirst { it.id == old.id }
            s.shapes[index] = old.copy(name = value.name, x = value.x, y = value.y, w = value.w, h = value.h)
            recordEdit(s.key, before, s.shapes.toList()); syncRows(); repaint()
        }

        private fun requestCoordinates(initial: UnifiedShape, previousName: String? = null): AnnotationCoordinates? {
            val source = image ?: return null
            val dialog = AnnotationCoordinatesDialog(project, initial.name, initial.x, initial.y, initial.w, initial.h,
                source.width, source.height, kind == AnnotationKind.POINT) { name ->
                when {
                    !validName(name) -> ui("name.invalid")
                    name != previousName && name in occupiedNames(kind, currentImage.file.name) -> ui("name.duplicate")
                    else -> null
                }
            }
            return if (dialog.showAndGet()) dialog.value() else null
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
            val minW = min(MIN_RECT, source.width).toDouble()
            val minH = min(MIN_RECT, source.height).toDouble()
            w = w.coerceIn(minW, source.width.toDouble()); height = height.coerceIn(minH, source.height.toDouble())
            x = x.coerceIn(0.0, source.width - w); y = y.coerceIn(0.0, source.height - height)
            val updated = o.copy(x = x.roundToInt(), y = y.roundToInt(),
                w = w.roundToInt().coerceAtMost(source.width - x.roundToInt()), h = height.roundToInt().coerceAtMost(source.height - y.roundToInt()))
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
                if (shape.name in hidden.getValue(kind)) continue
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
            return handleAt(r, p)
        }

        private fun handleAt(rect: Rectangle, p: Point): String? = handlePoints(rect).entries.firstOrNull {
            abs(p.x - it.value.x) <= HANDLE_MARGIN && abs(p.y - it.value.y) <= HANDLE_MARGIN
        }?.key

        private fun dragCoord(screen: Point) {
            val source = image ?: return
            val original = coordOriginal ?: return
            val start = coordStart ?: return
            val dx = ((screen.x - start.x) / scale).roundToInt()
            val dy = ((screen.y - start.y) / scale).roundToInt()
            val handle = coordHandle
            if (handle == null) {
                coordRect = Rectangle((original.x + dx).coerceIn(0, source.width - original.width),
                    (original.y + dy).coerceIn(0, source.height - original.height), original.width, original.height)
            } else {
                var left = original.x; var top = original.y
                var right = left + original.width; var bottom = top + original.height
                if (handle in listOf("left", "tl", "bl")) left = (left + dx).coerceIn(0, right - 1)
                if (handle in listOf("right", "tr", "br")) right = (right + dx).coerceIn(left + 1, source.width)
                if (handle in listOf("top", "tl", "tr")) top = (top + dy).coerceIn(0, bottom - 1)
                if (handle in listOf("bottom", "bl", "br")) bottom = (bottom + dy).coerceIn(top + 1, source.height)
                coordRect = Rectangle(left, top, right - left, bottom - top)
            }
            repaint()
        }

        private fun handlePoints(r: Rectangle): Map<String,Point> = mapOf(
            "tl" to Point(r.x,r.y), "tr" to Point(r.x+r.width,r.y), "bl" to Point(r.x,r.y+r.height), "br" to Point(r.x+r.width,r.y+r.height),
            "top" to Point(r.x+r.width/2,r.y), "bottom" to Point(r.x+r.width/2,r.y+r.height), "left" to Point(r.x,r.y+r.height/2), "right" to Point(r.x+r.width,r.y+r.height/2),
        )

        private fun recalcFit() {
            val source = image ?: return
            val hostWidth = canvasHost.width.takeIf { it > 0 } ?: canvasHost.preferredSize.width.coerceAtLeast(1)
            val hostHeight = canvasHost.height.takeIf { it > 0 } ?: canvasHost.preferredSize.height.coerceAtLeast(1)
            val fit = annotationCanvasFit(source.width, source.height, hostWidth, hostHeight)
            fitScale = fit.scale
            val fitSize = Dimension(fit.width, fit.height)
            if (preferredSize != fitSize) {
                preferredSize = fitSize
                revalidate()
                canvasHost.revalidate()
            }
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

class ShowUnifiedAnnotationsAction : AnAction(), DumbAware {
    override fun actionPerformed(e: AnActionEvent) { e.project?.let { ToolWindowManager.getInstance(it).getToolWindow(UNIFIED_ANNOTATION_TOOL_WINDOW_ID)?.show() } }
}
class ShowUnifiedResourcesAction : AnAction(), DumbAware {
    override fun actionPerformed(e: AnActionEvent) { e.project?.let { ToolWindowManager.getInstance(it).getToolWindow(UNIFIED_RESOURCE_PREVIEW_TOOL_WINDOW_ID)?.show() } }
}
