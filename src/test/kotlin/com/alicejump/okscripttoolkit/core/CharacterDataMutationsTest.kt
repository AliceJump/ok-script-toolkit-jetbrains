package com.alicejump.okscripttoolkit.core

import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 技能 JSON 的写回。重点盯住强化组：真实数据（ok-end-field 79 条强化）里
 * `effects`（产出效果，79/79）、`enhancement_visible_pulse`（46/79）、
 * `trigger_condition.effects`（一律 `{all|any: [id...]}`）都是全量字段，
 * 早先按空节点重建会把它们整片抹掉。
 */
class CharacterDataMutationsTest {

    private val JSON = ObjectMapper()

    private val skillJson = """
        {
          "character_id": "yi_feng",
          "name": "伊冯",
          "skills": [
            {
              "skill_id": "yi_feng_s1",
              "name": "霜噬",
              "skill_type": "主动",
              "effects": [{"effect_id": "ATTACH_COLD"}],
              "enhancements": [
                {
                  "name": "秘杖·矩阵位移",
                  "trigger_condition": {
                    "text": "当有敌人被施加法术异常时可以发动",
                    "effects": {"all": ["STATUS_SPELL_ANOMALY"]}
                  },
                  "effects": [
                    {"effect_id": "STATUS_HEAVY_HIT", "value": 1, "duration": null, "target": "enemy", "count": 1}
                  ],
                  "enhancement_effect": "位移一段距离",
                  "enhancement_visible_pulse": true,
                  "custom_extra": "keep me"
                }
              ]
            }
          ]
        }
    """.trimIndent()

    private fun writeSkill(): java.io.File {
        val dir = createTempDirectory("ok-character-mutations").toFile()
        return dir.resolve("yvonne.json").apply { writeText(skillJson, Charsets.UTF_8) }
    }

    private fun enhancementOf(file: java.io.File) = JSON.readTree(file)
        .get("skills").get(0).get("enhancements").get(0)

    /** 只改名称，其余字段必须原样保留。 */
    @Test
    fun `updateEnhancement keeps the output effects, pulse and unknown fields`() {
        val file = writeSkill()
        CharacterDataMutations.updateEnhancement(
            file.absolutePath, "yi_feng_s1", 0,
            mapOf(
                "name" to "秘杖·矩阵位移（改）",
                "trigger_text" to "当有敌人被施加法术异常时可以发动",
                "trigger_effects" to "STATUS_SPELL_ANOMALY",
                "trigger_effect_mode" to "all",
                "enhancement_effect" to "位移一段距离",
                "effects" to "STATUS_HEAVY_HIT",
                "visible_pulse" to "true",
            ),
        )
        val node = enhancementOf(file)
        assertEquals("秘杖·矩阵位移（改）", node.get("name").asText())
        assertEquals("keep me", node.get("custom_extra").asText(), "未知字段要保留")
        assertTrue(node.get("enhancement_visible_pulse").asBoolean(), "可见脉冲不能被重置")
        assertEquals(
            "STATUS_HEAVY_HIT",
            node.get("effects").get(0).get("effect_id").asText(),
            "产出效果不能被抹掉",
        )
        assertEquals(1, node.get("effects").get(0).get("value").asInt(), "产出效果的附加字段要保留")
        assertEquals("enemy", node.get("effects").get(0).get("target").asText())
        val trigger = node.get("trigger_condition").get("effects")
        assertTrue(trigger.has("all"), "触发条件要保持 {all|any: [...]} 形态")
        assertEquals("STATUS_SPELL_ANOMALY", trigger.get("all").get(0).asText())
    }

    @Test
    fun `updateEnhancement rewrites the trigger mode and drops nothing else`() {
        val file = writeSkill()
        CharacterDataMutations.updateEnhancement(
            file.absolutePath, "yi_feng_s1", 0,
            mapOf(
                "name" to "改模式",
                "trigger_text" to "t",
                "trigger_effects" to "A, B",
                "trigger_effect_mode" to "any",
                "effects" to "OUT_1",
            ),
        )
        val node = enhancementOf(file)
        val trigger = node.get("trigger_condition").get("effects")
        assertTrue(trigger.has("any") && !trigger.has("all"), "切到 any 后不应残留 all")
        assertEquals(listOf("A", "B"), trigger.get("any").map { it.asText() })
        assertEquals("OUT_1", node.get("effects").get(0).get("effect_id").asText())
        // 对话框里没带的可见脉冲这次确实会关掉 —— 它是表单字段，不是隐藏数据
        assertEquals(false, node.get("enhancement_visible_pulse").asBoolean())
    }

    @Test
    fun `updateEnhancement accepts an out-of-range index only by failing`() {
        val file = writeSkill()
        assertFailsWith<CharacterDataMutations.MutationException> {
            CharacterDataMutations.updateEnhancement(
                file.absolutePath, "yi_feng_s1", 3, mapOf("name" to "x"),
            )
        }
        assertFailsWith<CharacterDataMutations.MutationException> {
            CharacterDataMutations.updateEnhancement(
                file.absolutePath, "missing_skill", 0, mapOf("name" to "x"),
            )
        }
        assertEquals("秘杖·矩阵位移", enhancementOf(file).get("name").asText(), "失败时不应写坏文件")
    }

    @Test
    fun `addEnhancement appends a well-formed node`() {
        val file = writeSkill()
        CharacterDataMutations.addEnhancement(
            file.absolutePath, "yi_feng_s1",
            mapOf(
                "name" to "新增强化",
                "trigger_text" to "触发",
                "trigger_effects" to "STUN",
                "trigger_effect_mode" to "all",
                "enhancement_effect" to "效果",
                "effects" to "SLOW",
                "visible_pulse" to "false",
            ),
        )
        val list = JSON.readTree(file).get("skills").get(0).get("enhancements")
        assertEquals(2, list.size())
        val added = list.get(1)
        assertEquals("新增强化", added.get("name").asText())
        assertEquals("STUN", added.get("trigger_condition").get("effects").get("all").get(0).asText())
        assertEquals("SLOW", added.get("effects").get(0).get("effect_id").asText())
        assertTrue(JSON.readTree(file).get("skills").get(0).get("has_enhancement").asBoolean())
    }

    @Test
    fun `deleteEnhancement clears has_enhancement when the list empties`() {
        val file = writeSkill()
        CharacterDataMutations.deleteEnhancement(file.absolutePath, "yi_feng_s1", 0)
        val skill = JSON.readTree(file).get("skills").get(0)
        assertEquals(0, skill.get("enhancements").size())
        assertEquals(false, skill.get("has_enhancement").asBoolean())
    }
}
