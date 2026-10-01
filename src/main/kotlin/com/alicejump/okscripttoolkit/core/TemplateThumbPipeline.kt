package com.alicejump.okscripttoolkit.core

import java.awt.image.BufferedImage
import java.nio.file.Path
import java.util.concurrent.Executors
import javax.imageio.ImageIO
import javax.swing.ImageIcon
import javax.swing.SwingUtilities

/**
 * 模板缩略图**公共管线**：磁盘缓存（[TemplateThumbCache]，内容哈希 + bbox + 高度做键）
 * → 按源图分组懒解码（[TemplateThumbBatch]，同一张原图只解码一次）→ 裁剪缩放。
 *
 * 模板管理（原图整卡缩略图）与框管理（运行时框的 bbox 裁剪缩略图）共用这一条管线；
 * 谁都不许自己手搓一份缓存 / 解码循环 —— 那会绕开内容哈希失效与磁盘缓存，
 * 重启后同一批解码全部重做（见 [TemplateThumbCache] 的类注释）。
 */
object TemplateThumbPipeline {

    /** 一条缩略图请求：`key` 用于回 UI 定位，`imagePath` 是源图，`bbox` 是要裁的像素框。 */
    data class Request<K>(val key: K, val imagePath: Path, val bbox: IntArray)

    /** 单线程执行器：串行解码的堆压力远小于并发持有几张 15MB 的原图。 */
    private val thumbExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ok-script-thumb-loader").apply { isDaemon = true }
    }

    /**
     * 公共加载入口：缓存命中直接出图，未命中的按源图分组懒解码（解一张 → 裁完这一组 →
     * 释放），裁剪结果写回磁盘缓存；全部回调都在 UI 线程。
     *
     * 过期 / 重试等面板自己的簿记由调用方在 `onThumb` 里处理 —— 管线只负责
     * "缓存 → 解码 → 裁剪 → 存储"这一段，两块面板的刷新语义本来就不一样。
     */
    fun <K> loadThumbs(
        project: com.intellij.openapi.project.Project,
        requests: List<Request<K>>,
        targetHeight: Int,
        annotatedSource: Boolean = false,
        onThumb: (K, ImageIcon?) -> Unit,
    ) {
        if (requests.isEmpty()) return
        val cache = TemplateThumbCache.getInstance(project)
        for (group in TemplateThumbBatch.groupByImage(requests) { it.imagePath }) {
            thumbExecutor.submit {
                if (project.isDisposed) return@submit
                // 内容 hash 既做缓存键，也决定"能不能用缓存"：取不到就退回当场解码、不写缓存
                val contentHash = cache.contentHash(group.imagePath)
                // 懒解码：这一组全都命中磁盘缓存时，连原图都不用解
                var original: BufferedImage? = null
                val results = group.items.map { request ->
                    if (annotatedSource) {
                        val file = AnnotatedSourcePreview.fileFor(project, request.imagePath, request.bbox) {
                            if (original == null) original = decode(group.imagePath)
                            original
                        }
                        val image = file?.let { decode(it) }
                        val thumb = image?.let { cropToThumb(it, intArrayOf(0, 0, it.width, it.height), targetHeight, 240) }
                        return@map request.key to thumb?.let { ImageIcon(it) }
                    }
                    val cached = contentHash?.let { cache.load(it, request.bbox, targetHeight) }
                    if (cached != null) return@map request.key to ImageIcon(cached)
                    if (original == null) original = decode(group.imagePath)
                    val thumb = original?.let { cropToThumb(it, request.bbox, targetHeight) }
                    if (thumb != null && contentHash != null) {
                        cache.store(contentHash, request.bbox, targetHeight, thumb)
                    }
                    request.key to thumb?.let { ImageIcon(it) }
                }
                SwingUtilities.invokeLater {
                    if (!project.isDisposed) results.forEach { (key, icon) -> onThumb(key, icon) }
                }
            }
        }
    }

    /** 从**已解码**的原图上裁 bbox 并等比缩放到 targetHeight（绝不拉伸）。供写磁盘缓存用。 */
    fun cropToThumb(
        original: BufferedImage,
        bbox: IntArray,
        targetHeight: Int,
        maxWidth: Int = Int.MAX_VALUE,
    ): BufferedImage? {
        return try {
            val x = bbox[0].coerceIn(0, original.width - 1)
            val y = bbox[1].coerceIn(0, original.height - 1)
            val w = bbox[2].coerceAtMost(original.width - x)
            val h = bbox[3].coerceAtMost(original.height - y)
            if (w <= 0 || h <= 0) return null
            val crop = original.getSubimage(x, y, w, h)
            val scale = minOf(targetHeight.toDouble() / h, maxWidth.toDouble() / w)
            val targetW = (w * scale).toInt().coerceAtLeast(1)
            val targetH = (h * scale).toInt().coerceAtLeast(1)
            val thumb = BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB)
            val g = thumb.createGraphics()
            g.drawImage(crop, 0, 0, targetW, targetH, null)
            g.dispose()
            thumb
        } catch (e: Exception) {
            null
        }
    }

    /** 解码原图。失败返回 null —— 一张坏图不该让整组缩略图都消失。 */
    private fun decode(imagePath: Path): BufferedImage? = try {
        val file = imagePath.toFile()
        if (file.exists()) ImageIO.read(file) else null
    } catch (e: Exception) {
        null
    }
}
