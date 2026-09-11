package com.alicejump.okscripttoolkit.core

import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

/** 一张临时截图（文件名即 id） */
data class TempShot(val id: String, val file: File) {
    val name: String get() = file.name
}

/**
 * 临时截图的磁盘存储（纯文件逻辑，不依赖 IDE，便于单测）。
 *
 * 对齐 VSCode 版 tempScreenshotStore.ts：
 * - 文件名 `shot_<13 位毫秒时间戳>_<3 位序号>.png`，字典序即时间序，因此
 *   **列表直接从目录派生，无需额外索引文件**；
 * - 最多保留 [MAX_SHOTS] 张，超出淘汰最早的一张；
 * - 升序列出即为轮播播放顺序。
 */
class TempShotFiles(private val dir: File) {

    companion object {
        /** 临时截图上限（与 VSCode 版一致） */
        const val MAX_SHOTS = 10

        private val seq = AtomicInteger(0)
        private val NAME_RE = Regex("""^shot_\d{13}_\d{3}\.png$""")

        /** 时间戳定宽 + 自增序号，保证同一毫秒内多次写入也有稳定顺序 */
        fun nextName(): String =
            "shot_%013d_%03d.png".format(System.currentTimeMillis(), seq.getAndIncrement() % 1000)

        /** 是否是本存储生成的临时截图文件名（过滤目录里的无关文件） */
        fun isShotName(name: String): Boolean = NAME_RE.matches(name)
    }

    val directory: File get() = dir

    private fun ensureDir(): Boolean = dir.isDirectory || dir.mkdirs()

    /** 按时间升序列出全部临时截图（升序即轮播播放顺序） */
    fun list(): List<TempShot> {
        val files = dir.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && isShotName(it.name) }
            .sortedBy { it.name }
            .map { TempShot(it.name, it) }
    }

    fun get(id: String): TempShot? = list().firstOrNull { it.id == id }

    /** 为外部写入者（截图脚本）预留落盘路径，随后调用 [register] 生效 */
    fun newFilePath(): File {
        ensureDir()
        return File(dir, nextName())
    }

    /** 外部写入完成后登记：执行数量淘汰。文件不存在返回 null。 */
    fun register(file: File): TempShot? {
        if (!file.isFile) return null
        enforceLimit()
        return TempShot(file.name, file)
    }

    /** 把内存图片写成 PNG 并登记 */
    fun writeImage(image: BufferedImage): TempShot? {
        val target = newFilePath()
        return try {
            ImageIO.write(image, "png", target)
            register(target)
        } catch (_: Exception) {
            null
        }
    }

    fun remove(id: String): Boolean {
        val target = get(id) ?: return false
        return target.file.delete()
    }

    /** 清空全部临时截图，返回删除数量 */
    fun clear(): Int {
        var removed = 0
        for (shot in list()) {
            if (shot.file.delete()) removed++
        }
        return removed
    }

    /** 超出上限时删除最早的若干张 */
    private fun enforceLimit() {
        val shots = list()
        val overflow = shots.size - MAX_SHOTS
        for (i in 0 until overflow) {
            shots[i].file.delete()
        }
    }
}
