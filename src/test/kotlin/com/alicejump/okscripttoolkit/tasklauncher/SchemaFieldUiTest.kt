package com.alicejump.okscripttoolkit.tasklauncher

import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.JTextField
import kotlin.test.Test
import kotlin.test.assertTrue

class SchemaFieldUiTest {
    @Test
    fun `long description wraps when the available width shrinks`() {
        val description = SchemaFieldUi.WrappingDescription(
            "A configuration explanation with several words that should occupy multiple lines in a narrow task pane.",
        )
        val host = JPanel(BorderLayout()).apply { add(description, BorderLayout.CENTER) }
        host.setSize(360, 400)
        host.doLayout()
        val wideHeight = description.preferredSize.height
        host.setSize(150, 400)
        host.doLayout()
        val narrowHeight = description.preferredSize.height
        assertTrue(narrowHeight > wideHeight, "description must reflow with the detail pane")
    }

    @Test
    fun `long control and label do not set the minimum pane width`() {
        val field = TaskLauncherService.TaskParamField(
            key = "key",
            displayKey = "A very long configuration field label that should not force horizontal scrolling",
            desc = "A long description that wraps within the available width.",
        )
        val row = SchemaFieldUi.row(field, JTextField("x", 80))
        assertTrue(row.preferredSize.width <= 300)
        assertTrue(row.minimumSize.width <= 100)
    }
}
