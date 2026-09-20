package com.alicejump.okscripttoolkit.core

import java.nio.file.Files
import java.nio.file.Paths

/**
 * ok-script **项目根目录**的解析规则：设置优先，回退到工作区根。
 *
 * 之所以抽成纯对象：这条规则原本在插件里有**三份各自漂移的实现** ——
 * `ScreenshotCapture.detectProjectDir`、`TaskLauncherToolWindowFactory.detectProjectPath`、
 * 以及 `TaskLauncherService.getProjectRoot()`。前两份是「设置优先 + 校验 config.py」，
 * 第三份**只取 `project.basePath`**，于是任务启动器真正跑脚本时用的目录
 * 可能和界面上判定/展示的目录不是同一个（见 [ProjectDirResolution] 的调用方注释）。
 *
 * 判据：**不变量 + 多处复制 + 需要单测但依赖 `Project`** → 抽纯对象。
 * 与 `SyncedSkillPolicy` / `TaskConfigMerge` / `SchemaTreeOverlap` 同一模式。
 *
 * ⚠️ 注意区分两个概念，别再把它们混为一谈：
 *
 * - **工作区根**（`project.basePath`）：`.idea/` / `.vscode/` 数据文件的落点。
 *   子仓 `TaskLauncherService.getProjectRoot()` 与父仓 `taskLauncher.ts` 的 `dataFile()`
 *   用的都是它（父仓明确用 `workspaceFolders[0]`，与设置无关）。
 * - **ok-script 项目根**（本对象解析出来的）：真正跑 `parse_config_tasks.py` /
 *   `probe_task_schemas.py` 时传的目录。父仓用 `resolveProjectContext()` 得到的就是它。
 */
internal object ProjectDirResolution {

    /**
     * 解析 ok-script 项目根。
     *
     * @param configured 设置项里的路径（可能含 `~`，可能为空）
     * @param basePath   工作区根（`project.basePath` / `workspaceFolders[0]`）
     * @param homeDir    用于展开 `~`
     * @param isDirectory 目录存在性判定（注入以便单测）
     * @param hasConfigFile 「该目录下有没有 `src/config.py` 或 `config.py`」判定（注入以便单测）
     * @return 解析出的项目根；**无法确定时返回空串**（调用方负责提示"未找到项目"）
     */
    fun resolve(
        configured: String,
        basePath: String,
        homeDir: String,
        isDirectory: (String) -> Boolean,
        hasConfigFile: (String) -> Boolean,
    ): String {
        if (configured.isNotBlank()) {
            val expanded = configured.replace("~", homeDir).trimEnd('/', '\\')
            if (expanded.isNotBlank() && isDirectory(expanded)) return expanded
        }
        if (basePath.isNotBlank() && hasConfigFile(basePath)) return basePath
        return ""
    }

    /**
     * 真实文件系统版的便捷入口 —— **生产代码统一走这里**。
     *
     * 原先 `ScreenshotCapture.detectProjectDir` 与
     * `TaskLauncherToolWindowFactory.detectProjectPath` 各自写了一份一模一样的
     * `isDirectory` / `hasConfigFile` 谓词；多一个消费点就多一份，迟早漂移。
     *
     * 谓词仍留在 [resolve] 的签名里，是因为单测不该碰真实文件系统。
     */
    fun resolve(configured: String, basePath: String, homeDir: String): String = resolve(
        configured = configured,
        basePath = basePath,
        homeDir = homeDir,
        isDirectory = { Files.isDirectory(Paths.get(it)) },
        hasConfigFile = { base ->
            Files.exists(Paths.get(base, "src", "config.py")) || Files.exists(Paths.get(base, "config.py"))
        },
    )
}
