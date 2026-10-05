package com.alicejump.okscripttoolkit

import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey
import java.text.MessageFormat

private const val BUNDLE = "messages.OkScriptToolkitBundle"

/**
 * Plugin message bundle resolved with the IDE/default locale. Chinese locales keep the historical
 * zh_TW -> zh_CN -> English fallback through [LocaleBundles].
 */
object OkScriptToolkitBundle {
    @JvmStatic
    @Nls
    fun message(
        @PropertyKey(resourceBundle = BUNDLE) key: String,
        vararg params: Any,
    ): String {
        val value = LocaleBundles.bundle(BUNDLE).getString(key)
        return if (params.isEmpty()) value else MessageFormat.format(value, *params)
    }
}
