package com.alicejump.okscripttoolkit.ui

import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout

/** 窄工具窗口的工具栏按可用宽度换行，并为每行保留实际高度。 */
internal class WrappingToolbarLayout : FlowLayout(LEFT, 4, 2) {
    override fun preferredLayoutSize(target: Container): Dimension = synchronized(target.treeLock) {
        val insets = target.insets
        var available = target.width
        var ancestor = target.parent
        while (available <= 0 && ancestor != null) {
            available = ancestor.width
            ancestor = ancestor.parent
        }
        val maxWidth = (if (available > 0) available else Int.MAX_VALUE) - insets.left - insets.right - hgap * 2
        var width = 0
        var height = 0
        var rowWidth = 0
        var rowHeight = 0
        for (component in target.components.filter { it.isVisible }) {
            val size = component.preferredSize
            if (rowWidth > 0 && rowWidth + hgap + size.width > maxWidth) {
                width = maxOf(width, rowWidth)
                height += rowHeight + vgap
                rowWidth = 0
                rowHeight = 0
            }
            if (rowWidth > 0) rowWidth += hgap
            rowWidth += size.width
            rowHeight = maxOf(rowHeight, size.height)
        }
        Dimension(maxOf(width, rowWidth) + insets.left + insets.right + hgap * 2,
            height + rowHeight + insets.top + insets.bottom + vgap * 2)
    }
}
