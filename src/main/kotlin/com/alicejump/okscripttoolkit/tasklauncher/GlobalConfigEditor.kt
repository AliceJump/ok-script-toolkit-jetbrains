package com.alicejump.okscripttoolkit.tasklauncher

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Rectangle
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.Scrollable
import javax.swing.SpinnerNumberModel
import javax.swing.SwingConstants
import javax.swing.text.JTextComponent

/** An editor for the same global config snapshot that is injected into run_executor.py. */
internal object GlobalConfigEditor {
    private val mapper = ObjectMapper()

    data class Result(val values: Map<String, Any?>)

    internal data class FieldControl(
        val field: TaskLauncherService.TaskParamField,
        val component: JComponent,
        val read: () -> Any?,
        val booleanControl: JCheckBox? = null,
    )

    fun show(
        parent: Component,
        group: TaskLauncherService.GlobalConfigGroup,
        existing: Map<String, Any?>,
        accountOverride: Boolean = false,
        project: Project? = null,
    ): Result? {
        val controls = group.fields.map { field ->
            val value = if (existing.containsKey(field.key)) existing[field.key] else field.value ?: field.default
            makeControl(field, value, project)
        }
        // 比较控件的初始读数，而不是 schema 原始值：某些控件会把 null 呈现为
        // 未勾选或空文本。未触碰的字段不能在保存其它字段时被改写。
        val initialValues = controls.associate { control ->
            control.field.key to runCatching { control.read() }.getOrNull()
        }
        val form = object : JPanel(GridBagLayout()), Scrollable {
            override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
            override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = 16
            override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) =
                (visibleRect.height - 16).coerceAtLeast(16)
            override fun getScrollableTracksViewportWidth() = true
            override fun getScrollableTracksViewportHeight() = false
        }
        var rowIndex = 0
        if (!group.description.isNullOrBlank()) {
            form.add(SchemaFieldUi.WrappingDescription(group.description), GridBagConstraints().apply {
                gridx = 0; gridy = rowIndex++; weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                insets = Insets(8, 12, 8, 12)
            })
        }
        rowIndex = addFieldRows(form, controls, rowIndex, Insets(1, 8, 1, 8), defaultOpen = accountOverride)
        form.add(JPanel().apply { isOpaque = false }, GridBagConstraints().apply {
            gridx = 0; gridy = rowIndex; weighty = 1.0; fill = GridBagConstraints.BOTH
        })
        val scroll = JBScrollPane(form).apply {
            preferredSize = Dimension(560, (controls.size * 90 + 32).coerceIn(160, 500))
            border = null
        }
        val options = if (accountOverride) arrayOf(
            OkScriptToolkitBundle.message("annotation.save"),
            OkScriptToolkitBundle.message("annotation.cancel"),
        ) else arrayOf(
            OkScriptToolkitBundle.message("annotation.save"),
            OkScriptToolkitBundle.message("taskLauncher.gconfigSync"),
            OkScriptToolkitBundle.message("taskLauncher.gconfigReset"),
            OkScriptToolkitBundle.message("annotation.cancel"),
        )

