package com.alicejump.okscripttoolkit.core

import java.io.File
import java.nio.file.Path

/** 原图删除失败时恢复已清理的 Rect / Point 工作文件，避免三种资源相互悬空。 */
internal fun deleteAnnotationImage(
    image: File,
    secondarySources: List<Path>,
    removeSecondaryAnnotations: () -> Boolean,
    deleteTemplateImage: () -> Boolean,
): Boolean {
    val transaction = ResourceFileTransaction.capture(secondarySources) ?: return false
    val deleted = runCatching { removeSecondaryAnnotations() && deleteTemplateImage() }.getOrDefault(false)
    if (!deleted && image.exists()) transaction.rollback()
    return deleted
}
