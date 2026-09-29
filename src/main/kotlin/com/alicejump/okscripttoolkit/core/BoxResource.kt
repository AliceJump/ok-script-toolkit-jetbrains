package com.alicejump.okscripttoolkit.core

import kotlin.math.roundToInt
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 框资源的纯数据契约。与 VS Code 侧 `src/boxResourcePure.ts` 一一对应。
 *
 * 标注资源是 `<模板目录>/boxes.json`（version 2，与模板标注同一套 Pixel 模型：
 * 每张被引用原图的 width/height + 整数 bbox `[x, y, w, h]`）。
 * normalized 只存在于运行时资源（version 1 不变）；Publish 是唯一的归一化入口。
 * 旧 normalized 格式不受支持：解析直接报 version 错误，不做迁移。
 * 设计见仓库根 `docs/box-resources.md`。
 */
object BoxResource {
    const val AUTHORING_VERSION = 2
    const val RUNTIME_VERSION = 1
    const val RECT_DECIMALS = 6
    const val AUTHORING_FILE_NAME = "boxes.json"

    val RESERVED_ROOTS = setOf("panels")

    /** 段规则的唯一来源。VS Code 侧随 config 消息下发给 webview 的是同一个字面量。 */
    const val SEGMENT_SOURCE = "^[A-Za-z_][A-Za-z0-9_]*$"

    private val SEGMENT = Regex(SEGMENT_SOURCE)

    private val JSON = com.fasterxml.jackson.databind.ObjectMapper()

    data class PixelBox(val x: Int, val y: Int, val w: Int, val h: Int)

    data class AuthoringImage(val file: String, val width: Int, val height: Int)

