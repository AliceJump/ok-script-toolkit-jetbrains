package com.alicejump.okscripttoolkit.core

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 与 `scripts/test_box_resource.js` 钉同一组不变量（Pixel authoring + normalized runtime）。 */
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
        assertEquals(BoxResource.SEGMENT_SOURCE, "^[A-Za-z_][A-Za-z0-9_]*$")
    }

    @Test
    fun `pixel bbox validation matches the template annotation rules`() {
        val size = AnnotationSwap.Size(1920, 1080)
        assertNull(BoxResource.bboxError(intArrayOf(184, 112, 1544, 853), size))
        assertEquals("rect", BoxResource.bboxError(intArrayOf(-1, 0, 100, 100), size))
        assertEquals("rect", BoxResource.bboxError(intArrayOf(0, 0, 0, 100), size))
        assertEquals("rect", BoxResource.bboxError(intArrayOf(1900, 0, 100, 100), size))
        assertNull(BoxResource.bboxError(intArrayOf(0, 0, 1920, 100), size), "贴边矩形可以保存")
        assertNull(BoxResource.bboxError(intArrayOf(0, 0, 30, 20), null), "尺寸未知时只查正性")
        assertTrue(BoxResource.isStorableRuntimeRect(doubleArrayOf(0.0, 0.0, 1.0, 1.0)))
        assertFalse(BoxResource.isStorableRuntimeRect(doubleArrayOf(-0.1, 0.0, 0.5, 0.5)))
    }

    @Test
    fun `pixel union stays in pixels`() {
        val union = BoxResource.unionPixelBoxes(
            listOf(
                BoxResource.PixelBox(100, 50, 40, 30),
                BoxResource.PixelBox(120, 60, 60, 20),
            ),
        )!!
        assertEquals(100, union.x)
        assertEquals(50, union.y)
        assertEquals(80, union.w)
        assertEquals(30, union.h)
        assertNull(BoxResource.unionPixelBoxes(emptyList()))
    }

    @Test
    fun `authoring json is version two and publish converts pixel to normalized`() {
        val file = BoxResource.AuthoringFile(
            images = listOf(BoxResource.AuthoringImage("12.png", 1920, 1080)),
            boxes = listOf(
                BoxResource.AuthoringBox("screen.main_viewport", "12.png", intArrayOf(184, 112, 1544, 853)),
            ),
        )
        val text = BoxResource.serializeAuthoring(file)
        val expected = """
            {
              "version": 2,
              "images": [
                { "file": "12.png", "width": 1920, "height": 1080 }
              ],
              "boxes": [
                {
                  "path": "screen.main_viewport",
                  "image": "12.png",
                  "bbox": [184, 112, 1544, 853]
                }
              ]
            }
        """.trimIndent() + "\n"
        assertEquals(expected, text)
        val parsed = BoxResource.parseAuthoring(text)
        assertEquals(emptyList(), parsed.errors)
        assertEquals("screen.main_viewport", parsed.file.boxes[0].path)
        assertEquals("184,112,1544,853", parsed.file.boxes[0].bbox.joinToString(","))

        val published = BoxResource.publish(parsed.file)
        assertTrue(published.errors.isEmpty())
        assertEquals(BoxResource.RUNTIME_VERSION, published.file.version)
        val runtimeText = BoxResource.serializeRuntime(published.file)
        assertTrue(!runtimeText.contains("\"image\""))
        assertTrue(runtimeText.contains("0.095833"), "Pixel → normalized 固定 6 位小数")
        val statuses = BoxResource.publishStatus(parsed.file, BoxResource.parseRuntime(runtimeText).file)
        assertTrue(statuses.all { it.status == BoxResource.PublishStatus.SAME })
        val orphan = BoxResource.publish(BoxResource.AuthoringFile(boxes = parsed.file.boxes))
        assertTrue(orphan.errors.any { it.startsWith("size:") }, "尺寸缺失的框不发布并记错")
    }

    @Test
    fun `legacy normalized authoring is a migration entry only`() {
        val legacyText = """{"version":1,"boxes":[
              {"path":"screen.main_viewport","image":"12.png","rect":[0.1,0.2,0.9,0.8]}
            ]}"""
        assertTrue(BoxResource.parseAuthoring(legacyText).errors.contains("legacy"))
        val legacy = BoxResource.parseLegacyAuthoring(legacyText)
        assertTrue(legacy.errors.isEmpty())
        assertEquals(1, legacy.file.size)
        val migrated = BoxResource.migrateAuthoringV1(legacy.file) { AnnotationSwap.Size(1920, 1080) }
        assertTrue(migrated.errors.isEmpty())
        assertEquals("192,216,1536,648", migrated.file.boxes[0].bbox.joinToString(","))
        assertEquals(1920, migrated.file.images.single().width)
        val orphan = BoxResource.migrateAuthoringV1(legacy.file) { null }
        assertEquals(listOf("migrate:12.png"), orphan.errors)
        assertEquals(0, orphan.file.boxes.size)
    }

    @Test
    fun `duplicate paths keep the first box and bad json does not throw`() {
        val duplicate = BoxResource.parseAuthoring(
            """{"version":2,"images":[{"file":"12.png","width":100,"height":100}],"boxes":[
              {"path":"screen.a","image":"12.png","bbox":[0,0,10,10]},
              {"path":"screen.a","image":"12.png","bbox":[20,20,10,10]}
            ]}""",
        )
        assertEquals(1, duplicate.file.boxes.size)
        assertEquals("0,0,10,10", duplicate.file.boxes[0].bbox.joinToString(","))
        assertTrue(duplicate.errors.any { it.endsWith(":duplicate") })
        val bad = BoxResource.parseRuntime("{")
        assertEquals(listOf("json"), bad.errors)
        assertEquals(0, bad.file.boxes.size)
    }

    @Test
    fun `a later duplicate path rejects the whole image replacement`() {
        val existing = BoxResource.AuthoringFile(
            images = listOf(BoxResource.AuthoringImage("1.png", 100, 100)),
            boxes = listOf(
                BoxResource.AuthoringBox("screen.a", "1.png", intArrayOf(0, 0, 50, 50)),
                BoxResource.AuthoringBox("screen.b", "1.png", intArrayOf(50, 50, 50, 50)),
            ),
        )
        fun edit(file: String, path: String) = BoxResource.ImageReplacement(
            file,
            100,
            100,
            listOf(BoxResource.ReplacementBox(path, 10, 10, 20, 20)),
        )
        val conflict = BoxResource.replaceAuthoringImages(
            existing,
            listOf(edit("1.png", "screen.c"), edit("2.png", "screen.c")),
        )
        assertEquals("duplicate", conflict.error)
        val outside = BoxResource.replaceAuthoringImages(
            existing,
            listOf(
                BoxResource.ImageReplacement(
                    "1.png",
                    100,
                    100,
                    listOf(BoxResource.ReplacementBox("screen.a", -10, 0, 20, 20)),
                ),
            ),
        )
        assertEquals("rect", outside.error)
        val applied = BoxResource.replaceAuthoringImages(
            existing,
            listOf(edit("1.png", "screen.a"), BoxResource.ImageReplacement("2.png", 200, 100, listOf(BoxResource.ReplacementBox("screen.d", 0, 0, 30, 30)))),
        )
        assertNull(applied.error)
        assertEquals(setOf("screen.a", "screen.d"), applied.file.boxes.map { it.path }.toSet())
        assertTrue(applied.file.images.any { it.file == "2.png" && it.width == 200 }, "首次编辑登记新图片尺寸")
    }

    @Test
    fun `swapping boxes maps proportionally when sizes differ`() {
        val file = BoxResource.AuthoringFile(
            images = listOf(
                BoxResource.AuthoringImage("big.png", 1920, 1080),
                BoxResource.AuthoringImage("small.png", 960, 540),
            ),
            boxes = listOf(
                BoxResource.AuthoringBox("screen.a", "big.png", intArrayOf(960, 540, 960, 540)),
                BoxResource.AuthoringBox("screen.b", "small.png", intArrayOf(480, 270, 480, 270)),
            ),
        )
        val sameSize = BoxResource.swapImageBoxes(
            file.copy(
                images = listOf(
                    BoxResource.AuthoringImage("big.png", 100, 100),
                    BoxResource.AuthoringImage("small.png", 100, 100),
                ),
            ),
            "big.png",
            "small.png",
        )
        assertNull(sameSize.error)
        assertEquals("small.png", sameSize.file.boxes.first { it.path == "screen.a" }.image)
        assertEquals("960,540,960,540", sameSize.file.boxes.first { it.path == "screen.a" }.bbox.joinToString(","), "同尺寸交换坐标逐字段不变")
        val mapped = BoxResource.swapImageBoxes(file, "big.png", "small.png")
        assertNull(mapped.error)
        val movedA = mapped.file.boxes.first { it.path == "screen.a" }
        assertEquals("small.png", movedA.image)
        assertEquals("480,270,480,270", movedA.bbox.joinToString(","))
        val movedB = mapped.file.boxes.first { it.path == "screen.b" }
        assertEquals("big.png", movedB.image)
        assertEquals("960,540,960,540", movedB.bbox.joinToString(","))
        val missing = BoxResource.swapImageBoxes(file.copy(images = emptyList()), "big.png", "small.png")
        assertEquals("size", missing.error)
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
