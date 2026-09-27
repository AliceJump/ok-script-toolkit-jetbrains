package com.alicejump.okscripttoolkit.tasklauncher

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea

/** Field layout shared by task, global, and account configuration forms. */
internal object SchemaFieldUi {
    fun row(
        field: TaskLauncherService.TaskParamField,
        control: JComponent,
        indented: Boolean = false,
    ): JPanel {
        val label = JBLabel(field.displayKey ?: field.key).apply { toolTipText = field.key }
        val heading = JPanel(BorderLayout(0, 4)).apply {
            isOpaque = false
            add(label, BorderLayout.NORTH)
            val description = field.displayDesc ?: field.desc
            if (!description.isNullOrBlank()) add(WrappingDescription(description), BorderLayout.CENTER)
        }
        return object : JPanel(BorderLayout(0, 6)) {
            override fun getPreferredSize(): Dimension = super.getPreferredSize().apply {
                width = width.coerceAtMost(300)
            }

            override fun getMinimumSize(): Dimension = super.getMinimumSize().apply { width = 100 }
        }.apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(6, if (indented) 22 else 8, 6, 8)
            add(heading, BorderLayout.NORTH)
            add(control, BorderLayout.CENTER)
        }
    }

    /** JTextArea supplies native wrapping without embedding HTML in business code. */
    internal class WrappingDescription(text: String) : JTextArea(text) {
        init {
            isEditable = false
            isFocusable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            border = null
            foreground = UIUtil.getContextHelpForeground()
            font = font.deriveFont((font.size2D - 1f).coerceAtLeast(9f))
        }

        override fun getPreferredSize(): Dimension {
            val available = (parent?.width ?: 0).takeIf { it > 0 } ?: 280
            super.setSize(available, Int.MAX_VALUE)
            return super.getPreferredSize().apply { width = available }
        }

        override fun getMinimumSize(): Dimension = Dimension(80, getFontMetrics(font).height)
    }
}
