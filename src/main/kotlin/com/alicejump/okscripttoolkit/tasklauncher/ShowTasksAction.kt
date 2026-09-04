package com.alicejump.okscripttoolkit.tasklauncher

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager

/**
 * 显示任务启动器工具窗口的操作。
 */
class ShowTasksAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("ok-script Tasks")
        toolWindow?.show()
    }
}
