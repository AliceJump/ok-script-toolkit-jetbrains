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
    }

    init {
        if (state.poDomains.isEmpty()) state.poDomains = mutableListOf("ocr")
        if (state.featureAliases.isEmpty()) state.featureAliases = mutableListOf("fL", "FeatureList")
    }

    companion object {
        fun getInstance(project: Project): OkScriptToolkitSettings = project.service()
    }

    fun langDirectory(): String = state.langDirectory.orEmpty().ifBlank { "assets/lang" }
    fun poDirectory(): String = state.poDirectory.orEmpty().ifBlank { "i18n" }
    fun poDomains(): List<String> = state.poDomains.filter { it.isNotBlank() }.ifEmpty { listOf("ocr") }
    fun displayLocale(): String = state.displayLocale.orEmpty().ifBlank { "auto" }
    fun featureAliases(): List<String> = state.featureAliases.filter { it.isNotBlank() }.ifEmpty { listOf("fL", "FeatureList") }
    fun effectsFile(): String = state.effectsFile.orEmpty().ifBlank { "src/data/effects.py" }
}
