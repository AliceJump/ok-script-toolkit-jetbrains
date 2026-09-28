package com.alicejump.okscripttoolkit.core

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 与 `scripts/test_box_resource.js` 钉同一组不变量。 */
class BoxResourceTest {
    private val root = "X:/proj"
    private val probe = Paths.get(root, "src", "scene", "boxes.json")

    @Test
    fun `runtime path prefers the convention over config py over the probe`() {
        val bare = BoxRuntimePath.plan(root, null, null)
        assertEquals(BoxRuntimePath.Layer.PROBE, bare.layer)
        assertNull(bare.preferred)
        assertEquals(listOf(probe), bare.probeCandidates)

        val declared = BoxRuntimePath.plan(root, "custom/boxes.json", "assets/boxes.json")
        assertEquals(BoxRuntimePath.Layer.CONVENTION, declared.layer)
        assertEquals(Paths.get(root, "custom", "boxes.json"), declared.preferred)

        val fromPy = BoxRuntimePath.plan(root, "  ", "assets/boxes.json")
        assertEquals(BoxRuntimePath.Layer.CONFIG_PY, fromPy.layer)
        assertEquals(BoxRuntimePath.writeTarget(bare), probe)
        assertEquals(BoxRuntimePath.writeTarget(declared), declared.preferred)
        assertNull(BoxRuntimePath.effectiveFile(bare) { false })
        assertEquals(declared.preferred, BoxRuntimePath.effectiveFile(declared) { true })

        val authoring = Paths.get(root, "ok_templates", "boxes.json")
        val aliased = Paths.get(root, "ok_templates", ".", "boxes.json")
        assertTrue(BoxRuntimePath.sameLocation(authoring, aliased))
        assertFalse(BoxRuntimePath.sameLocation(authoring, probe))
        val upper = Paths.get(root, "ok_templates", "Boxes.json")
        if (System.getProperty("os.name", "").contains("win", ignoreCase = true)) {
            assertTrue(BoxRuntimePath.sameLocation(authoring, upper))
        } else {
            assertFalse(BoxRuntimePath.sameLocation(authoring, upper))
        }
    }

    @Test
    fun `path rules keep panels for click points`() {
        assertNull(BoxResource.pathError("screen.main_viewport"))
        assertEquals("shallow", BoxResource.pathError("main_viewport"))
        assertEquals("reserved", BoxResource.pathError("panels.esc.mail"))
        assertEquals("segment", BoxResource.pathError("screen.bad-name"))
        assertEquals("12.png", BoxResource.imageFileName("ok_templates/12.png"))
    }

    @Test
    fun `an unchanged pixel box keeps the original normalized rect`() {
        val original = doubleArrayOf(0.0984, 0.1042, 0.8961, 0.8944)
        val pixel = BoxResource.rectToPixel(original, 2560, 1440)!!
        assertTrue(BoxResource.rectForSave(original, pixel, 2560, 1440).contentEquals(original))
        val moved = pixel.copy(x = pixel.x + 4)
        val rewritten = BoxResource.rectForSave(original, moved, 2560, 1440)!!
        assertTrue(rewritten[0] != original[0])
    }

    @Test
    fun `union uses the min and max edges of one image`() {
        val union = BoxResource.unionOnImage(
            listOf(
                BoxResource.PixelBox(10, 20, 30, 40),
                BoxResource.PixelBox(50, 10, 20, 15),
            ),
            100,
            100,
        )!!
        assertEquals(0.1, union[0])
        assertEquals(0.1, union[1])
        assertEquals(0.7, union[2])
        assertEquals(0.6, union[3])
        assertNull(BoxResource.unionOnImage(emptyList(), 100, 100))
    }

