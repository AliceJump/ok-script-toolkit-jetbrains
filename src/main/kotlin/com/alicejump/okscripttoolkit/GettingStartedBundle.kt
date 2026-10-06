package com.alicejump.okscripttoolkit

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey
import java.text.MessageFormat

private const val GETTING_STARTED_BUNDLE = "messages.OkScriptToolkitGettingStartedBundle"

/** Strings owned by the Getting Started experience. */
object GettingStartedBundle {
    @JvmStatic
    @Nls
    fun message(
        @PropertyKey(resourceBundle = GETTING_STARTED_BUNDLE) key: String,
        vararg params: Any,
    ): String {
        val value = LocaleBundles.bundle(GETTING_STARTED_BUNDLE, DynamicBundle.getLocale()).getString(key)
        return if (params.isEmpty()) value else MessageFormat.format(value, *params)
    }
}
