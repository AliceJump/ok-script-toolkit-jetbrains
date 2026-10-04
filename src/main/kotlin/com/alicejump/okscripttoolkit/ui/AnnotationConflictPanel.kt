package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.core.AnnotationConflict
import com.alicejump.okscripttoolkit.core.AnnotationConflictChoice
import com.alicejump.okscripttoolkit.core.MergeShape
import com.intellij.ui.components.JBLabel
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JScrollPane

/** Side-by-side choices for structured annotation conflicts. No file writes happen here. */
internal class AnnotationConflictPanel : JPanel(BorderLayout(0, 6)) {
    private val rows = JPanel()
    private val applyButton = JButton("Apply choices")
    private val choices = linkedMapOf<String, AnnotationConflictChoice>()
    private var conflicts: List<AnnotationConflict> = emptyList()
    private var onApply: ((Map<String, AnnotationConflictChoice>) -> Unit)? = null

    init {
        isVisible = false
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("External conflicts"),
            BorderFactory.createEmptyBorder(2, 4, 4, 4),
        )
        rows.layout = BoxLayout(rows, BoxLayout.Y_AXIS)
        val scroll = JScrollPane(rows)
        scroll.preferredSize = Dimension(260, 190)
        add(scroll, BorderLayout.CENTER)
        applyButton.isEnabled = false
        applyButton.addActionListener { onApply?.invoke(choices.toMap()) }
        add(applyButton, BorderLayout.SOUTH)
    }

    fun showConflicts(
        value: List<AnnotationConflict>,
        apply: (Map<String, AnnotationConflictChoice>) -> Unit,
    ) {
        conflicts = value
        onApply = apply
        choices.clear()
        rebuild()
        isVisible = value.isNotEmpty()
        revalidate()
        repaint()
    }

    fun clearConflicts() {
        conflicts = emptyList()
        choices.clear()
        rows.removeAll()
        applyButton.isEnabled = false
        isVisible = false
        revalidate()
        repaint()
    }

    private fun rebuild() {
        rows.removeAll()
        for (conflict in conflicts) {
            val row = JPanel()
            row.layout = BoxLayout(row, BoxLayout.Y_AXIS)
            row.border = BorderFactory.createEmptyBorder(3, 2, 7, 2)
            row.add(JBLabel(conflictTitle(conflict)))

            val group = ButtonGroup()
            val local = JRadioButton("Current edit · ${summary(conflict.local)}")
            val external = JRadioButton("External change · ${summary(conflict.external)}")
            group.add(local)
            group.add(external)
            local.addActionListener {
                choices[conflict.key] = AnnotationConflictChoice.LOCAL
                refreshApplyState()
            }
            external.addActionListener {
                choices[conflict.key] = AnnotationConflictChoice.EXTERNAL
                refreshApplyState()
            }
            row.add(local)
            row.add(external)
            rows.add(row)
        }
        refreshApplyState()
        rows.revalidate()
        rows.repaint()
    }

    private fun refreshApplyState() {
        applyButton.isEnabled = conflicts.isNotEmpty() && conflicts.all { it.key in choices }
    }

    private fun conflictTitle(conflict: AnnotationConflict): String {
        val name = conflict.local?.name ?: conflict.external?.name ?: conflict.base?.name ?: conflict.key
        val fields = conflict.fields.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "delete"
        return "$name  [$fields]"
    }

    private fun summary(shape: MergeShape?): String = shape?.let {
        "${it.name}  (${it.x}, ${it.y}, ${it.w}, ${it.h})"
    } ?: "Deleted"
}
