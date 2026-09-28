package com.alicejump.okscripttoolkit.ui

import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WrappingHintTest {
    @Test
    fun `hint footer does not widen its parent`() {
        val hint = WrappingHint(
            "拖拽画框 · 点击选中并拖动 · 边缘手柄调整大小 · 滚轮缩放，拖拽空白处平移 · 双击编辑框 · 确定后写回标注文件。",
        )
        val footer = JPanel(BorderLayout(0, 2)).apply {
            border = BorderFactory.createEmptyBorder(4, 4, 0, 4)
            add(JLabel(" "), BorderLayout.NORTH)
            add(hint, BorderLayout.CENTER)
        }
        val canvas = JPanel().apply { preferredSize = Dimension(900, 560) }
        val root = JPanel(BorderLayout(0, 4)).apply {
            add(canvas, BorderLayout.CENTER)
            add(footer, BorderLayout.SOUTH)
        }

        var width = 900
        repeat(12) {
            root.setSize(width, 700)
            root.doLayout()
            val minimum = root.minimumSize.width
            val preferred = root.preferredSize.width
            assertTrue(minimum <= width, "minimum $minimum exceeded current $width")
            assertTrue(preferred <= width, "preferred $preferred exceeded current $width")
            width = maxOf(width, minimum)
        }
        assertEquals(900, width)
    }

    @Test
    fun `hint grows taller when the available width shrinks`() {
        val hint = WrappingHint(
            "A long annotation hint that should wrap onto more lines when the footer becomes narrow.",
        )
        val host = JPanel(BorderLayout()).apply { add(hint, BorderLayout.CENTER) }
        host.setSize(640, 400)
        host.doLayout()
        val wideHeight = hint.preferredSize.height
        host.setSize(160, 400)
        host.doLayout()
        val narrowHeight = hint.preferredSize.height
        assertTrue(narrowHeight > wideHeight, "hint must reflow with the footer width")
    }
}