        while (true) {
            when (JOptionPane.showOptionDialog(
                parent,
                scroll,
                group.displayName ?: group.name,
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.PLAIN_MESSAGE,
                null,
                options,
                options[0],
            )) {
                0 -> {
                    val values = existing.toMutableMap() // preserve keys no longer in the schema
                    var invalid: String? = null
                    for (control in controls) {
                        try {
                            val value = control.read()
                            if (value != initialValues[control.field.key]) {
                                values[control.field.key] = value
                            }
                        } catch (_: IllegalArgumentException) {
                            invalid = control.field.displayKey ?: control.field.key
                            break
                        }
                    }
                    if (invalid != null) {
                        JOptionPane.showMessageDialog(
                            parent,
                            OkScriptToolkitBundle.message("taskLauncher.gconfigInvalidValue", invalid),
                            group.displayName ?: group.name,
                            JOptionPane.ERROR_MESSAGE,
                        )
                        continue
                    }
                    return Result(values)
                }
                1 -> {
                    if (accountOverride) return null
                    val values = existing.toMutableMap()
                    for (field in group.fields) {
                        if (!values.containsKey(field.key)) values[field.key] = field.defaultOrValue()
                    }
                    return Result(values)
                }
                2 -> if (!accountOverride) return Result(GlobalSnapshotRules.resetToDefaults(existing, group.fields)) else return null
                else -> return null
            }
        }
    }

    private data class OptionSection(val id: String, val parent: String, val label: String, val children: List<String>)

    /** Render non-boolean sub_configs as option groups, sharing one control for keys in several choices. */
    internal fun addFieldRows(
        form: JPanel,
        controls: List<FieldControl>,
        startRow: Int,
        rowInsets: Insets,
        defaultOpen: Boolean = false,
        isOpen: (String) -> Boolean = { defaultOpen },
        onOpenChanged: (String, Boolean) -> Unit = { _, _ -> },
    ): Int {
        val byKey = controls.associateBy { it.field.key }
        val sections = mutableListOf<OptionSection>()
        for (control in controls) {
            if (control.booleanControl != null) continue
            val rules = control.field.type?.get("sub_configs") as? Map<*, *> ?: continue
            val labels = control.field.type["sub_config_labels"] as? Map<*, *>
            for ((choice, children) in rules) {
                val keys = when (children) {
                    is String -> listOf(children)
                    is List<*> -> children.filterIsInstance<String>()
                    else -> emptyList()
                }.distinct().filter { it != control.field.key && it in byKey }
                if (keys.isEmpty()) continue
                val choiceKey = choice.toString()
                sections += OptionSection(
                    id = "${control.field.key}::$choiceKey",
                    parent = control.field.key,
                    label = labels?.get(choice)?.toString() ?: choiceKey,
                    children = keys,
                )
            }
        }
        val sectionsByParent = sections.groupBy { it.parent }
        val childCounts = sections.flatMap { it.children }.groupingBy { it }.eachCount()
        val uniqueChildren = childCounts.filterValues { it == 1 }.keys
        val rowsByKey = linkedMapOf<String, JPanel>()
        val sectionViews = mutableListOf<Pair<JPanel, List<String>>>()
        val rowCounters = hashMapOf<JPanel, Int>(form to startRow)
        val rendered = hashSetOf<String>()

        fun addRow(container: JPanel, component: JComponent) {
            val row = rowCounters.getOrDefault(container, 0)
            rowCounters[container] = row + 1
            container.add(component, GridBagConstraints().apply {
                gridx = 0; gridy = row; weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.NORTHWEST
                insets = rowInsets
            })
        }

        lateinit var renderField: (String, JPanel) -> Unit
        fun renderSection(section: OptionSection, container: JPanel) {
            val keys = section.children.filter { it in uniqueChildren }
            if (keys.isEmpty()) return
            val body = JPanel(GridBagLayout()).apply { isOpaque = false }
            val expanded = isOpen(section.id)
            body.isVisible = expanded
            val button = JButton(
                section.label,
                if (expanded) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight,
            ).apply {
                horizontalAlignment = SwingConstants.LEFT
                isBorderPainted = false
                isContentAreaFilled = false
            }
            button.addActionListener {
                val open = !body.isVisible
                body.isVisible = open
                button.icon = if (open) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
                onOpenChanged(section.id, open)
                form.revalidate()
                form.repaint()
            }
            val panel = JPanel(BorderLayout(0, 4)).apply {
                isOpaque = false
                border = javax.swing.BorderFactory.createCompoundBorder(
                    javax.swing.BorderFactory.createLineBorder(JBColor.border()),
                    javax.swing.BorderFactory.createEmptyBorder(4, 8, 6, 8),
                )
                add(button, BorderLayout.NORTH)
                add(body, BorderLayout.CENTER)
            }
            addRow(container, panel)
            sectionViews += panel to keys
            keys.forEach { renderField(it, body) }
        }

        renderField = { key, container ->
            val control = byKey[key]
            if (control != null && rendered.add(key)) {
                val row = SchemaFieldUi.row(control.field, control.component)
                rowsByKey[key] = row
                addRow(container, row)
                sectionsByParent[key].orEmpty().forEach { renderSection(it, container) }
            }
        }
        controls.filter { it.field.key !in uniqueChildren }.forEach { renderField(it.field.key, form) }
        // Bad or cyclic metadata must not make a field disappear.
        controls.forEach { renderField(it.field.key, form) }
        installVisibility(controls, rowsByKey, form, sectionViews)
        return rowCounters.getOrDefault(form, startRow)
    }

    /** Boolean sub_configs follow the same inline visibility rules as task parameters. */
    internal fun installVisibility(
        controls: List<FieldControl>,
        rowsByKey: Map<String, JPanel>,
        form: JPanel,
        optionSections: List<Pair<JPanel, List<String>>> = emptyList(),
    ) {
        val byKey = controls.associateBy { it.field.key }
        val rules = linkedMapOf<String, Map<Boolean, List<String>>>()
        val parentsByChild = linkedMapOf<String, MutableList<String>>()
        for (control in controls) {
            if (control.booleanControl == null) continue
            val raw = control.field.type?.get("sub_configs") as? Map<*, *> ?: continue
            val choices = linkedMapOf<Boolean, List<String>>()
            for ((choice, children) in raw) {
                val boolean = when (choice.toString().lowercase()) {
                    "true" -> true
                    "false" -> false
                    else -> continue
                }
                val keys = when (children) {
                    is String -> listOf(children)
                    is List<*> -> children.filterIsInstance<String>()
                    else -> emptyList()
                }
                choices[boolean] = keys
                keys.forEach { child -> parentsByChild.getOrPut(child) { mutableListOf() }.add(control.field.key) }
            }
            if (choices.isNotEmpty()) rules[control.field.key] = choices
        }
        fun visible(key: String, checking: Set<String> = emptySet()): Boolean {
            if (key in checking) return false
            return parentsByChild[key].orEmpty().all { parent ->
                val parentControl = byKey[parent] ?: return@all false
                visible(parent, checking + key) &&
                    rules[parent]?.get(parentControl.booleanControl?.isSelected == true)?.contains(key) == true
            }
        }
        val refresh = {
            rowsByKey.forEach { (key, row) -> row.isVisible = visible(key) }
            optionSections.forEach { (section, keys) ->
                section.isVisible = keys.any { rowsByKey[it]?.isVisible == true }
            }
            form.revalidate()
            form.repaint()
        }
        controls.forEach { it.booleanControl?.addActionListener { refresh() } }
        refresh()
    }

    internal fun makeControl(
        field: TaskLauncherService.TaskParamField,
        value: Any?,
        project: Project? = null,
        onChanged: (() -> Unit)? = null,
    ): FieldControl {
        val typeName = field.type?.get("type")?.toString().orEmpty()
        val options = field.type?.get("options") as? List<*>
        val labels = field.type?.get("option_labels") as? List<*> ?: emptyList<Any>()
        if (typeName == "cascade_drop_down" && field.type?.get("options") is Map<*, *>) {
            val groups = field.type["options"] as Map<*, *>
            val categoryLabels = field.type["category_labels"] as? Map<*, *>
            val optionLabels = field.type["option_labels"] as? Map<*, *>
            val groupCombo = JComboBox<Any?>().apply { groups.keys.forEach { addItem(it) } }
            val leafCombo = JComboBox<Any?>()
            groupCombo.renderer = labeledRenderer { option ->
                categoryLabels?.get(option)?.toString() ?: option?.toString().orEmpty()
            }
            leafCombo.renderer = labeledRenderer { option ->
                val currentGroup = groupCombo.selectedItem
                val values = groups[currentGroup] as? List<*>
                val index = values?.indexOf(option) ?: -1
                val groupLabels = optionLabels?.get(currentGroup) as? List<*>
                groupLabels?.getOrNull(index)?.toString() ?: option?.toString().orEmpty()
            }
            fun fillLeaves(group: Any?) {
                leafCombo.removeAllItems()
                (groups[group] as? List<*>)?.forEach { leafCombo.addItem(it) }
            }
            val owner = groups.entries.firstOrNull { (_, leaves) ->
                (leaves as? List<*>)?.any { it == value || it?.toString() == value?.toString() } == true
            }?.key
            if (owner != null) groupCombo.selectedItem = owner
            fillLeaves(groupCombo.selectedItem)
            if (value != null) {
                val selected = (groups[groupCombo.selectedItem] as? List<*>)?.firstOrNull {
                    it == value || it?.toString() == value.toString()
                }
                if (selected != null) leafCombo.selectedItem = selected
            }
            groupCombo.addActionListener { fillLeaves(groupCombo.selectedItem) }
            val panel = JPanel(BorderLayout(6, 0)).apply {
                isOpaque = false
                add(groupCombo, BorderLayout.WEST)
                add(leafCombo, BorderLayout.CENTER)
            }
            return FieldControl(field, panel, { leafCombo.selectedItem }).withChangeHook(onChanged)
        }
        if (!options.isNullOrEmpty() && (typeName == "drop_down" || (typeName.isEmpty() && value !is List<*>))) {
            val combo = JComboBox<Any?>()
            options.forEach { combo.addItem(it) }
            if (value != null && options.none { it == value || it?.toString() == value.toString() }) {
                combo.insertItemAt(value, 0)
            }
            combo.selectedItem = value
            combo.renderer = labeledRenderer { option ->
                val index = options.indexOfFirst { it == option || it?.toString() == option?.toString() }
                labels.getOrNull(index)?.toString() ?: option?.toString().orEmpty()
            }
            return FieldControl(field, combo, { combo.selectedItem }).withChangeHook(onChanged)
        }
        if (typeName == "bool" || value is Boolean) {
            val check = JCheckBox().apply { isSelected = value == true }
            return FieldControl(field, check, { check.isSelected }, check).withChangeHook(onChanged)
        }
        if (typeName == "multi_selection" || (value is List<*> && !options.isNullOrEmpty())) {
            val values = options.orEmpty()
            val list = JList<Any?>(values.toTypedArray())
            list.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
            list.cellRenderer = labeledRenderer { option ->
                val index = values.indexOfFirst { it == option || it?.toString() == option?.toString() }
                labels.getOrNull(index)?.toString() ?: option?.toString().orEmpty()
            }
            val selected = value as? List<*> ?: emptyList<Any>()
            list.selectedIndices = values.indices.filter { index ->
                selected.any { it == values[index] || it?.toString() == values[index]?.toString() }
            }.toIntArray()
            val scroll = JBScrollPane(list).apply { preferredSize = Dimension(240, 112) }
            return FieldControl(field, scroll, { list.selectedValuesList.toList() }).withChangeHook(onChanged)
        }
        if (value is Int) {
            val spinner = JSpinner(SpinnerNumberModel(value, Int.MIN_VALUE, Int.MAX_VALUE, 1))
            return FieldControl(field, spinner, { committedSpinnerValue(spinner).toInt() }).withChangeHook(onChanged)
        }
        if (value is Long) {
            val spinner = JSpinner(SpinnerNumberModel(value, Long.MIN_VALUE, Long.MAX_VALUE, 1L))
            return FieldControl(field, spinner, { committedSpinnerValue(spinner).toLong() }).withChangeHook(onChanged)
        }
        if (value is Number) {
            val spinner = JSpinner(SpinnerNumberModel(value.toDouble(), -Double.MAX_VALUE, Double.MAX_VALUE, 0.1))
            return FieldControl(field, spinner, { committedSpinnerValue(spinner).toDouble() }).withChangeHook(onChanged)
        }
        if (value is List<*> || value is Map<*, *> || typeName == "cond_sequence_editor") {
            if (value is List<*> && value.none { it is Map<*, *> } && typeName != "cond_sequence_editor") {
                val editor = ListEditorComponent(
                    project = project,
                    dialogTitle = field.displayKey ?: field.key,
                    typeMeta = field.type,
                    initialValue = value.toList(),
                    // 列表编辑器自带 onChanged（签名是 (List<Any?>) -> Unit），在创建点直接接
                    onChanged = { onChanged?.invoke() },
                )
                return FieldControl(field, editor, { editor.value })
            }
            val input = JTextArea(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value ?: emptyList<Any>()), 5, 24).apply {
                lineWrap = true
                wrapStyleWord = true
            }
            val scroll = JBScrollPane(input).apply { preferredSize = Dimension(240, 110) }
            return FieldControl(field, scroll, {
                val parsed = try { mapper.readTree(input.text) } catch (_: Exception) { null }
                    ?: throw IllegalArgumentException()
                if ((value is List<*> || typeName == "cond_sequence_editor") && !parsed.isArray) throw IllegalArgumentException()
                if (value is Map<*, *> && !parsed.isObject) throw IllegalArgumentException()
                mapper.convertValue(parsed, Any::class.java)
            }).withChangeHook(onChanged)
        }
        val text = value?.toString().orEmpty()
        val input = if (typeName == "text_edit" || text.contains('\n') || text.length > 80) {
            JTextArea(text, 4, 24).apply { lineWrap = true; wrapStyleWord = true }
        } else {
            JTextField(text, 24)
        }
        val component = if (input is JTextArea) {
            JBScrollPane(input).apply { preferredSize = Dimension(240, 96) }
        } else input
        return FieldControl(field, component, { input.text }).withChangeHook(onChanged)
    }

    /**
     * 给控件挂「值变了」回调（配置页内联卡片「改动即存」用；对话框路径传 null 不挂）。
     * 按组件类型套对应的监听器；容器（JPanel/JScrollPane）递归下钻 ——
     * 级联下拉、多选列表等都包在容器里。[ListEditorComponent] 自带 onChanged
     * 构造参数，在创建点直接接，不走这里。
     */
    private fun FieldControl.withChangeHook(onChanged: (() -> Unit)?): FieldControl {
        if (onChanged == null) return this
        fun wire(component: JComponent) {
            when (component) {
                is JCheckBox -> component.addActionListener { onChanged() }
                is JComboBox<*> -> component.addActionListener { onChanged() }
                is JSpinner -> {
                    component.addChangeListener { onChanged() }
                    (component.editor as? JSpinner.DefaultEditor)?.textField?.document
                        ?.addDocumentListener(changeHookDocumentListener(onChanged))
                }
                is JList<*> -> component.addListSelectionListener { onChanged() }
                is JTextArea -> component.document.addDocumentListener(changeHookDocumentListener(onChanged))
                is JTextField -> component.document.addDocumentListener(changeHookDocumentListener(onChanged))
                is JScrollPane -> (component.viewport?.view as? JComponent)?.let { wire(it) }
                is JPanel -> component.components.filterIsInstance<JComponent>().forEach { wire(it) }
            }
        }
        wire(component)
        return this
    }

    /** Raw editor text is needed to keep an incomplete JSON or number across a form rebuild. */
    internal fun editableText(control: FieldControl): JTextComponent? {
        fun find(component: JComponent): JTextComponent? = when (component) {
            is JSpinner -> (component.editor as? JSpinner.DefaultEditor)?.textField
            is JTextComponent -> component
            is JScrollPane -> (component.viewport?.view as? JComponent)?.let { find(it) }
            is JPanel -> component.components.filterIsInstance<JComponent>().firstNotNullOfOrNull { find(it) }
            else -> null
        }
        return find(control.component)
    }

    private fun changeHookDocumentListener(onChanged: () -> Unit) = object : javax.swing.event.DocumentListener {
        override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = onChanged()
        override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = onChanged()
        override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = onChanged()
    }

    private fun labeledRenderer(label: (Any?) -> String) = object : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
        ): Component = super.getListCellRendererComponent(
            list, label(value), index, isSelected, cellHasFocus,
        )
    }

    private fun committedSpinnerValue(spinner: JSpinner): Number {
        try {
            spinner.commitEdit()
        } catch (_: java.text.ParseException) {
            throw IllegalArgumentException()
        }
        return spinner.value as Number
    }
}
