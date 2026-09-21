package com.alicejump.okscripttoolkit.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 枚举类名变更校验的测试（`LabelEnumGuard`）。
 *
 * 与 VS Code 侧 `scripts/test_label_enum_guard.js` 一一对应 —— 判据必须一致，
 * 否则两端会对同一份项目给出不同的结论。
 *
 * 背景：`labelEnum.name` 现在有「个人偏好」层（IDE 设置 `labelEnumName`），而这一项
 * **决定写进源码的类名**，项目的代码是按名字 import 的：
 *
 * ```python
 * from src.data.feature_list import FeatureList      # 项目里有 10 处这么写
 * ```
 *
 * 个人覆盖改错 → 整个项目 `ImportError`，而界面上唯一的反馈是「导出成功」。
 * 这个对象算的就是「这次覆盖会废掉多少处 import」，好在写入前问一句。
 *
 * ⚠️ **不断言文案里的词**：`renameMessage` 走 `OkScriptToolkitBundle`，取的是
 * `Locale.getDefault()` —— 断言英文单词会让测试随运行机器的语言静默变红。
 * 所以这里只断言**与语言无关的结构**（参数是否出现在文案里、有没有多出那一句）。
 * 键本身有没有、六个语言包对不对等，由 [BundleCoverageTest] / [BundleParityTest] 负责。
 */
class LabelEnumGuardTest {

    /** 我们真正会写出来的那种文件。 */
    private fun enumSource(className: String) =
        "from enum import Enum\n\n\nclass $className(str, Enum):\n    a = 'a'\n"

    // ── 1. 从源码里认类名 ────────────────────────────────────────────

    @Test
    fun `extractClassName recognises the files we generate`() {
        assertEquals("FeatureList", LabelEnumGuard.extractClassName(enumSource("FeatureList")))
        assertEquals("X", LabelEnumGuard.extractClassName("class X:"), "没有基类也能认")
        assertEquals("_Private", LabelEnumGuard.extractClassName("class _Private(Base):"), "下划线开头的类名")
        assertEquals(
            "Indented",
            LabelEnumGuard.extractClassName("    class Indented(Base):"),
            "允许缩进（模块级之外也可能有 class）",
        )
    }

    @Test
    fun `extractClassName returns null when there is nothing to find`() {
        assertNull(LabelEnumGuard.extractClassName("from enum import Enum\nx = 1\n"))
        assertNull(LabelEnumGuard.extractClassName(""), "空文件")
        assertNull(
            LabelEnumGuard.extractClassName("# class Commented:\n"),
            "**注释里的 class 不算** —— 行首必须是 class 本身，否则会把说明文字当成类名",
        )
        assertNull(
            LabelEnumGuard.extractClassName("class 2Fast(Base):"),
            "非法标识符（数字开头）不算 —— 认出来也没用，那种文件本来就不是我们生成的",
        )
    }

    // ── 2. 谁 import 了这个名字 ──────────────────────────────────────

    @Test
    fun `importsName covers the import shapes people actually write`() {
        assertTrue(LabelEnumGuard.importsName("from src.data.feature_list import FeatureList\n", "FeatureList"))
        assertTrue(
            LabelEnumGuard.importsName("from src.data.feature_list import FeatureList as fL\n", "FeatureList"),
            "带 as 别名也算 —— 别名绑定的是同一个类，改名同样会断",
        )
        assertTrue(LabelEnumGuard.importsName("from src.data.feature_list import (A, FeatureList)\n", "FeatureList"))
        assertTrue(LabelEnumGuard.importsName("import src.data.feature_list.FeatureList\n", "FeatureList"))
    }

    @Test
    fun `importsName respects word boundaries`() {
        assertFalse(
            LabelEnumGuard.importsName("from src.data.feature_list import FeatureList2\n", "FeatureList"),
            "**词边界必须成立** —— `FeatureList2` 不是 `FeatureList`，否则会到处误报",
        )
        assertFalse(
            LabelEnumGuard.importsName("x = FeatureList.foo\n", "FeatureList"),
            "只用到名字、没 import 的（`import ... as m` 那种）查不到 —— 已知的漏报，宁可漏不可乱报",
        )
        assertFalse(
            LabelEnumGuard.importsName("from src.data.feature_list import FeatureList\n", ""),
            "空名字一律不匹配（读不出旧类名时不该把所有文件都算成引用方）",
        )
    }

    @Test
    fun `importsName is deliberately over-inclusive`() {
        assertTrue(
            LabelEnumGuard.importsName("# from x import FeatureList\n", "FeatureList"),
            "**注释掉的 import 也算命中** —— 有意的：这是「问一句」的依据，多报一次比漏报一次便宜",
        )
        assertTrue(
            LabelEnumGuard.importsName("from x import List+Thing\n", "List+Thing"),
            "名字里有正则特殊字符也不会抛异常（内部做了转义）",
        )
    }

    // ── 3. 写进源码的类名 ────────────────────────────────────────────

    @Test
    fun `writableClassName falls back to a safe name for illegal identifiers`() {
        assertEquals("FeatureList", LabelEnumGuard.writableClassName("FeatureList"))
        assertEquals("_X1", LabelEnumGuard.writableClassName("_X1"), "下划线开头 + 数字也合法")
        assertEquals(
            "LabelEnum",
            LabelEnumGuard.writableClassName("2Bad"),
            "数字开头 → 兜底名（否则生成的文件是 SyntaxError）",
        )
        assertEquals("LabelEnum", LabelEnumGuard.writableClassName("洗手台"), "非 ASCII → 兜底名")
        assertEquals("LabelEnum", LabelEnumGuard.writableClassName(""), "空串 → 兜底名")
        assertEquals("LabelEnum", LabelEnumGuard.writableClassName("a b"), "含空格 → 兜底名")
        assertEquals(
            "LabelEnum",
            LabelEnumGuard.FALLBACK_ENUM_CLASS_NAME,
            "兜底名与 VS Code 侧同值 —— 两端生成同一个文件，名字不一致就是新的不对等",
        )
    }

