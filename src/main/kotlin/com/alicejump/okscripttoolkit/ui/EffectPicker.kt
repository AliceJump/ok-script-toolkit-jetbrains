package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.alicejump.okscripttoolkit.core.CharacterEffectRef
import com.alicejump.okscripttoolkit.core.CharacterEffectView
import com.alicejump.okscripttoolkit.core.EffectParam
import com.intellij.openapi.ui.Splitter
import com.intellij.ui.CheckBoxList
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

private fun msg(key: String, vararg params: Any): String =
    if (params.isEmpty()) OkScriptToolkitBundle.message(key) else OkScriptToolkitBundle.message(key, *params)

/** 下拉里的一项，等价于 VSCode `<select multiple>` 里的一个 `<option>`。 */
data class EffectOption(
    val id: String,
    val displayName: String,
    val description: String,
    val category: String,
    val defined: Boolean,
) {
    /** 行文本，与 VSCode 的 `${displayName} · ${id} — ${description}` 一致。 */
    val rowText: String
        get() {
            val head = if (displayName.isBlank() || displayName == id) id else "$displayName · $id"
            return if (description.isBlank()) head else "$head — $description"
        }
}

fun List<CharacterEffectView>.toEffectOptions(): List<EffectOption> = map { view ->
    EffectOption(
        id = view.id,
        displayName = view.displayName,
        description = view.description,
        category = view.category,
        defined = view.defined,
    )
}

fun CharacterEffectRef.toEffectParam(): EffectParam = EffectParam(
    effectId = effectId,
    value = value?.toString() ?: "0",
    duration = duration?.toString().orEmpty(),
    target = target ?: "enemy",
    count = count?.toString() ?: "1",
)

private const val ALL_CATEGORIES = ""

/** 与 VSCode 的 `__undefined__` 一致。 */
private const val UNDEFINED_CATEGORY = "__undefined__"

