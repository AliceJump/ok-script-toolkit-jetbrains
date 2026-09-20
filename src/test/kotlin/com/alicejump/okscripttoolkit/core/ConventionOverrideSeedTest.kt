package com.alicejump.okscripttoolkit.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 老用户"补记账"迁移的测试（`ConventionOverrideSeed`）。
 *
 * 这段逻辑**只在升级路径上跑一次** —— 手测几乎覆盖不到，而写错的后果恰恰只在那一次出现：
 * 要么老用户改过的值被项目约定文件静默顶掉（漏种），要么用户没设过的东西被误记成
 * "我的设置"、从此看不到团队改了什么（多种）。
 *
 * 所以判据要两个方向都钉住。
 */
class ConventionOverrideSeedTest {

    private val DEFAULTS = mapOf(
        "okTemplatesDirectory" to "ok_templates",
        "langDirectory" to "assets/lang",
        "poDomains" to listOf("ocr"),
        "enablePoData" to true,
        "characterProjectPath" to "",
    )

    @Test
    fun `a value that differs from the builtin default is a real user change`() {
        val seeded = ConventionOverrideSeed.keysToSeed(
            overriddenKeys = emptyList(),
            current = mapOf("okTemplatesDirectory" to "my_tpl", "langDirectory" to "assets/lang"),
            defaults = DEFAULTS,
        )
        assertEquals(listOf("okTemplatesDirectory"), seeded, "只有与默认值不同的那一项需要补记账")
    }

    @Test
    fun `values equal to the builtin default are never seeded`() {
        val seeded = ConventionOverrideSeed.keysToSeed(
            overriddenKeys = emptyList(),
            current = mapOf(
                "okTemplatesDirectory" to "ok_templates",
                "langDirectory" to "assets/lang",
                "poDomains" to listOf("ocr"),
                "enablePoData" to true,
            ),
            defaults = DEFAULTS,
        )
        assertTrue(
            seeded.isEmpty(),
            "**默认值不会被写成非默认值** —— 所以「等于默认」就是「从没设过」，种了会把项目声明永久压住",
        )
    }

    @Test
    fun `null, blank and empty collection all count as not set`() {
        val seeded = ConventionOverrideSeed.keysToSeed(
            overriddenKeys = emptyList(),
            current = mapOf(
                "okTemplatesDirectory" to null,
                "langDirectory" to "   ",
                "poDomains" to emptyList<String>(),
                "characterProjectPath" to "",
            ),
            defaults = DEFAULTS,
        )
        assertTrue(
            seeded.isEmpty(),
            "空 / 全空白 / 空集合都表达「我没指定」—— 不能记成「我指定了一个空值」",
        )
    }

    @Test
    fun `the character project path is seeded when it points somewhere`() {
        val seeded = ConventionOverrideSeed.keysToSeed(
            overriddenKeys = emptyList(),
            current = mapOf("characterProjectPath" to "/home/me/other_proj"),
            defaults = DEFAULTS,
        )
        assertEquals(
            listOf("characterProjectPath"),
            seeded,
            "非空的角色项目根一定是用户填的（兜底是空串）",
        )
    }

    @Test
    fun `keys already accounted for are not seeded twice`() {
        val seeded = ConventionOverrideSeed.keysToSeed(
            overriddenKeys = listOf("okTemplatesDirectory"),
            current = mapOf("okTemplatesDirectory" to "my_tpl", "langDirectory" to "assets/mine"),
            defaults = DEFAULTS,
        )
        assertEquals(
            listOf("langDirectory"),
            seeded,
            "已经记过的键不再重复记账，但**新键照样要补** —— v1.7.x 的老用户集合非空，正是这一条救了他们",
        )
    }

    @Test
    fun `a boolean switched away from its default is seeded`() {
        val seeded = ConventionOverrideSeed.keysToSeed(
            overriddenKeys = emptyList(),
            current = mapOf("enablePoData" to false),
            defaults = DEFAULTS,
        )
        assertEquals(listOf("enablePoData"), seeded, "布尔项关掉默认值也是用户改过")
    }

    @Test
    fun `a list changed away from its default is seeded`() {
        val seeded = ConventionOverrideSeed.keysToSeed(
            overriddenKeys = emptyList(),
            current = mapOf("poDomains" to listOf("ocr", "ui")),
            defaults = DEFAULTS,
        )
        assertEquals(listOf("poDomains"), seeded, "列表内容不同即用户改过（比的是内容不是引用）")
    }

    @Test
    fun `keys missing from the state map are not seeded`() {
        val seeded = ConventionOverrideSeed.keysToSeed(
            overriddenKeys = emptyList(),
            current = mapOf("okTemplatesDirectory" to "my_tpl"),
            defaults = DEFAULTS,
        )
        assertEquals(
            listOf("okTemplatesDirectory"),
            seeded,
            "没出现在 current 里的键视为「没设过」—— 调用方漏传一个键不该变成「用户设过」",
        )
    }
}
