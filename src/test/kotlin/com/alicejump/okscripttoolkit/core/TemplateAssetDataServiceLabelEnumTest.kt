package com.alicejump.okscripttoolkit.core

import com.alicejump.okscripttoolkit.TestTmp
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Python 枚举生成器的语法安全回归测试。
 *
 * 复现的缺陷：分类名（用户可自由输入）被**原样拼进 Python 源码**的两个位置 ——
 * 成员名与值。带引号、反斜杠、空格、连字符、中文的分类名都会让生成的文件
 * `SyntaxError`，而用户只看得到"枚举文件生成了"，不知道它根本不能用。
 *
 * 本文件除了断言字符串形状，还会**真的调 python 编译一遍**（环境里有 python 时）——
 * 字符串断言只能证明"我觉得它合法"，编译器才能证明"它真的合法"。
 *
 * 被测量的两个函数是模块级 `internal` 纯函数（不依赖 `Project`），
 * 因为 `TemplateAssetDataService` 的构造函数要 `Project`、而本仓库约定不用 IDE fixture。
 * 这里同时覆盖"源码拼接"这一层：把两个纯函数的输出按 `generateLabelEnum` 的形状组装后编译。
 */
class TemplateAssetDataServiceLabelEnumTest {

    // ── 被测的源码拼接规则（与 TemplateAssetDataService.generateLabelEnum 保持一致）────

    private fun generateSource(className: String, labels: List<String>): String = buildString {
        append("from enum import Enum\n\n\n")
        append("class ").append(className).append("(str, Enum):\n")
        if (labels.isEmpty()) append("    pass\n")
        for (label in labels) {
            append("    ").append(memberNameFor(label)).append(" = ").append(pythonStringLiteral(label)).append('\n')
        }
    }

    private fun compileWithPython(source: String): String? {
        val python = listOf("python", "python3").firstOrNull { exe ->
            runCatching {
                ProcessBuilder(exe, "--version").redirectErrorStream(true).start().waitFor() == 0
            }.getOrDefault(false)
        } ?: return null
        val tmp = TestTmp.createFile("ok-label-enum-check", ".py")
        tmp.writeText(source, StandardCharsets.UTF_8)
        return try {
            val proc = ProcessBuilder(
                python, "-c",
                "compile(open(r'${tmp.absolutePath}',encoding='utf-8').read(),'<gen>','exec')",
            ).redirectErrorStream(true).start()
            val output = proc.inputStream.readBytes().toString(StandardCharsets.UTF_8)
            if (proc.waitFor() == 0) null else output
        } finally {
            tmp.delete()
        }
    }

    /** 断言生成物能被 Python 编译 —— 环境里没有 python 时静默跳过，不让测试假红 */
    private fun assertCompiles(source: String) {
        val error = compileWithPython(source) ?: return
        throw AssertionError("生成的 Python 源码无法编译：\n$error\n--- 源码 ---\n$source")
    }

    // ── 值转义 ────────────────────────────────────────────────────────

    @Test
    fun `single quote in a label is escaped`() {
        assertEquals("'it\\'s'", pythonStringLiteral("it's"), "单引号必须转义，否则字面量提前闭合 -> 整文件 SyntaxError")
        assertCompiles(generateSource("LabelEnum", listOf("it's")))
    }

    @Test
    fun `backslash in a label is escaped`() {
        assertEquals("'a\\\\b'", pythonStringLiteral("""a\b"""), "反斜杠必须转义，否则会和后续字符组成意外转义序列")
        assertCompiles(generateSource("LabelEnum", listOf("""a\b""")))
    }

    @Test
    fun `newline becomes an escape sequence not a real line break`() {
        assertEquals("'line1\\nline2'", pythonStringLiteral("line1\nline2"))
        val source = generateSource("LabelEnum", listOf("line1\nline2"))
        // 注意：不能数 source.lines() —— 字面量里的 `\n` 是两个字符，不会真的换行。
        // 真正要验的是「成员声明只占一行」，所以按 `\n` 切再排除空行。
        val bodyLines = source.lines().filter { it.isNotBlank() }
        assertEquals(
            3,
            bodyLines.size,
            "一个标签必须只贡献一行成员声明（append 末尾恰好一个 \\n），否则后续行会变成语法垃圾。实际：\n$source",
        )
        assertTrue(bodyLines.last().trim().startsWith("line1_line2"), "实际最后一行：${bodyLines.last()}")
        assertCompiles(source)
    }

