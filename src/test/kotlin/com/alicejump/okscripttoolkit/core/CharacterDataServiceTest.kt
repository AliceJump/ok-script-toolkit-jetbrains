package com.alicejump.okscripttoolkit.core

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CharacterDataServiceTest {

    /**
     * 真实项目里技能文件名几乎都不等于 character_id（ok-end-field 中 yvonne.json 的
     * character_id 是 yi_feng）。面板此前按 `<characterId>.json` 猜路径，导致所有角色
     * 都报「没有技能文件」。这里锁定：技能文件路径只能来自加载器扫描出的
     * sources.characterFiles / characterFilesByName。
     */
    @Test
    fun `skill file paths come from the scan, not from character_id guessing`() {
        val dir = createTempDirectory("ok-character-service").toFile()
        val skillsDir = dir.resolve("character_skills").apply { mkdirs() }
        skillsDir.resolve("yvonne.json").writeText(
            """
            {
              "character_id": "yi_feng",
              "name": "伊冯",
              "star": 6,
              "element": "冰",
              "profession": "术师",
              "weapon_type": "法杖",
              "skills": [
                {"skill_id": "yi_feng_s1", "name": "霜噬", "skill_type": "主动"}
              ]
            }
            """.trimIndent(),
        )

        val paths = CharacterDataPaths(
            projectDir = dir.absolutePath,
            masterFile = dir.resolve("characters.json").absolutePath,
            skillsDir = skillsDir.absolutePath,
            localeFile = dir.resolve("lang.json").absolutePath,
            effectsFile = dir.resolve("effects.py").absolutePath,
            effectNamesFile = dir.resolve("effect_names.json").absolutePath,
        )
        val result = CharacterDataService.load(paths, "zh_CN")

        assertEquals(1, result.snapshot.characters.size)
        assertEquals("yi_feng", result.snapshot.characters[0].characterId)

        val byId = result.sources.characterFiles["yi_feng"]
        assertNotNull(byId, "必须能通过 character_id 找到技能文件")
        assertTrue(byId.endsWith("yvonne.json"), "实际指向的文件是 yvonne.json，而不是 yi_feng.json")

        val byName = result.sources.characterFilesByName["yvonne.json"]
        assertNotNull(byName, "必须能通过文件名反查技能文件")
        assertEquals(byId, byName)
    }

    @Test
    fun `character_id falls back to the file name when absent`() {
        val dir = createTempDirectory("ok-character-service").toFile()
        val skillsDir = dir.resolve("character_skills").apply { mkdirs() }
        skillsDir.resolve("noid.json").writeText("""{"name": "无名", "skills": []}""")

        val paths = CharacterDataPaths(
            projectDir = dir.absolutePath,
            masterFile = dir.resolve("characters.json").absolutePath,
            skillsDir = skillsDir.absolutePath,
            localeFile = dir.resolve("lang.json").absolutePath,
            effectsFile = dir.resolve("effects.py").absolutePath,
            effectNamesFile = dir.resolve("effect_names.json").absolutePath,
        )
        val result = CharacterDataService.load(paths, "zh_CN")

        assertEquals("noid", result.sources.characterFiles.keys.single())
        assertTrue(result.sources.characterFiles.getValue("noid").endsWith("noid.json"))
    }

    /**
     * 显式 JSON `null` **绝不能**被读成字符串 `"null"`。
     *
     * Jackson 的坑：字段存在但值为 `null` 时，`get()` 返回的是 `NullNode`
     * 而**不是** Kotlin 的 `null`；而 `NullNode.asText()` **返回字面量 `"null"`**。
     * 于是最常见的写法 `node.get(k)?.asText() ?: fallback` 在遇到 `"k": null` 时
     * 既不触发兜底、又把 `"null"` 当成真实值 —— 界面上就显示出一个叫 "null" 的元素。
     *
     * 这不是假想场景：VSCode 侧 `sanitizeSkill` 写的是
     * `element: optionalString(data.element) || null`，空元素**就是** `"element": null`；
     * 对端 `characterData.ts:577` 的 `stringValue` 会兜底到角色元素，所以这里也必须兜底，
     * 否则同一个文件在两端显示不同（子仓会显示元素名 "null"）。
     *
     * 断言里刻意把**每一层**都覆盖到（角色 / 技能 / 效果引用 / 强化组），
     * 因为这个坑是逐个字段踩的，只测一个字段挡不住回归。
     */
    @Test
    fun `explicit JSON null never becomes the literal string null`() {
        val dir = createTempDirectory("ok-character-null").toFile()
        val skillsDir = dir.resolve("character_skills").apply { mkdirs() }
        skillsDir.resolve("nullish.json").writeText(
            """
            {
              "character_id": "null_char",
              "name": null,
              "element": "冰",
              "profession": null,
              "weapon_type": null,
              "wiki_item_id": null,
              "skills": [
                {
                  "skill_id": "null_char_s1",
                  "name": null,
                  "skill_type": null,
                  "element": null,
                  "description": null,
                  "damage_multiplier": null,
                  "cooldown": null,
                  "effects": [{"effect_id": null, "target": null}],
                  "enhancements": [
                    {
                      "name": "有效强化",
                      "enhancement_effect": null,
                      "trigger_condition": {"text": null, "effects": {"all": []}}
                    }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )

        val paths = CharacterDataPaths(
            projectDir = dir.absolutePath,
            masterFile = dir.resolve("characters.json").absolutePath,
            skillsDir = skillsDir.absolutePath,
            localeFile = dir.resolve("lang.json").absolutePath,
            effectsFile = dir.resolve("effects.py").absolutePath,
            effectNamesFile = dir.resolve("effect_names.json").absolutePath,
        )
        val result = CharacterDataService.load(paths, "zh_CN")

        val character = result.snapshot.characters.single()
        assertEquals("null_char", character.name, "name 为 null 应回退到 character_id，而不是字符串 \"null\"")
        assertEquals("冰", character.element)
        assertEquals("", character.profession, "profession 为 null 应视为空")
        assertEquals("", character.weaponType, "weapon_type 为 null 应视为空")
        assertEquals("", character.wikiItemId, "wiki_item_id 为 null 应视为空")

        val skill = character.skills.single()
        assertEquals("null_char_s1", skill.name, "技能 name 为 null 应回退到 skill_id")
        assertEquals("uncategorized", skill.skillType)
        assertEquals(
            "冰", skill.element,
            "技能 element 为 null 必须回退到角色元素 —— 这是实测踩到的那条，" +
                "原实现会把它显示成元素名 \"null\"",
        )
        assertEquals("", skill.description)
        assertEquals("", skill.damageMultiplier)
        assertEquals("", skill.cooldown)

        assertTrue(
            skill.effects.isEmpty(),
            "effect_id 为 null 的效果应被跳过，而不是生成一个 id 为 \"null\" 的假引用",
        )

        val enhancement = skill.enhancements.single()
        assertEquals("有效强化", enhancement.name, "有名字的强化组必须正常解析（别把 null 处理做过头）")
        assertEquals("", enhancement.enhancementEffect)
        assertEquals("", enhancement.triggerText, "trigger_condition.text 为 null 应视为空")

        // 兜底扫描：整份快照的任何字符串字段都不该出现字面量 "null"。
        val suspicious = listOf(
            character.name, character.element, character.profession,
            character.weaponType, character.wikiItemId,
            skill.name, skill.skillType, skill.element,
            skill.description, skill.damageMultiplier, skill.cooldown,
            enhancement.name, enhancement.enhancementEffect, enhancement.triggerText,
        ).filter { it == "null" }
        assertTrue(suspicious.isEmpty(), "不该有任何字段被读成字面量 \"null\"，实际有 ${suspicious.size} 个")
    }
}
