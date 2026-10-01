package com.alicejump.okscripttoolkit.core

import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class AnnotatedSourcePreviewTest {
    private fun source(): BufferedImage = BufferedImage(800, 800, BufferedImage.TYPE_INT_ARGB).apply {
        val g = createGraphics()
        g.color = Color(40, 200, 90)
        g.fillRect(0, 0, width, height)
        g.dispose()
    }

    @Test
    fun `preview includes surrounding source and highlights box position`() {
        val preview = assertNotNull(AnnotatedSourcePreview.render(source(), intArrayOf(250, 250, 20, 40)))
        assertEquals(382, preview.width)
        assertEquals(400, preview.height)
        assertEquals(Color(40, 200, 90).rgb, preview.getRGB(10, 10))
        assertEquals(Color(255, 40, 40).rgb, preview.getRGB(182, 182))
    }

    @Test
    fun `context clips to image edge and invalid boxes have no preview`() {
        val original = source()
        val edge = assertNotNull(AnnotatedSourcePreview.render(original, intArrayOf(0, 0, 20, 40)))
        assertEquals(220, edge.width)
        assertEquals(240, edge.height)
        for (bbox in listOf(intArrayOf(0, 0, 0, 2), intArrayOf(800, 0, 2, 2), intArrayOf(1))) {
            assertNull(AnnotatedSourcePreview.render(original, bbox))
        }
    }

    @Test
    fun `box hover uses packaged external HTML resources`() {
        val image = HtmlResources.render("box-preview", mapOf("IMAGE" to "base64-image"))
        val document = HtmlResources.render("box-documentation", mapOf(
            "PATH" to "screen.main", "RECT" to "0, 0, 1, 1", "PREVIEW" to image, "SOURCE" to "",
        ))
        assertTrue(document.contains("self.pos.screen.main.to_box()"))
        assertTrue(document.contains("data:image/png;base64,base64-image"))
        assertFalse(document.contains("__PREVIEW__"))
    }
}
