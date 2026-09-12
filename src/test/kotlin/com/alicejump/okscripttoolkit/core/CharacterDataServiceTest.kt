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
}
