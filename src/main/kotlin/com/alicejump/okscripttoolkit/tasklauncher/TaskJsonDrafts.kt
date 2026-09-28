package com.alicejump.okscripttoolkit.tasklauncher

/** Unsaved JSON text belongs to the project, task and field that created its editor. */
internal class TaskJsonDrafts {
    private data class Key(val root: String, val task: String, val field: String)

    private val drafts = mutableMapOf<Key, String>()

    fun textOrDefault(root: String, task: String, field: String, default: String): String =
        drafts[Key(root, task, field)] ?: default

    fun record(root: String, task: String, field: String, text: String, valid: Boolean) {
        val key = Key(root, task, field)
        if (valid) drafts.remove(key) else drafts[key] = text
    }

    fun clearTask(root: String, task: String): Boolean =
        drafts.keys.removeIf { it.root == root && it.task == task }

    fun clear() = drafts.clear()
}
