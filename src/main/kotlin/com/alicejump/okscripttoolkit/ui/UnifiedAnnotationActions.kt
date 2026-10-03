package com.alicejump.okscripttoolkit.ui

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindowManager

/** 保留原截图快捷键，但把目标迁移到统一标注管理窗口。 */
class ScreenshotToUnifiedAnnotationsAction : AnAction(), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(UNIFIED_ANNOTATION_TOOL_WINDOW_ID) ?: return
        toolWindow.show()
        val content = toolWindow.contentManager.contents.firstOrNull() ?: return
        content.getUserData(UNIFIED_ANNOTATION_PANEL_KEY)?.screenshotNow()
    }
}
