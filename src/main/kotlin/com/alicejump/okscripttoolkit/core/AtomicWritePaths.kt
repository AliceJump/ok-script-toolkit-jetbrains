package com.alicejump.okscripttoolkit.core

import java.io.File

/**
 * 原子写入时「备份文件放哪」的约定。
 *
 * 抽成不依赖 IDE 的纯对象，因为这是一条**看起来像 IO 细节、其实是"别碰用户源码树"的约定**，
 * 而且错了很难被发现：备份只是"这次写坏了能捞回来"的保险，用户不会天天去看目录，
 * 直到某天 `git status` 里冒出一串 `.bak`，或者打包产物里混进备份文件。
 *
 * 背景：原先两个写入点（角色数据 / 效果数据）都写 `<目标文件>.bak`，
 * 也就是**直接落在用户的 `ok_templates/` 源码树里**。这有三个问题：
 *
 * 1. **污染项目**：用户的 `git status` 多出未跟踪文件，容易被误提交；
 * 2. **无人清理**：每次写入覆盖同一个路径，但备份一旦产生就永远留在那儿；
 * 3. **可能被收进产物**：`.vscodeignore` / `build.gradle.kts` 都没排除 `*.bak`。
 *
 * 修法：备份改到系统临时目录。注意**不能用固定文件名**（如 `ok-script-toolkit-<name>.bak`）——
 * 两个写入线程用同一个备份路径会互相覆盖，备份本身就失去意义；
 * 因此走 [backupTarget] 拿到一个**独占的**临时文件（JVM 侧由 `Files.createTempFile` 保证）。
 *
 * 临时目录的 `.tmp` 侧仍留在目标文件旁边：它是"写一半"的中间产物，
 * 必须和目标在**同一文件系统**上，否则 `ATOMIC_MOVE` 会退化成"复制 + 删除"而失去原子性。
 */
internal object AtomicWritePaths {

    /** 备份文件在系统临时目录里的统一前缀，便于用户按需清理 */
    const val BACKUP_PREFIX = "ok-script-toolkit-"

    /**
     * 备份落盘根目录的覆盖开关。
     *
     * 默认就是系统临时目录（与改动前一致）。测试把它指向统一的测试临时根，
     * 好让生产写入路径产生的备份跟其他测试产物一起被删掉，不再漏到系统临时目录根部；
     * 需要时用户也可以借此把备份挪到别的盘。
     */
    const val BACKUP_DIR_PROPERTY = "ok-script-toolkit.backup.dir"

    /** 备份落盘根目录。见 [BACKUP_DIR_PROPERTY]。 */
    val backupRoot: File
        get() = System.getProperty(BACKUP_DIR_PROPERTY)
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?: File(System.getProperty("java.io.tmpdir"))

    /**
     * 目标文件旁边的临时文件（写入中），例如 `effects.py.ok-script-toolkit.tmp`。
     *
     * 必须与目标同目录 —— 见类注释关于 `ATOMIC_MOVE` 的说明。
     */
    fun tempBeside(target: File): File = File(target.parentFile, target.name + ".ok-script-toolkit.tmp")

    /**
     * 备份文件路径：放备份根目录（默认系统临时目录），且**每次调用都返回一个新的独占文件**。
     *
     * 目标文件还不存在时返回 null（没有东西可备份，不该凭空造一个空备份）。
     *
     * 已知局限：备份写完**不做清理**，成功路径也保留（这是"能回滚"的设计取舍）。
     * 因此它在时间上是单调增长的，清理责任在外部 —— 测试期统一由测试临时根收走。
     */
    fun backupTarget(target: File): File? {
        if (!target.exists()) return null
        val readable = target.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val root = backupRoot.apply { mkdirs() }
        return java.nio.file.Files.createTempFile(root.toPath(), BACKUP_PREFIX, "-$readable.bak").toFile()
    }
}
