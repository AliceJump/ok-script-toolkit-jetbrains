package com.alicejump.okscripttoolkit.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AnnotationDraftTest {
    @Test
    fun `reopened draft recomputes external conflicts from original branch`() {
        val base = listOf(MergeShape(42, "screen.a", 1, 1, 10, 10))
        val local = listOf(base.single().copy(x = 2))
        val firstExternal = listOf(base.single().copy(x = 3, h = 20))
        val pending = reconcileAnnotationSession(AnnotationMergeMode.RECT,
            AnnotationSessionSyncState(base, local, "old", true), firstExternal, "first")
        assertTrue(pending.hasConflicts)
        val recovered = assertNotNull(AnnotationDraft.decode(AnnotationDraft.encode(pending)))
        assertEquals(local, recovered.local)
        assertEquals(base, recovered.base)
        val next = reconcileAnnotationSession(AnnotationMergeMode.RECT, recovered,
            listOf(base.single().copy(x = 4, h = 30)), "second")
        assertTrue(next.hasConflicts)
        assertEquals(2, next.pending!!.result.conflicts.single().local!!.x)
        assertEquals(4, next.pending.result.conflicts.single().external!!.x)
    }

    @Test
    fun `own save event retains editor identity while undo can save an earlier snapshot`() {
        val before = listOf(MergeShape(77, "button", 1, 1, 10, 10))
        val after = listOf(before.single().copy(x = 20))
        val accepted = acceptAnnotationSave(after, "saved")
        assertFalse(accepted.dirty)
        // Disk allocates independent COCO IDs; the already accepted event must not replace editor IDs.
        val ownEvent = reconcileAnnotationSession(AnnotationMergeMode.TEMPLATE, accepted,
            listOf(after.single().copy(id = 1)), "saved")
        assertSame(accepted, ownEvent)
        val undone = ownEvent.copy(local = before, dirty = true)
        assertEquals(before, acceptAnnotationSave(undone.local, "undone").base)
    }

    @Test
    fun `malformed and unsupported drafts do not become empty annotations`() {
        assertNull(AnnotationDraft.decode("{\"version\":2}"))
        assertNull(AnnotationDraft.decode("{\"version\":1,\"base\":[],\"local\":[{}]}"))
        assertNull(AnnotationDraft.decode("broken"))
    }
}
