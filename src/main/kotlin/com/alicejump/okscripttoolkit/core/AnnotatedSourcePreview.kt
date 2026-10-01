package com.alicejump.okscripttoolkit.core

import com.intellij.openapi.project.Project
import java.awt.BasicStroke
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/** The authoring source with 200px of context and highlighted pixel bounds. */
internal object AnnotatedSourcePreview {
    fun fileFor(
        project: Project,
        imagePath: Path,
        bbox: IntArray,
        original: () -> BufferedImage? = { ImageIO.read(imagePath.toFile()) },
    ): Path? = runCatching {
        val thumbs = TemplateThumbCache.getInstance(project)
        val hash = thumbs.contentHash(imagePath) ?: return null
        val cache = thumbs.annotatedImages
        cache.cachedPath(hash, bbox)?.let { return it }
        val rendered = original()?.let { render(it, bbox) } ?: return null
        cache.store(hash, bbox, rendered)
    }.getOrNull()

    fun render(original: BufferedImage, bbox: IntArray): BufferedImage? {
        if (bbox.size != 4 || bbox[2] <= 0 || bbox[3] <= 0) return null
        val bx = bbox[0].coerceAtLeast(0)
        val by = bbox[1].coerceAtLeast(0)
        val right = (bbox[0].toLong() + bbox[2]).coerceAtMost(original.width.toLong()).toInt()
        val bottom = (bbox[1].toLong() + bbox[3]).coerceAtMost(original.height.toLong()).toInt()
        if (right <= bx || bottom <= by) return null
        val x0 = (bx - 200).coerceAtLeast(0)
        val y0 = (by - 200).coerceAtLeast(0)
        val x1 = (right.toLong() + 200).coerceAtMost(original.width.toLong()).toInt()
        val y1 = (bottom.toLong() + 200).coerceAtMost(original.height.toLong()).toInt()
        val cropW = x1 - x0
        val cropH = y1 - y0
        val scale = minOf(1.0, 400.0 / maxOf(cropW, cropH))
        val outW = (cropW * scale).roundToInt().coerceAtLeast(1)
        val outH = (cropH * scale).roundToInt().coerceAtLeast(1)
        val result = BufferedImage(outW, outH, BufferedImage.TYPE_INT_ARGB)
        val g = result.createGraphics()
        try {
            g.drawImage(original, 0, 0, outW, outH, x0, y0, x1, y1, null)
            val x = ((bx - x0) * scale).roundToInt()
            val y = ((by - y0) * scale).roundToInt()
            val w = ((right - bx) * scale).roundToInt().coerceAtLeast(1)
            val h = ((bottom - by) * scale).roundToInt().coerceAtLeast(1)
            g.stroke = BasicStroke(4f)
            g.color = Color.WHITE
            g.drawRect(x, y, w - 1, h - 1)
            g.stroke = BasicStroke(2f)
            g.color = Color(255, 40, 40)
            g.drawRect(x, y, w - 1, h - 1)
        } finally {
            g.dispose()
        }
        return result
    }
}
