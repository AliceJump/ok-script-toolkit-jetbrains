package com.alicejump.okscripttoolkit.core

fun newBoxAnnotationData(): CocoAnnotationData = CocoAnnotationData(
    fileName = BoxResource.AUTHORING_FILE_NAME,
    validateNames = BoxResource::boxNamesError,
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
