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
    // Character settings
    private val characterProjectPath = JBTextField()
    private val characterMasterFile = JBTextField()
    private val characterSkillsDirectory = JBTextField()
    private val characterLocaleFile = JBTextField()
    private val characterAvatarTemplateRegex = JBTextField()
    // Template assets settings
    private val okTemplatesDirectory = JBTextField()
    // LabelEnum settings
    private val labelEnumPath = JBTextField()
    private val labelEnumName = JBTextField()
    // Screenshot settings
    private val captureMethod = javax.swing.JComboBox(OkScriptToolkitSettings.CAPTURE_METHODS.toTypedArray())

    /**
     * 复制坐标分隔偏好 —— 唯一写 **Application 级** [GlobalPrefs] 的项：
     * 这是跟着人走的习惯，不进项目级取值链。
     */
    private val copyCoordsSpace = JBCheckBox(OkScriptToolkitBundle.message("settings.copyCoordsSpace"))

    init {
        captureMethod.toolTipText = OkScriptToolkitBundle.message("settings.captureMethodTooltip")
    }

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
        group(OkScriptToolkitBundle.message("settings.character")) {
            row(OkScriptToolkitBundle.message("settings.characterProjectPath")) {
                cell(characterProjectPath).align(AlignX.FILL)
            }
            row(OkScriptToolkitBundle.message("settings.characterMasterFile")) {
                cell(characterMasterFile).align(AlignX.FILL)
            }
            row(OkScriptToolkitBundle.message("settings.characterSkillsDirectory")) {
                cell(characterSkillsDirectory).align(AlignX.FILL)
            }
            row(OkScriptToolkitBundle.message("settings.characterLocaleFile")) {
                cell(characterLocaleFile).align(AlignX.FILL)
            }
            row(OkScriptToolkitBundle.message("settings.characterAvatarTemplateRegex")) {
                cell(characterAvatarTemplateRegex).align(AlignX.FILL)
            }
        }
        group(OkScriptToolkitBundle.message("settings.templateAssets")) {
            row(OkScriptToolkitBundle.message("settings.okTemplatesDirectory")) {
                cell(okTemplatesDirectory).align(AlignX.FILL)
            }
            row(OkScriptToolkitBundle.message("settings.labelEnumPath")) {
                cell(labelEnumPath).align(AlignX.FILL)
            }
            row(OkScriptToolkitBundle.message("settings.labelEnumName")) {
                cell(labelEnumName).align(AlignX.FILL)
            }
        }
        group(OkScriptToolkitBundle.message("settings.capture")) {
            row(OkScriptToolkitBundle.message("settings.captureMethod")) {
                cell(captureMethod)
            }
            row { cell(copyCoordsSpace) }
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
            okScriptPython.text != state.okScriptPython.orEmpty() ||
            characterProjectPath.text != state.characterProjectPath.orEmpty() ||
            characterMasterFile.text != state.characterMasterFile.orEmpty() ||
            characterSkillsDirectory.text != state.characterSkillsDirectory.orEmpty() ||
            characterLocaleFile.text != state.characterLocaleFile.orEmpty() ||
            characterAvatarTemplateRegex.text != state.characterAvatarTemplateRegex.orEmpty() ||
            okTemplatesDirectory.text != state.okTemplatesDirectory.orEmpty() ||
            labelEnumPath.text.trim() != state.labelEnumPath.orEmpty() ||
            labelEnumName.text.trim() != state.labelEnumName.orEmpty() ||
            (captureMethod.selectedItem as? String).orEmpty() !=
                OkScriptToolkitSettings.normalizeCaptureMethod(state.captureMethod) ||
            copyCoordsSpace.isSelected != GlobalPrefs.getInstance().state.copyCoordsSpace
    }

    override fun apply() {
        val settings = OkScriptToolkitSettings.getInstance(project)
        // 记账必须放在赋值**之前** —— 赋值后 state 已经是新值，比不出"变没变"。
        // 只有值真的变了才算"用户覆盖了这一项"，没变就继续让项目约定文件生效；
        // 否则打开一次设置面板点个「应用」就会把所有项目约定永久压住（静默）。
        settings.recordIfChanged(
            OkScriptToolkitSettings.KEY_OK_TEMPLATES_DIRECTORY,
            settings.state.okTemplatesDirectory.orEmpty(),
            okTemplatesDirectory.text.trim(),
        )
        settings.recordIfChanged(
            OkScriptToolkitSettings.KEY_LABEL_ENUM_PATH,
            settings.state.labelEnumPath.orEmpty(),
            labelEnumPath.text.trim(),
        )
        settings.recordIfChanged(
            OkScriptToolkitSettings.KEY_LABEL_ENUM_NAME,
            settings.state.labelEnumName.orEmpty(),
            labelEnumName.text.trim(),
        )
        settings.recordIfChanged(
            OkScriptToolkitSettings.KEY_LANG_DIRECTORY,
            settings.state.langDirectory.orEmpty(),
            langDirectory.text.trim(),
        )
        settings.recordIfChanged(
            OkScriptToolkitSettings.KEY_PO_DIRECTORY,
            settings.state.poDirectory.orEmpty(),
            poDirectory.text.trim(),
        )
        settings.recordIfChanged(
            OkScriptToolkitSettings.KEY_PO_DOMAINS,
            settings.state.poDomains.toList(),
            splitList(poDomains.text),
        )
        settings.recordIfChanged(
            OkScriptToolkitSettings.KEY_EFFECTS_FILE,
            settings.state.effectsFile.orEmpty(),
            effectsFile.text.trim(),
        )
        settings.recordIfChanged(
            OkScriptToolkitSettings.KEY_ENABLE_PO_DATA,
            settings.state.enablePoData,
            enablePoData.isSelected,
        )
        settings.recordIfChanged(
            OkScriptToolkitSettings.KEY_CHARACTER_PROJECT_PATH,
            settings.state.characterProjectPath.orEmpty(),
            characterProjectPath.text.trim(),
        )
        settings.recordIfChanged(
            OkScriptToolkitSettings.KEY_CHARACTER_MASTER_FILE,
            settings.state.characterMasterFile.orEmpty(),
            characterMasterFile.text.trim(),
        )
        settings.recordIfChanged(
            OkScriptToolkitSettings.KEY_CHARACTER_SKILLS_DIRECTORY,
            settings.state.characterSkillsDirectory.orEmpty(),
            characterSkillsDirectory.text.trim(),
        )
        settings.recordIfChanged(
            OkScriptToolkitSettings.KEY_CHARACTER_LOCALE_FILE,
            settings.state.characterLocaleFile.orEmpty(),
            characterLocaleFile.text.trim(),
        )
        settings.recordIfChanged(
            OkScriptToolkitSettings.KEY_CHARACTER_AVATAR_TEMPLATE_REGEX,
            settings.state.characterAvatarTemplateRegex.orEmpty(),
            characterAvatarTemplateRegex.text.trim(),
        )

        settings.state.langDirectory = langDirectory.text.trim()
        settings.state.poDirectory = poDirectory.text.trim()
        settings.state.poDomains = splitList(poDomains.text).toMutableList()
        settings.state.displayLocale = displayLocale.text.trim()
        settings.state.featureAliases = splitList(featureAliases.text).toMutableList()
        // 标记"用户动过"：让 init 的一次性迁移不再清空它 ——
        // 否则用户想把别名**故意设成**恰好等于内置默认值时，设置会被静默清掉。
        settings.state.featureAliasesTouched = true
        settings.state.effectsFile = effectsFile.text.trim()
        settings.state.enablePoData = enablePoData.isSelected
        settings.state.enableInlayHints = enableInlayHints.isSelected
        settings.state.enableTemplateGallery = enableTemplateGallery.isSelected
        settings.state.okScriptProjectPath = okScriptProjectPath.text.trim()
        settings.state.okScriptPython = okScriptPython.text.trim()
        settings.state.characterProjectPath = characterProjectPath.text.trim()
        settings.state.characterMasterFile = characterMasterFile.text.trim()
        settings.state.characterSkillsDirectory = characterSkillsDirectory.text.trim()
        settings.state.characterLocaleFile = characterLocaleFile.text.trim()
        settings.state.characterAvatarTemplateRegex = characterAvatarTemplateRegex.text.trim()
        settings.state.okTemplatesDirectory = okTemplatesDirectory.text.trim()
        settings.state.labelEnumPath = labelEnumPath.text.trim()
        settings.state.labelEnumName = labelEnumName.text.trim()
        settings.state.captureMethod = (captureMethod.selectedItem as? String).orEmpty()
        GlobalPrefs.getInstance().state.copyCoordsSpace = copyCoordsSpace.isSelected
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
        characterProjectPath.text = state.characterProjectPath.orEmpty()
        characterMasterFile.text = state.characterMasterFile.orEmpty()
        characterSkillsDirectory.text = state.characterSkillsDirectory.orEmpty()
        characterLocaleFile.text = state.characterLocaleFile.orEmpty()
        characterAvatarTemplateRegex.text = state.characterAvatarTemplateRegex.orEmpty()
        okTemplatesDirectory.text = state.okTemplatesDirectory.orEmpty()
        labelEnumPath.text = state.labelEnumPath.orEmpty()
        labelEnumName.text = state.labelEnumName.orEmpty()
        captureMethod.selectedItem = OkScriptToolkitSettings.normalizeCaptureMethod(state.captureMethod)
        copyCoordsSpace.isSelected = GlobalPrefs.getInstance().state.copyCoordsSpace
    }

    private fun splitList(value: String): List<String> = value
        .split(',', ';', '\n')
        .map(String::trim)
        .filter(String::isNotEmpty)

    /**
     * 值变了才记账（见 [OkScriptToolkitSettings.SettingsState.overriddenKeys]）。
     *
     * ⚠️ 必须在 `state` 赋值**之前**调用 —— 赋值后比不出"变没变"。
     * 没变的项不记账，于是它继续让项目约定文件生效；这就是"打开设置面板点一下应用
     * 不会把所有项目约定永久压住"的保障。
     */
    private fun <T> OkScriptToolkitSettings.recordIfChanged(key: String, old: T, new: T) {
        if (old != new) markOverridden(key)
    }
}
