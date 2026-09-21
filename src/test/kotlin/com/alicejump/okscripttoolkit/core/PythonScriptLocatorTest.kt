package com.alicejump.okscripttoolkit.core

import com.alicejump.okscripttoolkit.TestTmp
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

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
 *
 * 前提：这些断言只在**打包脚本真的进了 classpath** 时有意义。子仓独立 CI
 * 检不到父仓的 `python/`，那里会整体跳过（见 [requireBundledScripts]）；
 * 父仓 CI 带 submodules 检出，断言完整执行。
 */
class PythonScriptLocatorTest {

    /** 读取 classpath 上打包脚本的原始内容，作为"应该被解压成什么"的基准。 */
    private fun bundledText(name: String): String? =
        PythonScriptLocator::class.java.classLoader
            .getResourceAsStream("python/$name")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }

    /**
     * 打包脚本是否真的进了 classpath。
     *
     * **并非所有构建都有**，所以必须显式判断，不能默认它一定在：
     * `jetbrains/` 是**独立公开仓库**，它自己的 CI（`.github/workflows/ci.yml`）
     * 只检出本仓库，父仓的 `../python/` 不可见，`copyPythonScripts` 只能空跑。
     * `build.gradle.kts` 对这种情况是**有意放行**的 —— 那里明确写了
     * 「不要把 buildPlugin 放进 distributableTasks，放进去会让子仓独立 CI 必然失败」。
     *
     * 本测试当初漏了同一层考虑：在缺脚本的环境里 `extractBundledScripts` 按契约返回
     * `null`，四条断言于是**必然失败**，把子仓 CI 变成长期红灯（实测 6 次连续
     * run 全红，且早于本功能提交）—— 一个永远红的 CI 等于没有 CI。
     *
     * 判据与生产代码**用同一个表达式**（`BUNDLED_SCRIPTS.first()`），
     * 免得两边的"算不算有脚本"悄悄分叉。
     *
     * 父仓 CI 用 `submodules: recursive` 检出，`../python` 存在、脚本齐全，
     * 断言在那里**照常全跑** —— 跳过只发生在"本来就没有断言对象"的环境。
     */
    private val bundledScriptsPresent: Boolean
        get() = PythonScriptLocator::class.java.classLoader
            .getResource("python/${PythonScriptLocator.BUNDLED_SCRIPTS.first()}") != null

    private fun requireBundledScripts() {
        assumeTrue(
            bundledScriptsPresent,
            "classpath 上没有 python/*.py —— 只检出了 jetbrains 子仓库（父仓 python/ 不可见），" +
                "本断言无可断言对象，跳过。父仓 CI 会带 submodules 检出并完整跑这些断言。",
        )
    }

    @Test
    fun `extracts every bundled script into a stable directory`() {
        requireBundledScripts()
        val base = TestTmp.create("ok-scripts-test")

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
        requireBundledScripts()
        val base = TestTmp.create("ok-scripts-tamper")
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
        requireBundledScripts()
        val base = TestTmp.create("ok-scripts-legacy")
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
        requireBundledScripts()
        val base = TestTmp.create("ok-scripts-idempotent")

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
