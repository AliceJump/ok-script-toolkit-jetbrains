package com.alicejump.okscripttoolkit.core

import java.nio.file.Path
import java.nio.file.Paths

/**
 * 框资源的纯数据契约。与 VS Code 侧 `src/boxResourcePure.ts` 一一对应。
 *
 * 标注资源是 `<模板目录>/boxes.json`（带原图文件名）。
 * 运行时资源只有 path + rect，由 [BoxRuntimePath] 定位。
 * 设计见仓库根 `docs/box-resources.md`。
 */
object BoxResource {
    const val VERSION = 1
    const val RECT_DECIMALS = 6
    const val AUTHORING_FILE_NAME = "boxes.json"

    val RESERVED_ROOTS = setOf("panels")

    private val SEGMENT = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    data class PixelBox(val x: Int, val y: Int, val w: Int, val h: Int)

    data class AuthoringBox(val path: String, val image: String, val rect: DoubleArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AuthoringBox) return false
            return path == other.path && image == other.image && rect.contentEquals(other.rect)
        }

        override fun hashCode(): Int = path.hashCode()
    }

    data class RuntimeBox(val path: String, val rect: DoubleArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is RuntimeBox) return false
            return path == other.path && rect.contentEquals(other.rect)
        }

        override fun hashCode(): Int = path.hashCode()
    }

    data class AuthoringFile(val version: Int = VERSION, val boxes: List<AuthoringBox> = emptyList())
    data class RuntimeFile(val version: Int = VERSION, val boxes: List<RuntimeBox> = emptyList())

    data class ParseResult<T>(val file: T, val errors: List<String>)

    enum class PublishStatus { SAME, UNPUBLISHED, RUNTIME_ONLY }

    data class PathStatus(val path: String, val status: PublishStatus)

    fun authoringFile(projectDir: String, templatesDir: String): Path =
        Paths.get(projectDir, templatesDir, AUTHORING_FILE_NAME)

    /** 合法返回 null。否则返回稳定错误码。 */
    fun pathError(value: String): String? {
        val pathValue = value.trim()
        if (pathValue.isEmpty()) return "empty"
        val segments = pathValue.split('.')
        if (segments.size < 2) return "shallow"
        if (segments.any { !SEGMENT.matches(it) }) return "segment"
        if (segments.first() in RESERVED_ROOTS) return "reserved"
        return null
    }

    fun imageFileName(value: String): String =
        value.replace('\\', '/').substringAfterLast('/').trim()

    fun rectToPixel(rect: DoubleArray, width: Int, height: Int): PixelBox? {
        if (rect.size != 4 || width <= 0 || height <= 0) return null
        val x = Math.round(rect[0] * width).toInt()
        val y = Math.round(rect[1] * height).toInt()
        val w = Math.round((rect[2] - rect[0]) * width).toInt()
        val h = Math.round((rect[3] - rect[1]) * height).toInt()
        if (w <= 0 || h <= 0) return null
        return PixelBox(x, y, w, h)
    }

    fun pixelToRect(box: PixelBox, width: Int, height: Int): DoubleArray? {
        if (width <= 0 || height <= 0 || box.w <= 0 || box.h <= 0) return null
        return doubleArrayOf(
            box.x.toDouble() / width,
            box.y.toDouble() / height,
            (box.x + box.w).toDouble() / width,
            (box.y + box.h).toDouble() / height,
        )
    }

    fun samePixel(a: PixelBox, b: PixelBox): Boolean = a == b

    fun rectForSave(original: DoubleArray?, pixel: PixelBox, width: Int, height: Int): DoubleArray? {
        if (original != null) {
            val quantized = rectToPixel(original, width, height)
            if (quantized != null && samePixel(quantized, pixel)) return original.copyOf()
        }
        return pixelToRect(pixel, width, height)
    }

    data class ReplacementBox(
        val path: String,
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int,
        val original: DoubleArray? = null,
        val unchanged: Boolean = false,
    )

    data class ImageReplacement(
        val fileName: String,
        val width: Int,
        val height: Int,
        val boxes: List<ReplacementBox>,
    )

    data class ReplaceResult(val boxes: List<AuthoringBox>, val error: String?)

    /**
     * 在内存里依次替换多张图的框。任一图不合法就整批失败，调用方此时还不能写盘。
     */
    fun replaceAuthoringImages(existing: List<AuthoringBox>, edits: List<ImageReplacement>): ReplaceResult {
        var current = existing
        for (edit in edits) {
            val image = imageFileName(edit.fileName)
            if (image.isEmpty() || edit.width <= 0 || edit.height <= 0) return ReplaceResult(existing, "image")
            val kept = current.filter { !sameImageName(it.image, image) }
            val taken = kept.map { it.path }.toMutableSet()
            val next = ArrayList<AuthoringBox>(edit.boxes.size)
            for (box in edit.boxes) {
                val error = pathError(box.path)
                if (error != null) return ReplaceResult(existing, error)
                if (!taken.add(box.path)) return ReplaceResult(existing, "duplicate")
                val rect = if (box.unchanged && box.original != null) {
                    box.original
                } else {
                    rectForSave(box.original, PixelBox(box.x, box.y, box.w, box.h), edit.width, edit.height)
                        ?: return ReplaceResult(existing, "rect")
                }
                next += AuthoringBox(box.path, image, rect)
            }
            current = kept + next
        }
        return ReplaceResult(current, null)
    }

    private fun sameImageName(a: String, b: String): Boolean =
        imageFileName(a).equals(imageFileName(b), ignoreCase = true)

    fun unionOnImage(boxes: List<PixelBox>, width: Int, height: Int): DoubleArray? {
        if (boxes.isEmpty()) return null
        val left = boxes.minOf { it.x }
        val top = boxes.minOf { it.y }
        val right = boxes.maxOf { it.x + it.w }
        val bottom = boxes.maxOf { it.y + it.h }
        return pixelToRect(PixelBox(left, top, right - left, bottom - top), width, height)
    }

    fun publish(file: AuthoringFile): RuntimeFile =
        RuntimeFile(boxes = file.boxes.map { RuntimeBox(it.path, it.rect.copyOf()) })

    fun publishStatus(authoring: AuthoringFile, runtime: RuntimeFile): List<PathStatus> {
        val runtimeByPath = runtime.boxes.associateBy { it.path }
        val seen = mutableSetOf<String>()
        val result = mutableListOf<PathStatus>()
        for (box in authoring.boxes) {
            seen += box.path
            val published = runtimeByPath[box.path]
            val same = published != null && sameQuantized(published.rect, box.rect)
            result += PathStatus(box.path, if (same) PublishStatus.SAME else PublishStatus.UNPUBLISHED)
        }
        for (box in runtime.boxes) {
            if (box.path !in seen) result += PathStatus(box.path, PublishStatus.RUNTIME_ONLY)
        }
        return result
    }

    fun applyVisibility(ids: List<String>, hidden: Set<String>, action: String, target: String? = null): Set<String> {
        return when (action) {
            "showAll" -> emptySet()
            "hideAll" -> ids.toSet()
            "only" -> ids.toSet().let { next -> if (target != null) next - target else next }
            else -> {
                val live = ids.toSet()
                val next = hidden.filter { it in live }.toMutableSet()
                if (target != null && target in live) {
                    if (target in next) next.remove(target) else next.add(target)
                }
                next
            }
        }
    }

    fun isVisible(id: String, hidden: Set<String>): Boolean = id !in hidden

    fun parseAuthoring(text: String): ParseResult<AuthoringFile> {
        val parsed = parse(text, requireImage = true) ?: return ParseResult(AuthoringFile(), listOf("json"))
        return ParseResult(AuthoringFile(boxes = parsed.boxes), parsed.errors)
    }

    fun parseRuntime(text: String): ParseResult<RuntimeFile> {
        val parsed = parse(text, requireImage = false) ?: return ParseResult(RuntimeFile(), listOf("json"))
        return ParseResult(RuntimeFile(boxes = parsed.boxes.map { RuntimeBox(it.path, it.rect) }), parsed.errors)
    }

    fun serializeAuthoring(file: AuthoringFile): String = serialize(file.boxes, includeImage = true)

    fun serializeRuntime(file: RuntimeFile): String =
        serialize(file.boxes.map { AuthoringBox(it.path, "", it.rect) }, includeImage = false)

    private data class Parsed(val boxes: List<AuthoringBox>, val errors: List<String>)

    private fun parse(text: String, requireImage: Boolean): Parsed? {
        val root = runCatching { JSON.readTree(text) }.getOrNull() ?: return null
        if (!root.isObject) return Parsed(emptyList(), listOf("root"))
        val errors = mutableListOf<String>()
        if (root.path("version").asInt(-1) != VERSION) errors += "version"
        val array = root.path("boxes")
        if (!array.isArray) {
            errors += "boxes"
            return Parsed(emptyList(), errors)
        }
        val boxes = mutableListOf<AuthoringBox>()
        val seen = mutableSetOf<String>()
        array.forEachIndexed { index, entry ->
            val box = readBox(entry, requireImage)
            if (box == null) {
                errors += "$index:${entryError(entry, requireImage)}"
                return@forEachIndexed
            }
            if (!seen.add(box.path)) {
                errors += "$index:duplicate"
                return@forEachIndexed
            }
            boxes += box
        }
        return Parsed(boxes.sortedBy { it.path }, errors)
    }

    private fun readBox(entry: com.fasterxml.jackson.databind.JsonNode, requireImage: Boolean): AuthoringBox? {
        if (!entry.isObject) return null
        val path = entry.path("path").takeIf { it.isTextual }?.asText()?.trim() ?: return null
        if (pathError(path) != null) return null
        val image = entry.path("image").takeIf { it.isTextual }?.asText()?.let(::imageFileName).orEmpty()
        if (requireImage && image.isEmpty()) return null
        val rect = readRect(entry.path("rect")) ?: return null
        return AuthoringBox(path, image, rect)
    }

    private fun entryError(entry: com.fasterxml.jackson.databind.JsonNode, requireImage: Boolean): String {
        if (!entry.isObject) return "entry"
        val path = entry.path("path").takeIf { it.isTextual }?.asText()
        if (path == null || pathError(path) != null) return "path"
        val image = entry.path("image").takeIf { it.isTextual }?.asText()?.let(::imageFileName).orEmpty()
        if (requireImage && image.isEmpty()) return "image"
        if (readRect(entry.path("rect")) == null) return "rect"
        return "entry"
    }

    private fun readRect(node: com.fasterxml.jackson.databind.JsonNode): DoubleArray? {
        if (!node.isArray || node.size() != 4) return null
        val rect = DoubleArray(4) { index ->
            val item = node[index]
            if (!item.isNumber) return null
            item.asDouble()
        }
        if (rect.any { !it.isFinite() }) return null
        if (rect[0] < 0 || rect[1] < 0 || rect[2] > 1 || rect[3] > 1) return null
        if (rect[0] >= rect[2] || rect[1] >= rect[3]) return null
        return rect
    }

    private fun serialize(boxes: List<AuthoringBox>, includeImage: Boolean): String {
        val unique = boxes.distinctBy { it.path }.sortedBy { it.path }
        val body = unique.joinToString(",\n") { box ->
            val fields = mutableListOf(""""path": ${jsonString(box.path)}""")
            if (includeImage) fields += """"image": ${jsonString(box.image)}"""
            val rect = stableRect(box.rect)
        fields += """"rect": [${rect.joinToString(", ") { formatNumber(it) }}]"""
            fields.joinToString(",\n      ", prefix = "    {\n      ", postfix = "\n    }")
        }
        val array = if (body.isEmpty()) "[]" else "[\n$body\n  ]"
        return "{\n  \"version\": $VERSION,\n  \"boxes\": $array\n}\n"
    }

    private fun sameQuantized(a: DoubleArray, b: DoubleArray): Boolean {
        if (a.size != b.size) return false
        return a.indices.all { formatNumber(a[it]) == formatNumber(b[it]) }
    }

    private fun formatNumber(value: Double): String {
        val rounded = Math.round(value * 1_000_000.0) / 1_000_000.0
        val normalized = if (rounded == 0.0) 0.0 else rounded
        return "%.${RECT_DECIMALS}f".format(java.util.Locale.US, normalized)
    }

    private fun stableRect(rect: DoubleArray): DoubleArray {
        if (rect.size != 4) return rect
        val out = DoubleArray(4) { formatNumber(rect[it]).toDouble() }
        if (out[0] >= out[2]) {
            if (out[2] < 1.0) out[2] = (out[0] + 0.000001).coerceAtMost(1.0)
            if (out[0] >= out[2]) out[0] = (out[2] - 0.000001).coerceAtLeast(0.0)
        }
        if (out[1] >= out[3]) {
            if (out[3] < 1.0) out[3] = (out[1] + 0.000001).coerceAtMost(1.0)
            if (out[1] >= out[3]) out[1] = (out[3] - 0.000001).coerceAtLeast(0.0)
        }
        return out
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }

    private val JSON = com.fasterxml.jackson.databind.ObjectMapper()
}
