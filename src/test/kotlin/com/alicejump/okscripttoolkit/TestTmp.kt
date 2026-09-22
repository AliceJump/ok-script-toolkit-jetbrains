package com.alicejump.okscripttoolkit

import java.io.File
import java.nio.file.Files

/**
 * 测试临时目录的**唯一根**。
 *
 * 背景（2026-09-22 实测）：此前各测试自行调 `createTempDirectory("ok-xxx")`，
 * 目录直接散落在系统临时目录**根部**且从不清理 —— 三天堆了 2671 个目录；
 * 叠加 `AtomicWritePaths` 的备份，另有 1194 个 `ok-script-toolkit-*.bak`。
 * 散落导致既看不清、也没法"跑完一起删"。
 *
 * 布局（三端共用一个父目录，各自只清自己的子树，避免互相误删）：
 * ```
 * <系统临时目录>/ok-script-toolkit-tests/      <- [base]，统一根
 *   ├── kt/                                     <- [dir]，Kotlin 测试
 *   │   └── backup/                             <- 生产代码 .bak 备份的测试重定向目标
 *   ├── py/                                     <- Python 测试（见 python/tests/_test_tmp.py）
 *   └── js/                                     <- Node 测试（见 scripts/test-tmp.js）
 * ```
 *
 * 清理：[clean] 删掉 [dir]（`kt/` 整棵），[base] 空了再连它一起删。
 * 触发点有两个 —— JUnit 会话结束（[TestTmpCleanupExtension]）和 Gradle 的
 * `cleanTestTmpAfter` 任务（测试失败也会执行）。凡是要写临时文件的测试，
 * 都应经 [create] / [createFile] 取路径，不要再直接调 `createTempDirectory`。
 */
object TestTmp {

    /** 统一临时根的目录名。放在系统临时目录下的**固定子目录**，不再散落。 */
    const val ROOT_DIR_NAME = "ok-script-toolkit-tests"

    /** 本语言在统一根下的子目录名。 */
    const val LANG_DIR_NAME = "kt"

    /** 指定统一根的属性名（Gradle 注入；未设置时退回系统临时目录下的固定子目录）。 */
    const val ROOT_PROPERTY = "ok.test.tmp.root"

    /** 环境变量名，供 Python / Node 测试读取同一个统一根。 */
    const val ROOT_ENV = "OK_TEST_TMP_ROOT"

    /**
     * 生产代码备份文件的落盘根（见 `AtomicWritePaths`）。
     *
     * 正常运行时就是系统临时目录，与改动前行为一致；测试期间指向 [dir] 下的 `backup/`，
     * 这样生产写入路径产生的 `.bak` 也会被统一删除，不会漏到系统临时目录根部。
     */
    const val BACKUP_DIR_PROPERTY = "ok-script-toolkit.backup.dir"

    /** 统一临时根（三端共用的父目录）。 */
    val base: File by lazy {
        val configured = System.getProperty(ROOT_PROPERTY)
        if (!configured.isNullOrBlank()) {
            File(configured)
        } else {
            File(System.getProperty("java.io.tmpdir"), ROOT_DIR_NAME)
        }
    }

    /** Kotlin 测试实际落盘的目录：`<base>/kt`。 */
    val dir: File by lazy {
        File(base, LANG_DIR_NAME).apply {
            mkdirs()
            // 在 IDE 里直接跑测试时 Gradle 不会注入该属性，这里补一次，
            // 好让生产代码的 .bak 也落进统一根（否则会漏到系统临时目录根部）。
            if (System.getProperty(BACKUP_DIR_PROPERTY).isNullOrBlank()) {
                System.setProperty(BACKUP_DIR_PROPERTY, File(this, "backup").absolutePath)
            }
        }
    }

    /**
     * 新建一个独占的测试临时目录，落在 [dir] 下。
     *
     * 调用方**不需要**清理：整个 `kt/` 子树在测试会话结束时统一删除。
     * 需要"跑完立刻删"的个别用例可自行 `deleteRecursively()`，但不作要求。
     */
    fun create(prefix: String): File {
        dir.mkdirs()
        return Files.createTempDirectory(dir.toPath(), "$prefix-").toFile()
    }

    /** 新建一个独占的测试临时文件，落在 [dir] 下。 */
    fun createFile(prefix: String, suffix: String): File {
        dir.mkdirs()
        return Files.createTempFile(dir.toPath(), prefix, suffix).toFile()
    }

    /**
     * 删除 Kotlin 测试的临时子树；[base] 随之空了就连它一起删。可重复调用。
     *
     * 防御：[dir] 必须真的位于 [base] 之下，且 [base] 必须是"系统临时目录/<固定名>"
     * 或显式配置的路径 —— 绝不能因为某个空值把系统临时目录整个删掉。
     */
    fun clean() {
        val root = base
        require(root.name == ROOT_DIR_NAME || !System.getProperty(ROOT_PROPERTY).isNullOrBlank()) {
            "拒绝清理非预期的临时根：${root.absolutePath}"
        }
        val target = File(root, LANG_DIR_NAME)
        require(target.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
            "拒绝清理统一根之外的位置：${target.absolutePath}"
        }
        target.deleteRecursively()
        // 三端都清完时 base 就空了，顺手收掉，不留下一个空壳目录。
        if (root.listFiles().orEmpty().isEmpty()) root.delete()
    }
}
