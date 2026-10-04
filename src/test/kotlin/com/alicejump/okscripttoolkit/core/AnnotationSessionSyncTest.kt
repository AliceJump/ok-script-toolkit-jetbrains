package com.alicejump.okscripttoolkit.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnnotationSessionSyncTest {
    private fun shape(id: Int, name: String, x: Int, y: Int, w: Int = 10, h: Int = 10) =
        MergeShape(id, name, x, y, w, h)

    @Test
    fun `clean session follows external snapshot directly`() {
        val base = listOf(shape(1, "screen.a", 10, 10))
        val external = listOf(shape(1, "screen.a", 20, 10))
        val next = reconcileAnnotationSession(
            AnnotationMergeMode.RECT,
            AnnotationSessionSyncState(base, base, "r1", dirty = false),
            external,
            "r2",
        )
        assertFalse(next.dirty)
        assertEquals(external, next.local)
        assertEquals(external, next.base)
        assertEquals("r2", next.revision)
    }

    @Test
    fun `dirty non overlapping edits merge and advance base`() {
        val base = listOf(shape(1, "screen.a", 10, 10, 20, 20))
        val local = listOf(shape(1, "screen.a", 15, 10, 20, 20))
        val external = listOf(shape(1, "screen.a", 10, 10, 20, 30))
        val next = reconcileAnnotationSession(
            AnnotationMergeMode.RECT,
            AnnotationSessionSyncState(base, local, "r1", dirty = true),
            external,
            "r2",
        )
        assertTrue(next.dirty)
        assertFalse(next.hasConflicts)
        assertEquals(15, next.local.single().x)
        assertEquals(30, next.local.single().h)
        assertEquals(external, next.base)
        assertEquals("r2", next.revision)
    }

    @Test
    fun `true conflict preserves original local branch and both candidates`() {
        val base = listOf(shape(1, "screen.a", 10, 10))
        val local = listOf(shape(1, "screen.a", 20, 10))
        val external = listOf(shape(1, "screen.a", 30, 10))
        val next = reconcileAnnotationSession(
            AnnotationMergeMode.RECT,
            AnnotationSessionSyncState(base, local, "r1", dirty = true),
            external,
            "r2",
        )
        assertTrue(next.hasConflicts)
        assertEquals(local, next.local, "the user's branch stays untouched while choices are pending")
        val conflict = next.pending!!.result.conflicts.single()
        assertEquals(20, conflict.local?.x)
        assertEquals(30, conflict.external?.x)
        assertEquals(20, next.displayShapes.single().x)
        assertEquals("r1", next.revision, "accepted base revision does not advance before resolution")
    }

    @Test
    fun `new external revision recomputes a pending conflict from original local branch`() {
        val base = listOf(shape(1, "screen.a", 10, 10, 20, 20))
        val local = listOf(shape(1, "screen.a", 20, 10, 20, 20))
        val firstExternal = listOf(shape(1, "screen.a", 30, 10, 20, 30))
        val pending = reconcileAnnotationSession(
            AnnotationMergeMode.RECT,
            AnnotationSessionSyncState(base, local, "r1", dirty = true),
            firstExternal,
            "r2",
        )
        assertTrue(pending.hasConflicts)
        assertEquals(30, pending.displayShapes.single().h, "non-conflicting external field is visible")

        val secondExternal = listOf(shape(1, "screen.a", 40, 10, 20, 35))
        val recomputed = reconcileAnnotationSession(AnnotationMergeMode.RECT, pending, secondExternal, "r3")
        assertTrue(recomputed.hasConflicts)
        assertEquals(local, recomputed.local, "r2 auto-merge never became a local edit")
        assertEquals(35, recomputed.displayShapes.single().h)
        assertEquals(40, recomputed.pending!!.result.conflicts.single().external?.x)
    }

    @Test
    fun `conflict resolution advances base only after complete current revision choice`() {
        val base = listOf(shape(1, "screen.a", 10, 10))
        val local = listOf(shape(1, "screen.a", 20, 10))
        val external = listOf(shape(1, "screen.a", 30, 10))
        val pending = reconcileAnnotationSession(
            AnnotationMergeMode.RECT,
            AnnotationSessionSyncState(base, local, "r1", dirty = true),
            external,
            "r2",
        )
        val conflict = pending.pending!!.result.conflicts.single()

        assertNull(resolveAnnotationSessionConflicts(
            AnnotationMergeMode.RECT,
            pending,
            emptyMap(),
            "r2",
        ))
        assertNull(resolveAnnotationSessionConflicts(
            AnnotationMergeMode.RECT,
            pending,
            mapOf(conflict.key to AnnotationConflictChoice.EXTERNAL),
            "r3",
        ), "stale choices must not apply after disk changed again")

        val resolved = resolveAnnotationSessionConflicts(
            AnnotationMergeMode.RECT,
            pending,
            mapOf(conflict.key to AnnotationConflictChoice.EXTERNAL),
            "r2",
        )
        assertNotNull(resolved)
        assertFalse(resolved.hasConflicts)
        assertTrue(resolved.dirty)
        assertEquals(30, resolved.local.single().x)
        assertEquals(external, resolved.base)
        assertEquals("r2", resolved.revision)
    }
}
