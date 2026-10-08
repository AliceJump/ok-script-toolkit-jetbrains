package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.core.FeatureTemplate
import java.nio.file.Paths
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PreviewCardToolWindowsTest {
    @Test
    fun `template cards preserve runtime image geometry and configured alias`() {
        val image = Paths.get("assets", "images", "packed.png")
        val feature = FeatureTemplate("button", image, intArrayOf(12, 18, 20, 30), 20, 30)
        val cards = runtimeTemplatePreviews(listOf(feature), "Features")
        assertEquals(setOf("button"), cards.keys)
        val card = cards.getValue("button")
        assertEquals(image, card.imagePath)
        assertContentEquals(intArrayOf(12, 18, 20, 30), card.bbox)
        assertEquals("Features.button", card.tooltip)
        feature.bbox[0] = 99
        assertEquals(12, card.bbox[0], "卡片几何不得随下一次源快照刷新被修改")
        assertTrue(runtimeTemplatePreviews(emptyList(), "fL").isEmpty(),
            "没有运行时模板时不得用未发布的工作标注填充预览")
    }

    @Test
    fun `point preview keeps useful context around the point`() {
        assertContentEquals(intArrayOf(440, 220, 120, 60), pointPreviewBbox(500, 250, 1000, 500))
        assertContentEquals(intArrayOf(0, 0, 120, 60), pointPreviewBbox(0, 0, 1000, 500))
        assertContentEquals(intArrayOf(880, 440, 120, 60), pointPreviewBbox(1000, 500, 1000, 500))
    }

    @Test
    fun `point preview clamps to small images and rejects invalid sizes`() {
        assertContentEquals(intArrayOf(0, 0, 40, 30), pointPreviewBbox(20, 15, 40, 30))
        assertContentEquals(intArrayOf(0, 0, 0, 0), pointPreviewBbox(0, 0, 0, 30))
    }

    @Test
    fun `thumbnail generation rejects callbacks captured before invalidation`() {
        val generation = PreviewThumbGeneration()
        val before = generation.current()
        assertTrue(generation.isCurrent(before))

        generation.invalidate()

        assertFalse(generation.isCurrent(before))
        assertTrue(generation.isCurrent(generation.current()))
    }
}
