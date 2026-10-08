package com.alicejump.okscripttoolkit.ui

import com.intellij.openapi.editor.Editor

/** 在宿主写命令内调用；与 VS Code snippet 一样替换各光标的选区。 */
internal fun insertResourceReference(editor: Editor, text: String) {
    for (caret in editor.caretModel.allCarets.sortedByDescending { it.offset }) {
        val start = if (caret.hasSelection()) caret.selectionStart else caret.offset
        val end = if (caret.hasSelection()) caret.selectionEnd else start
        editor.document.replaceString(start, end, text)
        caret.removeSelection()
        caret.moveToOffset(start + text.length)
    }
}
