package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.alicejump.okscripttoolkit.core.CharacterSkillView
import com.alicejump.okscripttoolkit.core.CharacterView
import com.alicejump.okscripttoolkit.core.EffectParam
import com.alicejump.okscripttoolkit.core.EffectParamCodec
import com.alicejump.okscripttoolkit.core.SyncedSkillPolicy
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

private fun msg(key: String, vararg params: Any): String =
    if (params.isEmpty()) OkScriptToolkitBundle.message(key) else OkScriptToolkitBundle.message(key, *params)

enum class SkillDialogMode { ADD, EDIT }

/** 技能表单共用的字段骨架：标签在左、控件在右，最后一行可占满宽度。 */
abstract class FormDialog(project: Project) : DialogWrapper(project) {

    protected val form = JPanel(GridBagLayout())
    private var row = 0

    protected fun addField(label: String, component: JComponent) {
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

    /** 整行控件（效果多选器、说明文本等）。 */
    protected fun addWide(label: String, component: JComponent) {
        form.add(JLabel(label), GridBagConstraints().apply {
            gridx = 0; gridy = row; gridwidth = 2; anchor = GridBagConstraints.WEST
            insets = JBUI.insets(6, 4, 2, 4)
        })
        row++
        form.add(component, GridBagConstraints().apply {
            gridx = 0; gridy = row; gridwidth = 2; fill = GridBagConstraints.BOTH
            weightx = 1.0; weighty = 1.0; insets = JBUI.insets(0, 4, 4, 4)
        })
        row++
    }

    /**
     * 整行提示条（跨两列，无标签）。
     *
     * 用于「同步技能已锁定」这类**解释性**文案：被锁的控件在 Swing 里只会变灰，
     * 不说明原因的话用户会以为是坏了。VSCode 侧对应
     * `media/characterManager/app.js` 的 `.locked-notice`。
     */
    protected fun addNotice(text: String) {
        form.add(JLabel(text).apply { foreground = JBColor.GRAY }, GridBagConstraints().apply {
            gridx = 0; gridy = row; gridwidth = 2; anchor = GridBagConstraints.WEST
            insets = JBUI.insets(2, 4, 6, 4)
        })
        row++
    }

    /** 可编辑下拉：既能从既有值里选，也能手填新值。 */
    protected fun editableCombo(current: String, options: Collection<String>): JComboBox<String> {
        val box = JComboBox<String>()
        box.isEditable = true
        if (current.isNotBlank()) box.addItem(current)
        options.filter { it.isNotBlank() && it != current }.distinct().sorted().forEach { box.addItem(it) }
        box.selectedItem = current
        return box
    }
}

/** 技能新增/编辑表单。 */
class SkillDialog(
    project: Project,
    private val mode: SkillDialogMode,
    char: CharacterView,
    editSkillId: String?,
    private val effectOptions: List<EffectOption>,
    /** 用来给技能类型/元素下拉填充既有值。 */
    private val knownSkillTypes: Collection<String> = emptyList(),
    private val knownElements: Collection<String> = emptyList(),
) : FormDialog(project) {

    private val skillIdField = JBTextField(editSkillId ?: "")
    private val nameField = JBTextField()
    private lateinit var skillTypeBox: JComboBox<String>
    private lateinit var elementBox: JComboBox<String>
    private val descriptionArea = JBTextArea(3, 24)
    private val damageField = JBTextField()
    private val staggerField = JBTextField("0")
    private val cooldownField = JBTextField()
    private val spiritField = JBTextField("0")
    private lateinit var effectPicker: EffectPickerField

    private val editingSkill = editSkillId?.let { id -> char.skills.firstOrNull { it.skillId == id } }
    private val synced = editingSkill != null && editingSkill.source != "custom"

    init {
        title = msg(if (mode == SkillDialogMode.ADD) "characterManager.addSkill" else "characterManager.editSkill")
        setOKButtonText(msg("annotation.ok"))
        editingSkill?.let(::fillFrom)
        init()
    }

    private fun fillFrom(skill: CharacterSkillView) {
        nameField.text = skill.name
        descriptionArea.text = skill.description
        damageField.text = skill.damageMultiplier
        staggerField.text = skill.staggerValue.toString()
        cooldownField.text = skill.cooldown
        spiritField.text = skill.spiritCost.toString()
    }

    override fun createCenterPanel(): JComponent {
        val skill = editingSkill
        val types = buildList {
            addAll(SKILL_TYPE_PRESETS)
            addAll(knownSkillTypes)
        }
        skillTypeBox = editableCombo(skill?.skillType.orEmpty(), types)
        elementBox = editableCombo(skill?.element.orEmpty(), knownElements)
        effectPicker = EffectPickerField(
            effectOptions,
            skill?.effects?.map { it.toEffectParam() } ?: emptyList(),
            idsOnly = false,
        )
        // 同步技能：标识/语义字段只读，数值与效果仍可编辑（对齐 VSCode）。
        // 提示条放**最上面** —— VSCode 也是先 append notice 再排字段，
        // 用户要先知道"为什么灰"，再看到灰控件。
        if (synced) {
            applySyncedLocks()
            addNotice("🔒 " + msg("characterManager.syncedSkillLocked"))
        }
        addField("skill_id", skillIdField)
        addField("name", nameField)
        addField(msg("characterManager.fieldSkillType"), skillTypeBox)
        addField(msg("characterManager.fieldElement"), elementBox)
        addField("description", JBScrollPane(descriptionArea).apply { preferredSize = Dimension(320, 70) })
        addField("damage_multiplier", damageField)
        addField("stagger_value", staggerField)
        addField("cooldown", cooldownField)
        addField("spirit_cost", spiritField)
        addWide(msg("characterManager.fieldBaseEffects"), effectPicker)
        return JPanel(BorderLayout()).apply {
            add(form, BorderLayout.CENTER)
            preferredSize = Dimension(820, 620)
        }
    }

    /**
     * 把 [SyncedSkillPolicy.LOCKED_FIELDS] 对应的控件设为只读/禁用。
     *
     * 这里**只锁 UI，不承担校验**：真正的兜底在 `CharacterDataMutations.updateSkill`
     * （它按同一份规则裁剪表单）。两处都做是因为 UI 可能被绕过
     * （例如将来新增别的调用路径），而数据层的静默写坏代价更高。
     */
    private fun applySyncedLocks() {
        skillIdField.isEditable = false
        nameField.isEditable = false
        skillTypeBox.isEnabled = false
        elementBox.isEnabled = false
        descriptionArea.isEditable = false
    }

    fun enteredSkillId(): String = skillIdField.text.trim()

    /**
     * 表单字段。
     *
     * `skill_id` **必须在里面**：EDIT 模式下这个输入框原本是装饰性的 ——
     * `runSkillDialog` 用 `editSkillId` 当定位键，而这里不含 `skill_id`，
     * 于是用户改了 ID 保存后**静默无效**（P3-6 同源缺陷）。
     * VSCode 的 `sanitizeSkill` 也把 `skillId` 作为必填字段放进 payload。
     *
     * 同步技能不受影响：`SyncedSkillPolicy.LOCKED_FIELDS` 含 `skill_id`，
     * 数据层会把它连同其它标识字段一起摘掉。
     */
    fun formValues(): Map<String, String> = mapOf(
        "skill_id" to skillIdField.text.trim(),
        "name" to nameField.text.trim(),
        "skill_type" to (skillTypeBox.selectedItem as? String).orEmpty().trim(),
        "element" to (elementBox.selectedItem as? String).orEmpty().trim(),
        "description" to descriptionArea.text.trim(),
        "damage_multiplier" to damageField.text.trim(),
        "stagger_value" to staggerField.text.trim(),
        "cooldown" to cooldownField.text.trim(),
        "spirit_cost" to spiritField.text.trim(),
        "effects" to EffectParamCodec.encode(effectPicker.selectedParams()),
    )

    /**
     * 是否同步技能。同步时 [createCenterPanel] 会把标识/语义字段设为只读。
     *
     * 字段清单的**唯一来源**是 [SyncedSkillPolicy.LOCKED_FIELDS] ——
     * 别在这里另写一份，否则 UI 与数据层会各自漂移（P3-6 的成因就是两套规则）。
     */
    fun isSynced(): Boolean = synced

    companion object {
        /** 与 VSCode skillTypeOptions() 一致。 */
        private val SKILL_TYPE_PRESETS = listOf("普通攻击", "技能", "连携", "终结", "天赋", "潜能")
    }
}

/**
 * 强化组新增/编辑表单。
 *
 * 真实数据里强化组有 5 类内容：名称、trigger_condition.text、
 * trigger_condition.effects（{all|any: [id...]}）、enhancement_effect、
 * effects（强化产出，带 value/duration/target/count）。触发依赖与产出效果
 * 都用多选器选择，不再靠手打逗号分隔 ID。
 */
class EnhancementDialog(
    project: Project,
    private val effectOptions: List<EffectOption>,
    private val seed: CharacterEnhancementSeed,
) : FormDialog(project) {

    private val nameField = JBTextField(seed.name)
    private val triggerField = JBTextArea(seed.triggerText, 2, 40)
    private val effectField = JBTextArea(seed.enhancementEffect, 2, 40)
    private val triggerModeBox = JComboBox(arrayOf("all", "any"))
    private val visiblePulseBox = JCheckBox()
    private lateinit var triggerPicker: EffectPickerField
    private lateinit var outputPicker: EffectPickerField

    init {
        triggerModeBox.selectedItem =
            if (seed.triggerEffectMode.equals("any", ignoreCase = true)) "any" else "all"
        visiblePulseBox.isSelected = seed.visiblePulse
        title = msg("characterManager.enhancements")
        setOKButtonText(msg("annotation.ok"))
        init()
    }

    override fun createCenterPanel(): JComponent {
        triggerPicker = EffectPickerField(effectOptions, seed.triggerEffects, idsOnly = true)
        outputPicker = EffectPickerField(effectOptions, seed.outputEffects, idsOnly = false)
        addField(msg("characterManager.fieldEnhName"), nameField)
        addField(msg("characterManager.fieldVisiblePulse"), visiblePulseBox)
        addField(msg("characterManager.fieldTriggerText"), JBScrollPane(triggerField))
        addField(msg("characterManager.fieldEnhEffect"), JBScrollPane(effectField))
        addField(msg("characterManager.fieldTriggerEffectMode"), triggerModeBox)
        addWide(msg("characterManager.fieldTriggerEffects"), triggerPicker)
        addWide(msg("characterManager.fieldOutputEffects"), outputPicker)
        return JPanel(BorderLayout()).apply {
            add(form, BorderLayout.CENTER)
            preferredSize = Dimension(820, 640)
        }
    }

    fun formValues(): Map<String, String> = mapOf(
        "name" to nameField.text.trim(),
        "trigger_text" to triggerField.text.trim(),
        "trigger_effects" to EffectParamCodec.ids(triggerPicker.selectedParams()),
        "trigger_effect_mode" to (triggerModeBox.selectedItem as? String ?: "all"),
        "enhancement_effect" to effectField.text.trim(),
        "effects" to EffectParamCodec.encode(outputPicker.selectedParams()),
        "visible_pulse" to visiblePulseBox.isSelected.toString(),
    )
}

/** 强化组对话框的初始值。 */
data class CharacterEnhancementSeed(
    val name: String = "",
    val triggerText: String = "",
    val triggerEffectMode: String = "all",
    val triggerEffects: List<EffectParam> = emptyList(),
    val outputEffects: List<EffectParam> = emptyList(),
    val enhancementEffect: String = "",
    val visiblePulse: Boolean = false,
)

/** 新增效果：ID / 描述 / 所属分类（分类必须先存在于 effects.py）。 */
class EffectDialog(
    project: Project,
    categories: List<String>,
) : FormDialog(project) {

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
