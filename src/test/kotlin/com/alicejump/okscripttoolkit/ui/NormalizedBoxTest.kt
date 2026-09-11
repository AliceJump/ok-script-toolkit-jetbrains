package com.alicejump.okscripttoolkit.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NormalizedBoxTest {

    @Test
    fun `formats x,y,tox,toy with four decimals`() {
        // 1920x1080 图上 (240,108)-(1200,648) → 0.125/0.1 - 0.625/0.6
        val text = NormalizedBox.format(240.0, 108.0, 1200.0, 648.0, 1920, 1080)
        assertEquals("0.1250,0.1000,0.6250,0.6000", text)
    }

    @Test
    fun `is independent of corner order`() {
        val forward = NormalizedBox.format(240.0, 108.0, 1200.0, 648.0, 1920, 1080)
        val backward = NormalizedBox.format(1200.0, 648.0, 240.0, 108.0, 1920, 1080)
        assertEquals(forward, backward, "反向拖拽必须得到同一结果")
    }

    @Test
    fun `clamps out of range values to 0 and 1`() {
        val text = NormalizedBox.format(-500.0, -500.0, 5000.0, 5000.0, 1920, 1080)
        assertEquals("0.0000,0.0000,1.0000,1.0000", text)
    }

    @Test
    fun `degenerate boxes produce no text`() {
        assertEquals("", NormalizedBox.format(100.0, 100.0, 100.0, 100.0, 1920, 1080))
        assertTrue(NormalizedBox.format(100.0, 100.0, 101.0, 400.0, 1920, 1080).isEmpty())
    }

    @Test
    fun `invalid image size produces no text`() {
        assertEquals("", NormalizedBox.format(0.0, 0.0, 10.0, 10.0, 0, 0))
    }

    @Test
    fun `normalized result does not depend on the preview scale`() {
        // 降采样预览与原图应给出相同的归一化坐标（这是用小预览图精确取坐标的前提）
        val full = NormalizedBox.format(240.0, 108.0, 1200.0, 648.0, 1920, 1080)
        val half = NormalizedBox.format(120.0, 54.0, 600.0, 324.0, 960, 540)
        assertEquals(full, half)
    }
}
