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
    fun `generate box validation treats every occupied path as a duplicate`() {
        val occupied = mapOf("screen.foo" to "12.png", "combat.hp" to "3.png")
        assertNull(BoxResource.generateBoxPathProblem("screen.bar", occupied), "没人占用的 path 放行")
        assertEquals("duplicate", BoxResource.generateBoxPathProblem("screen.foo", occupied), "当前图片已有的 path 也算占用")
        assertEquals("duplicate", BoxResource.generateBoxPathProblem("combat.hp", occupied))
        assertEquals("empty", BoxResource.generateBoxPathProblem("  ", occupied))
        assertEquals("shallow", BoxResource.generateBoxPathProblem("mainonly", occupied))
        assertEquals("segment", BoxResource.generateBoxPathProblem("screen.2bad", occupied))
        assertEquals("reserved", BoxResource.generateBoxPathProblem("panels.esc", occupied))
    }

    @Test
    fun `bbox bounds use long math so int overflow cannot pass the check`() {
        val size = AnnotationSwap.Size(1920, 1080)
        // x + w 用 Int 加会回绕成负数骗过边界检查；必须按 Long 算
        assertEquals("rect", BoxResource.bboxError(intArrayOf(2147483647, 0, 1, 1), size))
        assertNull(BoxResource.bboxError(intArrayOf(1919, 0, 1, 1), size))
    }

    @Test
    fun `only a registered non-zero image size counts as usable`() {
        assertNull(BoxResource.usableImageSize(null), "没登记 = 不可用")
        assertNull(BoxResource.usableImageSize(BoxResource.AuthoringImage("a.png", 0, 0)), "0 尺寸占位 = 不可用")
        assertNull(BoxResource.usableImageSize(BoxResource.AuthoringImage("a.png", 0, 540)), "只有一边有效 = 不可用")
        assertEquals(
            AnnotationSwap.Size(960, 540),
            BoxResource.usableImageSize(BoxResource.AuthoringImage("a.png", 960, 540)),
        )
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
