package com.alicejump.okscripttoolkit.core

import com.alicejump.okscripttoolkit.settings.OkScriptToolkitSettings
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.io.File

/**
 * 项目约定文件 `ok-script-toolkit.json` 的**读盘侧**：定位项目根、读文件、缓存。
 *
 * 文件放在**被调试项目**根目录，JetBrains 插件与 VS Code 扩展共用同一份
 * （父仓对应实现见 `src/projectConfig.ts`，逻辑一一对应）。
 * 插件**只读**它 —— 由项目作者维护，插件绝不写入。
 *
 * 解析与取值链在 [ProjectConvention]（纯对象，可单测）。
 * 设计说明见仓库根的 `docs/project-config.md`。
 */
@Service(Service.Level.PROJECT)
class ProjectConventionConfig(private val project: Project) {

    private data class Cached(val path: String, val modified: Long, val convention: ProjectConvention)

    /** 缓存：同一文件 + 同一 mtime 就不重复读盘（访问器调用很频繁） */
    private var cache: Cached? = null

    /**
     * 读项目根的 `ok-script-toolkit.json`。
     *
     * **容错是刻意的**：这是可选的纯增量配置，文件缺席、JSON 语法错、顶层不是对象
     * 一律当成"没有约定"，绝不抛异常、绝不影响调用方。解析失败也会被缓存，
     * 免得每次访问都重试一遍坏文件。
     *
     * @param projectDir 显式指定项目根；不传则按 [ProjectDirResolution] 解析
     */
    fun load(projectDir: String? = null): ProjectConvention {
        val dir = projectDir?.takeIf { it.isNotBlank() } ?: resolveProjectDir()
        if (dir.isBlank()) {
            cache = null
            return ProjectConvention.EMPTY
        }
        val file = File(dir, PROJECT_CONFIG_FILE)
        if (!file.isFile) {
            cache = null
            return ProjectConvention.EMPTY
        }
        val modified = file.lastModified()
        cache?.let { if (it.path == file.path && it.modified == modified) return it.convention }

        // 解析与容错都在纯对象里（见 ProjectConvention.parseFile）—— 那边能用临时目录单测，
        // 本服务只负责"定位 + 缓存"这件必须依赖 Project 的事。
        val convention = ProjectConvention.parseFile(file)
        cache = Cached(file.path, modified, convention)
        return convention
    }

    /** 仅供测试：清掉缓存，避免用例之间互相污染 */
    fun clearCache() {
        cache = null
    }

    /**
     * 解析 ok-script 项目根。
     *
     * ⚠️ 这里**只在方法内**取 [OkScriptToolkitSettings]，不在构造函数里 ——
     * 反向依赖确实存在（`OkScriptToolkitSettings.featureAliases()` 会调本服务），
     * 但两边都不在**构造期**互相请求，所以安全。写进构造函数会触发平台的
     * `Cyclic service initialization`。
     */
    private fun resolveProjectDir(): String {
        val settings = OkScriptToolkitSettings.getInstance(project)
        return ProjectDirResolution.resolve(
            configured = settings.okScriptProjectPath(),
            basePath = project.basePath.orEmpty(),
            homeDir = System.getProperty("user.home").orEmpty(),
        )
    }

    companion object {
        /** 约定文件名。放在被调试项目的**根目录**，两端共用同一份。 */
        const val PROJECT_CONFIG_FILE = "ok-script-toolkit.json"

        fun getInstance(project: Project): ProjectConventionConfig = project.service()
    }
}
