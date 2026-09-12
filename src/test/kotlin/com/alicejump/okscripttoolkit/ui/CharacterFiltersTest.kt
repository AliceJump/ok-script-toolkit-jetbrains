package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.core.CharacterEffectRef
import com.alicejump.okscripttoolkit.core.CharacterEffectUsage
import com.alicejump.okscripttoolkit.core.CharacterEffectView
import com.alicejump.okscripttoolkit.core.CharacterEnhancementView
import com.alicejump.okscripttoolkit.core.CharacterMasterView
import com.alicejump.okscripttoolkit.core.CharacterSkillView
import com.alicejump.okscripttoolkit.core.CharacterView
import com.alicejump.okscripttoolkit.core.EffectUsageScope
import com.alicejump.okscripttoolkit.core.IssueSeverity
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 角色/效果/问题三张列表的过滤规则（对齐 VSCode `media/characterManager/app.js`
 * 的 filteredCharacters / renderEffects / renderIssues）。纯逻辑，不依赖 Swing。
 */
class CharacterFiltersTest {

    // ── 工厂 ────────────────────────────────────────────────────

    private fun effectRef(id: String, name: String = id) = CharacterEffectRef(
        effectId = id,
        displayName = name,
        value = null,
        duration = null,
        target = null,
        count = null,
        inferred = false,
        known = true,
    )

    private fun enhancement(
        name: String,
        triggerText: String = "",
        enhancementEffect: String = "",
        triggerEffects: List<CharacterEffectRef> = emptyList(),
    ) = CharacterEnhancementView(
        name = name,
        triggerText = triggerText,
        triggerEffectMode = "auto",
        triggerEffects = triggerEffects,
        effects = emptyList(),
        enhancementEffect = enhancementEffect,
        visiblePulse = false,
    )

    private fun skill(
        id: String,
        name: String = id,
        skillType: String = "",
        element: String = "",
        effects: List<CharacterEffectRef> = emptyList(),
        enhancements: List<CharacterEnhancementView> = emptyList(),
    ) = CharacterSkillView(
        skillId = id,
        name = name,
        skillType = skillType,
        element = element,
        description = "",
        damageMultiplier = "",
        staggerValue = 0,
        cooldown = "",
        spiritCost = 0,
        hasEnhancement = enhancements.isNotEmpty(),
        effects = effects,
        enhancements = enhancements,
        source = "custom",
    )

    private fun character(
        id: String,
        name: String = id,
        star: Int = 0,
        element: String = "",
        profession: String = "",
        locales: Map<String, String> = emptyMap(),
        skills: List<CharacterSkillView> = emptyList(),
        issueCount: Int = 0,
    ) = CharacterView(
        characterId = id,
        name = name,
        star = star,
        element = element,
        profession = profession,
        weaponType = "",
        wikiItemId = "",
        sourceFile = null,
        master = null,
        locales = locales,
        skills = skills,
        issueCount = issueCount,
        errorCount = 0,
    )

    // ── 角色检索 ────────────────────────────────────────────────

    @Test
    fun `haystack covers id name locale and nested skill text`() {
        val char = character(
            id = "yi_feng",
            name = "伊冯",
            locales = mapOf("en" to "Yvonne"),
            skills = listOf(
                skill(
                    id = "yi_feng_s1",
                    name = "霜噬",
                    skillType = "主动",
                    effects = listOf(effectRef("ATTACH_COLD", "寒冷附着")),
                    enhancements = listOf(
                        enhancement(name = "霜爆", triggerText = "触发", enhancementEffect = "额外伤害",
                            triggerEffects = listOf(effectRef("STUN", "眩晕"))),
                    ),
                ),
            ),
        )
        val hay = CharacterFilters.haystack(char)
        for (needle in listOf("yi_feng", "伊冯", "yvonne", "霜噬", "attach_cold", "霜爆", "stun")) {
            assertContains(hay, needle.lowercase())
        }
    }

    @Test
    fun `query filter is case insensitive and trims`() {
        val char = character(id = "yi_feng", name = "伊冯", locales = mapOf("en" to "Yvonne"))
        assertTrue(CharacterFilters.matches(char, CharacterFilterCriteria(query = "  YVONNE ")))
        // 名称不匹配时走 haystack 全字段，这里只有 id/name
        assertTrue(CharacterFilters.matches(char, CharacterFilterCriteria(query = "伊冯")))
        assertFalse(CharacterFilters.matches(char, CharacterFilterCriteria(query = "nope")))
    }

    // ── 角色下拉过滤 ────────────────────────────────────────────

    @Test
    fun `blank star element and profession collapse to the unknown placeholder`() {
        val char = character(id = "a", star = 0, element = "", profession = "")
        assertEquals(UNKNOWN_FILTER_VALUE, CharacterFilters.starValue(char))
        assertEquals(UNKNOWN_FILTER_VALUE, CharacterFilters.elementValue(char))
        assertEquals(UNKNOWN_FILTER_VALUE, CharacterFilters.professionValue(char))
        assertEquals("6", CharacterFilters.starValue(char.copy(star = 6)))
    }

    @Test
    fun `star element and profession filters compare exact values`() {
        val char = character(id = "a", star = 6, element = "冰", profession = "术师")
        assertTrue(CharacterFilters.matches(char, CharacterFilterCriteria(star = "6", element = "冰", profession = "术师")))
        assertFalse(CharacterFilters.matches(char, CharacterFilterCriteria(star = "5")))
        assertFalse(CharacterFilters.matches(char, CharacterFilterCriteria(element = "火")))
        assertFalse(CharacterFilters.matches(char, CharacterFilterCriteria(profession = "近卫")))
        // 空条件 = 不过滤
        assertTrue(CharacterFilters.matches(char, CharacterFilterCriteria()))
    }

