package com.alicejump.okscripttoolkit.settings

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
class OkScriptToolkitSettings : SimplePersistentStateComponent<OkScriptToolkitSettings.SettingsState>(SettingsState()) {
    class SettingsState : BaseState() {
        var langDirectory by string("assets/lang")
        var poDirectory by string("i18n")
        var poDomains by list<String>()
        var displayLocale by string("auto")
        var featureAliases by list<String>()
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
        if (state.featureAliases.isEmpty()) state.featureAliases = mutableListOf("fL", "FeatureList")
    }

    companion object {
        /** 截图方式可选值，与 capture_game_window.py 的 --method 保持一致 */
        val CAPTURE_METHODS = listOf("auto", "wgc", "bitblt", "foreground")

        /** 面板上的「硬前台」复选框对应的方式：激活窗口到前台再读屏幕 */
        const val CAPTURE_METHOD_FOREGROUND = "foreground"

        /** 非法值（含旧配置残留）一律回退到 auto，避免把脏值传给 python 脚本 */
        fun normalizeCaptureMethod(value: String?): String =
            value?.trim()?.takeIf { it in CAPTURE_METHODS } ?: "auto"

        fun getInstance(project: Project): OkScriptToolkitSettings = project.service()
    }

    fun langDirectory(): String = state.langDirectory.orEmpty().ifBlank { "assets/lang" }
    fun poDirectory(): String = state.poDirectory.orEmpty().ifBlank { "i18n" }
    fun poDomains(): List<String> = state.poDomains.filter { it.isNotBlank() }.ifEmpty { listOf("ocr") }
    fun displayLocale(): String = state.displayLocale.orEmpty().ifBlank { "auto" }
    fun featureAliases(): List<String> = state.featureAliases.filter { it.isNotBlank() }.ifEmpty { listOf("fL", "FeatureList") }
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
