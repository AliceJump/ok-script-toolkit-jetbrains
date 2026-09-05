package com.alicejump.okscripttoolkit.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PythonScriptUtilsTest {

    @Test
    fun `parseExtraArgs accepts JSON array`() {
        assertEquals(listOf("a", "b", "c"), PythonScriptUtils.parseExtraArgs("""["a","b","c"]"""))
        assertEquals(emptyList(), PythonScriptUtils.parseExtraArgs("[]"))
    }

    @Test
    fun `parseExtraArgs splits shell style with quotes`() {
        assertEquals(listOf("--flag", "hello world", "y"), PythonScriptUtils.parseExtraArgs("""--flag "hello world" y"""))
        assertEquals(listOf("it's"), PythonScriptUtils.parseExtraArgs("\"it's\""))
    }

    @Test
    fun `parseExtraArgs drops backslash escapes`() {
        // 契约：\x 转义为字面 x；行尾孤立反斜杠保留
        assertEquals(listOf("ab", "c"), PythonScriptUtils.parseExtraArgs("""a\b c"""))
        assertEquals(listOf("tail\\"), PythonScriptUtils.parseExtraArgs("""tail\"""))
    }

    @Test
    fun `parseExtraArgs rejects unclosed quote`() {
        assertFailsWith<IllegalArgumentException> { PythonScriptUtils.parseExtraArgs("\"unclosed") }
    }

    @Test
    fun `parseExtraArgs blank and null`() {
        assertEquals(emptyList(), PythonScriptUtils.parseExtraArgs(null))
        assertEquals(emptyList(), PythonScriptUtils.parseExtraArgs("   "))
    }

    @Test
    fun `parseJsonFromStdout picks last json line`() {
        val stdout = """
            some log line
            {"ok": false, "error": "early"}
            {"ok": true, "total": 3}
        """.trimIndent()
        assertEquals("""{"ok": true, "total": 3}""", PythonScriptUtils.parseJsonFromStdout(stdout))
        assertNull(PythonScriptUtils.parseJsonFromStdout("no json here"))
    }

    @Test
    fun `parsePo handles multiline values`() {
        val po = """
            msgid ""
            "借 款 金 额"
            msgstr ""
            "借款金额[:：]?[0-9]+"
        """.trimIndent()
        val entries = OkProjectDataService.parsePo(po)
        assertEquals(1, entries.size)
        assertEquals("借 款 金 额", entries[0].first)
        assertEquals("借款金额[:：]?[0-9]+", entries[0].second)
    }

    @Test
    fun `parsePo skips empty msgid and comments`() {
        val po = """
            # a comment
            msgid ""
            msgstr ""

            msgid "体力"
            msgstr "体力[0-9]+"
        """.trimIndent()
        val entries = OkProjectDataService.parsePo(po)
        assertEquals(listOf("体力" to "体力[0-9]+"), entries)
    }

    @Test
    fun `parseEffects extracts categories members and descriptions`() {
        val effects = """
            # 效果类型
            # 元素附着
            ATTACH_COLD = "ATTACH_COLD"
            ATTACH_FIRE = "ATTACH_FIRE"

            # 效果描述映射
            EFFECT_DESCRIPTIONS = {
                EffectType.ATTACH_COLD: "冷凝",
                EffectType.ATTACH_FIRE: "燃烧",
            }
        """.trimIndent()
        val parsed = OkProjectDataService.parseEffects(effects)
        assertEquals(setOf("ATTACH_COLD", "ATTACH_FIRE"), parsed.keys)
        assertEquals("元素附着", parsed["ATTACH_COLD"]?.category)
        assertEquals("冷凝", parsed["ATTACH_COLD"]?.description)
        assertEquals("燃烧", parsed["ATTACH_FIRE"]?.description)
    }

    @Test
    fun `parseEffects ignores header comment and period lines`() {
        val effects = """
            # 元素附着
            # 效果类型
            # 这是一个。含句号的说明行
            ONLY = "ONLY"
            # 效果描述映射
            EFFECT_DESCRIPTIONS = {
                EffectType.ONLY: "only",
            }
        """.trimIndent()
        val parsed = OkProjectDataService.parseEffects(effects)
        assertTrue("ONLY" in parsed)
        // "# 效果类型" 与句号行被排除，分类保持为最近的真实类目"元素附着"
        assertEquals("元素附着", parsed["ONLY"]?.category)
    }
}
