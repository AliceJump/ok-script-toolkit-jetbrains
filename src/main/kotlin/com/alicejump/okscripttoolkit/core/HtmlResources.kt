package com.alicejump.okscripttoolkit.core

import java.util.concurrent.ConcurrentHashMap

/** External HTML fragments; callers supply already escaped values. */
internal object HtmlResources {
    private val templates = ConcurrentHashMap<String, String>()
    private val placeholder = Regex("__([A-Z_]+)__")

    fun render(name: String, values: Map<String, String>): String {
        val template = templates.computeIfAbsent(name) {
            requireNotNull(javaClass.getResourceAsStream("/html/$it.html")).bufferedReader().use { reader -> reader.readText() }
        }
        return placeholder.replace(template) { values[it.groupValues[1]].orEmpty() }
    }
}
