package com.alicejump.okscripttoolkit.tasklauncher

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.ui.components.JBScrollPane
import java.awt.Component
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.JTextField

/** An editor for the same global config snapshot that is injected into run_executor.py. */
internal object GlobalConfigEditor {
    private val mapper = ObjectMapper()

    data class Result(val values: Map<String, Any?>)

    private data class FieldControl(
        val field: TaskLauncherService.TaskParamField,
        val component: JComponent,
        val read: () -> Any?,
    )

    fun show(
        parent: Component,
        group: TaskLauncherService.GlobalConfigGroup,
        existing: Map<String, Any?>,
        sparse: Boolean = false,
    ): Result? {
        val controls = group.fields.map { field ->
            val value = if (existing.containsKey(field.key)) existing[field.key] else field.value ?: field.default
            makeControl(field, value)
        }
        // 比较控件的初始读数，而不是 schema 原始值：某些控件会把 null 呈现为
        // 未勾选或空文本。账号覆盖保存时，这些未触碰控件不能变成新的覆盖键。
        val initialValues = controls.associate { control ->
            control.field.key to runCatching { control.read() }.getOrNull()
        }
        val form = JPanel(GridBagLayout())
        for ((index, control) in controls.withIndex()) {
            val field = control.field
            form.add(JLabel(field.displayKey ?: field.key).apply {
                toolTipText = field.displayDesc ?: field.desc ?: field.key
            }, GridBagConstraints().apply {
                gridx = 0; gridy = index; anchor = GridBagConstraints.WEST
                insets = Insets(4, 8, 4, 8)
            })
            form.add(control.component, GridBagConstraints().apply {
                gridx = 1; gridy = index; weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                insets = Insets(4, 0, 4, 8)
            })
        }
        val scroll = JBScrollPane(form).apply {
            preferredSize = Dimension(640, (controls.size * 42 + 16).coerceIn(120, 480))
            border = null
        }
        val options = if (sparse) arrayOf(
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
                            if (!sparse || value != initialValues[control.field.key]) {
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
                    if (sparse) return null
                    val values = existing.toMutableMap()
                    for (field in group.fields) {
                        if (!values.containsKey(field.key)) values[field.key] = field.default ?: field.value
                    }
                    return Result(values)
                }
                2 -> if (!sparse) return Result(GlobalSnapshotRules.resetToDefaults(existing, group.fields)) else return null
                else -> return null
            }
        }
    }

    private fun makeControl(field: TaskLauncherService.TaskParamField, value: Any?): FieldControl {
        val typeName = field.type?.get("type")?.toString().orEmpty()
        val options = field.type?.get("options") as? List<*>
        if (!options.isNullOrEmpty() && (typeName == "drop_down" || value !is List<*>)) {
            val combo = JComboBox(options.toTypedArray())
            val index = options.indexOf(value)
            if (index >= 0) combo.selectedIndex = index
            return FieldControl(field, combo) { combo.selectedItem }
        }
        if (typeName == "bool" || value is Boolean) {
            val check = JCheckBox().apply { isSelected = value == true }
            return FieldControl(field, check) { check.isSelected }
        }
        if (value is Number) {
            val input = JTextField(value.toString(), 32)
            return FieldControl(field, input) {
                val text = input.text.trim()
                if (text.isEmpty()) null else when (value) {
                    is Byte, is Short, is Int -> text.toIntOrNull() ?: throw IllegalArgumentException()
                    is Long -> text.toLongOrNull() ?: throw IllegalArgumentException()
                    else -> text.toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw IllegalArgumentException()
                }
            }
        }
        if (value is List<*> || value is Map<*, *> || typeName == "multi_selection" || typeName == "cond_sequence_editor") {
            val input = JTextArea(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value ?: emptyList<Any>()), 5, 40)
            input.lineWrap = true
            return FieldControl(field, JBScrollPane(input)) {
                val parsed = try { mapper.readTree(input.text) } catch (_: Exception) { null }
                    ?: throw IllegalArgumentException()
                if ((value is List<*> && !parsed.isArray) || (value is Map<*, *> && !parsed.isObject)) {
                    throw IllegalArgumentException()
                }
                mapper.convertValue(parsed, Any::class.java)
            }
        }
        val input = if (typeName == "text_edit" || (value is String && (value.contains('\n') || value.length > 80))) {
            JTextArea(value?.toString().orEmpty(), 4, 40).apply { lineWrap = true }
        } else {
            JTextField(value?.toString().orEmpty(), 40)
        }
        val component = if (input is JTextArea) JBScrollPane(input) else input
        return FieldControl(field, component) { input.text }
    }
}