    // ── 4. 要不要问 ──────────────────────────────────────────────────

    @Test
    fun `renameImpact stays quiet in the two routine cases`() {
        assertNull(
            LabelEnumGuard.renameImpact(null, "X"),
            "**文件不存在 → 不问** —— 全新生成，没有旧名字可废",
        )
        assertNull(
            LabelEnumGuard.renameImpact(enumSource("FeatureList"), "FeatureList"),
            "**同名 → 不问** —— 每次导出都会重新生成一遍，问了就是纯噪音",
        )
    }

    @Test
    fun `renameImpact asks when the class name would change`() {
        val impact = LabelEnumGuard.renameImpact(enumSource("FeatureList"), "MyEnum")
        assertNotNull(impact)
        assertEquals("FeatureList", impact.existingClassName, "报出旧名")
        assertEquals("MyEnum", impact.newClassName, "报出新名")
    }

    @Test
    fun `renameImpact asks when the target is not an enum file at all`() {
        val impact = LabelEnumGuard.renameImpact("x = 1\n", "FeatureList")
        assertNotNull(impact)
        assertEquals(
            "",
            impact.existingClassName,
            "**文件在、却认不出类名 → 也要问** —— 用户很可能把路径填到了一个普通模块上，覆盖会删掉里面的东西",
        )
    }

    // ── 5. 挑出引用方 ────────────────────────────────────────────────

    private val files = listOf(
        "src/tasks/farm.py" to "from src.data.feature_list import FeatureList\n",
        "src/main.py" to "from src.data.feature_list import FeatureList as fL\n",
        "src/other.py" to "import os\n",
        // 枚举文件自己**不算**引用方：它只有 `from enum import Enum`，不会 import 自己的类。
        "src/data/feature_list.py" to enumSource("FeatureList"),
    )

    @Test
    fun `referencingFiles picks the importers and sorts them`() {
        val hits = LabelEnumGuard.referencingFiles(files, "FeatureList")
        assertEquals(
            listOf("src/main.py", "src/tasks/farm.py"),
            hits,
            "**按路径排序** —— 提示文案里的顺序必须稳定，否则同一件事每次说的都不一样",
        )
        assertFalse(
            hits.contains("src/data/feature_list.py"),
            "枚举文件自身不算引用方（它不 import 自己）—— 否则文案里会出现「这个文件会被自己弄坏」",
        )
    }

    @Test
    fun `referencingFiles returns nothing when there is nothing to look for`() {
        assertTrue(LabelEnumGuard.referencingFiles(files, "").isEmpty(), "旧类名读不出来时不报任何引用方")
        assertTrue(
            LabelEnumGuard.referencingFiles(files, "Nonexistent").isEmpty(),
            "没人 import 的名字返回空列表 —— 文案会退化成「只改类名、不提引用」",
        )
    }

    // ── 6. 提示文案（只断言结构，不断言词）────────────────────────────

    @Test
    fun `renameMessage names both the old and the new class`() {
        val impact = LabelEnumGuard.RenameImpact("FeatureList", "MyEnum")
        val msg = LabelEnumGuard.renameMessage(impact, listOf("src/a.py", "src/b.py"))
        assertTrue(msg.contains("FeatureList") && msg.contains("MyEnum"), "文案里同时出现旧名与新名（用户得知道改成什么）")
        assertTrue(msg.contains("src/a.py") && msg.contains("src/b.py"), "列出受影响的文件")
    }

    @Test
    fun `renameMessage reports the total but lists only a few`() {
        val impact = LabelEnumGuard.RenameImpact("FeatureList", "MyEnum")
        val many = (0 until 40).map { "src/f$it.py" }
        val msg = LabelEnumGuard.renameMessage(impact, many)
        assertTrue(msg.contains("40"), "**总数照实报** —— 用户要的是「会炸多少处」，不是「前 5 个是谁」")
        assertTrue(msg.contains("src/f0.py") && !msg.contains("src/f39.py"), "列表只列前几个，避免撑爆对话框")
    }

    @Test
    fun `renameMessage only adds the references clause when there are references`() {
        val impact = LabelEnumGuard.RenameImpact("FeatureList", "MyEnum")
        val without = LabelEnumGuard.renameMessage(impact, emptyList())
        val with = LabelEnumGuard.renameMessage(impact, listOf("src/a.py"))
        assertTrue(
            with.length > without.length,
            "有引用方时才会多出那一句 —— 否则是一句永远不成立的恐吓",
        )
        assertTrue(
            with.startsWith(without),
            "没有引用方时只是**少了最后一段**，前半句完全一样 —— 证明它是拼接而不是另写一套",
        )
    }

    @Test
    fun `renameMessage says something different when the class could not be read`() {
        val recognized = LabelEnumGuard.renameMessage(LabelEnumGuard.RenameImpact("Old", "FeatureList"), emptyList())
        val unrecognized = LabelEnumGuard.renameMessage(LabelEnumGuard.RenameImpact("", "FeatureList"), emptyList())
        assertTrue(unrecognized.contains("FeatureList"), "仍然要说清会写入哪个类名")
        assertFalse(unrecognized.contains("Old"), "认不出类名时不能提旧名字（它根本不存在）")
        assertTrue(
            recognized != unrecognized,
            "两种情况必须给出不同的说法（一个说「改名」、一个说「内容会被覆盖」）",
        )
    }
}
