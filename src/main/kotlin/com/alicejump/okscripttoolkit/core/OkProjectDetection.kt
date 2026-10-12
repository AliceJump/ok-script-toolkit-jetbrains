package com.alicejump.okscripttoolkit.core

import com.alicejump.okscripttoolkit.settings.OkScriptToolkitSettings
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path

/**
 * 是否是 ok-script 项目：与 VS Code `isSupportedProject` 相同 —— 解析出的项目根下有
 * `config.py` 或 `src/config.py`；另外接受根目录的 `ok-script-toolkit.json`。
 * 工具窗口和状态栏用它决定是否出现，非 ok-script 项目里不占 stripe 位置。
 */
object OkProjectDetection {
    fun isOkScriptProject(projectDir: String): Boolean {
        if (projectDir.isBlank()) return false
        val root = runCatching { Path.of(projectDir) }.getOrNull() ?: return false
        if (!Files.isDirectory(root)) return false
        return Files.isRegularFile(root.resolve("config.py")) ||
            Files.isRegularFile(root.resolve("src").resolve("config.py")) ||
            Files.isRegularFile(root.resolve("ok-script-toolkit.json"))
    }

    fun isOkScriptProject(project: Project): Boolean {
        if (project.isDisposed) return false
        val dir = ProjectDirResolution.resolve(
            configured = OkScriptToolkitSettings.getInstance(project).okScriptProjectPath(),
            basePath = project.basePath.orEmpty(),
            homeDir = System.getProperty("user.home").orEmpty(),
        )
        return isOkScriptProject(dir) || isOkScriptProject(project.basePath.orEmpty())
    }
}
