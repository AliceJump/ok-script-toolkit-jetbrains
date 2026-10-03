from pathlib import Path
import re


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise RuntimeError(f"missing replacement anchor in {path}: {old[:100]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


def sub_once(path: str, pattern: str, replacement: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        raise RuntimeError(f"expected one regex replacement in {path}, got {count}: {pattern[:100]!r}")
    p.write_text(updated, encoding="utf-8")


# 1) Point saves are committed as one combined snapshot so cross-image renames do not depend on session order.
point_path = "src/main/kotlin/com/alicejump/okscripttoolkit/core/PointCatalogService.kt"
sub_once(
    point_path,
    r'''    /\*\* 保存一张图的全部点。命名唯一性只在 point 域内检查。 \*/\n    @Synchronized\n    fun savePointsForImage\(imagePath: Path, points: List<Pair<String, java\.awt\.Point>>\): String\? \{.*?\n        return write\(current\.file\)\n    \}\n\n    @Synchronized\n    fun removeImage''',
    '''    /** 保存一张图的全部点。命名唯一性只在 point 域内检查。 */
    fun savePointsForImage(imagePath: Path, points: List<Pair<String, java.awt.Point>>): String? =
        savePoints(linkedMapOf(imagePath to points))

    /** 一次替换所有编辑图片后再统一校验并写盘，避免跨图片改名受保存顺序影响。 */
    @Synchronized
    fun savePoints(edits: Map<Path, List<Pair<String, java.awt.Point>>>): String? {
        if (edits.isEmpty()) return null
        val current = read()
        if (current.errors.isNotEmpty()) return "parse"
        val editedNames = edits.keys.map { it.fileName.toString() }
        current.file.points.removeAll { point -> editedNames.any { sameImage(point.image, it) } }
        val occupied = current.file.points.mapTo(mutableSetOf()) { it.path }

        for ((imagePath, points) in edits) {
            val imageName = imagePath.fileName.toString()
            val size = project.service<TemplateAssetDataService>().readImageHeaderSize(imagePath.toFile()) ?: return "image"
            if (size.first <= 0 || size.second <= 0) return "image"
            for ((rawPath, p) in points) {
                val path = rawPath.trim()
                if (positionPathError(path) != null) return "path"
                if (!occupied.add(path)) return "duplicate"
                if (p.x < 0 || p.y < 0 || p.x > size.first || p.y > size.second) return "geometry"
                current.file.points += Point(path, imageName, p.x, p.y)
            }
            val image = current.file.images.firstOrNull { sameImage(it.file, imageName) }
            if (image == null) current.file.images += Image(imageName, size.first, size.second)
            else { image.width = size.first; image.height = size.second }
        }

        current.file.images.removeAll { candidate ->
            current.file.points.none { sameImage(it.image, candidate.file) }
        }
        return write(current.file)
    }

    @Synchronized
    fun removeImage''',
)

replace_once(
    point_path,
    '''        private val SEGMENT = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

        fun positionPathError(value: String): String? {''',
    '''        private val SEGMENT = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
        private val PYTHON_KEYWORDS = setOf(
            "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class",
            "continue", "def", "del", "elif", "else", "except", "finally", "for", "from", "global",
            "if", "import", "in", "is", "lambda", "nonlocal", "not", "or", "pass", "raise", "return",
            "try", "while", "with", "yield",
        )
        private val GENERATED_MEMBER_NAMES = setOf("__init__", "_parent")

        fun positionPathError(value: String): String? {''',
)
replace_once(
    point_path,
    '''            if (parts.any { !SEGMENT.matches(it) }) return "segment"
            return null''',
    '''            if (parts.any { !SEGMENT.matches(it) || it in PYTHON_KEYWORDS || it in GENERATED_MEMBER_NAMES }) return "segment"
            return null''',
)

# 2) Generated Python must contain real Python quotes, and Python publication must remain confined and recover ScreenRatio on pair failure.
pos_path = "src/main/kotlin/com/alicejump/okscripttoolkit/core/PositionResource.kt"
p = Path(pos_path)
text = p.read_text(encoding="utf-8")
start = text.index('    fun serializeScreenRatioPython(): String = """')
end = text.index('    fun serializePositionMapPython', start)
segment = text[start:end]
segment = segment.replace('    \\\"\\\"\\\"A normalized screen point (x, y) or rectangle (left, top, right, bottom).\\\"\\\"\\\"', "    '''A normalized screen point (x, y) or rectangle (left, top, right, bottom).'''")
segment = segment.replace('\\\"', '"')
text = text[:start] + segment + text[end:]
p.write_text(text, encoding="utf-8")

sub_once(
    pos_path,
    r'''    fun publish\(format: Format, overwriteHandwritten: Boolean = false\): Result \{.*?\n    \}\n\}''',
    '''    private fun sceneDirectory(root: Path): Path? {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val realRoot = runCatching { normalizedRoot.toRealPath() }.getOrNull() ?: return null
        val scene = normalizedRoot.resolve("src").resolve("scene")
        val existing = generateSequence(scene) { it.parent }.firstOrNull { Files.exists(it) } ?: return null
        val realExisting = runCatching { existing.toRealPath() }.getOrNull() ?: return null
        if (!realExisting.startsWith(realRoot)) return null
        return scene
    }

    fun publish(format: Format, overwriteHandwritten: Boolean = false): Result {
        val published = collect()
        if (published.errors.isNotEmpty()) return Result(errors = published.errors)
        val root = project.service<OkProjectDataService>().rootPath() ?: return Result(errors = listOf("root"))
        val scene = sceneDirectory(root) ?: return Result(errors = listOf("outside"))
        return when (format) {
            Format.JSON -> {
                val target = scene.resolve("positions.json")
                if (!writeAnnotationText(target, PositionResource.serializeJson(published))) Result(errors = listOf("write"))
                else Result(written = listOf(target))
            }
            Format.PYTHON -> {
                val screen = scene.resolve("ScreenRatio.py")
                val map = scene.resolve("PositionMap.py")
                val conflicts = listOf(screen, map).filter { Files.isRegularFile(it) && !runCatching { Files.readString(it).startsWith(PositionResource.GENERATED_MARKER) }.getOrDefault(false) }
                if (conflicts.isNotEmpty() && !overwriteHandwritten) return Result(conflicts = conflicts)

                val screenSource = PositionResource.serializeScreenRatioPython()
                val mapSource = PositionResource.serializePositionMapPython(published)
                val previousScreen = if (Files.isRegularFile(screen)) runCatching { Files.readString(screen) }.getOrNull() else null
                if (!writeAnnotationText(screen, screenSource)) return Result(errors = listOf("write:ScreenRatio.py"))
                if (!writeAnnotationText(map, mapSource)) {
                    val restored = if (previousScreen == null) {
                        runCatching { Files.deleteIfExists(screen); true }.getOrDefault(false)
                    } else {
                        writeAnnotationText(screen, previousScreen)
                    }
                    return Result(errors = listOf(if (restored) "write:PositionMap.py" else "write:PositionMap.py:rollback"))
                }
                Result(written = listOf(screen, map))
            }
        }
    }
}''',
)

# 3) Unified dialog saves each resource kind in batches; open sessions are authoritative for template names too.
ui_path = "src/main/kotlin/com/alicejump/okscripttoolkit/ui/UnifiedAnnotationUi.kt"
sub_once(
    ui_path,
    r'''    private fun saveAll\(\): String\? \{.*?\n        return null\n    \}\n\n    override fun doOKAction''',
    '''    private fun saveAll(): String? {
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

        // Point is the only domain with cross-image global uniqueness; validate/commit it as one snapshot first.
        pointCatalog.savePoints(pointEdits)?.let { return "point:$it" }
        if (templateEdits.isNotEmpty() && !templateData.saveAnnotationEdits(templateEdits)) {
            return "template:${templateData.lastError ?: "write"}"
        }
        if (rectEdits.isNotEmpty() && !boxCatalog.annotations.saveAnnotationEdits(rectEdits)) {
            return "rect:${boxCatalog.annotations.lastError ?: "write"}"
        }
        return null
    }

    override fun doOKAction''',
)

sub_once(
    ui_path,
    r'''    private fun occupiedNames\(targetKind: AnnotationKind, currentFile: String\): MutableSet<String> \{.*?\n        return names\n    \}''',
    '''    private fun occupiedNames(targetKind: AnnotationKind, currentFile: String): MutableSet<String> {
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
            // open sessions are authoritative for files edited in this dialog
            when (targetKind) {
                AnnotationKind.TEMPLATE -> Unit
                AnnotationKind.RECT -> boxCatalog.boxesForImage(open.key.file).forEach { names.remove(it.path) }
                AnnotationKind.POINT -> pointCatalog.pointsForImage(open.key.file).forEach { names.remove(it.path) }
            }
            open.shapes.forEach { names += it.name }
        }
        session(SessionKey(currentFile, targetKind)).shapes.forEach { names += it.name }
        return names
    }''',
)

# Add screenshot support to the unified annotation tool window and expose its panel to the existing shortcut action.
replace_once(
    ui_path,
    '''import java.nio.file.Path
import javax.imageio.ImageIO''',
    '''import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import javax.imageio.ImageIO''',
)
replace_once(
    ui_path,
    '''private enum class AnnotationKind { TEMPLATE, RECT, POINT }
''',
    '''internal const val UNIFIED_ANNOTATION_TOOL_WINDOW_ID = "ok-script Annotation Management"
internal const val UNIFIED_RESOURCE_PREVIEW_TOOL_WINDOW_ID = "ok-script Resource Preview"
internal val UNIFIED_ANNOTATION_PANEL_KEY = com.intellij.openapi.util.Key.create<AnnotationManagerPanel>("okScriptToolkit.unifiedAnnotationPanel")

private enum class AnnotationKind { TEMPLATE, RECT, POINT }
''',
)
replace_once(
    ui_path,
    '''        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)''',
    '''        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.putUserData(UNIFIED_ANNOTATION_PANEL_KEY, panel)
        toolWindow.contentManager.addContent(content)''',
)
replace_once(
    ui_path,
    '''private class AnnotationManagerPanel(private val project: Project) : JPanel(BorderLayout(0, 4)) {
    private val data = project.service<TemplateAssetDataService>()
    private val list = JBList<TemplateImage>()
    private var images: List<TemplateImage> = emptyList()
''',
    '''internal class AnnotationManagerPanel(private val project: Project) : JPanel(BorderLayout(0, 4)) {
    private val data = project.service<TemplateAssetDataService>()
    private val list = JBList<TemplateImage>()
    private val hardForegroundCheck = HardForegroundToggle.create(project)
    private var images: List<TemplateImage> = emptyList()
''',
)
replace_once(
    ui_path,
    '''        val refresh = JButton("Refresh")
        val import = JButton("Import")
        val open = JButton("Open")
        toolbar.add(refresh); toolbar.add(import); toolbar.add(open)''',
    '''        val refresh = JButton("Refresh")
        val import = JButton("Import")
        val screenshot = JButton("Screenshot")
        val open = JButton("Open")
        toolbar.add(hardForegroundCheck); toolbar.add(refresh); toolbar.add(import); toolbar.add(screenshot); toolbar.add(open)''',
)
replace_once(
    ui_path,
    '''        refresh.addActionListener { reload() }
        import.addActionListener { importImages() }
        open.addActionListener { openSelected() }''',
    '''        refresh.addActionListener { reload() }
        import.addActionListener { importImages() }
        screenshot.addActionListener { screenshotNow() }
        open.addActionListener { openSelected() }''',
)
replace_once(
    ui_path,
    '''    private fun importImages() {
        val root=project.service<OkProjectDataService>().rootPath()?:return
        val chooser=JFileChooser().apply { isMultiSelectionEnabled=true; fileSelectionMode=JFileChooser.FILES_ONLY }
        if(chooser.showOpenDialog(this)!=JFileChooser.APPROVE_OPTION)return
        val target=root.resolve(OkScriptToolkitSettings.getInstance(project).okTemplatesDirectory()).toFile()
        data.importImages(chooser.selectedFiles.toList(),target)
        reload()
    }
}''',
    '''    private fun importImages() {
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
                    error != null -> Messages.showErrorDialog(project, error.message ?: "Screenshot failed", "Annotation Management")
                    result?.second == null -> reload()
                    result?.second != ScreenshotCapture.CANCELLED -> Messages.showErrorDialog(project, result?.second ?: "Screenshot failed", "Annotation Management")
                }
            }
        }
    }
}''',
)
replace_once(
    ui_path,
    '''ToolWindowManager.getInstance(it).getToolWindow("ok-script Annotation Management")?.show()''',
    '''ToolWindowManager.getInstance(it).getToolWindow(UNIFIED_ANNOTATION_TOOL_WINDOW_ID)?.show()''',
)
replace_once(
    ui_path,
    '''ToolWindowManager.getInstance(it).getToolWindow("ok-script Resource Preview")?.show()''',
    '''ToolWindowManager.getInstance(it).getToolWindow(UNIFIED_RESOURCE_PREVIEW_TOOL_WINDOW_ID)?.show()''',
)

# Existing shortcut/action classes now route to the unified annotation window and invoke its screenshot capability.
legacy_ui = "src/main/kotlin/com/alicejump/okscripttoolkit/ui/TemplateAssetToolWindowFactory.kt"
replace_once(
    legacy_ui,
    '''        const val TOOL_WINDOW_ID = "ok-script Assets"''',
    '''        const val TOOL_WINDOW_ID = UNIFIED_ANNOTATION_TOOL_WINDOW_ID''',
)
sub_once(
    legacy_ui,
    r'''    override fun actionPerformed\(e: AnActionEvent\) \{\n        val project = e\.project \?: return\n        val toolWindow = com\.intellij\.openapi\.wm\.ToolWindowManager\.getInstance\(project\)\n            \.getToolWindow\(TemplateAssetPanel\.TOOL_WINDOW_ID\) \?: return\n        toolWindow\.show\(\)\n        // 面板还没被创建过时 content 为空 —— show\(\) 之后由 ToolWindowFactory 建好，所以这时能拿到\n        val content = toolWindow\.contentManager\.contents\.firstOrNull\(\) \?: return\n        content\.getUserData\(TemplateAssetPanel\.PANEL_KEY\)\?\.screenshotNow\(\)\n    \}''',
    '''    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindow = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            .getToolWindow(UNIFIED_ANNOTATION_TOOL_WINDOW_ID) ?: return
        toolWindow.show()
        val content = toolWindow.contentManager.contents.firstOrNull() ?: return
        content.getUserData(UNIFIED_ANNOTATION_PANEL_KEY)?.screenshotNow()
    }''',
)

# Task tools page keeps its old labels for compatibility, but routes to the two surviving windows.
task_path = "src/main/kotlin/com/alicejump/okscripttoolkit/tasklauncher/TaskLauncherToolWindowFactory.kt"
replace_once(task_path, 'showToolWindow("ok-script Templates")', 'showToolWindow("ok-script Resource Preview")')
replace_once(task_path, 'showToolWindow("ok-script Assets")', 'showToolWindow("ok-script Annotation Management")')

# 4) Regression tests for review findings and generator contract.
coord_test = "src/test/kotlin/com/alicejump/okscripttoolkit/core/CoordinateTupleTest.kt"
replace_once(
    coord_test,
    '''    @Test
    fun `point mode can project a regular box to its center`() {''',
    '''    @Test
    fun `invalid tuples and tolerance boundaries are rejected or clamped`() {
        assertNull(CoordinateTuple.interpret(listOf(0.1, 0.2, 0.3), CoordinateTupleFormat.XYWH))
        assertNull(CoordinateTuple.interpret(listOf(Double.NaN, 0.0, 0.0, 0.0), CoordinateTupleFormat.XYWH))
        assertNull(CoordinateTuple.interpret(listOf(Double.POSITIVE_INFINITY, 0.0, 0.0, 0.0), CoordinateTupleFormat.XYWH))
        assertNull(CoordinateTuple.interpret(listOf(-0.1, 0.0, 0.1, 0.1), CoordinateTupleFormat.XYWH))
        assertNull(CoordinateTuple.interpret(listOf(0.8, 0.8, 0.3, 0.3), CoordinateTupleFormat.XYWH))

        val withinTolerance = CoordinateTuple.interpret(listOf(0.0, 0.0, 1.0 + 1e-7, 1.0), CoordinateTupleFormat.XYWH)
        assertEquals(NormalizedAnnotationRect(0.0, 0.0, 1.0, 1.0), withinTolerance?.rect)
        assertNull(CoordinateTuple.interpret(listOf(0.0, 0.0, 1.0 + 1e-5, 1.0), CoordinateTupleFormat.XYWH))
    }

    @Test
    fun `point mode can project a regular box to its center`() {''',
)

pos_test = "src/test/kotlin/com/alicejump/okscripttoolkit/core/PositionResourceTest.kt"
replace_once(
    pos_test,
    '''    @Test
    fun `json serializer keeps two coordinates for points and four for rects`() {''',
    '''    @Test
    fun `screen ratio serializer emits literal Python quotes`() {
        val source = PositionResource.serializeScreenRatioPython()
        assertFalse(source.contains("\\\\\\\""))
        assertTrue(source.contains("raise ValueError(\\\"ScreenRatio requires either 2 point coordinates or 4 rect coordinates\\\")"))
        assertTrue(source.contains("'''A normalized screen point"))
    }

    @Test
    fun `Python keyword and generated member paths are rejected`() {
        val result = PositionResource.publish(
            listOf(
                PositionResource.Item("screen.class", "a.png", PositionResource.Kind.POINT, 1, 1),
                PositionResource.Item("screen._parent", "a.png", PositionResource.Kind.POINT, 2, 2),
            ),
            listOf(PositionResource.Image("a.png", 10, 10)),
        )
        assertTrue("segment:screen.class" in result.errors)
        assertTrue("segment:screen._parent" in result.errors)
    }

    @Test
    fun `json serializer keeps two coordinates for points and four for rects`() {''',
)

print("PR #22 review fixes applied")
