package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.core.OkProjectDetection
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

/** ok-script 动作基类：只在 ok-script 项目里可用（菜单里灰掉而不是报错）。 */
abstract class OkProjectAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && OkProjectDetection.isOkScriptProject(project)
    }
}

/** 打开 ok-script 工具窗口「运行」tab。 */
class ShowOkScriptRunAction : OkProjectAction() {
    override fun actionPerformed(e: AnActionEvent) { e.project?.let { OkScriptToolWindows.show(it, OkTab.RUN) } }
}

/**
 * 截到收件箱：与 VS Code「截到收件箱」同语义。
 * 默认快捷键 Ctrl+Alt+Shift+O（macOS ⌘⌥⇧O）：不用 Ctrl+Alt+S（IDE Settings）；
 * 已核对 2025.1 自带全部 keymap 与内置插件的 keyboard-shortcut，无冲突（docs/keymap.md）。
 */
class CaptureToInboxAction : OkProjectAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        OkScriptToolWindows.withResources(project, ResourceSection.INBOX) { it.captureToInbox() }
    }
}

/** 预览并发布：打开资源 · 草稿并进入统一发布流程（含发布预览）。 */
class PublishResourcesAction : OkProjectAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        OkScriptToolWindows.withResources(project, ResourceSection.DRAFT) { it.publish() }
    }
}
