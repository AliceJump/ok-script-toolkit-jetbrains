package com.alicejump.okscripttoolkit.ui

import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThumbnailActionsTest {
    @Test
    fun `actions stay visible at bottom right and execute without activating the thumbnail`() {
        SwingUtilities.invokeAndWait {
            var thumbnailClicks = 0
            var actionClicks = 0
            val image = JLabel().apply {
                preferredSize = Dimension(120, 72)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) { thumbnailClicks++ }
                })
            }
            val buttons = listOf("Edit", "Open", "Swap", "Delete").map { text ->
                JButton(text).apply { addActionListener { actionClicks++ } }
            }
            val preview = ThumbnailActions(image, *buttons.toTypedArray())
            preview.setSize(160, 90)
            preview.doLayout()
            for (button in buttons) {
                assertTrue(button.isVisible)
                assertTrue(button.width > 0 && button.height > 0)
                assertEquals(button.toolTipText, button.accessibleContext.accessibleName)
                button.doClick(0)
            }
            val row = buttons.first().parent
            assertTrue(row.x > 0 && row.y > 0)
            assertTrue(row.x + row.width <= preview.width)
            assertTrue(row.y + row.height <= preview.height)
            assertEquals(4, actionClicks)
            assertEquals(0, thumbnailClicks)
            buttons.last().isEnabled = false
            buttons.last().doClick(0)
            assertEquals(4, actionClicks, "disabled actions must retain their existing state")
            preview.setSize(120, 72)
            preview.doLayout()
            assertTrue(buttons.last().x + buttons.last().width <= row.width)
        }
    }
}
