package com.alicejump.okscripttoolkit.tasklauncher

/** Keeps unsaved account-editor text while a store operation returns a fresh snapshot. */
internal class AccountEditorDrafts {
    private val mapDrafts = mutableMapOf<String, String>()

    fun listText(displayed: String, previousPersisted: String?, freshPersisted: String): String =
        if (previousPersisted != null && displayed != previousPersisted && displayed != freshPersisted) {
            displayed
        } else {
            freshPersisted
        }

    fun captureMap(id: String?, displayed: String, persisted: String) {
        if (id.isNullOrEmpty()) return
        if (displayed == persisted) mapDrafts.remove(id) else mapDrafts[id] = displayed
    }

    fun mapText(id: String?, persisted: String): String {
        if (id.isNullOrEmpty()) return persisted
        val draft = mapDrafts[id] ?: return persisted
        if (draft == persisted) {
            mapDrafts.remove(id)
            return persisted
        }
        return draft
    }

    fun forgetMap(id: String?) {
        if (!id.isNullOrEmpty()) mapDrafts.remove(id)
    }
}
