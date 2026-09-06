package com.alicejump.okscripttoolkit.toolbox

import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolboxStoreTest {

    private val json = ObjectMapper()

    @Test
    fun `parse store with game connection and overlay`() {
        val node = json.readTree(
            """
            {
              "projects": {
                "C:/games/ok-ww": {
                  "overlay": true,
                  "game": {
                    "hwnd": 13371337,
                    "pid": 4242,
                    "title": "鸣潮  ",
                    "exe": "C:/game/client.exe",
                    "connectedAt": 1725600000000
                  }
                }
              }
            }
            """.trimIndent(),
        )

        val store = ToolboxService.parseStore(node)
        val state = store.getValue("C:/games/ok-ww")
        assertTrue(state.overlay)
        val game = state.game!!
        assertEquals(13371337L, game.hwnd)
        assertEquals(4242L, game.pid)
        assertEquals("鸣潮  ", game.title)
        assertEquals("C:/game/client.exe", game.exe)
        assertEquals(1725600000000L, game.connectedAt)
    }

    @Test
    fun `parse store tolerates missing fields and null game`() {
        val node = json.readTree(
            """
            {
              "projects": {
                "C:/p1": { "overlay": true, "game": null },
                "C:/p2": {}
              }
            }
            """.trimIndent(),
        )

        val store = ToolboxService.parseStore(node)
        assertTrue(store.getValue("C:/p1").overlay)
        assertNull(store.getValue("C:/p1").game)
        assertEquals(ToolboxService.ToolboxState(), store.getValue("C:/p2"))
    }

    @Test
    fun `serialized state round-trips through parseStore`() {
        val original = mapOf(
            "C:/games/ok-ww" to ToolboxService.ToolboxState(
                overlay = true,
                game = ToolboxService.GameConnection(
                    hwnd = 987654321L,
                    pid = 777,
                    title = "游戏窗口",
                    exe = "D:/game.exe",
                    connectedAt = 1725600000123L,
                ),
            ),
            "C:/disconnected" to ToolboxService.ToolboxState(overlay = false, game = null),
        )

        val serialized = json.writeValueAsString(mapOf("projects" to original))
        val parsed = ToolboxService.parseStore(json.readTree(serialized))

        assertEquals(original, parsed)
    }
}
