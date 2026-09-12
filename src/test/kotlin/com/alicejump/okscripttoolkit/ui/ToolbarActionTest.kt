package com.alicejump.okscripttoolkit.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 工具栏按钮悬浮提示的回归保护。
 *
 * ActionButton.updateToolTipText() 用 presentation.text 作提示标题、
 * description 作正文。只设置 description 时提示为空 —— 插件里所有图标按钮
 * 都曾因此完全没有悬浮提示。这里钉住「text 必须非空」。
 */
class ToolbarActionTest {

    // 避免依赖 IconManager（AllIcons 需要已初始化的应用）
    private val dummyIcon = object : javax.swing.Icon {
        override fun paintIcon(c: java.awt.Component?, g: java.awt.Graphics?, x: Int, y: Int) {}
        override fun getIconWidth() = 16
        override fun getIconHeight() = 16
    }

    @Test
    fun `text is set so the button has a tooltip`() {
        val action = ToolbarAction(dummyIcon, "Refresh") {}
        assertEquals("Refresh", action.templatePresentation.text)
        assertEquals("Refresh", action.templatePresentation.description)
    }

    @Test
    fun `description can override the tooltip body`() {
        val action = ToolbarAction(dummyIcon, "Refresh", "Reload everything from disk") {}
        assertEquals("Refresh", action.templatePresentation.text)
        assertEquals("Reload everything from disk", action.templatePresentation.description)
    }

    @Test
    fun `icon is set`() {
        val action = ToolbarAction(dummyIcon, "Refresh") {}
        assertEquals(dummyIcon, action.templatePresentation.icon)
    }
}
