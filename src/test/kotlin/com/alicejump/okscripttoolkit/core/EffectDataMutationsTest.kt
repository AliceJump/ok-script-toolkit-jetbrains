package com.alicejump.okscripttoolkit.core

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `src/data/effects.py` 的改写。锚点结构取自 ok-end-field 的真实文件：
 * `class EffectType(Enum)` 里按 `    # 分类` 注释分组，`# 效果描述映射` 之后是
 * `EFFECT_DESCRIPTIONS`，`# 效果术语映射` 收尾。
 */
class EffectDataMutationsTest {

    private val fixture = """
        from enum import Enum


        class EffectType(Enum):
            # 元素附着
            ATTACH_COLD = "ATTACH_COLD"

            # 状态
            STUN = "STUN"


        # 效果描述映射
        EFFECT_DESCRIPTIONS: dict[EffectType, str] = {
            # 元素附着
            EffectType.ATTACH_COLD: "敌人被施加寒冷元素",

            # 状态
            EffectType.STUN: "眩晕",
        }


        # 效果术语映射：游戏文案中出现的术语 -> 效果ID
        EFFECT_TERMS = {}
        """.trimIndent()

    private fun writeFixture(): java.io.File {
        val dir = createTempDirectory("ok-effects-mutation").toFile()
        val file = dir.resolve("effects.py")
        file.writeText(fixture, Charsets.UTF_8)
        return file
    }

    private fun lines(file: java.io.File) = file.readText(Charsets.UTF_8).lines()

    /** List.indexOf 没有 fromIndex 重载，这里按行精确匹配往后找。 */
    private fun List<String>.indexOfAfter(needle: String, from: Int): Int =
        ((from + 1)..lastIndex).firstOrNull { this[it] == needle } ?: -1

    @Test
    fun `addEffect writes both the enum member and the description entry`() {
        val file = writeFixture()
        EffectDataMutations.addEffect(file.absolutePath, "ATTACH_FIRE", "敌人被施加火焰元素", "元素附着")
        val text = file.readText(Charsets.UTF_8)

        assertContains(text, "    ATTACH_FIRE = \"ATTACH_FIRE\"")
        assertContains(text, "    EffectType.ATTACH_FIRE: \"敌人被施加火焰元素\",")

        val out = lines(file)
        val enumGroup = out.indexOf("    # 元素附着")
        val enumMember = out.indexOf("    ATTACH_FIRE = \"ATTACH_FIRE\"")
        val enumCold = out.indexOf("    ATTACH_COLD = \"ATTACH_COLD\"")
        val enumStatus = out.indexOf("    # 状态")
        // 插在「元素附着」段内、紧跟已有成员之后，且不跨到下一个分类
        assertTrue(enumGroup < enumCold, "分类注释应在成员之前")
        assertTrue(enumCold < enumMember, "新成员应排在已有成员之后")
        assertTrue(enumMember < enumStatus, "新成员不应越过下一个分类注释")

        val mapGroup = out.indexOfAfter("    # 元素附着", enumStatus)
        val mapEntry = out.indexOf("    EffectType.ATTACH_FIRE: \"敌人被施加火焰元素\",")
        val mapCold = out.indexOf("    EffectType.ATTACH_COLD: \"敌人被施加寒冷元素\",")
        val mapStatus = out.indexOfAfter("    # 状态", mapGroup)
        assertTrue(mapGroup < mapCold && mapCold < mapEntry && mapEntry < mapStatus, "描述条目应落在同一分类段内")

        // 结尾 `}` 仍在描述字典收尾处，没有把结构写坏
        assertEquals("}", out[out.indexOf("    EffectType.STUN: \"眩晕\",") + 1].trim())
    }

    @Test
    fun `addEffect appends into a non-empty last category`() {
        val file = writeFixture()
        EffectDataMutations.addEffect(file.absolutePath, "SLOW", "移动速度下降", "状态")
        val out = lines(file)
        val stun = out.indexOf("    STUN = \"STUN\"")
        assertEquals("    SLOW = \"SLOW\"", out[stun + 1])
        val desc = out.indexOf("    EffectType.STUN: \"眩晕\",")
        assertEquals("    EffectType.SLOW: \"移动速度下降\",", out[desc + 1])
    }

