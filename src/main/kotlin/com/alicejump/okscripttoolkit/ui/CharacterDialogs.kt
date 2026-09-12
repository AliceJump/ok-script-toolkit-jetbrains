package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.alicejump.okscripttoolkit.core.CharacterSkillView
import com.alicejump.okscripttoolkit.core.CharacterView
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

private fun msg(key: String, vararg params: Any): String =
    if (params.isEmpty()) OkScriptToolkitBundle.message(key) else OkScriptToolkitBundle.message(key, *params)

enum class SkillDialogMode { ADD, EDIT }

/** 技能新增/编辑表单（对齐 CharacterSkillView 的核心字段）。 */
class SkillDialog(
    project: Project,
    private val mode: SkillDialogMode,
    char: CharacterView,
    editSkillId: String?,
) : DialogWrapper(project) {

    private val skillIdField = JBTextField(editSkillId ?: "")
    private val nameField = JBTextField()
    private val skillTypeField = JBTextField()
    private val elementField = JBTextField()
    private val descriptionArea = JBTextArea(3, 24)
    private val damageField = JBTextField()
    private val staggerField = JBTextField()
    private val cooldownField = JBTextField()
    private val spiritField = JBTextField()

    init {
        title = msg(if (mode == SkillDialogMode.ADD) "characterManager.addSkill" else "characterManager.editSkill")
        setOKButtonText(msg("annotation.ok"))
        editSkillId?.let { id -> char.skills.firstOrNull { it.skillId == id } }?.let(::fillFrom)
        init()
    }

    private fun fillFrom(skill: CharacterSkillView) {
        nameField.text = skill.name
        skillTypeField.text = skill.skillType
        elementField.text = skill.element
        descriptionArea.text = skill.description
        damageField.text = skill.damageMultiplier
        staggerField.text = skill.staggerValue.toString()
        cooldownField.text = skill.cooldown
        spiritField.text = skill.spiritCost.toString()
    }

    override fun createCenterPanel(): JComponent {
        val form = JPanel(GridBagLayout())
        var row = 0
        fun addRow(label: String, component: JComponent) {
            form.add(JLabel(label), GridBagConstraints().apply {
                gridx = 0; gridy = row; anchor = GridBagConstraints.WEST
                insets = JBUI.insets(3, 4, 3, 8)
            })
            form.add(component, GridBagConstraints().apply {
                gridx = 1; gridy = row; fill = GridBagConstraints.HORIZONTAL
                weightx = 1.0; insets = JBUI.insets(3, 0, 3, 4)
            })
            row++
        }
        addRow("skill_id", skillIdField)
        addRow("name", nameField)
        addRow("skill_type", skillTypeField)
        addRow("element", elementField)
        addRow("description", JBScrollPane(descriptionArea).apply { preferredSize = Dimension(320, 70) })
        addRow("damage_multiplier", damageField)
        addRow("stagger_value", staggerField)
        addRow("cooldown", cooldownField)
        addRow("spirit_cost", spiritField)
        return form
    }

    fun enteredSkillId(): String = skillIdField.text.trim()

    fun formValues(): Map<String, String> = mapOf(
        "name" to nameField.text.trim(),
        "skill_type" to skillTypeField.text.trim(),
        "element" to elementField.text.trim(),
        "description" to descriptionArea.text.trim(),
        "damage_multiplier" to damageField.text.trim(),
        "stagger_value" to staggerField.text.trim(),
        "cooldown" to cooldownField.text.trim(),
        "spirit_cost" to spiritField.text.trim(),
    ).filterValues { it.isNotEmpty() }
}

/** 强化组编辑表单（名称 / 触发文本 / 强化产出效果）。 */
class EnhancementDialog(
    project: Project,
    initialName: String?,
    initialTrigger: String?,
    initialEffect: String? = null,
) : DialogWrapper(project) {

    private val nameField = JBTextField(initialName ?: "")
    private val triggerField = JBTextField(initialTrigger ?: "")
    private val effectField = JBTextField(initialEffect ?: "")

    init {
        title = msg("characterManager.enhancements")
        setOKButtonText(msg("annotation.ok"))
        init()
    }

    override fun createCenterPanel(): JComponent {
        val form = JPanel(GridBagLayout())
        var row = 0
        fun addField(label: String, comp: JComponent) {
            form.add(JLabel(label), GridBagConstraints().apply {
                gridx = 0; gridy = row; anchor = GridBagConstraints.WEST
                insets = JBUI.insets(3, 4, 3, 8)
            })
            form.add(comp, GridBagConstraints().apply {
                gridx = 1; gridy = row; fill = GridBagConstraints.HORIZONTAL
                weightx = 1.0; insets = JBUI.insets(3, 0, 3, 4)
            })
            row++
        }
        addField(msg("characterManager.fieldEnhName"), nameField)
        addField(msg("characterManager.fieldTriggerText"), triggerField)
        addField(msg("characterManager.fieldEnhEffect"), effectField)
        form.preferredSize = Dimension(420, form.preferredSize.height)
        return form
    }

    fun formValues(): Map<String, String> = mapOf(
        "name" to nameField.text.trim(),
        "trigger_text" to triggerField.text.trim(),
        "enhancement_effect" to effectField.text.trim(),
        "effects" to "",
    )
}

/** 新增效果：ID / 描述 / 所属分类（分类必须先存在于 effects.py）。 */
class EffectDialog(
    project: Project,
    categories: List<String>,
) : DialogWrapper(project) {

    private val idField = JBTextField()
    private val descriptionField = JBTextField()
    private val categoryBox = JComboBox<String>()

    init {
        title = msg("characterManager.effect.add")
        setOKButtonText(msg("annotation.ok"))
        categoryBox.addItem("")
        categories.filter { it.isNotBlank() }.forEach { categoryBox.addItem(it) }
        categoryBox.isEditable = true
        init()
    }

    override fun createCenterPanel(): JComponent {
        val form = JPanel(GridBagLayout())
        var row = 0
        fun addField(label: String, comp: JComponent) {
            form.add(JLabel(label), GridBagConstraints().apply {
                gridx = 0; gridy = row; anchor = GridBagConstraints.WEST
                insets = JBUI.insets(3, 4, 3, 8)
            })
            form.add(comp, GridBagConstraints().apply {
                gridx = 1; gridy = row; fill = GridBagConstraints.HORIZONTAL
                weightx = 1.0; insets = JBUI.insets(3, 0, 3, 4)
            })
            row++
        }
        addField(msg("characterManager.effect.id"), idField)
        addField(msg("characterManager.effect.description"), descriptionField)
        addField(msg("characterManager.effect.category"), categoryBox)
        form.preferredSize = Dimension(420, form.preferredSize.height)
        return form
    }

    override fun getPreferredFocusedComponent(): JComponent? = idField

    /** (effectId, description, category) */
    fun values(): Triple<String, String, String> = Triple(
        idField.text.trim(),
        descriptionField.text.trim(),
        (categoryBox.selectedItem as? String).orEmpty().trim(),
    )
}
