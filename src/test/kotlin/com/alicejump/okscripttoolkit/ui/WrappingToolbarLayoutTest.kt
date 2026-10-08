package com.alicejump.okscripttoolkit.ui

import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertTrue

class WrappingToolbarLayoutTest {
    @Test fun `narrow toolbar reserves height for every visible action`() {
        val toolbar = JPanel(WrappingToolbarLayout())
        repeat(8) { toolbar.add(JButton().apply { preferredSize = Dimension(80, 24) }) }
        toolbar.setSize(700, 100)
        val wideHeight = toolbar.preferredSize.height
        toolbar.setSize(220, 100)
        val narrowHeight = toolbar.preferredSize.height
        assertTrue(narrowHeight > wideHeight)
        toolbar.setSize(220, narrowHeight)
        toolbar.doLayout()
        assertTrue(toolbar.components.all { it.y + it.height <= narrowHeight },
            "所有动作都必须留在工具栏可见高度内")
    }
}
