package com.alicejump.okscripttoolkit.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PositionResourceTest {
    @Test
    fun `point and rect normalize into one runtime format`() {
        val result = PositionResource.publish(
            listOf(
                PositionResource.Item("screen.viewport", "a.png", PositionResource.Kind.RECT, 100, 200, 300, 400),
                PositionResource.Item("panels.esc.mail", "a.png", PositionResource.Kind.POINT, 800, 900),
            ),
            listOf(PositionResource.Image("a.png", 1000, 1000)),
        )
        assertTrue(result.errors.isEmpty())
        assertEquals(listOf(0.8, 0.9), result.positions.first { it.path == "panels.esc.mail" }.coordinates)
        assertEquals(listOf(0.1, 0.2, 0.4, 0.6), result.positions.first { it.path == "screen.viewport" }.coordinates)
    }

    @Test
    fun `box and point exact path collision is rejected at publish time`() {
        val result = PositionResource.publish(
            listOf(
                PositionResource.Item("screen.same", "a.png", PositionResource.Kind.RECT, 0, 0, 20, 20),
                PositionResource.Item("screen.same", "a.png", PositionResource.Kind.POINT, 10, 10),
            ),
            listOf(PositionResource.Image("a.png", 100, 100)),
        )
        assertTrue("duplicate:screen.same" in result.errors)
    }

    @Test
    fun `partial publish checks readable unselected paths in the shared namespace`() {
        assertEquals(
            listOf("duplicate:screen.same"),
            positionNamespaceConflicts(listOf("screen.same"), listOf("screen.same")),
        )
        assertEquals(
            listOf("prefix:screen.group"),
            positionNamespaceConflicts(listOf("screen.group"), listOf("screen.group.child")),
        )
        assertTrue(positionNamespaceConflicts(listOf("screen.left"), listOf("screen.right")).isEmpty())
    }

    @Test
    fun `generated grouping classes preserve exact path segment boundaries`() {
        val result = PositionResource.PublishResult(
            positions = listOf(
                PositionResource.RuntimePosition("a_b.c.first", listOf(0.1, 0.1)),
                PositionResource.RuntimePosition("a.b_c.second", listOf(0.2, 0.2)),
                PositionResource.RuntimePosition("foo.child.lower", listOf(0.3, 0.3)),
                PositionResource.RuntimePosition("Foo.child.upper", listOf(0.4, 0.4)),
            ),
            errors = emptyList(),
        )
        val source = PositionResource.serializePositionMapPython(result)
        assertTrue("class Position_3_a_b" in source)
        assertTrue("class Position_1_a" in source)
        assertTrue("class Position_3_foo" in source)
        assertTrue("class Position_3_Foo" in source)
        assertFalse(source.contains("class ABCPosition"))
    }

    @Test
    fun `screen ratio serializer emits literal Python quotes`() {
        val source = PositionResource.serializeScreenRatioPython()
        assertFalse(source.contains(charArrayOf('\\', '"').concatToString()))
        assertTrue(source.contains("raise ValueError(\"ScreenRatio requires either 2 point coordinates or 4 rect coordinates\")"))
        assertTrue(source.contains("'''A normalized screen point"))
    }

    @Test
    fun `Python keyword generated and dunder member paths are rejected`() {
        val result = PositionResource.publish(
            listOf(
                PositionResource.Item("screen.class", "a.png", PositionResource.Kind.POINT, 1, 1),
                PositionResource.Item("screen._parent", "a.png", PositionResource.Kind.POINT, 2, 2),
                PositionResource.Item("screen.__slots__", "a.png", PositionResource.Kind.POINT, 3, 3),
                PositionResource.Item("screen.__class__", "a.png", PositionResource.Kind.POINT, 4, 4),
            ),
            listOf(PositionResource.Image("a.png", 10, 10)),
        )
        assertTrue("segment:screen.class" in result.errors)
        assertTrue("segment:screen._parent" in result.errors)
        assertTrue("segment:screen.__slots__" in result.errors)
        assertTrue("segment:screen.__class__" in result.errors)
        assertFalse(PositionResource.serializePositionMapPython(result).contains("__slots__"))
    }

    @Test
    fun `json serializer keeps two coordinates for points and four for rects`() {
        val result = PositionResource.PublishResult(
            positions = listOf(
                PositionResource.RuntimePosition("panels.esc.mail", listOf(0.8, 0.9)),
                PositionResource.RuntimePosition("screen.viewport", listOf(0.1, 0.2, 0.4, 0.6)),
            ),
            errors = emptyList(),
        )
        val json = PositionResource.serializeJson(result)
        assertTrue(json.contains("\"version\" : 2"))
        assertTrue(json.contains("0.8"))
        assertTrue(json.contains("0.6"))
    }
}
