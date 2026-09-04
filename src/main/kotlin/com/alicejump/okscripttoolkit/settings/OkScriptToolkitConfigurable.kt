package com.alicejump.okscripttoolkit.settings

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

class OkScriptToolkitConfigurable(private val project: Project) : Configurable {
    private val langDirectory = JBTextField()
    private val poDirectory = JBTextField()
    private val poDomains = JBTextField()
    private val displayLocale = JBTextField()
    private val featureAliases = JBTextField()
    private val effectsFile = JBTextField()
    private val enablePoData = JBCheckBox(OkScriptToolkitBundle.message("settings.enablePoData"))
    private val enableInlayHints = JBCheckBox(OkScriptToolkitBundle.message("settings.enableInlayHints"))
    private val enableTemplateGallery = JBCheckBox(OkScriptToolkitBundle.message("settings.templateGallery"))
    // TaskLauncher settings
    private val okScriptProjectPath = JBTextField()
    private val okScriptPython = JBTextField()

    override fun getDisplayName(): String = OkScriptToolkitBundle.message("settings.displayName")

    override fun createComponent(): JComponent = panel {
        group(OkScriptToolkitBundle.message("settings.paths")) {
            row(OkScriptToolkitBundle.message("settings.langDirectory")) {
                cell(langDirectory).align(AlignX.FILL)
            }
            row(OkScriptToolkitBundle.message("settings.poDirectory")) {
                cell(poDirectory).align(AlignX.FILL)
            }
            row(OkScriptToolkitBundle.message("settings.poDomains")) {
                cell(poDomains).align(AlignX.FILL)
            }
            row(OkScriptToolkitBundle.message("settings.effectsFile")) {
                cell(effectsFile).align(AlignX.FILL)
            }
        }
        group(OkScriptToolkitBundle.message("settings.editor")) {
            row(OkScriptToolkitBundle.message("settings.displayLocale")) {
                cell(displayLocale).align(AlignX.FILL)
            }
            row(OkScriptToolkitBundle.message("settings.featureAliases")) {
                cell(featureAliases).align(AlignX.FILL)
            }
            row { cell(enablePoData) }
            row { cell(enableInlayHints) }
            row { cell(enableTemplateGallery) }
        }
        group(OkScriptToolkitBundle.message("settings.taskLauncher")) {
            row(OkScriptToolkitBundle.message("settings.okScriptProjectPath")) {
                cell(okScriptProjectPath).align(AlignX.FILL)
            }
            row(OkScriptToolkitBundle.message("settings.okScriptPython")) {
                cell(okScriptPython).align(AlignX.FILL)
            }
        }
    }.also { reset() }

    override fun isModified(): Boolean {
        val state = OkScriptToolkitSettings.getInstance(project).state
        return langDirectory.text != state.langDirectory.orEmpty() ||
            poDirectory.text != state.poDirectory.orEmpty() ||
            splitList(poDomains.text) != state.poDomains ||
            displayLocale.text != state.displayLocale.orEmpty() ||
            splitList(featureAliases.text) != state.featureAliases ||
            effectsFile.text != state.effectsFile.orEmpty() ||
            enablePoData.isSelected != state.enablePoData ||
            enableInlayHints.isSelected != state.enableInlayHints ||
            enableTemplateGallery.isSelected != state.enableTemplateGallery ||
            okScriptProjectPath.text != state.okScriptProjectPath.orEmpty() ||
            okScriptPython.text != state.okScriptPython.orEmpty()
    }

    override fun apply() {
        val settings = OkScriptToolkitSettings.getInstance(project)
        settings.state.langDirectory = langDirectory.text.trim()
        settings.state.poDirectory = poDirectory.text.trim()
        settings.state.poDomains = splitList(poDomains.text).toMutableList()
        settings.state.displayLocale = displayLocale.text.trim()
        settings.state.featureAliases = splitList(featureAliases.text).toMutableList()
        settings.state.effectsFile = effectsFile.text.trim()
        settings.state.enablePoData = enablePoData.isSelected
        settings.state.enableInlayHints = enableInlayHints.isSelected
        settings.state.enableTemplateGallery = enableTemplateGallery.isSelected
        settings.state.okScriptProjectPath = okScriptProjectPath.text.trim()
        settings.state.okScriptPython = okScriptPython.text.trim()
    }

    override fun reset() {
        val state = OkScriptToolkitSettings.getInstance(project).state
        langDirectory.text = state.langDirectory.orEmpty()
        poDirectory.text = state.poDirectory.orEmpty()
        poDomains.text = state.poDomains.joinToString(", ")
        displayLocale.text = state.displayLocale.orEmpty()
        featureAliases.text = state.featureAliases.joinToString(", ")
        effectsFile.text = state.effectsFile.orEmpty()
        enablePoData.isSelected = state.enablePoData
        enableInlayHints.isSelected = state.enableInlayHints
        enableTemplateGallery.isSelected = state.enableTemplateGallery
        okScriptProjectPath.text = state.okScriptProjectPath.orEmpty()
        okScriptPython.text = state.okScriptPython.orEmpty()
    }

    private fun splitList(value: String): List<String> = value
        .split(',', ';', '\n')
        .map(String::trim)
        .filter(String::isNotEmpty)
}
