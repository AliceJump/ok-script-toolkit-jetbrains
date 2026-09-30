package com.alicejump.okscripttoolkit.core

import java.nio.file.Path

/** 框的名称规则、旧源文件导入和 normalized 运行时导出；源数据共用模板 COCO。 */
object BoxResource {
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
        CocoAnnotationData.templateDir(projectDir, templatesDir).resolve(AUTHORING_FILE_NAME)

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

    /**
     * authoring 尺寸条目 → 可用尺寸。缺失条目、以及 `width/height` 为 0 的条目
     * 都算**不可用**。
     *
     * 这是"登记尺寸能不能拿来当除数"的**唯一**判据：交换映射、UI 预检查、补登记
     * 三处都走它。各处各写一遍 `width > 0` 时，漏掉一处的表现是"store 肯换、
     * 界面先拦住"这种两边不一致的怪相。
     */
    fun usableImageSize(entry: AuthoringImage?): AnnotationSwap.Size? =
        entry?.let { AnnotationSwap.Size(it.width, it.height) }?.takeIf { AnnotationSwap.isUsable(it) }

    /* ── Pixel bbox 校验：与模板 COCO 的 bbox 同一条线（整数、正宽高、落在图内）。── */

    fun roundBbox(values: List<Double?>): IntArray? = AnnotationGeometry.roundBbox(values)

