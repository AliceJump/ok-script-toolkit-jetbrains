package com.alicejump.okscripttoolkit.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnnotationMergeTest {
    private fun shape(id: Int, name: String, x: Int, y: Int, w: Int = 10, h: Int = 10) =
        MergeShape(id, name, x, y, w, h)

    @Test
    fun `non overlapping changes merge automatically`() {
        val base = listOf(shape(1, "screen.a", 10, 10), shape(2, "screen.b", 20, 20))
        val local = listOf(shape(1, "screen.a", 11, 10), shape(2, "screen.b", 20, 20))
        val external = listOf(shape(1, "screen.a", 10, 10), shape(2, "screen.b", 20, 21))
        val result = mergeAnnotations(AnnotationMergeMode.RECT, base, local, external)
        assertTrue(result.conflicts.isEmpty())
        assertEquals(11, result.merged.single { it.name == "screen.a" }.x)
        assertEquals(21, result.merged.single { it.name == "screen.b" }.y)
    }

    @Test
    fun `same field edit keeps both conflict candidates`() {
        val base = listOf(shape(7, "title", 10, 10))
        val local = listOf(shape(7, "title", 30, 10))
        val external = listOf(shape(7, "title", 40, 10))
        val result = mergeAnnotations(AnnotationMergeMode.TEMPLATE, base, local, external)
        assertEquals(1, result.conflicts.size)
        assertEquals(setOf("x"), result.conflicts.single().fields)
        assertEquals(30, result.conflicts.single().local?.x)
        assertEquals(40, result.conflicts.single().external?.x)
        assertEquals(30, result.merged.single().x)
    }

    @Test
    fun `delete versus modify is a real conflict`() {
        val base = listOf(shape(1, "screen.point", 10, 10, 0, 0))
        val external = listOf(shape(1, "screen.point", 11, 10, 0, 0))
        val result = mergeAnnotations(AnnotationMergeMode.POINT, base, emptyList(), external)
        assertEquals(AnnotationConflictKind.DELETE_MODIFY, result.conflicts.single().kind)
        assertNull(result.conflicts.single().local)
        assertEquals(11, result.conflicts.single().external?.x)
    }

    @Test
    fun `template identity merges rename with external geometry`() {
        val base = listOf(shape(4, "old_name", 10, 10))
        val local = listOf(shape(4, "new_name", 10, 10))
        val external = listOf(shape(4, "old_name", 10, 15))
        val result = mergeAnnotations(AnnotationMergeMode.TEMPLATE, base, local, external)
        assertTrue(result.conflicts.isEmpty())
        assertEquals("new_name", result.merged.single().name)
        assertEquals(15, result.merged.single().y)
    }

    @Test
    fun `same path added differently conflicts`() {
        val local = listOf(shape(1, "screen.same", 10, 10))
        val external = listOf(shape(99, "screen.same", 20, 10))
        val result = mergeAnnotations(AnnotationMergeMode.RECT, emptyList(), local, external)
        assertEquals(AnnotationConflictKind.ADD_ADD, result.conflicts.single().kind)
    }
}
