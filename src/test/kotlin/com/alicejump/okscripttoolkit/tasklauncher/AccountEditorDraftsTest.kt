package com.alicejump.okscripttoolkit.tasklauncher

import kotlin.test.Test
import kotlin.test.assertEquals

class AccountEditorDraftsTest {
    @Test
    fun `refresh preserves unsaved account list but accepts a successful save`() {
        val drafts = AccountEditorDrafts()
        assertEquals("edited", drafts.listText("edited", "old", "other operation"))
        assertEquals("edited", drafts.listText("edited", "old", "edited"))
        assertEquals("server", drafts.listText("old", "old", "server"))
        assertEquals("server", drafts.listText("", null, "server"))
    }

    @Test
    fun `map drafts follow account id across selection and refresh`() {
        val drafts = AccountEditorDrafts()
        drafts.captureMap("account-1", "unsaved map", "saved map")
        drafts.captureMap("account-2", "second draft", "second saved")

        assertEquals("unsaved map", drafts.mapText("account-1", "new server map"))
        assertEquals("second draft", drafts.mapText("account-2", "second saved"))
        assertEquals("", drafts.mapText(null, ""))

        drafts.forgetMap("account-1")
        assertEquals("new server map", drafts.mapText("account-1", "new server map"))
    }

    @Test
    fun `draft clears when saved text matches the refreshed snapshot`() {
        val drafts = AccountEditorDrafts()
        drafts.captureMap("account-1", "saved edit", "old")
        assertEquals("saved edit", drafts.mapText("account-1", "saved edit"))
        assertEquals("newer", drafts.mapText("account-1", "newer"))
    }
}
