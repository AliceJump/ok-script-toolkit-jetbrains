package com.alicejump.okscripttoolkit.tasklauncher

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBTabbedPane
import java.awt.Component
import java.awt.Container

private const val TASK_TOOL_WINDOW_ID = "ok-script Tasks"

fun showTaskLauncherTasks(project: Project) =
    showTaskLauncherTab(project, "taskLauncher.tabTasks")

fun showTaskLauncherRunner(project: Project) =
    showTaskLauncherTab(project, "taskLauncher.tabRunner")

private fun showTaskLauncherTab(project: Project, titleKey: String) {
    val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TASK_TOOL_WINDOW_ID) ?: return
    toolWindow.show {
        val root = toolWindow.contentManager.contents.firstOrNull()?.component ?: return@show
        selectTaskLauncherTab(root, OkScriptToolkitBundle.message(titleKey))
    }
}

internal fun selectTaskLauncherTab(root: Component, title: String): Boolean {
    val tabs = findTabbedPane(root) ?: return false
    val index = (0 until tabs.tabCount).firstOrNull { tabs.getTitleAt(it) == title } ?: return false
    tabs.selectedIndex = index
    return true
}

private fun findTabbedPane(component: Component): JBTabbedPane? {
    if (component is JBTabbedPane) return component
    if (component !is Container) return null
    for (child in component.components) {
        findTabbedPane(child)?.let { return it }
    }
    return null
}
