package com.alicejump.okscripttoolkit.core

import com.alicejump.okscripttoolkit.ui.PUBLISH_ANNOTATION_PANEL_KEY
import com.alicejump.okscripttoolkit.ui.UNIFIED_ANNOTATION_TOOL_WINDOW_ID
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.wm.ToolWindowManager
import java.nio.file.Paths
import kotlin.concurrent.thread

/**
 * Windows 系统级 Ctrl+Alt+S 监听器。
 *
 * JetBrains Keymap 只能在 IDE 获得焦点时触发；这里复用父仓打包的 global_hotkey.py，
 * 让 RegisterHotKey 在游戏前台时也能收到快捷键，再回到现有统一标注管理截图链路。
 */
@Service(Service.Level.APP)
class GlobalScreenshotHotkeyService : Disposable {
    companion object {
        private val LOG = Logger.getInstance(GlobalScreenshotHotkeyService::class.java)
    }

    @Volatile
    private var process: Process? = null

    @Volatile
    private var preferredProject: Project? = null

    @Synchronized
    fun ensureStarted(project: Project) {
        preferredProject = project
        if (!SystemInfo.isWindows || process?.isAlive == true) return

        try {
            val script = Paths.get(PythonScriptLocator.findScriptDir(), "global_hotkey.py")
            val projectDir = ScreenshotCapture.detectProjectDir(project)
            val pythonPath = ScreenshotCapture.detectPythonPath(projectDir, project)
            val child = ProcessBuilder(
                pythonPath,
                script.toString(),
                "--parent-pid",
                ProcessHandle.current().pid().toString(),
            ).redirectErrorStream(false).start()
            process = child

            thread(name = "ok-global-hotkey-stdout", isDaemon = true) {
                runCatching {
                    child.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        lines.forEach(::handleLine)
                    }
                }.onFailure { LOG.warn("Global screenshot hotkey stdout reader failed", it) }
            }
            thread(name = "ok-global-hotkey-stderr", isDaemon = true) {
                runCatching {
                    child.errorStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        lines.forEach { line ->
                            if (line.isNotBlank()) LOG.warn("Global screenshot hotkey helper stderr: $line")
                        }
                    }
                }.onFailure { LOG.warn("Global screenshot hotkey stderr reader failed", it) }
            }
            child.onExit().thenRun {
                synchronized(this) {
                    if (process === child) process = null
                }
                if (child.exitValue() != 0) {
                    LOG.warn("Global screenshot hotkey helper exited with code ${child.exitValue()}")
                }
            }
        } catch (error: Exception) {
            LOG.warn("Failed to start global screenshot hotkey helper", error)
        }
    }

    private fun handleLine(line: String) {
        when {
            line == "TRIGGER" -> triggerScreenshot()
            line == "READY" -> LOG.info("Global screenshot hotkey registered: Ctrl+Alt+S")
            line == "UNSUPPORTED" -> Unit
            line.startsWith("ERROR ") -> LOG.warn("Global screenshot hotkey unavailable: ${line.removePrefix("ERROR ")}")
        }
    }

    private fun triggerScreenshot() {
        ApplicationManager.getApplication().invokeLater {
            val project = preferredProject
                ?.takeIf { !it.isDisposed }
                ?: ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed }
                ?: return@invokeLater
            val window = ToolWindowManager.getInstance(project)
                .getToolWindow(UNIFIED_ANNOTATION_TOOL_WINDOW_ID)
                ?: return@invokeLater
            window.show {
                window.contentManager.contents.firstOrNull()
                    ?.getUserData(PUBLISH_ANNOTATION_PANEL_KEY)
                    ?.screenshotNow()
            }
        }
    }

    @Synchronized
    override fun dispose() {
        val child = process
        process = null
        if (child != null && child.isAlive) {
            child.destroy()
            if (child.isAlive) child.destroyForcibly()
        }
        preferredProject = null
    }
}

class GlobalScreenshotHotkeyProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        ApplicationManager.getApplication().service<GlobalScreenshotHotkeyService>().ensureStarted(project)
    }
}
