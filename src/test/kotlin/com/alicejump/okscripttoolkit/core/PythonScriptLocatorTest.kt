package com.alicejump.okscripttoolkit.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 打包脚本解压的回归测试。
 *
 * 盯的是一个真实踩过的坑（2026-09-20）：旧实现把
 * `classLoader.getResource(...).openConnection().lastModified` 当版本戳拼进目录名，
 * 但 **JAR 内资源的 `lastModified` 恒为 0** —— 目录名恒为
 * `ok-script-toolkit-scripts-0`，再加上「8 个文件都在就直接 return」，
 * 结果**一旦解压过，插件升级后再也不会重新解压**。
 *
 * 实测后果：用户装的 1.7.1 里 `run_executor.py` 是「配置沙箱」提交之前的版本，
 * 装上含沙箱的新插件也没用 —— 临时目录里的旧脚本继续跑，**沙箱完全没生效**，
 * 执行器照样写目标项目的 `configs/`。
 *
 * 所以这里的核心断言是：**篡改过的脚本必须在下一次调用时被恢复**。
 */
class PythonScriptLocatorTest {

    /** 读取 classpath 上打包脚本的原始内容，作为"应该被解压成什么"的基准。 */
    private fun bundledText(name: String): String? =
        PythonScriptLocator::class.java.classLoader
            .getResourceAsStream("python/$name")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }

    @Test
    fun `extracts every bundled script into a stable directory`() {
        val base = createTempDirectory("ok-scripts-test").toFile()

        val dir = PythonScriptLocator.extractBundledScripts(base)
        assertNotNull(dir, "classpath 上有 python/*.py 时必须解压成功（build 里由 copyPythonScripts 提供）")

        assertEquals(
            PythonScriptLocator.SCRIPT_DIR_NAME, dir.fileName.toString(),
            "目录名必须稳定且**不含时间戳** —— 带时间戳时它恒为 -0，正是旧 bug 的成因",
        )
        assertTrue(dir.parent.toFile() == base, "必须解压到传入的 baseDir 下（单测靠它隔离，不碰真实临时目录）")

        for (name in PythonScriptLocator.BUNDLED_SCRIPTS) {
            assertTrue(
                dir.resolve(name).toFile().isFile,
                "打包脚本 $name 必须被解压出来（BUNDLED_SCRIPTS 与父仓 python/ 目录保持一致）",
            )
        }
    }

    /**
     * 核心回归断言：**篡改过的脚本必须被重新覆盖**。
     *
     * 旧实现会因为"文件都在"直接 return，篡改内容会一直留着 ——
     * 这正是"插件升级后仍跑旧脚本"的等价场景（把"篡改"换成"新版内容不同"）。
     */
    @Test
    fun `a tampered script is overwritten on the next call`() {
        val base = createTempDirectory("ok-scripts-tamper").toFile()
        val target = "run_executor.py"
        val original = bundledText(target)
        assertNotNull(original, "基准内容必须可读，否则这条断言没有意义")

        val dir = PythonScriptLocator.extractBundledScripts(base)
        assertNotNull(dir)
        val file = dir.resolve(target).toFile()

        val tampered = "# STALE — 模拟旧版插件解压出来的脚本\n"
        file.writeText(tampered, Charsets.UTF_8)
        assertEquals(tampered, file.readText(Charsets.UTF_8), "前置条件：篡改确实写进去了")

        PythonScriptLocator.extractBundledScripts(base)

        assertNotEquals(
            tampered, file.readText(Charsets.UTF_8),
            "第二次调用必须覆盖写出 —— 旧实现会因「文件都在」而跳过，把旧脚本一直留在盘上",
        )
        assertEquals(
            original, file.readText(Charsets.UTF_8),
            "恢复后的内容必须与 classpath 上的打包脚本逐字节一致",
        )
    }

    /** 历史遗留目录（旧版的时间戳命名）要被清掉，否则它会一直占着旧脚本。 */
    @Test
    fun `legacy timestamped directories are cleaned up`() {
        val base = createTempDirectory("ok-scripts-legacy").toFile()
        val legacy = File(base, "ok-script-toolkit-scripts-0").apply { mkdirs() }
        File(legacy, "run_executor.py").writeText("# 旧版留下的脚本\n", Charsets.UTF_8)

        PythonScriptLocator.extractBundledScripts(base)

        assertTrue(
            !legacy.exists(),
            "旧的时间戳目录必须被删除 —— 否则「升级插件却仍跑旧脚本」会继续发生",
        )
        assertTrue(
            File(base, PythonScriptLocator.SCRIPT_DIR_NAME).isDirectory,
            "当前使用的目录必须保留",
        )
    }

    /** 多次调用必须幂等：内容始终与 classpath 一致，不因重复解压而损坏。 */
    @Test
    fun `repeated extraction is idempotent`() {
        val base = createTempDirectory("ok-scripts-idempotent").toFile()

        val first = PythonScriptLocator.extractBundledScripts(base)
        val second = PythonScriptLocator.extractBundledScripts(base)
        assertNotNull(first)
        assertNotNull(second)
        assertEquals(first, second, "解压目录必须稳定，不能每次换一个新目录")

        for (name in PythonScriptLocator.BUNDLED_SCRIPTS) {
            assertEquals(
                bundledText(name), second.resolve(name).toFile().readText(Charsets.UTF_8),
                "$name 反复解压后内容仍须与打包版本一致",
            )
        }
    }
}
