package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.core.TestRepoLayout
import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 静态 UI 审计（对应 VS Code 侧 scripts/test_design_system.js）：
 * - ui/ 与 tasklauncher/ 不得散写颜色字面量，统一走 OkTheme / TaskLauncherTheme；
 * - 按钮 / 标签不得用 Unicode 符号当图标（用 AllIcons）；
 * - plugin.xml 的每个工具窗口都有 6 语言的 stripe 标题。
 */
class UiAuditTest {
    private val sourceRoot = TestRepoLayout.locate("src/main/kotlin/com/alicejump/okscripttoolkit")
    private fun sources(pkg: String) = File(sourceRoot, pkg).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    private val themeFiles = setOf("OkTheme.kt", "TaskLauncherTheme.kt")

    private fun offenders(regex: Regex, packages: List<String>, skip: Set<String> = emptySet()): List<String> =
        packages.flatMap(::sources).filter { it.name !in skip }.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                val code = line.substringBefore("//")
                if (regex.containsMatchIn(code) && !code.trimStart().startsWith("*")) "${file.name}:${index + 1}: ${line.trim()}" else null
            }
        }

    @Test
    fun `no color literals outside the theme objects`() {
        val literal = Regex("""JBColor\(0x|(?<![\w.])Color\(\d|JBColor\.(GRAY|GREEN|RED|BLUE|ORANGE)\b|(?<![\w.])Color\.(WHITE|BLACK|RED|GRAY|GREEN)\b""")
        assertEquals(emptyList(), offenders(literal, listOf("ui", "tasklauncher"), themeFiles))
    }

    @Test
    fun `no unicode glyphs used as button or label icons`() {
        val glyph = Regex("""(JButton|JBLabel|JLabel|JToggleButton|ResourceCardAction)\("[^\p{L}\p{N}"\s]{1,2}"""")
        assertEquals(emptyList(), offenders(glyph, listOf("ui", "tasklauncher")))
    }

    @Test
    fun `no raw pixel sizes in UI packages`() {
        val raw = Regex("""(?<![\w.])(Dimension\(\d+, ?\d+\)|BorderFactory\.createEmptyBorder\(\d|Insets\(\d+, ?\d+, ?\d+, ?\d+\))""")
        assertEquals(emptyList(), offenders(raw, listOf("ui", "tasklauncher", "settings")))
    }

    @Test
    fun `every tool window has a localized stripe title in all locales`() {
        val xml = TestRepoLayout.locate("src/main/resources/META-INF/plugin.xml").readText()
        val ids = Regex("""<toolWindow\s[^>]*id="([^"]+)"""").findAll(xml).map { it.groupValues[1] }.toList()
        assertTrue(ids.isNotEmpty())
        val dir = TestRepoLayout.locate("src/main/resources/messages")
        for (suffix in listOf("", "_zh_CN", "_zh_TW", "_ja", "_ko", "_es")) {
            val props = Properties().apply { File(dir, "OkScriptToolkitBundle$suffix.properties").reader(Charsets.UTF_8).use(::load) }
            for (id in ids) {
                val key = "toolwindow.stripe." + id.replace(' ', '_')
                assertTrue(!props.getProperty(key).isNullOrBlank(), "$key missing in OkScriptToolkitBundle$suffix")
            }
        }
    }
}