    @Test
    fun `skill type filter matches any skill case insensitively`() {
        val char = character(
            id = "a",
            skills = listOf(skill(id = "s1", skillType = "主动"), skill(id = "s2", skillType = "被动")),
        )
        assertTrue(CharacterFilters.matches(char, CharacterFilterCriteria(skillType = "被动")))
        assertFalse(CharacterFilters.matches(char, CharacterFilterCriteria(skillType = "连携")))
    }

    @Test
    fun `enhancementOnly and issueOnly filters`() {
        val withEnh = character(
            id = "a",
            skills = listOf(skill(id = "s1", enhancements = listOf(enhancement("强")))),
        )
        val plain = character(id = "b", skills = listOf(skill(id = "s1")))
        assertTrue(CharacterFilters.matches(withEnh, CharacterFilterCriteria(enhancementOnly = true)))
        assertFalse(CharacterFilters.matches(plain, CharacterFilterCriteria(enhancementOnly = true)))

        val broken = character(id = "c", issueCount = 3)
        assertTrue(CharacterFilters.matches(broken, CharacterFilterCriteria(issueOnly = true)))
        assertFalse(CharacterFilters.matches(plain, CharacterFilterCriteria(issueOnly = true)))
    }

    @Test
    fun `values are distinct and sorted case insensitively`() {
        val chars = listOf(
            character(id = "a", element = "火"),
            character(id = "b", element = "冰"),
            character(id = "c", element = "火"),
            character(id = "d", element = ""),
        )
        // `—`(U+2014) 排在汉字之前，空值占位因此落在首位
        assertEquals(
            listOf(UNKNOWN_FILTER_VALUE, "冰", "火"),
            CharacterFilters.values(CharacterFilters::elementValue, chars),
        )
    }

    // ── 效果 ────────────────────────────────────────────────────

    private fun effect(
        id: String,
        name: String = id,
        description: String = "",
        category: String = "",
        defined: Boolean = true,
        usages: List<CharacterEffectUsage> = emptyList(),
    ) = CharacterEffectView(
        id = id,
        displayName = name,
        description = description,
        category = category,
        defined = defined,
        usages = usages,
    )

    private fun usage(characterName: String, skillName: String) = CharacterEffectUsage(
        characterId = "c",
        characterName = characterName,
        skillId = "s",
        skillName = skillName,
        skillType = "主动",
        scope = EffectUsageScope.SKILL,
    )

    @Test
    fun `effect haystack covers id name description category and usages`() {
        val e = effect(
            id = "ATTACH_COLD",
            name = "寒冷附着",
            description = "敌人被施加寒冷元素",
            category = "元素附着",
            usages = listOf(usage("伊冯", "霜噬")),
        )
        val hay = CharacterFilters.effectHaystack(e)
        for (needle in listOf("attach_cold", "寒冷附着", "敌人被施加寒冷元素", "元素附着", "伊冯", "霜噬", "skill")) {
            assertContains(hay, needle.lowercase())
        }
    }

    @Test
    fun `effect category and usage filters`() {
        val used = effect(id = "STUN", category = "状态", usages = listOf(usage("伊冯", "霜噬")))
        val unused = effect(id = "SLOW", category = "状态")
        val unknown = effect(id = "MISSING", category = "", defined = false)

        assertTrue(CharacterFilters.matches(used, EffectFilterCriteria(category = "状态")))
        assertFalse(CharacterFilters.matches(used, EffectFilterCriteria(category = "元素附着")))

        assertTrue(CharacterFilters.matches(used, EffectFilterCriteria(usage = EffectUsageFilter.USED)))
        assertFalse(CharacterFilters.matches(unused, EffectFilterCriteria(usage = EffectUsageFilter.USED)))

        assertTrue(CharacterFilters.matches(unused, EffectFilterCriteria(usage = EffectUsageFilter.UNUSED)))
        assertFalse(CharacterFilters.matches(used, EffectFilterCriteria(usage = EffectUsageFilter.UNUSED)))

        assertTrue(CharacterFilters.matches(unknown, EffectFilterCriteria(usage = EffectUsageFilter.UNKNOWN)))
        assertFalse(CharacterFilters.matches(used, EffectFilterCriteria(usage = EffectUsageFilter.UNKNOWN)))

        assertTrue(CharacterFilters.matches(used, EffectFilterCriteria()))
    }

    @Test
    fun `effect query matches id or description`() {
        val e = effect(id = "ATTACH_COLD", description = "敌人被施加寒冷元素")
        assertTrue(CharacterFilters.matches(e, EffectFilterCriteria(query = "attach")))
        assertTrue(CharacterFilters.matches(e, EffectFilterCriteria(query = "寒冷")))
        assertFalse(CharacterFilters.matches(e, EffectFilterCriteria(query = "stun")))
    }

    // ── 问题 ────────────────────────────────────────────────────

    @Test
    fun `issue matches by severity and free text`() {
        assertTrue(
            CharacterFilters.issueMatches(IssueSeverity.ERROR, "缺少技能文件", "MISSING_SKILL", "", null),
        )
        assertFalse(
            CharacterFilters.issueMatches(IssueSeverity.ERROR, "x", " y", "", IssueSeverity.WARNING),
        )
        assertTrue(
            CharacterFilters.issueMatches(IssueSeverity.WARNING, "效果未定义", "UNKNOWN_EFFECT", "unknown_eff", null),
        )
        assertTrue(
            CharacterFilters.issueMatches(IssueSeverity.INFO, "效果未定义", "UNKNOWN_EFFECT", "未定义", null),
        )
        assertFalse(
            CharacterFilters.issueMatches(IssueSeverity.INFO, "效果未定义", "UNKNOWN_EFFECT", "zzz", null),
        )
    }
}