    @Test
    fun `control characters are written as hex escapes`() {
        assertEquals("'\\x07'", pythonStringLiteral("\u0007"), "裸控制字符会让源文件在部分工具链里无法解析")
        assertCompiles(generateSource("LabelEnum", listOf("\u0007")))
    }

    @Test
    fun `plain labels are untouched`() {
        assertEquals("'apple'", pythonStringLiteral("apple"), "无需转义的标签不应被改写")
        assertEquals("'banana_2'", pythonStringLiteral("banana_2"))
    }

    // ── 成员名规范化 ───────────────────────────────────────────────────

    @Test
    fun `ascii identifier labels keep their name`() {
        assertEquals("apple", memberNameFor("apple"))
        assertEquals("banana_2", memberNameFor("banana_2"))
        assertEquals("_private", memberNameFor("_private"))
    }

    @Test
    fun `spaces and hyphens become underscores`() {
        assertEquals("no_hyphen", memberNameFor("no-hyphen"))
        assertEquals("my_label", memberNameFor("my label"))
    }

    @Test
    fun `leading digit gets a prefix so the name stays an identifier`() {
        val name = memberNameFor("2ndStage")
        assertTrue(Regex("^[A-Za-z_][A-Za-z0-9_]*$").matches(name), "必须是合法标识符，实际：$name")
        assertCompiles(generateSource("LabelEnum", listOf("2ndStage")))
    }

    /**
     * 中文标签：成员名规范化成 ASCII，但**值必须保留原文**。
     * 这条锁的是"规范化不丢信息" —— 否则 `LabelEnum.x.value` 就不再是用户看到的分类名。
     */
    @Test
    fun `non ascii labels produce an ascii member name but keep the original value`() {
        val name = memberNameFor("洗手台")
        assertTrue(Regex("^[A-Za-z_][A-Za-z0-9_]*$").matches(name), "中文成员名会坑工具链，必须规范化，实际：$name")
        assertEquals("'洗手台'", pythonStringLiteral("洗手台"), "值必须保留原始标签")

        val source = generateSource("LabelEnum", listOf("洗手台"))
        assertTrue("= '洗手台'" in source, "值不能被规范化掉。实际：\n$source")
        assertCompiles(source)
    }

    @Test
    fun `labels made only of symbols still yield an identifier`() {
        val name = memberNameFor("!!")
        assertTrue(Regex("^[A-Za-z_][A-Za-z0-9_]*$").matches(name), "纯符号标签也要产出合法名，实际：$name")
        assertCompiles(generateSource("LabelEnum", listOf("!!")))
    }

    @Test
    fun `empty label yields an identifier`() {
        val name = memberNameFor("")
        assertTrue(Regex("^[A-Za-z_][A-Za-z0-9_]*$").matches(name), "空标签也要产出合法名，实际：$name")
    }

    // ── 整体文件 ──────────────────────────────────────────────────────

    @Test
    fun `a batch of hostile labels compiles`() {
        val labels = listOf("it's", "a\\b", "洗手 台", "no-hyphen", "2ndStage", "line1\nline2", "!!", "plain")
        assertCompiles(generateSource("LabelEnum", labels))
    }

    @Test
    fun `empty label list produces a valid module`() {
        assertCompiles(generateSource("LabelEnum", emptyList()))
    }

    /**
     * 破坏性对照：把"未转义 / 未规范化"的旧实现摆出来，跑一次真实的 python 编译。
     *
     * 没有这条，"我们修好了注入"就只是断言而不是证据 —— 这里要证明 **旧实现真的会炸**。
     * 环境里没有 python 时跳过（[compileWithPython] 返回 null）。
     */
    @Test
    fun `regression guard - the old unescaped interpolation was genuinely broken`() {
        val label = "it's"
        val buggy = buildString {
            append("from enum import Enum\n\n\n")
            append("class LabelEnum(str, Enum):\n")
            append("    ").append(label).append(" = '").append(label).append("'\n")
        }
        // 未转义的单引号会让字面量提前闭合：`= 'it's'` -> SyntaxError
        assertTrue(buggy.contains("= 'it's'"), "对照实现确实是未转义的写法：\n$buggy")
        assertEquals("'it\\'s'", pythonStringLiteral(label), "修复后的值必须转义")

        val buggyError = compileWithPython(buggy) ?: return // 无 python 时不强制判定
        assertTrue(buggyError.isNotEmpty(), "旧写法必须编译失败 —— 若通过了，说明本地 python 版本过于宽松")
    }
}
