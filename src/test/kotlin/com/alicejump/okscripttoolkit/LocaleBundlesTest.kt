package com.alicejump.okscripttoolkit

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class LocaleBundlesTest {
    @Test
    fun `zh HK falls back to zh CN before base bundle`() {
        val bundle = LocaleBundles.bundle(
            "messages.OkScriptToolkitAnnotationBundle",
            Locale.of("zh", "HK"),
        )
        assertEquals(Locale.of("zh", "CN"), bundle.locale)
    }

    @Test
    fun `regional locale falls back to matching language bundle before base`() {
        val bundle = LocaleBundles.bundle(
            "messages.OkScriptToolkitAnnotationBundle",
            Locale.of("ja", "JP"),
        )
        assertEquals(Locale.JAPANESE, bundle.locale)
    }
}
