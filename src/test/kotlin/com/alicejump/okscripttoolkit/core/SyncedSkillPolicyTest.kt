package com.alicejump.okscripttoolkit.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 同步技能保护粒度的回归测试（P3-6）。
 *
 * 被测契约：**同步技能只锁标识/语义，不锁数值**。
 * 早先 JetBrains 侧是「更新同步技能整个抛错」，而 VSCode 允许改数值字段 ——
 * 同一个技能文件在两端能改的东西不一样，且用户看不出原因。
 *
 * 本文件同时覆盖纯规则（[SyncedSkillPolicy]）与真实写回
 * （[CharacterDataMutations.updateSkill]），因为这条规则的价值恰恰在于
 * "规则写对了、但没接到数据层"这种半吊子修复。
 */
class SyncedSkillPolicyTest {

    // ── 纯规则 ────────────────────────────────────────────────────────

    /**
     * 锁定清单必须与 VSCode 逐字一致。
     *
     * 参照物有两处，必须同时成立：
     * - `src/characterPanel.ts:399-403`（非 custom 时被还原的五个字段）
     * - `media/characterManager/app.js:247,251`（渲染成 readonlyControl 的字段）
     *
     * 这条断言是**契约钉子**：任何一端增删字段都会让它变红。
     * 没有它，两端就会像 P3-6 那样各自漂移而无人察觉。
     */
    @Test
    fun `locked fields match the VS Code list exactly`() {
        assertEquals(
            setOf("skill_id", "name", "skill_type", "element", "description"),
            SyncedSkillPolicy.LOCKED_FIELDS,
            "锁定清单必须与 VSCode 的 updateSkill 还原字段一一对应",
        )
    }

    /**
     * 数值与效果字段**绝不能**进锁定清单。
     *
     * 这正是 P3-6 的修复本体：同步数据不负责平衡性，倍率/失衡/冷却/技力消耗/效果
     * 是留给本地调参的。一旦有人"顺手"把它们也锁上，功能就退回修复前的状态。
     */
    @Test
    fun `numeric and effect fields are deliberately not locked`() {
        for (field in listOf("damage_multiplier", "stagger_value", "cooldown", "spirit_cost", "effects")) {
            assertFalse(
                field in SyncedSkillPolicy.LOCKED_FIELDS,
                "$field 必须可改 —— 同步技能唯一允许本地调整的就是数值与效果",
            )
        }
    }

    @Test
    fun `missing or false custom flag counts as synced`() {
        assertTrue(SyncedSkillPolicy.isSynced(custom = false), "标记为 false 即同步技能")
        assertFalse(SyncedSkillPolicy.isSynced(custom = true), "标记为 true 才是自定义技能")
    }

    @Test
    fun `synced form keeps only the editable fields`() {
        val form = mapOf(
            "name" to "新名字",
            "skill_type" to "终结",
            "element" to "冰",
            "description" to "改过的描述",
            "damage_multiplier" to "250%",
            "stagger_value" to "99",
            "cooldown" to "3s",
            "spirit_cost" to "40",
            "effects" to "[]",
        )

        val filtered = SyncedSkillPolicy.filterForm(form, synced = true)

        assertEquals(
            setOf("damage_multiplier", "stagger_value", "cooldown", "spirit_cost", "effects"),
            filtered.keys,
            "同步技能下只应剩下数值与效果字段",
        )
        assertEquals("250%", filtered["damage_multiplier"], "数值字段必须原样保留")
    }

    @Test
    fun `custom form passes through untouched`() {
        val form = mapOf("name" to "新名字", "damage_multiplier" to "250%")

        assertEquals(
            form,
            SyncedSkillPolicy.filterForm(form, synced = false),
            "自定义技能不做任何裁剪 —— 五个标识字段本来就该能改",
        )
    }

    /**
     * 破坏性对照：证明上面的裁剪断言不是"空过"。
     *
     * 若把实现写成"同步技能一律拒绝"（即过滤掉**全部**字段），
     * 数值字段就会消失 —— 那正是修复前的行为。
     * 这条断言用同一个输入对比两种实现，确认二者的差别真实存在。
     */
    @Test
    fun `regression guard - rejecting the whole form would lose the numeric edits`() {
        val form = mapOf("name" to "新名字", "damage_multiplier" to "250%")

        val rejectEverything = form.filterKeys { false }   // 修复前的等价行为
        val realBehaviour = SyncedSkillPolicy.filterForm(form, synced = true)

        assertEquals(emptyMap(), rejectEverything, "对照实现确实会丢掉全部字段（含数值）")
        assertEquals(
            mapOf("damage_multiplier" to "250%"),
            realBehaviour,
            "真实现必须保住数值字段，否则等于没修",
        )
    }

