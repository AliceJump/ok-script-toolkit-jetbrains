package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.alicejump.okscripttoolkit.core.OkProjectDetection
import com.alicejump.okscripttoolkit.core.ProjectDirResolution
import com.alicejump.okscripttoolkit.settings.OkScriptToolkitSettings
import com.alicejump.okscripttoolkit.tasklauncher.TaskRunnerService
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.Consumer
import java.awt.Component
import java.awt.event.MouseEvent

internal const val OK_EXECUTOR_WIDGET_ID = "okScriptToolkit.executor"

/** 状态栏文字（纯函数，单测覆盖）：与 VS Code StatusBarItem 同一信息量。 */
internal fun executorWidgetText(state: TaskRunnerService.ExecutorState, displayName: (String) -> String): String {
    val base = OkScriptToolkitBundle.message("okscript.widget.prefix")
    return when {
        state.status == "connecting" -> "$base · " + OkScriptToolkitBundle.message("okscript.widget.connecting")
        state.status != "running" -> "$base · " + OkScriptToolkitBundle.message("okscript.widget.idle")
        else -> buildString {
            append(base).append(" · ")
            append(OkScriptToolkitBundle.message(if (state.paused) "okscript.widget.paused" else "okscript.widget.running"))
            if (state.current.isNotBlank()) append(" · ").append(displayName(state.current))
            if (state.onetimeQueue.isNotEmpty()) append(" · ").append(OkScriptToolkitBundle.message("okscript.widget.queue", state.onetimeQueue.size))
        }
    }
}

class OkExecutorStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = OK_EXECUTOR_WIDGET_ID
    override fun getDisplayName(): String = OkScriptToolkitBundle.message("okscript.widget.displayName")
    override fun isAvailable(project: Project): Boolean = OkProjectDetection.isOkScriptProject(project)
    override fun createWidget(project: Project): StatusBarWidget = OkExecutorStatusBarWidget(project)
}

private class OkExecutorStatusBarWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {
    private var statusBar: StatusBar? = null
    private val runner = TaskRunnerService.getInstance(project)
    private val listener: (TaskRunnerService.ExecutorState) -> Unit = {
        ApplicationManager.getApplication().invokeLater({ statusBar?.updateWidget(ID()) }, project.disposed)
    }

    override fun ID(): String = OK_EXECUTOR_WIDGET_ID
    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this
    override fun install(statusBar: StatusBar) { this.statusBar = statusBar; runner.addStateListener(listener) }
    override fun dispose() { runner.removeStateListener(listener); statusBar = null }

    private fun ownsExecutor(): Boolean {
        val dir = ProjectDirResolution.resolve(
            OkScriptToolkitSettings.getInstance(project).okScriptProjectPath(),
            project.basePath.orEmpty(), System.getProperty("user.home").orEmpty(),
        )
        return runner.runningProjectDir.isBlank() || runner.runningProjectDir == dir
    }

    override fun getText(): String {
        val state = if (ownsExecutor()) runner.currentState() else TaskRunnerService.ExecutorState()
        return executorWidgetText(state) { key -> key.substringAfterLast("::").ifBlank { key } }
    }

    override fun getAlignment(): Float = Component.LEFT_ALIGNMENT
    override fun getTooltipText(): String = OkScriptToolkitBundle.message("okscript.widget.tooltip")

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer { event ->
        val state = runner.currentState()
        val running = state.status == "running" && ownsExecutor()
        val group = DefaultActionGroup().apply {
            add(simple("okscript.widget.openRun", AllIcons.Actions.Execute) { OkScriptToolWindows.show(project, OkTab.RUN) })
            if (running) {
                if (state.paused) add(simple("taskLauncher.resume", AllIcons.Actions.Resume) { runner.sendCommand("resume", runner.runningProjectDir) })
                else add(simple("taskLauncher.pause", AllIcons.Actions.Pause) { runner.sendCommand("pause", runner.runningProjectDir) })
                if (state.current.isNotBlank()) add(simple("taskLauncher.stopCurrent", AllIcons.Actions.Suspend) { runner.stopCurrent(runner.runningProjectDir) })
            }
            add(simple("taskLauncher.viewLog", AllIcons.Toolwindows.ToolWindowMessages) { runner.showConsole() })
        }
        val context = SimpleDataContext.getProjectContext(project)
        JBPopupFactory.getInstance()
            .createActionGroupPopup(null, group, context, JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true)
            .show(RelativePoint(event))
    }

    private fun simple(key: String, icon: javax.swing.Icon, run: () -> Unit) =
        object : DumbAwareAction(OkScriptToolkitBundle.message(key), null, icon) {
            override fun actionPerformed(e: AnActionEvent) = run()
        }
}
