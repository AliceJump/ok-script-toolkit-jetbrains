package com.alicejump.okscripttoolkit.core

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CopyOnWriteArrayList

private val JSON = com.fasterxml.jackson.databind.ObjectMapper()

data class CocoReadResult(val data: CocoData = CocoData(), val errors: List<String> = emptyList(), val legacy: Boolean = false)

/** 解析失败的原文件不允许被空数据覆盖；名称规则留给具体资源入口。 */
fun parseCocoText(text: String): CocoReadResult {
    val root = runCatching { JSON.readTree(text) }.getOrNull() ?: return CocoReadResult(errors = listOf("json"))
    val errors = mutableListOf<String>()
    if (!root.isObject) return CocoReadResult(errors = listOf("root"))
    for (key in listOf("images", "annotations", "categories")) {
        if (!root.path(key).isArray) errors += key
    }
    if (errors.isNotEmpty()) return CocoReadResult(errors = errors)
    val imageIds = mutableSetOf<Int>()
    val files = mutableSetOf<String>()
    for ((index, image) in root.path("images").withIndex()) {
        if (!image.isObject || !image.path("id").isIntegralNumber || !image.path("id").canConvertToInt() ||
            !imageIds.add(image.path("id").asInt()) || !image.path("file_name").isTextual ||
            image.path("file_name").asText().isBlank() || !files.add(image.path("file_name").asText().lowercase()) ||
            listOf("width", "height").any { !image.path(it).isIntegralNumber || !image.path(it).canConvertToInt() || image.path(it).asInt() < 0 }) {
            errors += "images:$index"
        }
    }
    val categoryIds = mutableSetOf<Int>()
    for ((index, category) in root.path("categories").withIndex()) {
        if (!category.isObject || !category.path("id").isIntegralNumber || !category.path("id").canConvertToInt() ||
            !categoryIds.add(category.path("id").asInt()) || !category.path("name").isTextual || category.path("name").asText().isBlank()) {
            errors += "categories:$index"
        }
    }
    val data = CocoAnnotationData.parseCoco(root)
    val annotationIds = mutableSetOf<Int>()
    for ((index, annotation) in root.path("annotations").withIndex()) {
        val image = data.images.firstOrNull { it.id == annotation.path("image_id").asInt() }
        val bbox = annotation.path("bbox").takeIf { it.isArray }?.map { it.takeIf { n -> n.isNumber }?.asDouble() }
            ?.let(AnnotationGeometry::roundBbox)
        if (!annotation.isObject || listOf("id", "image_id", "category_id").any {
                !annotation.path(it).isIntegralNumber || !annotation.path(it).canConvertToInt()
            } || !annotationIds.add(annotation.path("id").asInt()) || image == null ||
            annotation.path("category_id").asInt() !in categoryIds || bbox == null ||
            AnnotationGeometry.bboxError(bbox, AnnotationSwap.Size(image.width, image.height)) != null) errors += "annotations:$index"
    }
    return if (errors.isEmpty()) CocoReadResult(data) else CocoReadResult(errors = errors)
}

/** 文件落盘后直接通知编辑器和画廊；不依赖 IDE 何时扫描到磁盘变更。 */
object AnnotationDataChanges {
    private val listeners = CopyOnWriteArrayList<(Path) -> Unit>()

    fun subscribe(listener: (Path) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    fun notify(path: Path) {
        listeners.forEach { listener ->
            runCatching { listener(path) }.onFailure {
                com.intellij.openapi.diagnostic.Logger.getInstance(AnnotationDataChanges::class.java).warn("Failed to refresh annotations at $path", it)
            }
        }
    }
}

/** 两种源数据和框的导出共用同目录临时文件替换。 */
fun writeAnnotationText(target: Path, text: String): Boolean {
    var temporary: Path? = null
    return try {
        Files.createDirectories(target.parent)
        val staged = Files.createTempFile(target.parent, ".annotations-", ".tmp")
        temporary = staged
        Files.writeString(staged, text, StandardCharsets.UTF_8)
        try {
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING)
        }
        true
    } catch (e: Exception) {
        com.intellij.openapi.diagnostic.Logger.getInstance(CocoAnnotationData::class.java).warn("Failed to write $target", e)
        false
    } finally {
        temporary?.let { runCatching { Files.deleteIfExists(it) } }
    }
}
