package com.alicejump.okscripttoolkit.core

import java.awt.image.BufferedImage
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TempShotFilesTest {

    private fun newStore(): TempShotFiles =
        TempShotFiles(kotlin.io.path.createTempDirectory("ok-temp-shots").toFile())

    private fun image(width: Int = 8, height: Int = 8): BufferedImage =
        BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)

    @Test
    fun `list is ordered oldest first`() {
        val store = newStore()
        repeat(3) { store.writeImage(image()) }
        val names = store.list().map { it.id }
        assertEquals(names.sorted(), names, "文件名含定宽时间戳与序号，字典序即时间序")
    }

    @Test
    fun `caps at MAX_SHOTS and evicts the oldest`() {
        val store = newStore()
        val total = TempShotFiles.MAX_SHOTS + 4
        val written = (1..total).mapNotNull { store.writeImage(image())?.id }
        assertEquals(total, written.size, "全部写入都应成功")

        val kept = store.list()
        assertEquals(TempShotFiles.MAX_SHOTS, kept.size, "最多保留 $TempShotFiles.MAX_SHOTS 张")
        // 保留下来的应是最晚写入的一批
        assertEquals(written.takeLast(TempShotFiles.MAX_SHOTS), kept.map { it.id })
    }

    @Test
    fun `remove and clear delete the files`() {
        val store = newStore()
        val first = store.writeImage(image())!!
        val second = store.writeImage(image())!!

        assertTrue(store.remove(first.id))
        assertEquals(false, first.file.isFile)
        assertEquals(listOf(second.id), store.list().map { it.id })

        assertEquals(1, store.clear())
        assertTrue(store.list().isEmpty())
        assertEquals(false, second.file.isFile)
    }

    @Test
    fun `register ignores files that were not created`() {
        val store = newStore()
        val missing = File(store.newFilePath().parentFile, "shot_0000000000000_000.png")
        assertNull(store.register(missing), "外部写入失败时不应登记")
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `newFilePath can be registered by an external writer`() {
        val store = newStore()
        val path = store.newFilePath()
        path.writeBytes(byteArrayOf(0x01, 0x02))
        val shot = store.register(path)
        assertEquals(path.name, shot?.id)
        assertEquals(1, store.list().size)
    }

    @Test
    fun `unrelated files in the directory are ignored`() {
        val dir = kotlin.io.path.createTempDirectory("ok-temp-shots").toFile()
        val store = TempShotFiles(dir)
        File(dir, "screenshot_20250101_000000.png").writeBytes(byteArrayOf())
        File(dir, "notes.txt").writeBytes(byteArrayOf())
        store.writeImage(image())
        assertEquals(1, store.list().size, "只认 shot_<13 位时间戳>_<3 位序号>.png")
        assertEquals(false, TempShotFiles.isShotName("screenshot_20250101_000000.png"))
        assertEquals(true, TempShotFiles.isShotName(TempShotFiles.nextName()))
    }
}
