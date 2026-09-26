package com.alicejump.okscripttoolkit.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 头像模板匹配规则的对齐测试（对齐 VS Code characterPanel.ts 的
 * avatarTemplateIndex / characterAvatars 四条约定）。
 */
class AvatarMatcherTest {

    private val names = listOf(
        "battle_icon_yvonne",      // 标准命名：正则剥前缀后剩 yvonne
        "battle_icon mist",        // 剥前缀后带空格：归一化后剩 mist
        "battle-icon-lumi",        // 连字符变体
        "battle_icon_echo_2",      // 后缀带编号（精确 key 不含编号）
        "portrait_nia",            // 正则不命中
    )

    private fun select(regex: String, candidates: List<String?>, names: List<String> = this.names): String? =
        AvatarMatcher.select(names, { it }, AvatarMatcher.compile(regex, "^battle[_-]?icon[_-]?"), candidates)

    @Test
    fun `normalized keys match separator and case variants`() {
        // master.en / characterId 与模板名分隔符、大小写不一致时照样命中
        assertEquals("battle_icon_yvonne", select("^battle[_-]?icon[_-]?", listOf("YVONNE")))
        assertEquals("battle_icon mist", select("^battle[_-]?icon[_-]?", listOf("Mist")))
        assertEquals("battle-icon-lumi", select("^battle[_-]?icon[_-]?", listOf("l_u_m_i")))
    }

    @Test
    fun `capture group takes precedence over remainder`() {
        // 正则里有捕获组时以组内容为准（VS Code: match[1] || remainder）
        assertEquals("battle_icon_yvonne", select("^battle[_-]?icon[_-]?(.*)", listOf("yvonne")))
    }

    @Test
    fun `unique suffix fallback picks the single name ending with the key`() {
        // 精确 key 未命中（模板名后缀带编号），但有且只有一个 endsWith 候选
        assertEquals("battle_icon_echo_2", select("^battle[_-]?icon[_-]?", listOf("echo_2")))
        // 不做后缀回退时正常命中不受影响
        assertEquals("battle_icon_yvonne", select("^battle[_-]?icon[_-]?", listOf("yvonne")))
    }

    @Test
    fun `ambiguous suffix matches return null rather than guessing`() {
        assertNull(
            select("^battle[_-]?icon[_-]?", listOf("icon"), listOf("a_icon", "b_icon")),
            "两个 endsWith 候选时不允许猜",
        )
    }

    @Test
    fun `second candidate is tried when the first misses`() {
        assertEquals(
            "battle_icon_yvonne",
            select("^battle[_-]?icon[_-]?", listOf(null, "yvonne")),
            "master.en 缺失时回退 characterId",
        )
        assertNull(select("^battle[_-]?icon[_-]?", listOf("nia")), "正则不命中的模板不参与精确匹配")
    }

    @Test
    fun `invalid regex falls back to the built-in default`() {
        // 用户把正则写坏 → 回退默认 ^battle[_-]?icon[_-]?，而不是整个面板头像全灭
        val regex = AvatarMatcher.compile("(unclosed", "^battle[_-]?icon[_-]?")
        assertEquals("battle_icon_yvonne", AvatarMatcher.select(names, { it }, regex, listOf("yvonne")))
    }
}
