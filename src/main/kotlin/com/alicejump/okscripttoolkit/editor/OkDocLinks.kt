package com.alicejump.okscripttoolkit.editor

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle

/**
 * 悬停文档底部的动作链接（对应 VS Code 的 trusted command link）：
 * 「在资源中定位」「查找引用」。用 `psi_element://ok-…:` 前缀，
 * 由 [OkDocumentationProvider.getDocumentationElementForLink] 截获执行，不跳转任何 PSI。
 */
object OkDocLinks {
    const val REVEAL = "ok-reveal:"
    const val REFS = "ok-refs:"

    fun featureLinks(name: String): String {
        val encoded = java.net.URLEncoder.encode(name, Charsets.UTF_8)
        val reveal = OkScriptToolkitBundle.message("documentation.revealInResources")
        val refs = OkScriptToolkitBundle.message("documentation.findReferences")
        return "<p><a href=\"psi_element://$REVEAL$encoded\">$reveal</a> · <a href=\"psi_element://$REFS$encoded\">$refs</a></p>"
    }

    /** 解析链接 → (动作前缀, 名字)；不是本插件的链接返回 null。 */
    fun parse(link: String): Pair<String, String>? {
        val body = link.removePrefix("psi_element://")
        for (prefix in listOf(REVEAL, REFS)) {
            if (body.startsWith(prefix)) return prefix to java.net.URLDecoder.decode(body.removePrefix(prefix), Charsets.UTF_8)
        }
        return null
    }
}