    @Test
    fun `addEffect lowercases input and rejects invalid or duplicate ids`() {
        val file = writeFixture()
        EffectDataMutations.addEffect(file.absolutePath, " attach_fire ", "x", "元素附着")
        assertContains(file.readText(Charsets.UTF_8), "ATTACH_FIRE = \"ATTACH_FIRE\"")

        assertFailsWith<EffectDataMutations.MutationException> {
            EffectDataMutations.addEffect(file.absolutePath, "ATTACH_FIRE", "x", "元素附着")
        }
        assertFailsWith<EffectDataMutations.MutationException> {
            EffectDataMutations.addEffect(file.absolutePath, "1BAD", "x", "元素附着")
        }
        assertFailsWith<EffectDataMutations.MutationException> {
            EffectDataMutations.addEffect(file.absolutePath, "OK_ID", " ", "元素附着")
        }
    }

    @Test
    fun `addEffect rejects an unknown category`() {
        val file = writeFixture()
        assertFailsWith<EffectDataMutations.MutationException> {
            EffectDataMutations.addEffect(file.absolutePath, "BRAND_NEW", "x", "不存在的分类")
        }
    }

    @Test
    fun `addEffect refuses to touch a structurally broken effects file`() {
        val file = writeFixture()
        file.writeText("class EffectType(Enum):\n    A = \"A\"\n", Charsets.UTF_8)
        assertFailsWith<EffectDataMutations.MutationException> {
            EffectDataMutations.addEffect(file.absolutePath, "B", "x", "A")
        }
        assertEquals("class EffectType(Enum):\n    A = \"A\"\n", file.readText(Charsets.UTF_8))
    }

    @Test
    fun `addCategory inserts the comment into both the enum and the description map`() {
        val file = writeFixture()
        EffectDataMutations.addCategory(file.absolutePath, "新分类")
        val out = lines(file)

        val enumAt = out.indexOf("    # 新分类")
        assertTrue(enumAt >= 0, "枚举区应有新分类注释")
        assertTrue(enumAt < out.indexOf("# 效果描述映射"), "新分类应落在枚举区内")
        assertTrue(out.indexOf("    STUN = \"STUN\"") < enumAt, "新分类应排在最末成员之后")

        val mapAt = out.indexOfAfter("    # 新分类", enumAt + 1)
        assertTrue(mapAt >= 0, "描述映射区应有新分类注释")
        assertTrue(out.indexOf("EFFECT_DESCRIPTIONS") < mapAt, "第二个注释应落在描述映射区内")
        assertTrue(mapAt < out.indexOfFirst { it.startsWith("# 效果术语映射") }, "第二个注释不应越到术语区")
        assertEquals("}", out[mapAt + 1].trim(), "新分类注释应紧贴描述字典的收尾括号")
    }

    @Test
    fun `addCategory validates the name and rejects duplicates`() {
        val file = writeFixture()
        assertFailsWith<EffectDataMutations.MutationException> {
            EffectDataMutations.addCategory(file.absolutePath, "   ")
        }
        assertFailsWith<EffectDataMutations.MutationException> {
            EffectDataMutations.addCategory(file.absolutePath, "元素附着")
        }
        assertEquals(fixture, file.readText(Charsets.UTF_8))
    }

    @Test
    fun `a category added by addCategory is immediately usable by addEffect`() {
        val file = writeFixture()
        EffectDataMutations.addCategory(file.absolutePath, "增益")
        EffectDataMutations.addEffect(file.absolutePath, "ATK_UP", "攻击力提升", "增益")
        val text = file.readText(Charsets.UTF_8)
        assertContains(text, "    ATK_UP = \"ATK_UP\"")
        assertContains(text, "    EffectType.ATK_UP: \"攻击力提升\",")
    }

    @Test
    fun `atomicWriteText keeps a backup and replaces the target`() {
        val dir = createTempDirectory("ok-atomic-write").toFile()
        val file = dir.resolve("sample.txt").apply { writeText("old", Charsets.UTF_8) }
        EffectDataMutations.atomicWriteText(file.absolutePath, "new")
        assertEquals("new", file.readText(Charsets.UTF_8))
        assertEquals("old", file.parentFile.resolve("sample.txt.bak").readText(Charsets.UTF_8))
        assertTrue(dir.resolve("sample.txt.ok-script-toolkit.tmp").exists().not(), "临时文件应已清理")
    }
}
