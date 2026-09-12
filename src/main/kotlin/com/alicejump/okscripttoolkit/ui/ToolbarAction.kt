package com.alicejump.okscripttoolkit.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import javax.swing.Icon

/**
 * 工具栏动作：保存 enabled 状态供 ActionToolbar 刷新，
 * 替代 New UI 下呈亮色块的默认 JButton（图标/UI 随主题适配）。
 *
 * **必须同时设置 text 与 description**：ActionButton.updateToolTipText()
 * 用 `presentation.text` 作为悬浮提示标题、`description` 作为正文。只设
 * description 时提示为空（按钮看起来完全没有悬浮提示）。
 * 图标按钮不会因为设置了 text 就显示文字：ActionToolbarImpl 只在特定
 * ActionPlace（主工具栏等）下才创建 ActionButtonWithText，本插件用的是
 * 自定义 place，仍是纯图标按钮。
 */
internal class ToolbarAction(
    icon: Icon,
    text: String,
    description: String? = null,
    private val onClick: () -> Unit,
) : AnAction() {
    var isEnabled2: Boolean = true
        set(value) {
            field = value
            templatePresentation.isEnabled = value
        }

    init {
        templatePresentation.icon = icon
        templatePresentation.text = text
        templatePresentation.description = description ?: text
    }

    override fun actionPerformed(e: AnActionEvent) {
        onClick()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = isEnabled2
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
