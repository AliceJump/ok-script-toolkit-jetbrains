package com.alicejump.okscripttoolkit.core

import java.io.File

/**
 * 测试用的**仓库路径定位**。
 *
 * Gradle 跑测试时工作目录是模块根（`jetbrains/`），但从 IDE 里单独跑测试类时
 * 工作目录可能是仓库根或更外层 —— 写死相对路径会变成"本地能过、CI 挂了"的经典陷阱。
 * 所以从当前工作目录逐级往上找，两种布局（`<cwd>/<rel>` 与 `<cwd>/jetbrains/<rel>`）都试。
 *
 * 完全找不到时**显式失败**，而不是返回空结果把断言变成恒真。
 */
internal object TestRepoLayout {

    fun locate(relative: String): File {
        var current: File? = File("").absoluteFile
        val candidates = mutableListOf<File>()
        while (current != null) {
            candidates += File(current, relative)
            candidates += File(current, "jetbrains/$relative")
            current = current.parentFile
        }
        return candidates.firstOrNull { it.exists() }
            ?: throw AssertionError(
                "找不到 $relative。已尝试：\n" + candidates.joinToString("\n") { "  ${it.path}" },
            )
    }

    /** `src/main/kotlin` 下的某个源码文件。 */
    fun mainSource(relativePath: String): File = locate("src/main/kotlin/$relativePath")
}