    // ── 真实写回（端到端）─────────────────────────────────────────────

    private fun skillFile(custom: Boolean): File {
        val dir = createTempDirectory("ok-skill-policy").toFile()
        val file = File(dir, "skills.json")
        val customFlag = if (custom) "\"_ok_lang_hints_custom\": true," else ""
        file.writeText(
            """
            {
              "character_id": "test_char",
              "skills": [
                {
                  $customFlag
                  "skill_id": "test_char_skill",
                  "name": "原始名称",
                  "skill_type": "技能",
                  "element": "火",
                  "description": "原始描述",
                  "damage_multiplier": "100%",
                  "stagger_value": 10,
                  "cooldown": "5s",
                  "spirit_cost": 20,
                  "effects": []
                }
              ]
            }
            """.trimIndent(),
            Charsets.UTF_8,
        )
        return file
    }

    private fun readSkill(file: File): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        val root = com.fasterxml.jackson.databind.ObjectMapper().readValue(file, Map::class.java) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val skills = root["skills"] as List<Map<String, Any?>>
        return skills.first()
    }

    /**
     * 端到端：改同步技能的数值 —— 数值落盘，标识字段原样。
     *
     * 这条是 P3-6 的用户可见行为：修复前它会抛 `is synced and locked`。
     */
    @Test
    fun `updating a synced skill applies numeric edits but keeps identity fields`() {
        val file = skillFile(custom = false)

        CharacterDataMutations.updateSkill(
            file.absolutePath,
            "test_char_skill",
            mapOf(
                "name" to "被篡改的名字",
                "skill_type" to "终结",
                "element" to "冰",
                "description" to "被篡改的描述",
                "damage_multiplier" to "250%",
                "stagger_value" to "99",
                "cooldown" to "3s",
                "spirit_cost" to "40",
                "effects" to "[]",
            ),
        )

        val skill = readSkill(file)
        assertEquals("250%", skill["damage_multiplier"], "倍率必须落盘 —— 这是同步技能唯一能调的部分")
        assertEquals(99, (skill["stagger_value"] as Number).toInt(), "失衡值必须落盘")
        assertEquals("3s", skill["cooldown"], "冷却必须落盘")
        assertEquals(40, (skill["spirit_cost"] as Number).toInt(), "技力消耗必须落盘")

        assertEquals("原始名称", skill["name"], "同步技能的名称受保护，不得被改写")
        assertEquals("技能", skill["skill_type"], "技能类型受保护")
        assertEquals("火", skill["element"], "元素受保护")
        assertEquals("原始描述", skill["description"], "描述受保护")
    }

    /**
     * 端到端：更新同步技能**不得**给它打上自定义标记。
     *
     * 原实现在 `updateSkill` 末尾无条件 `put("_ok_lang_hints_custom", true)`，
     * 因为前面必抛错而不可达；一旦放开限制就会把同步技能悄悄"洗白"成自定义，
     * 从此逃过后续所有同步保护。VSCode 侧不在 updateSkill 里写这个标记。
     */
    @Test
    fun `updating a synced skill must not mark it custom`() {
        val file = skillFile(custom = false)

        CharacterDataMutations.updateSkill(
            file.absolutePath,
            "test_char_skill",
            mapOf("damage_multiplier" to "250%", "effects" to "[]"),
        )

        assertNull(
            readSkill(file)["_ok_lang_hints_custom"],
            "同步技能被更新后仍须保持同步状态 —— 否则它会被静默转成自定义技能",
        )
    }

    /** 端到端：自定义技能仍可改标识字段（回归保护，别把范围修窄了）。 */
    @Test
    fun `updating a custom skill still allows renaming`() {
        val file = skillFile(custom = true)

        CharacterDataMutations.updateSkill(
            file.absolutePath,
            "test_char_skill",
            mapOf("name" to "新名称", "damage_multiplier" to "300%", "effects" to "[]"),
        )

        val skill = readSkill(file)
        assertEquals("新名称", skill["name"], "自定义技能必须能改名")
        assertEquals("300%", skill["damage_multiplier"], "自定义技能的数值同样可改")
    }
}
