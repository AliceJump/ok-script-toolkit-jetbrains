package com.alicejump.okscripttoolkit.tasklauncher

import com.intellij.openapi.actionSystem.ActionUpdateThread
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

    /**
     * 必须显式声明更新线程。
     *
     * 新版平台不再给 `update()` 兜底选线程：不覆写这个方法时，平台会记一条
     * "ActionUpdateThread is not specified" 并**退化到 EDT 同步调用**——
     * 本 Action 的 `update()` 会去查平台服务，放在 EDT 上做属于把 IO 拖到 UI 线程。
     *
     * 选 `BGT` 与本仓库其它 Action 保持一致（见 `ShowCharacterManagerAction`）：
     * 本 Action 不读写 Swing 组件，只在 `actionPerformed` 里 `show()` 工具窗（那个回调是 EDT）。
     */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
