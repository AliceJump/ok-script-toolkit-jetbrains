package com.alicejump.okscripttoolkit.core

import java.nio.file.Path

/** Rect authoring rules. Runtime publication is handled exclusively by unified Position resources. */
object BoxResource {
    const val AUTHORING_FILE_NAME = "boxes.json"

    /** Segment grammar shared with VS Code. */
    const val SEGMENT_SOURCE = "^[A-Za-z_][A-Za-z0-9_]*$"
    private val SEGMENT = Regex(SEGMENT_SOURCE)

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

    fun authoringFile(projectDir: String, templatesDir: String): Path =
        CocoAnnotationData.templateDir(projectDir, templatesDir).resolve(AUTHORING_FILE_NAME)

    /** Valid paths contain at least two Python identifier segments. */
    fun pathError(value: String): String? {
        val pathValue = value.trim()
        if (pathValue.isEmpty()) return "empty"
        val segments = pathValue.split('.')
        if (segments.size < 2) return "shallow"
        if (segments.any { !SEGMENT.matches(it) }) return "segment"
        return null
    }

    fun imageFileName(value: String): String =
        value.replace('\\', '/').substringAfterLast('/').trim()

    fun sameImageName(a: String, b: String): Boolean =
        imageFileName(a).equals(imageFileName(b), ignoreCase = true)

    fun usableImageSize(entry: AuthoringImage?): AnnotationSwap.Size? =
        entry?.let { AnnotationSwap.Size(it.width, it.height) }?.takeIf { AnnotationSwap.isUsable(it) }

    fun roundBbox(values: List<Double?>): IntArray? = AnnotationGeometry.roundBbox(values)

    fun bboxError(bbox: IntArray, size: AnnotationSwap.Size?): String? =
        AnnotationGeometry.bboxError(bbox, size)

    fun applyVisibility(ids: List<String>, hidden: Set<String>, action: String, target: String? = null): Set<String> =
        when (action) {
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

    fun isVisible(id: String, hidden: Set<String>): Boolean = id !in hidden

    /** Read-only projection used by preview and unified Position publication. */
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

    /** Name validation is the only rect-specific editing rule. */
    fun boxNamesError(existing: CocoData, edits: List<CocoAnnotationEdit>): String? {
        val replaced = edits.map { it.fileName.lowercase() }.toSet()
        val names = authoringFromCoco(existing).boxes
            .filter { it.image.lowercase() !in replaced }
            .map { it.path }
            .toMutableSet()
        for (edit in edits) for ((name, _) in edit.boxes) {
            pathError(name)?.let { return it }
            if (!names.add(name)) return "duplicate"
        }
        return null
    }
}
