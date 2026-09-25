package com.alicejump.okscripttoolkit.core

import java.nio.file.Files
import java.nio.file.Paths

/**
 * ok-script **项目根目录**的解析规则：显式设置优先；仅未设置时自动检测工作区根。
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
     * @param hasConfigFile 「该目录下有没有 `src/config.py` 或 `config.py`」判定（注入以便单测）
     * @return 解析出的项目根；显式路径即使不存在也原样返回，由调用方提示错误并阻止运行。
     *         只有未配置且工作区无法自动检测时返回空串。
     */
    fun resolve(
        configured: String,
        basePath: String,
        homeDir: String,
        hasConfigFile: (String) -> Boolean,
    ): String {
        if (configured.isNotBlank()) {
            // 与 VS Code 的 resolveProjectDir 一致：只展开开头的 ~，路径中间的 ~ 是普通字符。
            val expanded = if (configured.startsWith('~')) homeDir + configured.drop(1) else configured
            val trimmed = expanded.trimEnd('/', '\\')
            // 去尾部分隔符不能把 / 或 C:\ 这样的根目录变成空串或 C:。
            return if (trimmed.isEmpty() ||
                (trimmed.length == 2 && trimmed[1] == ':' && expanded.length > trimmed.length)
            ) expanded else trimmed
        }
        if (basePath.isNotBlank() && hasConfigFile(basePath)) return basePath
        return ""
    }

    /**
     * 真实文件系统版的便捷入口 —— **生产代码统一走这里**。
     *
     * 原先 `ScreenshotCapture.detectProjectDir` 与
     * `TaskLauncherToolWindowFactory.detectProjectPath` 各自写了一份路径检测；
     * 多一个消费点就多一份，迟早漂移。
     *
     * `hasConfigFile` 谓词仍留在 [resolve] 的签名里，是因为单测不该碰真实文件系统。
     */
    fun resolve(configured: String, basePath: String, homeDir: String): String = resolve(
        configured = configured,
        basePath = basePath,
        homeDir = homeDir,
        hasConfigFile = { base ->
            Files.exists(Paths.get(base, "src", "config.py")) || Files.exists(Paths.get(base, "config.py"))
        },
    )

    /** 解析之后由实际运行入口校验；显式路径无效时不能退回另一个项目。 */
    fun isExistingDirectory(path: String): Boolean =
        path.isNotBlank() && runCatching { Files.isDirectory(Paths.get(path)) }.getOrDefault(false)
}
