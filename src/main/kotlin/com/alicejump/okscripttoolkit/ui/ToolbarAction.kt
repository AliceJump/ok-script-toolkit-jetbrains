package com.alicejump.okscripttoolkit.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import javax.swing.Icon

/**
 * 工具栏动作：保存 enabled 状态供 ActionToolbar 刷新，
 * 替代 New UI 下呈亮色块的默认 JButton（图标/UI 随主题适配）。
 */
internal class ToolbarAction(
    icon: Icon,
    tooltip: String,
    private val onClick: () -> Unit,
) : AnAction() {
    var isEnabled2: Boolean = true
        set(value) {
            field = value
            templatePresentation.isEnabled = value
        }

    init {
        templatePresentation.icon = icon
        templatePresentation.description = tooltip
    }

    override fun actionPerformed(e: AnActionEvent) {
        onClick()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = isEnabled2
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
