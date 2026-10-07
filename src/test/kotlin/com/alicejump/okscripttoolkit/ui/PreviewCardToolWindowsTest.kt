package com.alicejump.okscripttoolkit.ui

import org.junit.Test
import kotlin.test.assertContentEquals

class PreviewCardToolWindowsTest {
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
}
