package com.alicejump.okscripttoolkit.tasklauncher

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TaskJsonDraftsTest {
    @Test
    fun `unfinished text survives refresh and stays within its project task and field`() {
        val drafts = TaskJsonDrafts()
        drafts.record("first", "task", "conditions", "[{", valid = false)
        drafts.record("first", "task", "actions", "", valid = false)

        assertEquals("[{", drafts.textOrDefault("first", "task", "conditions", "[]"))
        assertEquals("", drafts.textOrDefault("first", "task", "actions", "[]"))
        assertEquals("[]", drafts.textOrDefault("second", "task", "conditions", "[]"))
        assertEquals("[]", drafts.textOrDefault("first", "other", "conditions", "[]"))

        drafts.record("first", "task", "conditions", "[{}]", valid = true)
        assertEquals("[]", drafts.textOrDefault("first", "task", "conditions", "[]"))
        assertTrue(drafts.clearTask("first", "task"))
        assertFalse(drafts.clearTask("first", "task"))
    }
}
