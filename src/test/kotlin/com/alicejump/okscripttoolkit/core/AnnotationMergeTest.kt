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

    @Test
    fun `resolver requires every conflict choice`() {
        val base = listOf(shape(1, "screen.a", 10, 10), shape(2, "screen.b", 20, 20))
        val local = listOf(shape(1, "screen.a", 11, 10), shape(2, "screen.b", 21, 20))
        val external = listOf(shape(1, "screen.a", 12, 10), shape(2, "screen.b", 22, 20))
        val result = mergeAnnotations(AnnotationMergeMode.RECT, base, local, external)
        assertNull(resolveAnnotationConflicts(
            AnnotationMergeMode.RECT,
            result,
            mapOf(result.conflicts.first().key to AnnotationConflictChoice.LOCAL),
        ))
    }

    @Test
    fun `external conflict choice changes only conflicting fields`() {
        val base = listOf(shape(7, "title", 10, 10, 20, 20))
        val local = listOf(shape(7, "title", 30, 10, 25, 20))
        val external = listOf(shape(7, "title", 40, 10, 20, 30))
        val result = mergeAnnotations(AnnotationMergeMode.TEMPLATE, base, local, external)
        val conflict = result.conflicts.single()
        assertEquals(setOf("x"), conflict.fields)
        val resolved = resolveAnnotationConflicts(
            AnnotationMergeMode.TEMPLATE,
            result,
            mapOf(conflict.key to AnnotationConflictChoice.EXTERNAL),
        )!!.single()
        assertEquals(40, resolved.x)
        assertEquals(25, resolved.w, "local-only width edit is preserved")
        assertEquals(30, resolved.h, "external-only height edit was already auto-merged")
    }

    @Test
    fun `delete modify conflict can keep deletion or restore external`() {
        val base = listOf(shape(1, "screen.point", 10, 10, 0, 0))
        val external = listOf(shape(1, "screen.point", 15, 10, 0, 0))
        val result = mergeAnnotations(AnnotationMergeMode.POINT, base, emptyList(), external)
        val conflict = result.conflicts.single()
        val keepLocal = resolveAnnotationConflicts(
            AnnotationMergeMode.POINT,
            result,
            mapOf(conflict.key to AnnotationConflictChoice.LOCAL),
        )!!
        assertTrue(keepLocal.isEmpty())
        val useExternal = resolveAnnotationConflicts(
            AnnotationMergeMode.POINT,
            result,
            mapOf(conflict.key to AnnotationConflictChoice.EXTERNAL),
        )!!
        assertEquals(15, useExternal.single().x)
    }

    @Test
    fun `external deletion removes locally modified shape`() {
        val base = listOf(shape(1, "screen.rect", 10, 10))
        val local = listOf(shape(1, "screen.rect", 15, 10))
        val result = mergeAnnotations(AnnotationMergeMode.RECT, base, local, emptyList())
        val conflict = result.conflicts.single()
        val resolved = resolveAnnotationConflicts(
            AnnotationMergeMode.RECT,
            result,
            mapOf(conflict.key to AnnotationConflictChoice.EXTERNAL),
        )!!
        assertTrue(resolved.isEmpty())
    }
}
