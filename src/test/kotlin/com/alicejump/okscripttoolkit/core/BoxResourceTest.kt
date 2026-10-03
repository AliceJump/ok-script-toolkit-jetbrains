package com.alicejump.okscripttoolkit.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Rect authoring is COCO-only; runtime normalization belongs to PositionResource. */
class BoxResourceTest {
    @Test
    fun `rect paths use the shared position grammar`() {
        assertNull(BoxResource.pathError("screen.main_viewport"))
        assertNull(BoxResource.pathError("panels.esc.mail"), "panels is no longer a reserved legacy runtime root")
        assertEquals("shallow", BoxResource.pathError("main_viewport"))
        assertEquals("segment", BoxResource.pathError("screen.bad-name"))
        assertEquals("segment", BoxResource.pathError("screen.2bad"))
        assertEquals("12.png", BoxResource.imageFileName("ok_templates/12.png"))
        assertEquals("^[A-Za-z_][A-Za-z0-9_]*$", BoxResource.SEGMENT_SOURCE)
    }

    @Test
    fun `pixel bbox validation matches template annotation rules`() {
        val size = AnnotationSwap.Size(1920, 1080)
        assertNull(BoxResource.bboxError(intArrayOf(184, 112, 1544, 853), size))
        assertEquals("rect", BoxResource.bboxError(intArrayOf(-1, 0, 100, 100), size))
        assertEquals("rect", BoxResource.bboxError(intArrayOf(0, 0, 0, 100), size))
        assertEquals("rect", BoxResource.bboxError(intArrayOf(1900, 0, 100, 100), size))
        assertNull(BoxResource.bboxError(intArrayOf(0, 0, 1920, 100), size), "edge-aligned rects are valid")
        assertNull(BoxResource.bboxError(intArrayOf(0, 0, 30, 20), null), "unknown image size only checks positive geometry")
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
    fun `new rect validation only rejects occupied rect paths`() {
        val occupied = mapOf("screen.foo" to "12.png", "combat.hp" to "3.png")
        assertNull(BoxResource.generateBoxPathProblem("screen.bar", occupied))
        assertNull(BoxResource.generateBoxPathProblem("panels.esc", occupied), "cross-kind collisions are checked by Position publish")
        assertEquals("duplicate", BoxResource.generateBoxPathProblem("screen.foo", occupied))
        assertEquals("duplicate", BoxResource.generateBoxPathProblem("combat.hp", occupied))
        assertEquals("empty", BoxResource.generateBoxPathProblem("  ", occupied))
        assertEquals("shallow", BoxResource.generateBoxPathProblem("mainonly", occupied))
        assertEquals("segment", BoxResource.generateBoxPathProblem("screen.2bad", occupied))
    }

    @Test
    fun `bbox bounds use long math so int overflow cannot pass`() {
        val size = AnnotationSwap.Size(1920, 1080)
        assertEquals("rect", BoxResource.bboxError(intArrayOf(Int.MAX_VALUE, 0, 1, 1), size))
        assertNull(BoxResource.bboxError(intArrayOf(1919, 0, 1, 1), size))
    }

    @Test
    fun `only a registered non-zero image size counts as usable`() {
        assertNull(BoxResource.usableImageSize(null))
        assertNull(BoxResource.usableImageSize(BoxResource.AuthoringImage("a.png", 0, 0)))
        assertNull(BoxResource.usableImageSize(BoxResource.AuthoringImage("a.png", 0, 540)))
        assertEquals(
            AnnotationSwap.Size(960, 540),
            BoxResource.usableImageSize(BoxResource.AuthoringImage("a.png", 960, 540)),
        )
    }

    @Test
    fun `visibility is a set of ids and does not duplicate data`() {
        val hidden = BoxResource.applyVisibility(listOf("a", "b"), emptySet(), "hideAll")
        assertEquals(setOf("a", "b"), hidden)
        assertEquals(emptySet(), BoxResource.applyVisibility(listOf("a", "b"), hidden, "showAll"))
        val only = BoxResource.applyVisibility(listOf("a", "b"), emptySet(), "only", "b")
        assertEquals(setOf("a"), only)
        assertTrue(BoxResource.isVisible("b", only))
        assertFalse(BoxResource.isVisible("a", only))
    }
}
