package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.AnnotationUiBundle
import com.alicejump.okscripttoolkit.core.AnnotationConflict
import com.alicejump.okscripttoolkit.core.AnnotationConflictChoice
import com.alicejump.okscripttoolkit.core.MergeShape
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JScrollPane
import kotlin.math.max
import kotlin.math.min

/** Side-by-side choices for structured annotation conflicts. No file writes happen here. */
internal class AnnotationConflictPanel : JPanel(BorderLayout(0, 6)) {
    private val rows = JPanel()
    private val applyButton = JButton(AnnotationUiBundle.message("conflict.apply"))
    private val choices = linkedMapOf<String, AnnotationConflictChoice>()
    private var conflicts: List<AnnotationConflict> = emptyList()
    private var onApply: ((Map<String, AnnotationConflictChoice>) -> Unit)? = null

    init {
        isVisible = false
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder(AnnotationUiBundle.message("conflict.title")),
            BorderFactory.createEmptyBorder(2, 4, 4, 4),
        )
        rows.layout = BoxLayout(rows, BoxLayout.Y_AXIS)
        val scroll = JScrollPane(rows)
        scroll.preferredSize = Dimension(280, 260)
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
            row.border = BorderFactory.createEmptyBorder(3, 2, 9, 2)
            row.add(JBLabel(conflictTitle(conflict)))
            row.add(CandidatePreview(conflict))

            val group = ButtonGroup()
            val local = JRadioButton("${AnnotationUiBundle.message("conflict.current")} · ${summary(conflict.local)}")
            val external = JRadioButton("${AnnotationUiBundle.message("conflict.external")} · ${summary(conflict.external)}")
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
        val fields = conflict.fields.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: AnnotationUiBundle.message("conflict.deleted")
        return "$name  [$fields]"
    }

    private fun summary(shape: MergeShape?): String = shape?.let {
        "${it.name}  (${it.x}, ${it.y}, ${it.w}, ${it.h})"
    } ?: AnnotationUiBundle.message("conflict.deleted")

    /**
     * Tiny geometry preview using the same visual contract as the editor canvas:
     * the current edit is solid, while the external candidate is dashed.
     */
    private class CandidatePreview(private val conflict: AnnotationConflict) : JComponent() {
        companion object {
            private val LOCAL = JBColor(0x0078D4, 0x4A9EFF)
            private val EXTERNAL = JBColor(0xE08700, 0xFFA02E)
            private val BORDER = JBColor(0xC8C8C8, 0x555555)
        }

        init {
            preferredSize = Dimension(250, 86)
            minimumSize = Dimension(180, 70)
            maximumSize = Dimension(Int.MAX_VALUE, 96)
            toolTipText = AnnotationUiBundle.message("conflict.tooltip")
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = BORDER
                g2.drawRect(0, 0, (width - 1).coerceAtLeast(0), (height - 1).coerceAtLeast(0))

                val candidates = listOfNotNull(conflict.local, conflict.external)
                if (candidates.isEmpty()) return
                val minX = candidates.minOf { it.x }
                val minY = candidates.minOf { it.y }
                val maxX = candidates.maxOf { it.x + max(1, it.w) }
                val maxY = candidates.maxOf { it.y + max(1, it.h) }
                val spanX = max(1, maxX - minX)
                val spanY = max(1, maxY - minY)
                val pad = 12.0
                val usableW = max(1.0, width - pad * 2)
                val usableH = max(1.0, height - pad * 2)
                val scale = min(usableW / spanX, usableH / spanY)

                fun drawCandidate(shape: MergeShape, external: Boolean) {
                    g2.color = if (external) EXTERNAL else LOCAL
                    g2.stroke = if (external) {
                        BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(6f, 4f), 0f)
                    } else BasicStroke(2f)
                    val x = (pad + (shape.x - minX) * scale).toInt()
                    val y = (pad + (shape.y - minY) * scale).toInt()
                    if (shape.w == 0 && shape.h == 0) {
                        g2.drawOval(x - 4, y - 4, 8, 8)
                        g2.drawLine(x - 7, y, x + 7, y)
                        g2.drawLine(x, y - 7, x, y + 7)
                    } else {
                        val w = max(2, (shape.w * scale).toInt())
                        val h = max(2, (shape.h * scale).toInt())
                        g2.drawRect(x, y, w, h)
                    }
                }

                conflict.local?.let { drawCandidate(it, external = false) }
                conflict.external?.let { drawCandidate(it, external = true) }
            } finally {
                g2.dispose()
            }
        }
    }
}
