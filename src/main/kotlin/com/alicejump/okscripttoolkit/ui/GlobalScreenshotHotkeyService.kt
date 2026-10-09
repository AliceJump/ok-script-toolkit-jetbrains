package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.core.PythonScriptLocator
import com.alicejump.okscripttoolkit.core.ScreenshotCapture
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
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.Window
import java.nio.file.Paths
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
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
        private const val INITIAL_RETRY_DELAY_MS = 2_000L
        private const val MAX_RETRY_DELAY_MS = 30_000L
    }

    @Volatile
    private var process: Process? = null

    @Volatile
    private var preferredProject: Project? = null

    @Volatile
    private var disposed = false

    private var retryFuture: ScheduledFuture<*>? = null
    private var retryDelayMs = INITIAL_RETRY_DELAY_MS

    /** Starts the shared hotkey helper once and remembers the latest usable project as a fallback target. */
    @Synchronized
    fun ensureStarted(project: Project) {
        preferredProject = project
        if (disposed || !SystemInfo.isWindows || process?.isAlive == true) return

        retryFuture?.cancel(false)
        retryFuture = null
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
                val exitCode = child.exitValue()
                val shouldRetry = synchronized(this) {
                    if (process !== child) {
                        false
                    } else {
                        process = null
                        !disposed && exitCode != 0
                    }
                }
                if (shouldRetry) {
                    LOG.warn("Global screenshot hotkey helper exited with code $exitCode")
                    scheduleRetry()
                }
            }
        } catch (error: Exception) {
            LOG.warn("Failed to start global screenshot hotkey helper", error)
            scheduleRetry()
        }
    }

    /** Handles the line-oriented helper protocol without duplicating screenshot logic in the helper. */
    private fun handleLine(line: String) {
        when {
            line == "TRIGGER" -> triggerScreenshot()
            line == "READY" -> {
                synchronized(this) { retryDelayMs = INITIAL_RETRY_DELAY_MS }
                LOG.info("Global screenshot hotkey registered: Ctrl+Alt+S")
            }
            line == "UNSUPPORTED" -> Unit
            line.startsWith("ERROR ") -> LOG.warn("Global screenshot hotkey unavailable: ${line.removePrefix("ERROR ")}")
        }
    }

    /** Routes a global key press to the most recently focused IDE project and its existing screenshot action. */
    private fun triggerScreenshot() {
        ApplicationManager.getApplication().invokeLater {
            val project = resolveTargetProject() ?: return@invokeLater
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

    /** Resolves the last focused IDE frame first, then falls back to the latest startup project. */
    private fun resolveTargetProject(): Project? {
        val projects = ProjectManager.getInstance().openProjects.filterNot { it.isDisposed }
        val recentWindow = WindowManager.getInstance().mostRecentFocusedWindow
        if (recentWindow != null) {
            projects.firstOrNull { project ->
                val frame = WindowManager.getInstance().getFrame(project) ?: return@firstOrNull false
                belongsToFrame(recentWindow, frame)
            }?.let { return it }
        }
        return preferredProject
            ?.takeIf { !it.isDisposed }
            ?: projects.firstOrNull()
    }

    /** Returns true for a project frame itself or any owned dialog/window that was focused from that frame. */
    private fun belongsToFrame(window: Window, frame: Window): Boolean {
        var current: Window? = window
        while (current != null) {
            if (current === frame) return true
            current = current.owner
        }
        return false
    }

    /** Retries abnormal helper exits with bounded exponential backoff while the application service is alive. */
    @Synchronized
    private fun scheduleRetry() {
        if (disposed || !SystemInfo.isWindows || process?.isAlive == true) return
        if (retryFuture?.isDone == false) return

        val delayMs = retryDelayMs
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
        retryFuture = AppExecutorUtil.getAppScheduledExecutorService().schedule({
            val project = preferredProject
                ?.takeIf { !it.isDisposed }
                ?: ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed }
            synchronized(this) { retryFuture = null }
            if (project != null && !disposed) ensureStarted(project)
        }, delayMs, TimeUnit.MILLISECONDS)
        LOG.info("Retrying global screenshot hotkey registration after ${delayMs}ms")
    }

    /** Stops pending retries and releases the helper when the application service is disposed. */
    @Synchronized
    override fun dispose() {
        disposed = true
        retryFuture?.cancel(false)
        retryFuture = null
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
    /** Starts or retargets the application-level hotkey service when a project becomes available. */
    override suspend fun execute(project: Project) {
        ApplicationManager.getApplication().service<GlobalScreenshotHotkeyService>().ensureStarted(project)
    }
}
