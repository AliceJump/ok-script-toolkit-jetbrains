package com.alicejump.okscripttoolkit.core

import java.nio.file.Files
import java.nio.file.Path

fun newBoxAnnotationData(): CocoAnnotationData = CocoAnnotationData(
    BoxResource.AUTHORING_FILE_NAME, BoxResource::parseBoxCoco, BoxResource::boxNamesError,
)

/** 生成框与直接绘制都写入普通 COCO 标注。 */
fun addBoxAnnotation(source: CocoAnnotationData, path: String, image: String, bbox: IntArray): String? {
    BoxResource.pathError(path)?.let { return it }
    if (source.readErrors.isNotEmpty()) return "parse"
    val file = BoxResource.imageFileName(image)
    if (file.isBlank()) return "image"
    val size = source.resolveImageSize(file) ?: return "image"
    val names = source.categories().associate { it.id to it.name }
    val current = source.getImageEntryForFile(file)?.let { source.getAnnotationsForImage(it.id) }.orEmpty()
    val boxes = current.map { names[it.categoryId].orEmpty() to it.bbox } + (path.trim() to bbox)
    return if (source.saveAnnotationEdits(listOf(CocoAnnotationEdit(file, size, boxes)))) null else source.lastError ?: "write"
}

/** 空源、坏源、同路径和无法读取的运行时文件都不能覆盖已有发布结果。 */
fun publishBoxAnnotations(source: CocoAnnotationData, target: Path, runtimeErrors: List<String> = emptyList()): List<String> {
    if (source.readErrors.isNotEmpty()) return listOf("parse")
    val authoring = BoxResource.authoringFromCoco(source.data)
    if (authoring.boxes.isEmpty()) return listOf("empty")
    val file = source.annotationFile
    if (file != null && BoxRuntimePath.sameLocation(file, target)) return listOf("same")
    if (runtimeErrors.isNotEmpty()) return listOf("runtimeRead")
    if (!Files.notExists(target)) {
        val parsed = runCatching { BoxResource.parseRuntime(Files.readString(target)) }.getOrNull()
        if (parsed == null || parsed.errors.isNotEmpty()) return listOf("runtimeRead")
    }
    val images = authoring.images.map { image ->
        val size = source.resolveImageSize(image.file)
        if (size == null) image else image.copy(width = size.first, height = size.second)
    }
    val published = BoxResource.publish(authoring.copy(images = images))
    if (published.errors.isNotEmpty()) return published.errors
    if (!writeAnnotationText(target, BoxResource.serializeRuntime(published.file))) return listOf("write")
    AnnotationDataChanges.notify(target)
    return emptyList()
}
