package com.alicejump.okscripttoolkit.ui

import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.CaretModel
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals

class ResourceReferenceInsertionTest {
    private inline fun <reified T> proxy(crossinline action: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args -> action(method.name, args ?: emptyArray()) } as T

    private fun insert(initial: String, selections: List<Triple<Int, Int, Int>>, text: String): String {
        val buffer = StringBuilder(initial)
        val carets = selections.map { (offset, start, end) -> proxy<Caret> { name, _ -> when (name) {
            "getOffset" -> offset
            "hasSelection" -> start != end
            "getSelectionStart" -> start
            "getSelectionEnd" -> end
            else -> null
        } } }
        val model = proxy<CaretModel> { name, _ -> if (name == "getAllCarets") carets else null }
        val document = proxy<Document> { name, args ->
            if (name == "replaceString") buffer.replace(args[0] as Int, args[1] as Int, args[2] as String)
            null
        }
        val editor = proxy<Editor> { name, _ -> when (name) { "getCaretModel" -> model; "getDocument" -> document; else -> null } }
        insertResourceReference(editor, text)
        return buffer.toString()
    }

    @Test fun `reversed selections and multiple carets replace each selected expression`() {
        assertEquals("a self.pos.screen.x b self.pos.screen.x c",
            insert("a OLD b XX c", listOf(Triple(2, 2, 5), Triple(10, 8, 10)), "self.pos.screen.x"))
    }

    @Test fun `unselected carets insert references without removing surrounding text`() {
        assertEquals("fL.button + fL.button", insert(" + ", listOf(Triple(0, 0, 0), Triple(3, 3, 3)), "fL.button"))
    }
}