    @Test
    fun `authoring json is sorted and publish drops the image`() {
        val file = BoxResource.AuthoringFile(
            boxes = listOf(
                BoxResource.AuthoringBox("screen.main_viewport", "12.png", doubleArrayOf(0.0984, 0.1042, 0.8961, 0.8944)),
                BoxResource.AuthoringBox("screen.dialog_icon", "3.png", doubleArrayOf(0.845, 0.047, 0.975, 0.074)),
            ),
        )
        val text = BoxResource.serializeAuthoring(file)
        val expected = """
            {
              "version": 1,
              "boxes": [
                {
                  "path": "screen.dialog_icon",
                  "image": "3.png",
                  "rect": [0.845000, 0.047000, 0.975000, 0.074000]
                },
                {
                  "path": "screen.main_viewport",
                  "image": "12.png",
                  "rect": [0.098400, 0.104200, 0.896100, 0.894400]
                }
              ]
            }
        """.trimIndent() + "\n"
        assertEquals(expected, text)
        val parsed = BoxResource.parseAuthoring(text)
        assertEquals(emptyList(), parsed.errors)
        assertEquals("screen.dialog_icon", parsed.file.boxes[0].path)
        val runtime = BoxResource.serializeRuntime(BoxResource.publish(parsed.file))
        assertTrue(!runtime.contains("\"image\""))
        val statuses = BoxResource.publishStatus(parsed.file, BoxResource.parseRuntime(runtime).file)
        assertTrue(statuses.all { it.status == BoxResource.PublishStatus.SAME })
    }

    @Test
    fun `duplicate paths keep the first box and bad json does not throw`() {
        val duplicate = BoxResource.parseAuthoring(
            """{"version":1,"boxes":[
              {"path":"screen.a","image":"1.png","rect":[0,0,0.5,0.5]},
              {"path":"screen.a","image":"2.png","rect":[0,0,0.2,0.2]}
            ]}""",
        )
        assertEquals(1, duplicate.file.boxes.size)
        assertEquals("1.png", duplicate.file.boxes[0].image)
        assertTrue(duplicate.errors.any { it.endsWith(":duplicate") })
        val bad = BoxResource.parseRuntime("{")
        assertEquals(listOf("json"), bad.errors)
        assertEquals(0, bad.file.boxes.size)
    }

    @Test
    fun `a later duplicate path rejects the whole image replacement`() {
        val rect = doubleArrayOf(0.0, 0.0, 0.5, 0.5)
        val existing = listOf(
            BoxResource.AuthoringBox("screen.a", "1.png", rect),
            BoxResource.AuthoringBox("screen.b", "2.png", rect.copyOf()),
        )
        val pixel = BoxResource.rectToPixel(rect, 100, 100)!!
        fun edit(file: String, path: String) = BoxResource.ImageReplacement(
            file,
            100,
            100,
            listOf(BoxResource.ReplacementBox(path, pixel.x, pixel.y, pixel.w, pixel.h, rect)),
        )
        val conflict = BoxResource.replaceAuthoringImages(
            existing,
            listOf(edit("1.png", "screen.a"), edit("2.png", "screen.a")),
        )
        assertEquals("duplicate", conflict.error)
        val applied = BoxResource.replaceAuthoringImages(
            existing,
            listOf(edit("1.png", "screen.a"), edit("2.png", "screen.b")),
        )
        assertNull(applied.error)
        assertEquals(setOf("screen.a", "screen.b"), applied.boxes.map { it.path }.toSet())
    }

    @Test
    fun `visibility is a set of ids and does not invent a third copy of the data`() {
        val hidden = BoxResource.applyVisibility(listOf("a", "b"), emptySet(), "hideAll")
        assertEquals(setOf("a", "b"), hidden)
        assertEquals(emptySet(), BoxResource.applyVisibility(listOf("a", "b"), hidden, "showAll"))
        val only = BoxResource.applyVisibility(listOf("a", "b"), emptySet(), "only", "b")
        assertEquals(setOf("a"), only)
        assertTrue(BoxResource.isVisible("b", BoxResource.applyVisibility(listOf("a", "b"), only, "toggle", "a")))
    }
}