    fun bboxError(bbox: IntArray, size: AnnotationSwap.Size?): String? = AnnotationGeometry.bboxError(bbox, size)

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
        val w = Math.round(rect[2] * width).toInt() - x
        val h = Math.round(rect[3] * height).toInt() - y
        if (w <= 0 || h <= 0) return null
        return PixelBox(x, y, w, h)
    }

    fun pixelToRect(box: PixelBox, width: Int, height: Int): DoubleArray? {
        if (width <= 0 || height <= 0 || box.w <= 0 || box.h <= 0) return null
        return doubleArrayOf(
            box.x.toDouble() / width,
            box.y.toDouble() / height,
            (box.x.toLong() + box.w).toDouble() / width,
            (box.y.toLong() + box.h).toDouble() / height,
        )
    }

    /** Runtime 序列化约束：0–1、left<right、top<bottom。Authoring 不得使用。 */
    fun isStorableRuntimeRect(rect: DoubleArray): Boolean {
        if (rect.size != 4 || rect.any { !it.isFinite() }) return false
        if (rect[0] < 0 || rect[1] < 0 || rect[2] > 1 || rect[3] > 1) return false
        return rect[0] < rect[2] && rect[1] < rect[3]
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
        val seen = mutableSetOf<String>()
        for (box in file.boxes) {
            val error = pathError(box.path)
            if (error != null || !seen.add(box.path)) { errors += "path:${box.path}"; continue }
            val rect = publishedRect(box, file.images)
            if (rect == null || !isStorableRuntimeRect(rect)) {
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

    /** COCO 仅在发布和预览时投影成框条目，不参与源数据的编辑和序列化。 */
    fun authoringFromCoco(data: CocoData): AuthoringFile {
        val names = data.categories.associate { it.id to it.name }
        val images = data.images.associateBy { it.id }
        return AuthoringFile(
            images = data.images.map { AuthoringImage(it.fileName, it.width, it.height) },
            boxes = data.annotations.mapNotNull { annotation ->
                val image = images[annotation.imageId] ?: return@mapNotNull null
                val name = names[annotation.categoryId] ?: return@mapNotNull null
                AuthoringBox(name, image.fileName, annotation.bbox.copyOf())
            },
        )
    }

    /** 两种旧源格式只做内存转换；首次保存由共用数据层备份原文并写 COCO。 */
    fun parseBoxCoco(text: String, imageSize: (String) -> Pair<Int, Int>? = { null }): CocoReadResult {
        val root = runCatching { JSON.readTree(text) }.getOrNull() ?: return CocoReadResult(errors = listOf("json"))
        if (!root.isObject) return CocoReadResult(errors = listOf("root"))
        if (root.has("annotations") || root.has("categories")) return parseCocoText(text)
        val version = root.path("version").asInt(-1)
        if (version !in listOf(1, 2)) return CocoReadResult(errors = listOf("version"))
        val data = CocoData()
        val errors = mutableListOf<String>()
        if (version == 2) {
            if (!root.path("images").isArray) return CocoReadResult(errors = listOf("images"))
            for ((index, node) in root.path("images").withIndex()) {
                val name = node.path("file").takeIf { it.isTextual }?.asText()?.let(::imageFileName).orEmpty()
                val w = node.path("width").asInt()
                val h = node.path("height").asInt()
                if (name.isEmpty() || w <= 0 || h <= 0 || data.findImageByFileName(name) != null) errors += "images:$index"
                else data.addImage(name, w, h)
            }
        }
        if (!root.path("boxes").isArray) return CocoReadResult(errors = listOf("boxes"))
        val paths = mutableSetOf<String>()
        for ((index, node) in root.path("boxes").withIndex()) {
            val path = node.path("path").takeIf { it.isTextual }?.asText()?.trim().orEmpty()
            val name = node.path("image").takeIf { it.isTextual }?.asText()?.let(::imageFileName).orEmpty()
            val nameError = pathError(path)
            if (nameError != null || !paths.add(path)) { errors += "$index:${nameError ?: "duplicate"}"; continue }
            var image = data.findImageByFileName(name)
            if (image == null && version == 1 && name.isNotBlank()) {
                imageSize(name)?.takeIf { it.first > 0 && it.second > 0 }?.let { image = data.addImage(name, it.first, it.second) }
            }
            val entry = image
            if (entry == null) { errors += "$index:image"; continue }
            val bbox = if (version == 2) {
                node.path("bbox").takeIf { it.isArray }?.map { it.takeIf { n -> n.isNumber }?.asDouble() }?.let(::roundBbox)
            } else {
                val rect = node.path("rect").takeIf { it.isArray && it.size() == 4 && it.all { n -> n.isNumber } }
                    ?.map { it.asDouble() }?.toDoubleArray()
                rect?.takeIf(::isStorableRuntimeRect)?.let { rectToPixel(it, entry.width, entry.height) }
                    ?.let { intArrayOf(it.x, it.y, it.w, it.h) }
            }
            if (bbox == null || bboxError(bbox, AnnotationSwap.Size(entry.width, entry.height)) != null) { errors += "$index:rect"; continue }
            data.addAnnotation(entry.id, data.getOrCreateCategory(path).id, bbox)
        }
        if (errors.isNotEmpty()) return CocoReadResult(errors = errors)
        return parseCocoText(CocoAnnotationData.serializeCoco(data).toString()).copy(legacy = true)
    }

    /** 名称是框入口唯一的编辑差异；整批排除待替换图片，再查全局唯一性。 */
    fun boxNamesError(existing: CocoData, edits: List<CocoAnnotationEdit>): String? {
        val replaced = edits.map { it.fileName.lowercase() }.toSet()
        val names = authoringFromCoco(existing).boxes.filter { it.image.lowercase() !in replaced }.map { it.path }.toMutableSet()
        for (edit in edits) for ((name, _) in edit.boxes) {
            pathError(name)?.let { return it }
            if (!names.add(name)) return "duplicate"
        }
        return null
    }

    fun serializeRuntime(file: RuntimeFile): String {
        val unique = file.boxes.distinctBy { it.path }.sortedBy { it.path }
        val body = unique.joinToString(",\n") { box ->
            val rect = stableRect(box.rect)
            listOf(
                "\"path\": ${jsonString(box.path)}",
                "\"rect\": [${rect.joinToString(", ") { formatNumber(it) }}]",
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
