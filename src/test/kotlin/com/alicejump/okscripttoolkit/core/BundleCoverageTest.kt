package com.alicejump.okscripttoolkit.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 源码里引用的 bundle 键**必须都在语言包里**。
 *
 * 为什么需要（这是 [BundleParityTest] 覆盖不到的另一半）：对等性只保证
 * "6 个语言包键集相同"。如果某个 `OkScriptToolkitBundle.message("...")` 的键
 * 压根没进任何一个包，6 个包依然彼此对等、CI 依然全绿，而运行时
 * `ResourceBundle.getString(key)` 会抛 `MissingResourceException`（缺键不会回落英文，
 * 那是**别的**语言包缺键时的行为）—— 用户看到的是一个崩掉的对话框。
 *
 * 与 VS Code 侧 `scripts/release/verify-l10n.js` 的 §覆盖率 是同一条检查，两端对称。
 *
 * **只查字面量**：`message(key)` 这类"键由参数传进来"的写法静态查不到，不能假装查过
 * （本仓库有几处 helper 是这种形态，它们的键由调用点给出，由本测试在调用点处覆盖）。
 */
class BundleCoverageTest {

    /**
     * 键的形态：小写开头的点分标识符，如 `characterManager.fieldSkillId`。
     *
     * 用形态过滤是为了从 `message(...)` 的实参窗口里挑出**键**，
     * 而不误抓同样出现在窗口里的普通参数（那些通常不含点）。
     */
    private val keyPattern = Regex("^[a-z][A-Za-z0-9]*(\\.[A-Za-z0-9]+)+$")

    private val sourceRoot: File by lazy {
        val relative = "src/main/kotlin"
        var current: File? = File("").absoluteFile
        val candidates = mutableListOf<File>()
        while (current != null) {
            candidates += File(current, relative)
            candidates += File(current, "jetbrains/$relative")
            current = current.parentFile
        }
        candidates.firstOrNull { it.isDirectory }
            ?: throw AssertionError(
                "找不到源码目录。已尝试：\n" + candidates.joinToString("\n") { "  ${it.path}" },
            )
    }

    private fun kotlinFiles(): List<File> {
        val result = mutableListOf<File>()
        fun walk(dir: File) {
            dir.listFiles()?.sorted()?.forEach { file ->
                if (file.isDirectory) walk(file) else if (file.name.endsWith(".kt")) result += file
            }
        }
        walk(sourceRoot)
        return result
    }

    /**
     * 从一个 `message(` 起点开始，取到配平的右括号，返回这段区间里所有像键的字面量。
     *
     * 之所以不用"紧跟左括号的第一个字符串"，是因为仓库里有
     * `message(if (isTrigger) "taskLauncher.xxx" else "taskLauncher.yyy", ...)` 这种写法 ——
     * 只抓第一个会漏掉 `else` 那一支（而漏掉的键正是最容易写错的那个）。
     */
    private fun keysInCall(source: String, openParen: Int): List<String> {
        val found = mutableListOf<String>()
        var depth = 0
        var i = openParen
        var inString = false
        var escaped = false
        var stringStart = -1
        while (i < source.length) {
            val c = source[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> {
                    if (inString) {
                        val literal = source.substring(stringStart + 1, i)
                        if (keyPattern.matches(literal)) found += literal
                        inString = false
                    } else {
                        inString = true
                        stringStart = i
                    }
                }
                !inString && c == '(' -> depth++
                !inString && c == ')' -> {
                    depth--
                    if (depth == 0) return found
                }
            }
            i++
        }
        return found
    }

    @Test
    fun `every bundle key referenced from source exists in the base bundle`() {
        val baseKeys = TestMessages.loadKeys(TestMessages.BASE_NAME)
        assertTrue(baseKeys.isNotEmpty(), "基准 bundle 不应为空")

        val marker = "OkScriptToolkitBundle.message("
        val referenced = mutableSetOf<String>()
        val missing = mutableListOf<String>()
        var calls = 0

        for (file in kotlinFiles()) {
            val source = file.readText()
            var index = source.indexOf(marker)
            while (index >= 0) {
                calls++
                val openParen = index + marker.length - 1
                for (key in keysInCall(source, openParen)) {
                    referenced += key
                    if (key !in baseKeys) {
                        missing += "${file.relativeTo(sourceRoot).invariantSeparatorsPath} -> $key"
                    }
                }
                index = source.indexOf(marker, index + marker.length)
            }
        }

        // 恒真的断言比没有断言更糟：先确认扫描真的找到了东西
        assertTrue(calls > 100, "扫描到的 message() 调用只有 $calls 处，定位源码的逻辑可能失效了")
        assertTrue(referenced.isNotEmpty(), "一个键都没提取到，说明键的形态判断失效了")

        assertTrue(
            missing.isEmpty(),
            "以下键被源码引用，但不在 ${TestMessages.BASE_NAME} 里（运行时 getString 会抛 MissingResourceException）：\n" +
                missing.sorted().joinToString("\n") { "  ✗ $it" },
        )
    }

    /**
     * 破坏性对照：证明键的形态判断**真的能区分**键与非键，
     * 而不是把所有字面量都当键（那样会把普通参数误报成缺键）。
     */
    @Test
    fun `regression guard - the key shape tells keys apart from plain arguments`() {
        assertTrue(keyPattern.matches("characterManager.fieldSkillId"), "点分小写标识符是键")
        assertTrue(keyPattern.matches("action.conventionSources.text"), "多层点分同样是键")
        assertTrue(!keyPattern.matches("assets/lang"), "路径不是键（含斜杠）")
        assertTrue(!keyPattern.matches("Some Label"), "自然语言文案不是键")
        assertTrue(!keyPattern.matches("wgc"), "单个词不是键（缺命名空间）")
    }

    /**
     * 破坏性对照：证明 [keysInCall] 的配平扫描**真的会走到 `else` 分支**，
     * 而不是只抓第一个字面量就返回。
     */
    @Test
    fun `regression guard - a conditional key argument is scanned on both branches`() {
        val sample = """OkScriptToolkitBundle.message(
                if (isTrigger) "taskLauncher.triggerEnabled" else "taskLauncher.oneTimeEnabled",
            )"""
        val keys = keysInCall(sample, sample.indexOf('('))
        assertTrue(
            keys.containsAll(listOf("taskLauncher.triggerEnabled", "taskLauncher.oneTimeEnabled")),
            "条件表达式的两个分支都要抓到，实际：$keys",
        )
    }
}
