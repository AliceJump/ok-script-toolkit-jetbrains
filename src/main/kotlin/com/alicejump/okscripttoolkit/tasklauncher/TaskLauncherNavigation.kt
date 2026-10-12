package com.alicejump.okscripttoolkit.tasklauncher

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBTabbedPane
import java.awt.Component
import java.awt.Container


fun showTaskLauncherTasks(project: Project) =
    showTaskLauncherTab(project, "taskLauncher.tabTasks")

fun showTaskLauncherRunner(project: Project) =
    showTaskLauncherTab(project, "taskLauncher.tabRunner")

private fun showTaskLauncherTab(project: Project, titleKey: String) {
    com.alicejump.okscripttoolkit.ui.OkScriptToolWindows.withTasks(project) { panel ->
        selectTaskLauncherTab(panel.mainPanel, OkScriptToolkitBundle.message(titleKey))
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
