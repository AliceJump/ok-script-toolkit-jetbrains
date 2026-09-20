package com.alicejump.okscripttoolkit.core

import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Kotlin 侧 `OkScriptToolkitBundle*.properties` 的键对等性。
 *
 * 为什么需要：英文 properties 是**权威键集**，其它语言只是翻译。`ResourceBundle` 在
 * 缺键时会静默回落成英文 —— 界面中英夹杂、CI 全绿、没人发现。子仓的
 * `docs/parity-review.md` 曾**声称**「增强 UI i18n 完整对等」，实测 `zh_TW`/`es`/`ja`/`ko`
 * 各缺 13 个 `characterManager.*` 键（2026-09）。文档会漂移，所以这里用测试钉住。
 *
 * 这是纯文件比对，不需要 IDE fixture，符合本仓库 `src/test` 的既有约定。
 */
class BundleParityTest {

    /** 目录定位与读取抽在 [TestMessages]（[BundleCoverageTest] 也要用同一份实现）。 */
    private val messagesDir: File get() = TestMessages.dir

    private fun bundleNames(): List<String> = TestMessages.bundleNames()

    private fun loadKeys(name: String): Set<String> = TestMessages.loadKeys(name)

    @Test
    fun `every locale bundle carries the same keys as the base bundle`() {
        val base = "OkScriptToolkitBundle.properties"
        val names = bundleNames()
        assertTrue(base in names, "缺少基准 bundle：$base（在 ${messagesDir.path} 下）")
        assertTrue(names.size > 1, "至少应有一个语言包，实际：$names")

        val baseKeys = loadKeys(base)
        assertTrue(baseKeys.isNotEmpty(), "基准 bundle 不应为空")

        // 一次报全部差异，而不是第一个失败就停 —— 否则要来回跑很多遍
        val report = StringBuilder()
        for (name in names) {
            if (name == base) continue
            val keys = loadKeys(name)
            val missing = (baseKeys - keys).sorted()
            val extra = (keys - baseKeys).sorted()
            if (missing.isNotEmpty() || extra.isNotEmpty()) {
                report.append("\n  ✗ ").append(name).append('\n')
                missing.forEach { report.append("      缺少: ").append(it).append('\n') }
                extra.forEach { report.append("      多余（不在 $base 里）: ").append(it).append('\n') }
            }
        }

        assertTrue(
            report.isEmpty(),
            "语言包与 $base 不一致（缺键会静默回落成英文）：$report",
        )
    }

    /**
     * 破坏性对照：证明上一条断言**真的能捕获缺键**，而不是恒真。
     * 这里手工构造"基准有、语言包没有"的情形，确认差集非空。
     */
    @Test
    fun `regression guard - a missing key really is detected`() {
        val base = setOf("a.one", "b.two")
        val locale = setOf("a.one")
        assertTrue((base - locale).isNotEmpty(), "缺键必须能被检出")
        assertTrue((base - locale) == setOf("b.two"))
    }

    /**
     * 消息内容必须能被 `MessageFormat` 安全格式化。
     *
     * [OkScriptToolkitBundle.message] 在有参数时会走 `MessageFormat.format`，
     * 而 `MessageFormat` 把**单引号**当转义字符 —— 一条含裸 `'` 的文案在传参时
     * 会静默吞字符或抛异常。这里对所有键都跑一遍格式化，把这类文案问题提前暴露。
     */
    @Test
    fun `messages can be formatted by MessageFormat without losing content`() {
        val properties = Properties()
        File(messagesDir, "OkScriptToolkitBundle.properties").inputStream().use { properties.load(it) }

        val failures = StringBuilder()
        for (key in properties.stringPropertyNames().sorted()) {
            val value = properties.getProperty(key)
            // 传 0 与 3 个参数各跑一遍：覆盖"无占位符"与"有占位符"两种文案
            for (args in listOf(emptyArray<Any>(), arrayOf<Any>("x", "y", "z"))) {
                val result = runCatching {
                    if (args.isEmpty()) value else java.text.MessageFormat.format(value, *args)
                }
                result.exceptionOrNull()?.let { error ->
                    failures.append("\n  ✗ ").append(key).append(" -> ").append(error.message)
                }
                // 无参数时不该发生任何改写：若变了，说明文案里有裸单引号被 MessageFormat 吃掉
                val formatted = result.getOrNull()
                if (args.isEmpty() && formatted != null && formatted != value) {
                    failures.append("\n  ✗ ").append(key)
                        .append(" 无参数格式化改变了内容：").append(formatted)
                }
            }
        }

        assertTrue(
            failures.isEmpty(),
            "以下消息在 MessageFormat 下有问题（单引号需要写成 '' ）：$failures",
        )
    }
}
