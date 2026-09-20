package com.alicejump.okscripttoolkit.core

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/**
 * 模板缩略图的**磁盘缓存**（项目级）。
 *
 * 为什么需要：缩略图 = "解码整张原图 → 裁 bbox → 缩放"，其中解码最贵（2560×1440 的 PNG
 * 约 50ms）。没有持久缓存时，**每次 IDE 重启**、以及**每次数据变更**（面板会
 * `thumbs.clear()`）都要把这些解码重做一遍 —— 实测 ok-end-field 是 276 个模板 / 16 张原图，
 * 即 16 次解码；ok-wuthering-waves 是 156 张原图，约 8 秒。
 *
 * 有缓存之后，命中时只需要读一张几 KB 的小 PNG，**连原图都不用解码**
 * （见 `TemplatesToolWindowFactory.requestThumbs` 里的懒解码）。
 *
 * 落盘位置：`<IDE system>/ok-script-toolkit/template-thumbs/<项目哈希>/` ——
 * 与 [TempScreenshotStore.directoryFor] 同一套约定（按项目隔离，且**不写进项目目录**：
 * 缓存不该出现在用户的 git 里）。
 *
 * 与 VS Code 侧 `pngCrop` 的磁盘缩略图缓存对应（那边是 content-hash 命名的文件 + Worker 预热）。
 *
 * ⚠️ 缓存是**纯优化**：读失败、写失败、目录建不出来一律静默降级成"当场解码"。
 */
@Service(Service.Level.PROJECT)
class TemplateThumbCache(private val project: Project) {

    companion object {
        private val LOG = Logger.getInstance(TemplateThumbCache::class.java)

        /**
         * 目录里最多留多少个文件，超了按最后修改时间淘汰最旧的。
         *
         * 内容 hash 做键 ⇒ **原图被替换后会留下永远不会再命中的旧文件**，所以需要兜底淘汰。
         * 量级参考：281 个模板的缩略图约 1.4MB，正常项目远达不到这个上限。
         */
        private const val MAX_FILES = 5000

        fun getInstance(project: Project): TemplateThumbCache = project.service()

        /** 按项目路径哈希隔离的缓存目录（与 [TempScreenshotStore.directoryFor] 同一套约定）。 */
        fun directoryFor(project: Project): File {
            val base = project.basePath ?: "default"
            val hash = MessageDigest.getInstance("SHA-1")
                .digest(base.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(12)
            return Paths.get(PathManager.getSystemPath(), "ok-script-toolkit", "template-thumbs", hash).toFile()
        }
    }

    private data class ContentHash(val size: Long, val mtimeMs: Long, val hash: String)

    /** 原图路径 → 内容指纹。**仅当 size + mtimeMs 都没变时复用**。 */
    private val hashes = ConcurrentHashMap<String, ContentHash>()

    val directory: File = directoryFor(project)

    init {
        try {
            if (!directory.isDirectory && !directory.mkdirs()) {
                LOG.warn("Template thumbnail cache directory not writable: $directory")
            }
        } catch (e: Exception) {
            LOG.warn("Failed to prepare template thumbnail cache directory: $directory", e)
        }
        sweepIfOversized()
    }

    /**
     * 原图内容 sha1（前 16 hex）。
     *
     * **按 (size, mtimeMs) 记忆化**：算一次要读整个文件，每次渲染都算就等于把省下的解码
     * 又花回去了。反过来，只要 size 或 mtime 变了就重算 —— **内容被替换一定要被感知**，
     * 否则会复用旧缩略图（父仓 `pngCrop` 的注释记着这个坑：面板里的图和点开的不一致）。
     *
     * 取不到（文件不存在/不可读）返回 `null`，调用方据此退回"当场解码 + 不写缓存"。
     */
    fun contentHash(imagePath: Path): String? {
        val key = imagePath.toString()
        val file = imagePath.toFile()
        if (!file.isFile) {
            hashes.remove(key)
            return null
        }
        val size = file.length()
        val mtime = file.lastModified()
        hashes[key]?.let { if (it.size == size && it.mtimeMs == mtime) return it.hash }
        return try {
            val digest = MessageDigest.getInstance("SHA-1").digest(file.readBytes())
            val hash = digest.joinToString("") { "%02x".format(it) }.take(16)
            hashes[key] = ContentHash(size, mtime, hash)
            hash
        } catch (e: Exception) {
            LOG.warn("Failed to hash template source image: $imagePath", e)
            hashes.remove(key)
            null
        }
    }

    /** 丢弃某个原图的内容指纹记录，强制下次重新计算。 */
    fun invalidateContentHash(imagePath: Path) {
        hashes.remove(imagePath.toString())
    }

    /** 读缓存。未命中、文件坏了、读不出来一律返回 `null`（缓存不该让缩略图消失）。 */
    fun load(contentHash: String, bbox: IntArray, targetHeight: Int): BufferedImage? = try {
        val file = File(directory, TemplateThumbCacheKey.fileNameOf(contentHash, bbox, targetHeight))
        if (file.isFile) ImageIO.read(file) else null
    } catch (e: Exception) {
        LOG.warn("Failed to read cached template thumbnail: ${e.message}")
        null
    }

    /** 写缓存。**失败静默** —— 写不进去只该慢一点，不该给用户报错。 */
    fun store(contentHash: String, bbox: IntArray, targetHeight: Int, image: BufferedImage) {
        try {
            val file = File(directory, TemplateThumbCacheKey.fileNameOf(contentHash, bbox, targetHeight))
            if (!ImageIO.write(image, "png", file)) {
                LOG.warn("No PNG writer available for template thumbnail cache")
            }
        } catch (e: Exception) {
            LOG.warn("Failed to write cached template thumbnail: ${e.message}")
        }
    }

    /**
     * 超量时淘汰最旧的。
     *
     * 只在**初始化时**跑一次（不是每次写），所以正常情况下的开销就是一次 `listFiles()`；
     * 而且是在 `MAX_FILES` 之上才排序删除。
     */
    private fun sweepIfOversized() {
        try {
            val files = directory.listFiles { f -> f.isFile && f.name.endsWith(".png") } ?: return
            if (files.size <= MAX_FILES) return
            files.sortBy { it.lastModified() }
            var removed = 0
            for (file in files.take(files.size - MAX_FILES)) {
                if (file.delete()) removed++
            }
            LOG.info("Pruned $removed stale template thumbnails from $directory")
        } catch (e: Exception) {
            LOG.warn("Failed to prune template thumbnail cache: ${e.message}")
        }
    }
}
