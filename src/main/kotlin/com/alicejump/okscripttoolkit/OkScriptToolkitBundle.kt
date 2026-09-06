package com.alicejump.okscripttoolkit

import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey
import java.text.MessageFormat
import java.util.Locale
import java.util.ResourceBundle
import java.util.concurrent.ConcurrentHashMap

private const val BUNDLE = "messages.OkScriptToolkitBundle"

/**
 * 插件消息Bundle：按 IDE 当前语言解析（Locale.getDefault），与 DynamicBundle 的
 * 语言解析语义一致；zh 系语言缺失时回退 zh_CN 再回退英文基础包。
 * 自实现以摆脱对已弃用 DynamicBundle(String) 构造器的依赖。
 */
object OkScriptToolkitBundle {

    /** 缺失语言回退链（ResourceBundle 默认只回退到语言级，zh_TW 不会落到 zh_CN） */
    private val FALLBACKS = mapOf(
        "zh" to listOf("zh_CN", ""),
        "zh_TW" to listOf("zh_TW", "zh_CN", ""),
        "zh_HK" to listOf("zh_HK", "zh_CN", ""),
    )

    private val cache = ConcurrentHashMap<Locale, ResourceBundle>()

    @JvmStatic
    @Nls
    fun message(
        @PropertyKey(resourceBundle = BUNDLE) key: String,
        vararg params: Any,
    ): String {
        val value = bundle().getString(key)
        return if (params.isEmpty()) value else MessageFormat.format(value, *params)
    }

    private fun bundle(): ResourceBundle {
        val locale = Locale.getDefault()
        cache[locale]?.let { return it }
        val bundle = loadBundle(locale) ?: loadBundle(Locale.ROOT) ?: error("Missing base bundle $BUNDLE")
        cache[locale] = bundle
        return bundle
    }

    private fun loadBundle(locale: Locale): ResourceBundle? {
        for (candidate in fallbackCandidates(locale)) {
            try {
                return ResourceBundle.getBundle(BUNDLE, candidate, OkScriptToolkitBundle::class.java.classLoader)
            } catch (_: Exception) {
                // 该 locale 无包：尝试下一个候选
            }
        }
        return null
    }

    private fun fallbackCandidates(locale: Locale): List<Locale> {
        val language = locale.language.lowercase()
        val country = locale.country.uppercase()
        val full = if (country.isEmpty()) language else "${language}_$country"
        val chain = FALLBACKS[full] ?: FALLBACKS[language] ?: listOf(full.ifEmpty { "" })
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
