package com.alicejump.okscripttoolkit

import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey
import java.text.MessageFormat

private const val ANNOTATION_UI_BUNDLE = "messages.OkScriptToolkitAnnotationBundle"

/** Strings owned by the unified Template / Box / Point annotation UI. */
object AnnotationUiBundle {
    @JvmStatic
    @Nls
    fun message(
        @PropertyKey(resourceBundle = ANNOTATION_UI_BUNDLE) key: String,
        vararg params: Any,
    ): String {
        val value = LocaleBundles.bundle(ANNOTATION_UI_BUNDLE).getString(key)
        return if (params.isEmpty()) value else MessageFormat.format(value, *params)
    }
}
