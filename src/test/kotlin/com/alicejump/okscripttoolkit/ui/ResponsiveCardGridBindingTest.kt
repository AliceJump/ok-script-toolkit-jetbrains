package com.alicejump.okscripttoolkit.ui

import com.intellij.ui.components.JBList
import java.awt.Dimension
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import org.junit.Test
import kotlin.test.assertEquals

class ResponsiveCardGridBindingTest {
    @Test
    fun `card grid follows the visible viewport width`() {
        val list = JBList(arrayOf("a", "b", "c", "d"))
        configureResponsiveCardListForTest(list)
        val scroll = JScrollPane(list)

        SwingUtilities.invokeAndWait {
            scroll.setSize(300, 240)
            scroll.doLayout()
            scroll.viewport.extentSize = Dimension(300, 220)
        }
        SwingUtilities.invokeAndWait {}
        assertEquals(150, list.fixedCellWidth)

        SwingUtilities.invokeAndWait {
            scroll.viewport.extentSize = Dimension(428, 220)
            scroll.viewport.dispatchEvent(java.awt.event.ComponentEvent(scroll.viewport, java.awt.event.ComponentEvent.COMPONENT_RESIZED))
        }
        assertEquals(142, list.fixedCellWidth)
    }
}
