package com.alicejump.okscripttoolkit

import java.util.Locale
import java.util.ResourceBundle
import java.util.concurrent.ConcurrentHashMap

/** Shared IDE-locale bundle resolver used by plugin UI bundles. */
internal object LocaleBundles {
    private val fallbacks = mapOf(
        "zh" to listOf("zh_CN", ""),
        "zh_TW" to listOf("zh_TW", "zh_CN", ""),
        "zh_HK" to listOf("zh_HK", "zh_CN", ""),
    )
    private val caches = ConcurrentHashMap<String, ConcurrentHashMap<Locale, ResourceBundle>>()
    private val noImplicitFallback = ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_DEFAULT)

    fun bundle(baseName: String, locale: Locale = Locale.getDefault()): ResourceBundle {
        val cache = caches.computeIfAbsent(baseName) { ConcurrentHashMap() }
        cache[locale]?.let { return it }
        val bundle = loadBundle(baseName, locale)
            ?: loadBundle(baseName, Locale.ROOT)
            ?: error("Missing base bundle $baseName")
        cache[locale] = bundle
        return bundle
    }

    private fun loadBundle(baseName: String, locale: Locale): ResourceBundle? {
        for (candidate in fallbackCandidates(locale)) {
            try {
                val bundle = ResourceBundle.getBundle(
                    baseName,
                    candidate,
                    LocaleBundles::class.java.classLoader,
                    noImplicitFallback,
                )
                if (candidate == Locale.ROOT) {
                    if (locale == Locale.ROOT && bundle.locale == Locale.ROOT) return bundle
                    continue
                }
                if (bundle.locale == candidate) return bundle
            } catch (_: Exception) {
                // No exact bundle for this candidate; continue through the explicit fallback chain.
            }
        }
        return null
    }

    private fun fallbackCandidates(locale: Locale): List<Locale> {
        val language = locale.language.lowercase()
        val country = locale.country.uppercase()
        val full = if (country.isEmpty()) language else "${language}_$country"
        val chain = fallbacks[full]
            ?: fallbacks[language]
            ?: listOf(full.ifEmpty { "" }, language, "").distinct()
        val candidates = mutableListOf<Locale>()
        for (tag in chain) {
            when (tag) {
                "" -> candidates.add(Locale.ROOT)
                else -> {
                    val parts = tag.split("_")
                    candidates.add(if (parts.size == 2) Locale.of(parts[0], parts[1]) else Locale.of(tag))
                }
            }
        }
        return candidates.ifEmpty { listOf(Locale.ROOT) }
    }
}
