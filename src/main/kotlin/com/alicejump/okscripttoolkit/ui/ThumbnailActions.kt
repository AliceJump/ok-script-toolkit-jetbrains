package com.alicejump.okscripttoolkit.ui

import com.intellij.util.ui.JBUI
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLayeredPane
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer

private const val CLICK_TIMER = "thumbnailClickTimer"

/** Visible one-click actions at the bottom right of a thumbnail. */
internal class ThumbnailActions(preview: JComponent, vararg buttons: JButton) : JLayeredPane() {
    private val image = preview
    private val actions = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(2), 0)).apply {
        isOpaque = false
        for (button in buttons) {
            val label = button.text ?: button.toolTipText
            button.toolTipText = label
            button.accessibleContext.accessibleName = label
            button.text = null
            button.margin = JBUI.insets(2)
            button.preferredSize = JBUI.size(24, 24)
            button.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            button.addActionListener {
                var parent = button.parent
                while (parent != null) {
                    ((parent as? JComponent)?.getClientProperty(CLICK_TIMER) as? Timer)?.stop()
                    parent = parent.parent
                }
            }
            add(button)
        }
    }

    init {
        preferredSize = Dimension(maxOf(preview.preferredSize.width, actions.preferredSize.width + JBUI.scale(6)),
            maxOf(preview.preferredSize.height, JBUI.scale(30)))
        add(preview, Integer.valueOf(DEFAULT_LAYER))
        add(actions, Integer.valueOf(PALETTE_LAYER))
    }

    override fun doLayout() {
        image.setBounds(0, 0, width, height)
        val inset = JBUI.scale(3)
        val size = actions.preferredSize
        actions.setBounds((width - size.width - inset).coerceAtLeast(0),
            (height - size.height - inset).coerceAtLeast(0), minOf(size.width, width), minOf(size.height, height))
        actions.doLayout()
    }
}

/** A double click copies without first inserting; direct buttons cancel pending clicks. */
internal open class ThumbnailClicks(card: JComponent, onSingle: () -> Unit, private val onDouble: () -> Unit) : MouseAdapter() {
    private val timer = Timer(500) { if (card.isShowing) onSingle() }.apply { isRepeats = false }

    init { card.putClientProperty(CLICK_TIMER, timer) }

    override fun mouseClicked(e: MouseEvent) {
        if (!SwingUtilities.isLeftMouseButton(e)) return
        timer.stop()
        if (e.clickCount >= 2) onDouble() else timer.restart()
    }
}
