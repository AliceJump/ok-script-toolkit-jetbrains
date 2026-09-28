package com.alicejump.okscripttoolkit.core

import com.alicejump.okscripttoolkit.TestTmp
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnnotatedImageCacheTest {
    private fun cache(): AnnotatedImageCache =
        AnnotatedImageCache(TestTmp.create("annotated-images").toPath().resolve("annotated"))

    private fun image(): BufferedImage = BufferedImage(8, 6, BufferedImage.TYPE_INT_ARGB)

    @Test
    fun `reuses rendered image and changes key with source content or bbox`() {
        val cache = cache()
        val bbox = intArrayOf(1, 2, 3, 4)
        val first = assertNotNull(cache.store("source-hash-1", bbox, image()))
        assertEquals(first, cache.cachedPath("source-hash-1", bbox))
        assertEquals(first, cache.store("source-hash-1", bbox, image()))
        assertEquals(8, ImageIO.read(first.toFile()).width)

        val changedSource = assertNotNull(cache.store("source-hash-2", bbox, image()))
        val changedBbox = assertNotNull(cache.store("source-hash-1", intArrayOf(1, 2, 3, 5), image()))
        assertNotEquals(first, changedSource)
        assertNotEquals(first, changedBbox)
        assertTrue(Files.isRegularFile(changedSource))
        assertTrue(Files.isRegularFile(changedBbox))
    }

    @Test
    fun `cache file name is safe and incomplete images are rebuilt`() {
        val cache = cache()
        val bbox = intArrayOf(1, 2, 3, 4)
        val fileName = AnnotatedImageCache.fileNameOf("../unsafe\\template:name", bbox)
        assertTrue(fileName.matches(Regex("a2_[0-9a-f]{16}\\.png")))
        val target = assertNotNull(cache.store("../unsafe\\template:name", bbox, image()))
        assertEquals(fileName, target.fileName.toString())

        Files.write(target, byteArrayOf(1, 2, 3))
        assertNull(cache.cachedPath("../unsafe\\template:name", bbox))
        assertEquals(target, cache.store("../unsafe\\template:name", bbox, image()))
        assertEquals(8, ImageIO.read(target.toFile()).width)
        Files.list(target.parent).use { entries ->
            assertFalse(entries.anyMatch { it.fileName.toString().endsWith(".tmp") })
        }
    }
}
