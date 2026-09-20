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
    fun okTemplatesDirectory(): String = state.okTemplatesDirectory.orEmpty().ifBlank { "ok_templates" }
    fun captureMethod(): String = normalizeCaptureMethod(state.captureMethod)
}
