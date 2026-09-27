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
 * 核心断言：篡改过的脚本必须被恢复，且相同打包内容共用一个版本目录。
 *
 * 测试构建若拿到共享 python/ 源码，classpath 上就必须包含脚本。
 * 仅在源码本身不可用的非发行构建里跳过解压断言。
 */
class PythonScriptLocatorTest {

    /** 读取 classpath 上打包脚本的原始内容，作为"应该被解压成什么"的基准。 */
    private fun bundledText(name: String): String? =
        PythonScriptLocator::class.java.classLoader
            .getResourceAsStream("python/$name")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }

    /** 只允许没有共享源码的非发行构建跳过。 */
    private val bundledScriptsPresent: Boolean
        get() = PythonScriptLocator::class.java.classLoader
            .getResource("python/${PythonScriptLocator.BUNDLED_SCRIPTS.first()}") != null

    private fun requireBundledScripts() {
        assumeTrue(
            System.getProperty("ok.bundled.python.source")?.let(::File)?.isDirectory == true,
            "共享 python/ 源码不可用，跳过非发行构建的解压断言",
        )
        assertTrue(bundledScriptsPresent, "共享 python/ 源码存在，但脚本未进入测试 classpath")
    }

    @Test
    fun `extracts every bundled script into a stable directory`() {
        requireBundledScripts()
        val base = TestTmp.create("ok-scripts-test")

        val dir = PythonScriptLocator.extractBundledScripts(base)
        assertNotNull(dir, "classpath 上有 python/*.py 时必须解压成功（build 里由 copyPythonScripts 提供）")

        assertTrue(
            dir.fileName.toString().startsWith("${PythonScriptLocator.SCRIPT_DIR_NAME}-"),
            "目录必须由脚本内容摘要区分版本，不能使用恒为 0 的 JAR 时间戳",
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

    /**
     * classpath 上 `python/` 资源目录里实际存在的 .py 文件名。
     *
     * 打包形态有两种：IDE 沙箱/开发运行时是**目录** classpath（`file:` URL），
     * 生产环境是**JAR**（`jar:file:` URL）—— 枚举必须同时支持，否则这条断言
     * 只在其中一个形态下生效。
     *
     * 只统计**本插件自己的** `python/` 目录：测试 classpath 上还有 IDE 发行版
     * 的 `python/` 资源（PyCharm 平台 JAR 里也塞了 `python/prompthooks.py`），
     * 用 `parse_config_tasks.py` 这个平台绝无仅有的脚本当标记过滤 ——
     * 不做这一步会把平台脚本误判成白名单缺口。
     */
    private fun bundledPythonResources(): Set<String> {
        val marker = "parse_config_tasks.py"
        val names = mutableSetOf<String>()
        val loader = PythonScriptLocator::class.java.classLoader
        val urls = loader.getResources("python")
        while (urls.hasMoreElements()) {
            val url = urls.nextElement()
            val scripts = when (url.protocol) {
                "file" -> {
                    val dir = java.io.File(url.toURI())
                    dir.listFiles { f -> f.isFile && f.name.endsWith(".py") }
                        ?.map { it.name }
                }
                "jar" -> {
                    // jar:file:/.../x.jar!/python → 取出 JAR 路径与条目前缀
                    val path = url.path
                    val jarPath = java.net.URLDecoder.decode(
                        path.removePrefix("file:").substringBefore("!/"), Charsets.UTF_8,
                    )
                    val prefix = path.substringAfterLast("!/", "python")
                    java.util.jar.JarFile(jarPath).use { jar ->
                        val entries = jar.entries()
                        val found = mutableListOf<String>()
                        while (entries.hasMoreElements()) {
                            val e = entries.nextElement()
                            if (!e.isDirectory && e.name.startsWith("$prefix/") && e.name.endsWith(".py")) {
                                found.add(e.name.substringAfterLast('/'))
                            }
                        }
                        found
                    }
                }
                else -> null
            } ?: continue
            // 标记过滤：只有同时能取到我们独有脚本的目录才算本插件的 python/
            val hasMarker = when (url.protocol) {
                "file" -> java.io.File(url.toURI()).resolve(marker).isFile
                "jar" -> {
                    val path = url.path
                    val jarPath = java.net.URLDecoder.decode(
                        path.removePrefix("file:").substringBefore("!/"), Charsets.UTF_8,
                    )
                    val prefix = path.substringAfterLast("!/", "python")
                    java.util.jar.JarFile(jarPath).use { it.getJarEntry("$prefix/$marker") != null }
                }
                else -> false
            }
            if (hasMarker) names.addAll(scripts)
        }
        return names
    }

    /**
     * **白名单覆盖性断言**：classpath 上 `python/` 里的每个脚本都必须进 [PythonScriptLocator.BUNDLED_SCRIPTS]。
     *
     * 盯的是另一个真实踩过的坑（2026-09-26）：`copyPythonScripts` 是整目录 Sync（JAR 里
     * 脚本齐全），但运行时解压按 [PythonScriptLocator.BUNDLED_SCRIPTS] 白名单执行 ——
     * `task_visibility.py` 在父仓 python/ 里一直存在、却从未进白名单，解压目录里于是
     * 没有它，`run_executor.py` 的 `from task_visibility import ...`（从脚本同目录导入）
     * 启动即 `ModuleNotFoundError`，执行器一个任务都跑不起来。上面
     * [extracts every bundled script into a stable directory] 只断言「白名单内的文件被
     * 解压出来」，对「白名单外的脚本被漏掉」是盲的。
     */
    @Test
    fun `BUNDLED_SCRIPTS covers every python script on the classpath`() {
        requireBundledScripts()
        val onClasspath = bundledPythonResources()
        assertTrue(onClasspath.isNotEmpty(), "classpath 上必须能枚举到打包脚本目录")

        val missing = onClasspath - PythonScriptLocator.BUNDLED_SCRIPTS.toSet()
        assertTrue(
            missing.isEmpty(),
            "classpath 上的脚本 $missing 不在 BUNDLED_SCRIPTS 里 —— 解压白名单漏掉它时，" +
                "run_executor.py 从同目录 import 会直接 ModuleNotFoundError",
        )
    }

    /** 旧版目录可能仍被另一个 IDE 进程使用；新版本不应删除它。 */
    @Test
    fun `legacy directories are left alone while new content uses its own path`() {
        requireBundledScripts()
        val base = TestTmp.create("ok-scripts-legacy")
        val legacy = File(base, "ok-script-toolkit-scripts-0").apply { mkdirs() }
        File(legacy, "run_executor.py").writeText("# 旧版留下的脚本\n", Charsets.UTF_8)

        PythonScriptLocator.extractBundledScripts(base)

        assertTrue(
            legacy.exists(),
            "另一个 IDE 进程可能仍在使用旧目录，不能在本进程启动时删除",
        )
        assertTrue(
            base.listFiles()?.any { it.isDirectory && it.name.startsWith("${PythonScriptLocator.SCRIPT_DIR_NAME}-") } == true,
            "当前打包内容应使用独立的摘要目录",
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

    @Test
    fun `concurrent extraction returns complete scripts in one version directory`() {
        requireBundledScripts()
        val base = TestTmp.create("ok-scripts-concurrent")
        val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
        try {
            val paths = (1..12).map {
                pool.submit<java.nio.file.Path?> { PythonScriptLocator.extractBundledScripts(base) }
            }.map { it.get() }
            assertEquals(1, paths.toSet().size)
            val path = assertNotNull(paths.first())
            for (name in PythonScriptLocator.BUNDLED_SCRIPTS) {
                assertEquals(bundledText(name), path.resolve(name).toFile().readText(Charsets.UTF_8))
            }
        } finally {
            pool.shutdownNow()
        }
    }
}
