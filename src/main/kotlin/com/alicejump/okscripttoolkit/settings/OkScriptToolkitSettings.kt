package com.alicejump.okscripttoolkit.settings

import com.alicejump.okscripttoolkit.core.ProjectConventionConfig
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(
    name = "com.alicejump.okscripttoolkit.settings.OkScriptToolkitSettings",
    storages = [Storage("ok-script-toolkit.xml")],
)
class OkScriptToolkitSettings(
    private val project: Project,
) : SimplePersistentStateComponent<OkScriptToolkitSettings.SettingsState>(SettingsState()) {
    class SettingsState : BaseState() {
        var langDirectory by string("assets/lang")
        var poDirectory by string("i18n")
        var poDomains by list<String>()
        var displayLocale by string("auto")
        var featureAliases by list<String>()

        /** 用户是否**动过** `featureAliases`（点过设置面板的「应用」）。见 [init] 的迁移说明。 */
        var featureAliasesTouched by property(false)

        /**
         * 用户**真正改过**的设置键（点过设置面板的「应用」且值确实变了）。
         *
         * 为什么需要它：本 state 里这些字段**都带非空默认值**（`ok_templates`、
         * `assets/lang`、`src/data/effects.py` …），而 `BaseState` 的取值就是"字段值"，
         * 没有"是否设置过"这一说。于是"读一下 state"永远拿得到值 → 个人偏好层永远命中
         * → **项目约定文件里声明的值永远不生效**，且症状完全静默（界面一切正常，
         * 只是项目里配的东西没反应）。
         *
         * 这与 VS Code 侧 `getConfiguration().get()` 会把 `package.json` 的 `default`
         * 一并返回是同一个坑 —— 那边用 `inspect()` 区分"用户写过"与"默认值"，
         * 这边用本集合区分。**凡是要接取值链的设置项都必须先在这里记账**，
         * 否则接了等于没接。
         *
         * 由 [OkScriptToolkitConfigurable.apply] 维护（只记录值真的变了的键）；
         * 老用户由 [init] 一次性播种。
         */
        var overriddenKeys by list<String>()

        var effectsFile by string("src/data/effects.py")
        var enablePoData by property(true)
        var enableInlayHints by property(true)
        var enableTemplateGallery by property(true)
        // TaskLauncher settings
        var okScriptProjectPath by string("")
        var okScriptPython by string("")
        // Character settings
        var characterProjectPath by string("")
        var characterMasterFile by string("assets/data/characters.json")
        var characterSkillsDirectory by string("assets/data/character_skills")
        var characterLocaleFile by string("assets/lang/characters.json")
        var characterAvatarTemplateRegex by string("^battle[_-]?icon[_-]?")
        // Template assets settings
        var okTemplatesDirectory by string("ok_templates")
        // Screenshot settings
        var captureMethod by string("auto")
    }

    init {
        if (state.poDomains.isEmpty()) state.poDomains = mutableListOf("ocr")
        // ⚠️ 这里**不能**再给 featureAliases 填默认值 —— 见 [featureAliases] 的注释：
        // 填了默认值就等于"个人偏好层永远命中"，项目约定文件里声明的 aliases 永远不生效。
        //
        // 但旧版本填过（并已持久化进老用户的 xml），所以做一次性清理：
        // 值恰好等于内置兜底、且用户从没点过「应用」→ 视为从未设置过。
        // `featureAliasesTouched` 用来区分"init 自动写的"与"用户手填的同样值" ——
        // 没有它，用户就永远无法用设置覆盖成"恰好等于内置默认"。
        if (!state.featureAliasesTouched && state.featureAliases == DEFAULT_FEATURE_ALIASES) {
            state.featureAliases = mutableListOf()
        }
        // 老用户没有 overriddenKeys 这个集合，给他们播一次种：
        // 凡是"值不等于内置默认"的，一定是用户自己改过的（默认值不会被写成非默认值）。
        // 只在集合为空时跑 —— 之后由设置面板维护，而"空集合"本身就是
        // "用户什么都没覆盖"的合法状态，重复播种是幂等的。
        if (state.overriddenKeys.isEmpty() &&
            state.okTemplatesDirectory.orEmpty().isNotBlank() &&
            state.okTemplatesDirectory != DEFAULT_TEMPLATES_DIRECTORY
        ) {
            state.overriddenKeys = mutableListOf(KEY_OK_TEMPLATES_DIRECTORY)
        }
    }

    companion object {
        /** 截图方式可选值，与 capture_game_window.py 的 --method 保持一致 */
        val CAPTURE_METHODS = listOf("auto", "wgc", "bitblt", "foreground")

        /** 面板上的「硬前台」复选框对应的方式：激活窗口到前台再读屏幕 */
        const val CAPTURE_METHOD_FOREGROUND = "foreground"

        /**
         * 枚举引用别名的**内置兜底**。
         *
         * ⚠️ 它是取值链的最后一层，**不是** `SettingsState.featureAliases` 的默认值 ——
         * 一旦当成 state 默认值写进去，"个人偏好"这一层就永远非空，项目约定文件失效。
         */
        val DEFAULT_FEATURE_ALIASES = listOf("fL", "FeatureList")

        /**
         * 模板目录的**内置兜底**（`ok_templates`）。
         *
         * ⚠️ 与 [DEFAULT_FEATURE_ALIASES] 同理：它是取值链的最后一层，
         * **不是**"个人偏好层"的值。VS Code 侧 `projectConfig.DEFAULT_TEMPLATES_DIRECTORY` 同值。
         */
        const val DEFAULT_TEMPLATES_DIRECTORY = "ok_templates"

        /**
         * `templates.directory` 对应的设置键名，用于 [SettingsState.overriddenKeys] 记账。
         * 与设置面板字段名一一对应。
         */
        const val KEY_OK_TEMPLATES_DIRECTORY = "okTemplatesDirectory"

        /** 非法值（含旧配置残留）一律回退到 auto，避免把脏值传给 python 脚本 */
        fun normalizeCaptureMethod(value: String?): String =
            value?.trim()?.takeIf { it in CAPTURE_METHODS } ?: "auto"

        fun getInstance(project: Project): OkScriptToolkitSettings = project.service()
    }

    fun langDirectory(): String = state.langDirectory.orEmpty().ifBlank { "assets/lang" }
    fun poDirectory(): String = state.poDirectory.orEmpty().ifBlank { "i18n" }
    fun poDomains(): List<String> = state.poDomains.filter { it.isNotBlank() }.ifEmpty { listOf("ocr") }
    fun displayLocale(): String = state.displayLocale.orEmpty().ifBlank { "auto" }

    /**
     * 枚举引用别名。
     *
     * 取值链：**个人偏好（IDE 设置）> 项目约定文件 `labelEnum.aliases` > 内置默认**。
     * 个人偏好排最高是用户定的：项目文件是"团队开箱默认"，我改过就用我的。
     *
     * 别名是"代码里怎么写 import"这一**项目约定** —— 项目 `config.py` 从不声明它，
     * 所以此前只能靠 `fL`/`FeatureList` 硬猜；项目把枚举导入成别的名字就完全失效。
     *
     * ⚠️ 这条链成立的前提是 `state.featureAliases` **空 = 没设置过**。
     * 如果哪天有人"顺手"给它加个默认值，这一层就会永远命中、项目声明永远被压住，
     * 而且是**静默**的（界面上一切正常，只是项目约定不生效）。
     */
    fun featureAliases(): List<String> {
        val ide = state.featureAliases.filter { it.isNotBlank() }
        return ProjectConventionConfig.getInstance(project).load().labelEnum.aliasesOr(ide, DEFAULT_FEATURE_ALIASES)
    }

    /** 设置面板用：显示"我设了什么"，而不是"最终生效什么"（取值链会掺进项目约定） */
    fun rawFeatureAliases(): List<String> = state.featureAliases.toList()
    fun effectsFile(): String = state.effectsFile.orEmpty().ifBlank { "src/data/effects.py" }
    fun okScriptProjectPath(): String = state.okScriptProjectPath.orEmpty()
    fun okScriptPython(): String = state.okScriptPython.orEmpty()
    fun characterProjectPath(): String = state.characterProjectPath.orEmpty()
    fun characterMasterFile(): String = state.characterMasterFile.orEmpty().ifBlank { "assets/data/characters.json" }
    fun characterSkillsDirectory(): String = state.characterSkillsDirectory.orEmpty().ifBlank { "assets/data/character_skills" }
    fun characterLocaleFile(): String = state.characterLocaleFile.orEmpty().ifBlank { "assets/lang/characters.json" }
    fun characterAvatarTemplateRegex(): String = state.characterAvatarTemplateRegex.orEmpty().ifBlank { "^battle[_-]?icon[_-]?" }

    /**
     * 模板目录名（相对项目根）。
     *
     * 取值链：**个人偏好（IDE 设置）> 项目约定文件 `templates.directory` > `ok_templates`**。
     * 个人偏好排最高是用户定的：项目文件是"团队开箱默认"，我改过就用我的。
     *
     * ⚠️ 只有用户**真正改过**（[SettingsState.overriddenKeys] 里有这个键）才把 state 的值
     * 当个人偏好。state 的默认值就是 `ok_templates`，直接读会让这一层永远命中、
     * **项目声明永远被压住** —— 与 [featureAliases] 是同一个陷阱，且同样静默。
     *
     * 历史：VS Code 侧这个设置此前是**死设置**（常量硬编码、无人读），而子仓会读（10 处），
     * 属反向不对等（`docs/project-config.md` §8.1）。现在两端都走取值链。
     */
    fun okTemplatesDirectory(): String {
        val ide = if (KEY_OK_TEMPLATES_DIRECTORY in state.overriddenKeys) state.okTemplatesDirectory else null
        return ProjectConventionConfig.getInstance(project)
            .load()
            .templates
            .directoryOr(ide, DEFAULT_TEMPLATES_DIRECTORY)
    }

    /**
     * 记录用户显式改过某个设置键 —— 该键的"个人偏好"层从此生效。
     *
     * 由设置面板的 `apply()` 在**值确实变了**时调用。详见 [SettingsState.overriddenKeys]。
     */
    fun markOverridden(key: String) {
        if (key !in state.overriddenKeys) {
            state.overriddenKeys = (state.overriddenKeys + key).toMutableList()
        }
    }

    fun captureMethod(): String = normalizeCaptureMethod(state.captureMethod)
}
