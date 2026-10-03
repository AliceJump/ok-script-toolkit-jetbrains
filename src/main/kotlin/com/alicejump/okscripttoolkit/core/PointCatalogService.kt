package com.alicejump.okscripttoolkit.core

import com.alicejump.okscripttoolkit.settings.OkScriptToolkitSettings
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.roundToInt

/** 点资源使用与模板/框相同的 COCO 三表，只是 bbox 固定为 [x,y,0,0]。 */
@Service(Service.Level.PROJECT)
class PointCatalogService(private val project: Project) {
    data class Image(val file: String, var width: Int, var height: Int)
    data class Point(val path: String, val image: String, val x: Int, val y: Int)
    data class Snapshot(val images: MutableList<Image> = mutableListOf(), val points: MutableList<Point> = mutableListOf())
    data class ReadResult(val file: Snapshot = Snapshot(), val errors: List<String> = emptyList(), val legacy: Boolean = false)

    private val mapper = ObjectMapper()

    fun authoringPath(): Path? = root()?.resolve(templatesDirectory())?.resolve(FILE_NAME)

    @Synchronized
    fun read(): ReadResult {
        val path = authoringPath() ?: return ReadResult()
        if (Files.notExists(path)) return ReadResult()
        val text = runCatching { Files.readString(path) }.getOrElse { return ReadResult(errors = listOf("read")) }
        val root = runCatching { mapper.readTree(text) }.getOrElse { return ReadResult(errors = listOf("json")) }
        return if (root.has("version") && root.has("points")) parseLegacy(root) else parseCoco(root)
    }

    fun pointsForImage(fileName: String): List<Point> = read().let { result ->
        if (result.errors.isNotEmpty()) emptyList() else result.file.points.filter { sameImage(it.image, fileName) }
    }

    fun pathOwners(): Map<String, String> = read().let { result ->
        if (result.errors.isNotEmpty()) emptyMap() else result.file.points.associate { it.path to it.image }
    }

    /** 保存一张图的全部点。命名唯一性只在 point 域内检查。 */
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
    fun removeImage(fileName: String): Boolean {
        val current = read()
        if (current.errors.isNotEmpty()) return false
        val before = current.file.points.size
        current.file.points.removeAll { sameImage(it.image, fileName) }
        current.file.images.removeAll { sameImage(it.file, fileName) }
        if (before == current.file.points.size && Files.notExists(authoringPath() ?: return true)) return true
        return write(current.file) == null
    }

    private fun parseLegacy(root: JsonNode): ReadResult {
        if (root.path("version").asInt(-1) != 1 || !root.path("images").isArray || !root.path("points").isArray) {
            return ReadResult(errors = listOf("schema"))
        }
        val images = mutableListOf<Image>()
        val imageNames = mutableSetOf<String>()
        for (node in root.path("images")) {
            val file = node.path("file").asText("")
            val width = node.path("width").asInt(0)
            val height = node.path("height").asInt(0)
            if (file.isBlank() || width <= 0 || height <= 0 || !imageNames.add(file.lowercase())) return ReadResult(errors = listOf("image"))
            images += Image(file, width, height)
        }
        val paths = mutableSetOf<String>()
        val points = mutableListOf<Point>()
        for (node in root.path("points")) {
            val path = node.path("path").asText("").trim()
            val image = node.path("image").asText("")
            val x = node.path("x").takeIf { it.isNumber }?.asDouble()?.roundToInt()
            val y = node.path("y").takeIf { it.isNumber }?.asDouble()?.roundToInt()
            val img = images.firstOrNull { sameImage(it.file, image) }
            if (positionPathError(path) != null || image.isBlank() || x == null || y == null || !paths.add(path)
                || img == null || x < 0 || y < 0 || x > img.width || y > img.height) return ReadResult(errors = listOf("point"))
            points += Point(path, image, x, y)
        }
        return ReadResult(Snapshot(images, points), legacy = true)
    }

