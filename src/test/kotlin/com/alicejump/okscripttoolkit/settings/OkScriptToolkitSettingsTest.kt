package com.alicejump.okscripttoolkit.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class OkScriptToolkitSettingsTest {

    @Test
    fun `normalizeCaptureMethod keeps every valid method`() {
        for (method in OkScriptToolkitSettings.CAPTURE_METHODS) {
            assertEquals(method, OkScriptToolkitSettings.normalizeCaptureMethod(method))
        }
    }

    @Test
    fun `normalizeCaptureMethod trims and falls back to auto`() {
        assertEquals("bitblt", OkScriptToolkitSettings.normalizeCaptureMethod("  bitblt  "))
        assertEquals("auto", OkScriptToolkitSettings.normalizeCaptureMethod(""))
        assertEquals("auto", OkScriptToolkitSettings.normalizeCaptureMethod(null))
        assertEquals(
            "auto",
            OkScriptToolkitSettings.normalizeCaptureMethod("printwindow"),
            "旧配置或手改 xml 留下的脏值不能透传给 python 脚本的 --method",
        )
    }

    @Test
    fun `foreground is an allowed method`() {
        assertEquals(
            "foreground",
            OkScriptToolkitSettings.normalizeCaptureMethod(OkScriptToolkitSettings.CAPTURE_METHOD_FOREGROUND),
        )
        assertEquals(
            "foreground",
            OkScriptToolkitSettings.CAPTURE_METHODS.last(),
            "面板的「硬前台」依赖该值被 capture_game_window.py 接受",
        )
    }
}