/** 效果多选器：分类筛选 + 搜索 + 复选框多选 + 每个已选项的参数网格。 */
class EffectPickerField(
    options: List<EffectOption>,
    initial: List<EffectParam>,
    /** true = 只要 ID（触发依赖效果在真实数据里是纯字符串数组）。 */
    private val idsOnly: Boolean,
) : JPanel(BorderLayout(0, 6)) {

    /** VSCode 会把「已引用但未定义」的 ID 也塞进选项列表，这里照做。 */
    private val allOptions: List<EffectOption> = options + initial
        .filter { param -> param.effectId.isNotBlank() && options.none { it.id == param.effectId } }
        .map { param -> EffectOption(param.effectId, param.effectId, "", UNDEFINED_CATEGORY, defined = false) }

    private class MutableParam(
        var value: String,
        var duration: String,
        var target: String,
        var count: String,
    )

    /** 已输入的参数按 ID 保留：取消勾选再勾回来，之前填的值还在（对齐 VSCode 的 values map）。 */
    private val params = LinkedHashMap<String, MutableParam>()

    /**
     * 勾选状态的唯一真相。不能从列表反查——筛掉的行不在列表里，
     * 反查会把用户已勾选的效果悄悄丢掉。
     */
    private val selected = LinkedHashSet<String>()

    private val categoryBox = JComboBox<String>()
    private val searchField = SearchTextField()
    private val list = EffectList()
    private val countLabel = JBLabel()
    private val paramsPanel = JPanel()
    private val paramsScroll = JBScrollPane(paramsPanel)

    /** 下拉里显示的是本地化后的分类名，这里记回原始值用于筛选。 */
    private val categoryLabels = LinkedHashMap<String, String>()

    private val visibleOptions: List<EffectOption>
        get() {
            val label = categoryBox.selectedItem as? String
            val category = categoryLabels[label] ?: ALL_CATEGORIES
            val query = searchField.text.trim().lowercase()
            return allOptions.filter { option ->
                (category == ALL_CATEGORIES || option.category == category) &&
                    (query.isEmpty() || option.rowText.lowercase().contains(query) || option.id.lowercase().contains(query))
            }
        }

    init {
        for (param in initial) {
            params[param.effectId] = MutableParam(param.value, param.duration, param.target, param.count)
            if (param.effectId.isNotBlank()) selected += param.effectId
        }

        categoryLabels[msg("characterManager.picker.allCategories")] = ALL_CATEGORIES
        allOptions.map { it.category }.distinct().sorted().forEach { raw ->
            val label = if (raw == UNDEFINED_CATEGORY) msg("characterManager.effect.undefined") else raw
            categoryLabels[label] = raw
        }
        categoryLabels.keys.forEach { categoryBox.addItem(it) }
        categoryBox.addActionListener { refill() }

        searchField.textEditor.emptyText.text = msg("characterManager.effect.search")
        searchField.textEditor.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = refill()
            override fun removeUpdate(e: DocumentEvent) = refill()
            override fun changedUpdate(e: DocumentEvent) = refill()
        })

        list.setCheckBoxListListener { index, checked ->
            val option = list.getItemAt(index) ?: return@setCheckBoxListListener
            if (checked) {
                selected += option.id
                params.putIfAbsent(option.id, MutableParam("0", "", "enemy", "1"))
            } else {
                selected -= option.id
            }
            renderParams()
            updateCount()
        }

        val filterRow = JPanel(BorderLayout(6, 0))
        filterRow.add(categoryBox, BorderLayout.WEST)
        filterRow.add(searchField, BorderLayout.CENTER)

        val listScroll = JBScrollPane(list)
        listScroll.minimumSize = Dimension(260, 160)
        paramsScroll.minimumSize = Dimension(240, 160)

        val split = if (idsOnly) null else Splitter(false, 0.5f).apply {
            firstComponent = listScroll
            secondComponent = paramsScroll
        }

        add(filterRow, BorderLayout.NORTH)
        add(split ?: listScroll, BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply { add(countLabel, BorderLayout.WEST) }, BorderLayout.SOUTH)

        preferredSize = Dimension(760, 320)
        minimumSize = Dimension(520, 220)

        refill()
        renderParams()
        updateCount()
    }

    /** 按当前筛选重建复选框列表；勾选状态来自 selected，不受筛选影响。 */
    private fun refill() {
        list.clear()
        for (option in visibleOptions) list.addItem(option, option.rowText, option.id in selected)
        if (visibleOptions.isEmpty()) {
            list.emptyText.text = msg("characterManager.picker.noMatch")
        }
        revalidate()
        repaint()
    }

    /** 已勾选的 ID，按选项表顺序返回，保证写回顺序稳定。 */
    private fun checkedIds(): List<String> = allOptions.map { it.id }.filter { it in selected }

    private fun updateCount() {
        countLabel.text = msg("characterManager.picker.selected", checkedIds().size)
    }

    private fun renderParams() {
        paramsPanel.removeAll()
        paramsPanel.layout = BoxLayout(paramsPanel, BoxLayout.Y_AXIS)
        val checked = checkedIds()
        if (idsOnly) {
            paramsPanel.add(JBLabel(msg("characterManager.picker.idsHint")).apply {
                componentStyle = UIUtil.ComponentStyle.SMALL
                fontColor = UIUtil.FontColor.BRIGHTER
            })
            paramsPanel.add(JBLabel(msg("characterManager.picker.selectedIds", checked.joinToString("、").ifBlank { "—" })))
        } else if (checked.isEmpty()) {
            paramsPanel.add(JBLabel(msg("characterManager.picker.empty")))
        } else {
            for (id in checked) {
                val option = allOptions.firstOrNull { it.id == id } ?: continue
                paramsPanel.add(buildParamBlock(option, params[id] ?: continue))
            }
        }
        paramsPanel.revalidate()
        paramsPanel.repaint()
    }

    private fun buildParamBlock(option: EffectOption, param: MutableParam): JComponent {
        val block = JPanel(GridBagLayout())
        block.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()),
            JBUI.Borders.empty(6, 4, 8, 4),
        )
        val inset = JBUI.insets(2, 2, 2, 8)
        var row = 0

        fun addRow(label: String, control: JComponent) {
            block.add(JBLabel(label).apply {
                componentStyle = UIUtil.ComponentStyle.SMALL
            }, GridBagConstraints().apply {
                gridx = 0; gridy = row; anchor = GridBagConstraints.WEST; insets = inset
            })
            block.add(control, GridBagConstraints().apply {
                gridx = 1; gridy = row; fill = GridBagConstraints.HORIZONTAL; weightx = 1.0; insets = JBUI.insets(2, 0, 2, 0)
            })
            row++
        }

        block.add(JBLabel(option.displayName.ifBlank { option.id }).apply {
            font = font.deriveFont(java.awt.Font.BOLD)
        }, GridBagConstraints().apply {
            gridx = 0; gridy = row++; gridwidth = 2; anchor = GridBagConstraints.WEST; insets = JBUI.insets(0, 2, 2, 0)
        })
        if (option.description.isNotBlank()) {
            block.add(JBLabel(option.description).apply {
                componentStyle = UIUtil.ComponentStyle.SMALL
                fontColor = UIUtil.FontColor.BRIGHTER
            }, GridBagConstraints().apply {
                gridx = 0; gridy = row++; gridwidth = 2; anchor = GridBagConstraints.WEST; insets = JBUI.insets(0, 2, 4, 0)
            })
        }
        if (!option.defined) {
            block.add(JBLabel(msg("characterManager.effect.undefined")).apply {
                fontColor = UIUtil.FontColor.BRIGHTER
            }, GridBagConstraints().apply {
                gridx = 0; gridy = row++; gridwidth = 2; anchor = GridBagConstraints.WEST; insets = JBUI.insets(0, 2, 4, 0)
            })
        }

        val valueField = JBTextField(param.value)
        valueField.document.addDocumentListener(simpleListener { param.value = valueField.text })
        addRow(msg("characterManager.picker.value"), valueField)

        val durationField = JBTextField(param.duration)
        durationField.document.addDocumentListener(simpleListener { param.duration = durationField.text })
        addRow(msg("characterManager.picker.duration"), durationField)

        val targetBox = JComboBox(arrayOf("enemy", "ally", "self"))
        targetBox.selectedItem = param.target
        targetBox.addActionListener { param.target = targetBox.selectedItem as? String ?: "enemy" }
        addRow(msg("characterManager.picker.target"), targetBox)

        val countField = JBTextField(param.count)
        countField.document.addDocumentListener(simpleListener { param.count = countField.text })
        addRow(msg("characterManager.picker.count"), countField)

        val wrapper = JPanel(BorderLayout())
        wrapper.add(block, BorderLayout.NORTH)
        return wrapper
    }

    private fun simpleListener(onChange: () -> Unit) = object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent) = onChange()
        override fun removeUpdate(e: DocumentEvent) = onChange()
        override fun changedUpdate(e: DocumentEvent) = onChange()
    }

    /** 当前勾选的效果（按选项表顺序，保证写回顺序稳定）。 */
    fun selectedParams(): List<EffectParam> {
        val checked = checkedIds().toSet()
        return allOptions.filter { it.id in checked }.map { option ->
            val param = params[option.id]
            if (param == null) EffectParam(option.id)
            else EffectParam(option.id, param.value, param.duration, param.target, param.count)
        }
    }

    private inner class EffectList : CheckBoxList<EffectOption>() {
        /** 次要行：分类 + 描述，让分组信息在筛选时依然可见。 */
        override fun getSecondaryText(index: Int): String? {
            val option = getItemAt(index) ?: return null
            val category = if (option.defined && option.category != UNDEFINED_CATEGORY) {
                option.category
            } else {
                msg("characterManager.effect.undefined")
            }
            return listOf(category, option.description).filter { it.isNotBlank() }.joinToString(" · ")
        }
    }
}