    data class AuthoringBox(val path: String, val image: String, val bbox: IntArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AuthoringBox) return false
            return path == other.path && image == other.image && bbox.contentEquals(other.bbox)
        }

        override fun hashCode(): Int = path.hashCode()
    }

    data class AuthoringFile(
        val version: Int = AUTHORING_VERSION,
        val images: List<AuthoringImage> = emptyList(),
        val boxes: List<AuthoringBox> = emptyList(),
    )

    data class RuntimeBox(val path: String, val rect: DoubleArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is RuntimeBox) return false
            return path == other.path && rect.contentEquals(other.rect)
        }

        override fun hashCode(): Int = path.hashCode()
    }

    data class RuntimeFile(val version: Int = RUNTIME_VERSION, val boxes: List<RuntimeBox> = emptyList())

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

    /**
     * 「生成框」的即时校验：规则同 [pathError]，再加一条 —— 占用表里的任何 path
     * （**包括当前图片自己的框**）都是重复，新建框不允许和已有框重名。
     * `occupied` 是 path → 所属图片的全局占用表；返回错误码，null = 合法。
     */
    fun generateBoxPathProblem(value: String, occupied: Map<String, String>): String? {
        val pathValue = value.trim()
        val ruleError = pathError(pathValue)
        if (ruleError != null) return ruleError
        if (occupied.containsKey(pathValue)) return "duplicate"
        return null
    }

    fun imageFileName(value: String): String =
        value.replace('\\', '/').substringAfterLast('/').trim()

    fun sameImageName(a: String, b: String): Boolean =
        imageFileName(a).equals(imageFileName(b), ignoreCase = true)

    /* ── Pixel bbox 校验：与模板 COCO 的 bbox 同一条线（整数、正宽高、落在图内）。── */

    /** 非整数输入按四舍五入收进像素格（编辑器画布本来就只产生整数）。null 表示不是数字。 */
    fun roundBbox(values: List<Double?>): IntArray? {
        if (values.size != 4 || values.any { it == null || !it.isFinite() }) return null
        return intArrayOf(
            values[0]!!.roundToInt(),
            values[1]!!.roundToInt(),
            values[2]!!.roundToInt(),
            values[3]!!.roundToInt(),
        )
    }

    fun bboxError(bbox: IntArray, size: AnnotationSwap.Size?): String? {
        if (bbox.size != 4 || bbox.any { !it.toFloat().isFinite() }) return "rect"
        if (bbox[0] < 0 || bbox[1] < 0 || bbox[2] < 1 || bbox[3] < 1) return "rect"
        if (size != null && AnnotationSwap.isUsable(size)) {
            // 手改的 boxes.json 可能塞进 Int.MAX_VALUE：加法必须走 Long，否则回绕成
            // 负数反而骗过边界检查
            val right = bbox[0].toLong() + bbox[2].toLong()
            val bottom = bbox[1].toLong() + bbox[3].toLong()
            if (right > size.width || bottom > size.height) return "rect"
        }
        return null
    }

    /** 同一张原图上的像素框取最小包围矩形。空列表返回 null。不做任何归一化。 */
    fun unionPixelBoxes(boxes: List<PixelBox>): PixelBox? {
        if (boxes.isEmpty()) return null
        val left = boxes.minOf { it.x }
        val top = boxes.minOf { it.y }
        val right = boxes.maxOf { it.x + it.w }
        val bottom = boxes.maxOf { it.y + it.h }
        return PixelBox(left, top, right - left, bottom - top)
    }

    /* ── Runtime 转换层：只允许 Publish、Runtime Preview 和旧格式迁移调用。── */

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

    /** Runtime 序列化约束：0–1、left<right、top<bottom。Authoring 不得使用。 */
    fun isStorableRuntimeRect(rect: DoubleArray): Boolean {
        if (rect.size != 4 || rect.any { !it.isFinite() }) return false
        if (rect[0] < 0 || rect[1] < 0 || rect[2] > 1 || rect[3] > 1) return false
        return rect[0] < rect[2] && rect[1] < rect[3]
    }

    /* ── Authoring 的内存编辑。全程 Pixel，不出现 normalized。── */

    data class ReplacementBox(val path: String, val x: Int, val y: Int, val w: Int, val h: Int)

    data class ImageReplacement(
        val fileName: String,
        val width: Int,
        val height: Int,
        val boxes: List<ReplacementBox>,
    )

    data class ReplaceResult(val file: AuthoringFile, val error: String?)

    /**
     * 在内存里依次替换多张图的框，并登记 / 刷新它们的图片尺寸。
     * 任一图不合法就整批失败，调用方此时还不能写盘。
     * 尺寸来源是本次编辑拿到的真实值（图片头），已有条目被直接刷新成这个值。
     */
    fun replaceAuthoringImages(existing: AuthoringFile, edits: List<ImageReplacement>): ReplaceResult {
        var images = existing.images
        var current = existing.boxes
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
                val bbox = intArrayOf(box.x, box.y, box.w, box.h)
                if (bboxError(bbox, AnnotationSwap.Size(edit.width, edit.height)) != null) {
                    return ReplaceResult(existing, "rect")
                }
                next += AuthoringBox(box.path, image, bbox)
            }
            val entry = AuthoringImage(image, edit.width, edit.height)
            images = images.filterNot { sameImageName(it.file, image) } + entry
            current = kept + next
        }
        return ReplaceResult(AuthoringFile(boxes = current, images = images), null)
    }

    /* ── Publish：Pixel → normalized 的唯一入口。── */

    /** 单条框的发布投影。图片条目缺失或尺寸非法时返回 null，调用方必须报告而不是静默跳过。 */
    fun publishedRect(box: AuthoringBox, images: List<AuthoringImage>): DoubleArray? {
        val entry = images.firstOrNull { sameImageName(it.file, box.image) }
        if (entry == null || entry.width <= 0 || entry.height <= 0) return null
        return pixelToRect(PixelBox(box.bbox[0], box.bbox[1], box.bbox[2], box.bbox[3]), entry.width, entry.height)
    }

    data class PublishResult(val file: RuntimeFile, val errors: List<String>)

    /** Publish：把 Pixel authoring 投影成 normalized runtime。丢 image，留几何。 */
    fun publish(file: AuthoringFile): PublishResult {
        val boxes = mutableListOf<RuntimeBox>()
        val errors = mutableListOf<String>()
        for (box in file.boxes) {
            val rect = publishedRect(box, file.images)
            if (rect == null) {
                errors += "size:${box.path}"
                continue
            }
            boxes += RuntimeBox(box.path, rect)
        }
        return PublishResult(RuntimeFile(boxes = boxes), errors)
    }

    fun publishStatus(authoring: AuthoringFile, runtime: RuntimeFile): List<PathStatus> {
        val runtimeByPath = runtime.boxes.associateBy { it.path }
        val seen = mutableSetOf<String>()
        val result = mutableListOf<PathStatus>()
        for (box in authoring.boxes) {
            seen += box.path
            val published = runtimeByPath[box.path]
            val target = publishedRect(box, authoring.images)
            val same = published != null && target != null && sameQuantized(published.rect, target)
            result += PathStatus(box.path, if (same) PublishStatus.SAME else PublishStatus.UNPUBLISHED)
        }
        for (box in runtime.boxes) {
            if (box.path !in seen) result += PathStatus(box.path, PublishStatus.RUNTIME_ONLY)
        }
        return result
    }

    /* ── 图片交换：尺寸不同时按比例映射（复用模板交换的 AnnotationSwap.scaleBox）。── */

    data class SwapResult(val file: AuthoringFile, val error: String?)

    fun swapImageBoxes(file: AuthoringFile, fileA: String, fileB: String): SwapResult {
        val entryA = file.images.firstOrNull { sameImageName(it.file, fileA) }
        val entryB = file.images.firstOrNull { sameImageName(it.file, fileB) }
        val sizeA = entryA?.takeIf { it.width > 0 && it.height > 0 }?.let { AnnotationSwap.Size(it.width, it.height) }
        val sizeB = entryB?.takeIf { it.width > 0 && it.height > 0 }?.let { AnnotationSwap.Size(it.width, it.height) }
        if (sizeA == null || sizeB == null) return SwapResult(file, "size")

        fun remap(box: AuthoringBox, target: String, from: AnnotationSwap.Size, to: AnnotationSwap.Size): AuthoringBox {
            if (AnnotationSwap.isSameSize(from, to)) return AuthoringBox(box.path, target, box.bbox.copyOf())
            val scaled = AnnotationSwap.scaleBox(box.bbox, from, to)
            return AuthoringBox(box.path, target, scaled)
        }

        val next = file.boxes.map { box ->
            when {
                sameImageName(box.image, fileA) -> remap(box, imageFileName(fileB), sizeA, sizeB)
                sameImageName(box.image, fileB) -> remap(box, imageFileName(fileA), sizeB, sizeA)
                else -> box
            }
        }
        return SwapResult(AuthoringFile(images = file.images, boxes = next), null)
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

    /* ── 解析 / 序列化。Authoring 不再与 Runtime 共用 normalized 规则。── */

    fun parseAuthoring(text: String): ParseResult<AuthoringFile> {
        val root = runCatching { JSON.readTree(text) }.getOrNull()
            ?: return ParseResult(AuthoringFile(), listOf("json"))
        if (!root.isObject) return ParseResult(AuthoringFile(), listOf("root"))
        // version 1（旧 normalized rect）不受支持：authoring 只有 Pixel 一种模型，不做迁移。
        if (root.path("version").asInt(-1) != AUTHORING_VERSION) {
            return ParseResult(AuthoringFile(), listOf("version"))
        }
        val errors = mutableListOf<String>()
        val imagesNode = root.path("images")
        if (!imagesNode.isArray) return ParseResult(AuthoringFile(), listOf("images"))
        val images = mutableListOf<AuthoringImage>()
        val byFile = mutableMapOf<String, AuthoringImage>()
        imagesNode.forEachIndexed { index, entry ->
            val file = entry.path("file").takeIf { it.isTextual }?.asText()?.let(::imageFileName).orEmpty()
            val width = entry.path("width").takeIf { it.isNumber }?.asInt() ?: 0
            val height = entry.path("height").takeIf { it.isNumber }?.asInt() ?: 0
            if (file.isEmpty() || width <= 0 || height <= 0) {
                errors += "images:$index"
                return@forEachIndexed
            }
            if (byFile.containsKey(file.lowercase())) {
                errors += "images:$index:duplicate"
                return@forEachIndexed
            }
            val parsed = AuthoringImage(file, width, height)
            byFile[file.lowercase()] = parsed
            images += parsed
        }
        val boxesNode = root.path("boxes")
        if (!boxesNode.isArray) {
            errors += "boxes"
            return ParseResult(AuthoringFile(images = images), errors)
        }
        val boxes = mutableListOf<AuthoringBox>()
        val seen = mutableSetOf<String>()
        boxesNode.forEachIndexed { index, entry ->
            if (!entry.isObject) {
                errors += "$index:entry"
                return@forEachIndexed
            }
            val path = entry.path("path").takeIf { it.isTextual }?.asText()?.trim().orEmpty()
            if (path.isEmpty() || pathError(path) != null) {
                errors += "$index:path"
                return@forEachIndexed
            }
            val image = entry.path("image").takeIf { it.isTextual }?.asText()?.let(::imageFileName).orEmpty()
            val imageEntry = byFile[image.lowercase()]
            if (imageEntry == null) {
                errors += "$index:image"
                return@forEachIndexed
            }
            // Jackson 的数值节点不实现 java.lang.Number，必须用 isNumber/asDouble 提取
            val values = entry.path("bbox").takeIf { it.isArray }
                ?.map { node -> node.takeIf { it.isNumber }?.asDouble() }
            val bbox = values?.let { roundBbox(it) }
            if (bbox == null || bboxError(bbox, AnnotationSwap.Size(imageEntry.width, imageEntry.height)) != null) {
                errors += "$index:rect"
                return@forEachIndexed
            }
            if (!seen.add(path)) {
                errors += "$index:duplicate"
                return@forEachIndexed
            }
            boxes += AuthoringBox(path, imageEntry.file, bbox)
        }
        return ParseResult(AuthoringFile(images = images.sortedBy { it.file }, boxes = boxes.sortedBy { it.path }), errors)
    }

    fun serializeAuthoring(file: AuthoringFile): String {
        val imageBody = file.images.sortedBy { it.file }.joinToString(",\n") {
            "    { \"file\": ${jsonString(it.file)}, \"width\": ${it.width}, \"height\": ${it.height} }"
        }
        val boxBody = file.boxes.distinctBy { it.path }.sortedBy { it.path }.joinToString(",\n") { box ->
            listOf(
                """"path": ${jsonString(box.path)}""",
                """"image": ${jsonString(box.image)}""",
                """"bbox": [${box.bbox.joinToString(", ")}]""",
            ).joinToString(",\n      ", prefix = "    {\n      ", postfix = "\n    }")
        }
        val images = if (imageBody.isEmpty()) "[]" else "[\n$imageBody\n  ]"
        val boxes = if (boxBody.isEmpty()) "[]" else "[\n$boxBody\n  ]"
        return "{\n  \"version\": $AUTHORING_VERSION,\n  \"images\": $images,\n  \"boxes\": $boxes\n}\n"
    }

    fun serializeRuntime(file: RuntimeFile): String {
        val unique = file.boxes.distinctBy { it.path }.sortedBy { it.path }
        val body = unique.joinToString(",\n") { box ->
            val rect = stableRect(box.rect)
            listOf(
                """"path": ${jsonString(box.path)}""",
                """"rect": [${rect.joinToString(", ") { formatNumber(it) }}]""",
            ).joinToString(",\n      ", prefix = "    {\n      ", postfix = "\n    }")
        }
        val array = if (body.isEmpty()) "[]" else "[\n$body\n  ]"
        return "{\n  \"version\": $RUNTIME_VERSION,\n  \"boxes\": $array\n}\n"
    }

    fun parseRuntime(text: String): ParseResult<RuntimeFile> {
        val root = runCatching { JSON.readTree(text) }.getOrNull()
            ?: return ParseResult(RuntimeFile(), listOf("json"))
        if (!root.isObject) return ParseResult(RuntimeFile(), listOf("root"))
        val errors = mutableListOf<String>()
        if (root.path("version").asInt(-1) != RUNTIME_VERSION) errors += "version"
        val array = root.path("boxes")
        if (!array.isArray) {
            errors += "boxes"
            return ParseResult(RuntimeFile(), errors)
        }
        val boxes = mutableListOf<RuntimeBox>()
        val seen = mutableSetOf<String>()
        array.forEachIndexed { index, entry ->
            val path = entry.path("path").takeIf { it.isTextual }?.asText()?.trim().orEmpty()
            val rect = readRectNode(entry.path("rect"))
            if (path.isEmpty() || pathError(path) != null) {
                errors += "$index:path"
                return@forEachIndexed
            }
            if (rect == null || !isStorableRuntimeRect(rect)) {
                errors += "$index:rect"
                return@forEachIndexed
            }
            if (!seen.add(path)) {
                errors += "$index:duplicate"
                return@forEachIndexed
            }
            boxes += RuntimeBox(path, rect)
        }
        return ParseResult(RuntimeFile(boxes = boxes.sortedBy { it.path }), errors)
    }

    private fun readRectNode(node: com.fasterxml.jackson.databind.JsonNode): DoubleArray? {
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
}
