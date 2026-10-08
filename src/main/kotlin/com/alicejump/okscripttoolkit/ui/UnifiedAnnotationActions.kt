package com.alicejump.okscripttoolkit.ui

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

/** 保留原截图快捷键，但把目标迁移到统一标注管理窗口。 */
class ScreenshotToUnifiedAnnotationsAction : AnAction(), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        ScreenshotToPublishingAnnotationsAction().actionPerformed(e)
    }
}
