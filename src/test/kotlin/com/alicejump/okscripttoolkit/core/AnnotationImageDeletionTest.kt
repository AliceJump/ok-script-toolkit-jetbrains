package com.alicejump.okscripttoolkit.core

import com.alicejump.okscripttoolkit.TestTmp
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnnotationImageDeletionTest {
    @Test fun `template failure restores both secondary annotation sources and retains image`() {
        val directory = TestTmp.create("ok-delete-all-sources")
        val image = directory.resolve("a.png").apply { writeText("image") }
        val rect = directory.resolve("boxes.json").apply { writeText("original rect") }.toPath()
        val point = directory.resolve("points.json").apply { writeText("original point") }.toPath()
        assertFalse(deleteAnnotationImage(image, listOf(rect, point), {
            Files.writeString(rect, "removed rect")
            Files.writeString(point, "removed point")
            true
        }, { false }))
        assertEquals("original rect", Files.readString(rect))
        assertEquals("original point", Files.readString(point))
        assertEquals("image", image.readText())
    }
    @Test fun `later source failure restores earlier source without deleting image`() {
        val directory = TestTmp.create("ok-delete-source-fail")
        val image = directory.resolve("a.png").apply { writeText("image") }
        val rect = directory.resolve("boxes.json").apply { writeText("original") }.toPath()
        val point = directory.resolve("points.json").toPath()
        var attemptedDelete = false
        assertFalse(deleteAnnotationImage(image, listOf(rect, point), {
            Files.writeString(rect, "removed")
            false
        }, { attemptedDelete = true; true }))
        assertFalse(attemptedDelete)
        assertEquals("original", Files.readString(rect))
        assertFalse(Files.exists(point))
    }
    @Test fun `successful delete retains completed annotation cleanup`() {
        val directory = TestTmp.create("ok-delete-source-success")
        val image = directory.resolve("a.png").apply { writeText("image") }
        val source = directory.resolve("points.json").apply { writeText("original") }.toPath()
        assertTrue(deleteAnnotationImage(image, listOf(source), { Files.writeString(source, "removed"); true }, { image.delete() }))
        assertFalse(image.exists())
        assertEquals("removed", Files.readString(source))
    }
}