    private fun parseCoco(root: JsonNode): ReadResult {
        if (!root.path("images").isArray || !root.path("annotations").isArray || !root.path("categories").isArray) {
            return ReadResult(errors = listOf("coco"))
        }
        val imagesById = mutableMapOf<Int, Image>()
        for (node in root.path("images")) {
            val id = node.path("id").asInt(Int.MIN_VALUE)
            val file = node.path("file_name").asText("")
            val width = node.path("width").asInt(0)
            val height = node.path("height").asInt(0)
            if (id == Int.MIN_VALUE || id in imagesById || file.isBlank() || width <= 0 || height <= 0) return ReadResult(errors = listOf("image"))
            imagesById[id] = Image(file, width, height)
        }
        val namesById = mutableMapOf<Int, String>()
        val usedNames = mutableSetOf<String>()
        for (node in root.path("categories")) {
            val id = node.path("id").asInt(Int.MIN_VALUE)
            val name = node.path("name").asText("").trim()
            if (id == Int.MIN_VALUE || id in namesById || positionPathError(name) != null || !usedNames.add(name)) return ReadResult(errors = listOf("category"))
            namesById[id] = name
        }
        val pointNames = mutableSetOf<String>()
        val annotationIds = mutableSetOf<Int>()
        val points = mutableListOf<Point>()
        for (node in root.path("annotations")) {
            val id = node.path("id").asInt(Int.MIN_VALUE)
            val image = imagesById[node.path("image_id").asInt(Int.MIN_VALUE)] ?: return ReadResult(errors = listOf("annotation"))
            val name = namesById[node.path("category_id").asInt(Int.MIN_VALUE)] ?: return ReadResult(errors = listOf("annotation"))
            val bbox = node.path("bbox")
            if (id == Int.MIN_VALUE || !annotationIds.add(id) || !bbox.isArray || bbox.size() != 4 || bbox.any { !it.isNumber }) {
                return ReadResult(errors = listOf("annotation"))
            }
            val x = bbox[0].asDouble().roundToInt()
            val y = bbox[1].asDouble().roundToInt()
            val w = bbox[2].asDouble()
            val h = bbox[3].asDouble()
            if (w != 0.0 || h != 0.0 || x < 0 || y < 0 || x > image.width || y > image.height) return ReadResult(errors = listOf("pointGeometry"))
            if (!pointNames.add(name)) return ReadResult(errors = listOf("duplicate"))
            points += Point(name, image.file, x, y)
        }
        return ReadResult(Snapshot(imagesById.values.toMutableList(), points))
    }

    private fun write(file: Snapshot): String? {
        val path = authoringPath() ?: return "write"
        val usedImages = file.points.mapTo(mutableSetOf()) { it.image.lowercase() }
        val images = file.images.filter { it.file.lowercase() in usedImages }.sortedBy { it.file }
        val names = file.points.map { it.path }.distinct().sorted()
        val imageIds = images.mapIndexed { index, image -> image.file.lowercase() to index + 1 }.toMap()
        val categoryIds = names.mapIndexed { index, name -> name to index + 1 }.toMap()
        val root = mapper.createObjectNode()
        val imageRows = mapper.createArrayNode()
        images.forEachIndexed { index, image ->
            imageRows.add(mapper.createObjectNode().put("id", index + 1).put("file_name", image.file).put("width", image.width).put("height", image.height))
        }
        val categoryRows = mapper.createArrayNode()
        names.forEachIndexed { index, name ->
            categoryRows.add(mapper.createObjectNode().put("id", index + 1).put("name", name).put("supercategory", name.substringBefore('.', "point")))
        }
        val annotationRows = mapper.createArrayNode()
        file.points.sortedBy { it.path }.forEachIndexed { index, point ->
            val node = mapper.createObjectNode()
                .put("id", index + 1)
                .put("image_id", imageIds[point.image.lowercase()] ?: return "image")
                .put("category_id", categoryIds[point.path] ?: return "category")
                .put("area", 0)
                .put("iscrowd", 0)
            val bbox = mapper.createArrayNode().add(point.x).add(point.y).add(0).add(0)
            node.set<JsonNode>("bbox", bbox)
            annotationRows.add(node)
        }
        root.set<JsonNode>("images", imageRows)
        root.set<JsonNode>("categories", categoryRows)
        root.set<JsonNode>("annotations", annotationRows)
        if (!writeAnnotationText(path, root.toPrettyString() + "\n")) return "write"
        AnnotationDataChanges.notify(path)
        return null
    }

    private fun root(): Path? = project.service<OkProjectDataService>().rootPath()
    private fun templatesDirectory(): String = OkScriptToolkitSettings.getInstance(project).okTemplatesDirectory()

    companion object {
        const val FILE_NAME = "points.json"
        private val SEGMENT = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
        private val PYTHON_KEYWORDS = setOf(
            "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class",
            "continue", "def", "del", "elif", "else", "except", "finally", "for", "from", "global",
            "if", "import", "in", "is", "lambda", "nonlocal", "not", "or", "pass", "raise", "return",
            "try", "while", "with", "yield",
        )
        private val GENERATED_MEMBER_NAMES = setOf("__init__", "_parent")

        fun positionPathError(value: String): String? {
            val path = value.trim()
            if (path.isEmpty()) return "empty"
            val parts = path.split('.')
            if (parts.size < 2) return "shallow"
            if (parts.any { !SEGMENT.matches(it) || it in PYTHON_KEYWORDS || it in GENERATED_MEMBER_NAMES }) return "segment"
            return null
        }

        fun sameImage(a: String, b: String): Boolean = a.substringAfterLast('/').substringAfterLast('\\')
            .equals(b.substringAfterLast('/').substringAfterLast('\\'), ignoreCase = true)
    }
}
