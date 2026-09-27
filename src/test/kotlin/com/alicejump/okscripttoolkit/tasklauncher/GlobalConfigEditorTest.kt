package com.alicejump.okscripttoolkit.tasklauncher

import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBLabel
import java.awt.Component
import java.awt.Container
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.JTextArea
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GlobalConfigEditorTest {
    @Test
    fun `option subconfigs form groups unique fields and keeps shared field once`() {
        val selector = TaskLauncherService.TaskParamField(
            key = "mode",
            type = mapOf(
                "type" to "drop_down",
                "options" to listOf("delivery", "collection"),
                "sub_configs" to linkedMapOf(
                    "delivery" to listOf("shared", "destination"),
                    "collection" to listOf("shared", "source"),
                ),
                "sub_config_labels" to mapOf("delivery" to "Delivery settings", "collection" to "Collection settings"),
            ),
        )
        val fields = listOf(
            selector,
            TaskLauncherService.TaskParamField(key = "shared"),
            TaskLauncherService.TaskParamField(key = "destination"),
            TaskLauncherService.TaskParamField(key = "source"),
        )
        val controls = fields.map { GlobalConfigEditor.makeControl(it, if (it.key == "mode") "delivery" else "value") }
        val form = JPanel(GridBagLayout())
        val toggles = mutableMapOf<String, Boolean>()
        val rows = GlobalConfigEditor.addFieldRows(
            form, controls, 0, Insets(0, 0, 0, 0),
            onOpenChanged = { key, open -> toggles[key] = open },
        )

        fun descendants(component: Component): Sequence<Component> = sequence {
            yield(component)
            if (component is Container) component.components.forEach { yieldAll(descendants(it)) }
        }
        val labels = descendants(form).filterIsInstance<JBLabel>().map { it.text }.toList()
        val buttons = descendants(form).filterIsInstance<JButton>().filter { it.text.isNotBlank() }.toList()
        assertEquals(4, rows) // selector, shared field, and two option groups
        assertEquals(1, labels.count { it == "shared" })
        assertEquals(1, labels.count { it == "destination" })
        assertEquals(1, labels.count { it == "source" })
        assertEquals(listOf("Delivery settings", "Collection settings"), buttons.map { it.text })
        val body = (buttons.first().parent as JPanel).components.filterIsInstance<JPanel>().single()
        assertFalse(body.isVisible)
        buttons.first().doClick()
        assertTrue(body.isVisible)
        assertEquals(true, toggles["mode::delivery"])
    }

    @Test
    fun `account override controls keep schema values and localized dropdown labels`() {
        val field = TaskLauncherService.TaskParamField(
            key = "mode",
            type = mapOf("type" to "drop_down", "options" to listOf("fast", "safe"), "option_labels" to listOf("快速", "安全")),
        )
        val control = GlobalConfigEditor.makeControl(field, "safe")
        @Suppress("UNCHECKED_CAST")
        val combo = assertIs<JComboBox<*>>(control.component) as JComboBox<Any?>
        assertEquals("safe", control.read())
        val selected = combo.renderer.getListCellRendererComponent(JList<Any>(), "safe", 1, false, false)
        assertEquals("安全", (selected as javax.swing.JLabel).text)
        combo.selectedItem = "fast"
        assertEquals("fast", control.read())
    }

    @Test
    fun `multi selection uses original option values`() {
        val field = TaskLauncherService.TaskParamField(
            key = "regions",
            type = mapOf("type" to "multi_selection", "options" to listOf("a", "b", "c")),
        )
        val control = GlobalConfigEditor.makeControl(field, listOf("b"))
        val list = (assertIs<JBScrollPane>(control.component).viewport.view as JList<*>)
        assertEquals(listOf("b"), control.read())
        list.selectedIndices = intArrayOf(0, 2)
        assertEquals(listOf("a", "c"), control.read())
    }

    @Test
    fun `cascade dropdown keeps the selected leaf value`() {
        val field = TaskLauncherService.TaskParamField(
            key = "route",
            type = mapOf(
                "type" to "cascade_drop_down",
                "options" to linkedMapOf("north" to listOf("n1", "n2"), "south" to listOf("s1")),
            ),
        )
        val control = GlobalConfigEditor.makeControl(field, "n2")
        val panel = assertIs<JPanel>(control.component)
        val group = assertIs<JComboBox<*>>(panel.getComponent(0))
        assertEquals("north", group.selectedItem)
        assertEquals("n2", control.read())
        group.selectedItem = "south"
        assertEquals("s1", control.read())
    }

    @Test
    fun `numeric editor commits typed text before reading`() {
        val field = TaskLauncherService.TaskParamField(key = "count")
        val control = GlobalConfigEditor.makeControl(field, 3)
        val spinner = assertIs<JSpinner>(control.component)
        (spinner.editor as JSpinner.DefaultEditor).textField.text = "7"
        assertEquals(7, control.read())
    }

    @Test
    fun `incomplete numeric and JSON text can be restored after a form rebuild`() {
        var changes = 0
        val number = GlobalConfigEditor.makeControl(
            TaskLauncherService.TaskParamField(key = "count"), 3, onChanged = { changes++ },
        )
        val numericText = assertNotNull(GlobalConfigEditor.editableText(number))
        numericText.text = "not a number"
        assertTrue(changes > 0)
        assertFailsWith<IllegalArgumentException> { number.read() }

        val field = TaskLauncherService.TaskParamField(key = "payload")
        val json = GlobalConfigEditor.makeControl(field, mapOf("enabled" to true))
        val draft = assertNotNull(GlobalConfigEditor.editableText(json))
        draft.text = "{"
        assertFailsWith<IllegalArgumentException> { json.read() }
        val rebuilt = GlobalConfigEditor.makeControl(field, mapOf("enabled" to true))
        assertNotNull(GlobalConfigEditor.editableText(rebuilt)).text = draft.text
        assertEquals("{", GlobalConfigEditor.editableText(rebuilt)?.text)
    }

    @Test
    fun `list editor summary wraps plain text without HTML markup`() {
        val field = TaskLauncherService.TaskParamField(key = "items")
        val control = GlobalConfigEditor.makeControl(field, listOf("<first>", "second item with long text"))
        val editor = assertIs<ListEditorComponent>(control.component)
        val summary = editor.components.filterIsInstance<JTextArea>().single()
        assertTrue(summary.lineWrap)
        assertTrue(summary.text.contains("<first>"))
        assertFalse(summary.text.contains("<html>"))
    }

    @Test
    fun `boolean subfields follow the live switch`() {
        val parentField = TaskLauncherService.TaskParamField(
            key = "enabled",
            type = mapOf("type" to "bool", "sub_configs" to mapOf("true" to listOf("detail"))),
        )
        val childField = TaskLauncherService.TaskParamField(key = "detail")
        val controls = listOf(
            GlobalConfigEditor.makeControl(parentField, false),
            GlobalConfigEditor.makeControl(childField, "value"),
        )
        val rows = controls.associate { it.field.key to SchemaFieldUi.row(it.field, it.component) }
        GlobalConfigEditor.installVisibility(controls, rows, JPanel())
        assertFalse(rows.getValue("detail").isVisible)
        assertIs<JCheckBox>(controls.first().component).doClick()
        assertTrue(rows.getValue("detail").isVisible)
    }
}
