package com.alicejump.okscripttoolkit.tasklauncher

import kotlin.test.Test
import kotlin.test.assertEquals

class GlobalSaveEditsTest {
    @Test
    fun `later edits replace only their keys and retain earlier failed values`() {
        val failed = GlobalSaveEdits(mapOf("one" to 0, "two" to 0), mapOf("one" to 1))
        val combined = failed.followedBy(mapOf("one" to 1, "two" to 0), mapOf("two" to 2))
        assertEquals(mapOf("one" to 1, "two" to 2), combined.editedValues)
        assertEquals(mapOf("one" to 1, "two" to 2),
            GlobalSnapshotRules.mergeEdited(emptyMap(), combined.initialValues, combined.editedValues))
    }

    @Test
    fun `returning to failed value does not discard the original unsaved edit`() {
        val failed = GlobalSaveEdits(mapOf("one" to 0), mapOf("one" to 1))
        val combined = failed.followedBy(mapOf("one" to 1), mapOf("one" to 1))
        assertEquals(mapOf("one" to 1),
            GlobalSnapshotRules.mergeEdited(mapOf("one" to 0), combined.initialValues, combined.editedValues))
    }

    @Test
    fun `returning to persisted value supersedes failed value`() {
        val failed = GlobalSaveEdits(mapOf("one" to 0), mapOf("one" to 1))
        val combined = failed.followedBy(mapOf("one" to 1), mapOf("one" to 0))
        assertEquals(mapOf("one" to 0),
            GlobalSnapshotRules.mergeEdited(mapOf("one" to 4), combined.initialValues, combined.editedValues))
    }
}
