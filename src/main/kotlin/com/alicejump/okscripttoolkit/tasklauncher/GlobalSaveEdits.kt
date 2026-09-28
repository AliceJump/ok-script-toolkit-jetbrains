package com.alicejump.okscripttoolkit.tasklauncher

/** Values awaiting a successful global-config write, independent of the Swing controls that produced them. */
internal data class GlobalSaveEdits(
    val initialValues: Map<String, Any?>,
    val editedValues: Map<String, Any?>,
) {
    /** Later edits win while untouched values from an earlier failed write remain queued. */
    fun followedBy(initial: Map<String, Any?>, edited: Map<String, Any?>): GlobalSaveEdits {
        val combinedInitial = initialValues.toMutableMap()
        val combinedEdited = editedValues.toMutableMap()
        for ((key, value) in edited) {
            // If the user returns to the failed value, retain its original baseline so it still saves.
            if (!(combinedEdited.containsKey(key) && combinedEdited[key] == value && initial[key] == value)) {
                combinedInitial[key] = initial[key]
            }
            combinedEdited[key] = value
        }
        return GlobalSaveEdits(combinedInitial, combinedEdited)
    }
}
