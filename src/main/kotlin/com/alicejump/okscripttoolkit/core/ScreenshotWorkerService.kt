package com.alicejump.okscripttoolkit.core

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Path
import java.nio.file.Paths

/** 项目内所有截图入口共享解释器；切换目标或关闭项目时释放。 */
@Service(Service.Level.PROJECT)
class ScreenshotWorkerService(private val project: com.intellij.openapi.project.Project) : Disposable {
    private var key: List<String>? = null
    private var worker: ScreenshotWorker? = null
    private var disposed = false

    internal fun capture(python: String, script: String, projectDir: String, output: Path,
                         windowConfigJson: String?, method: String): ScreenshotWorker.Reply {
        return selectWorker(python, script, projectDir).capture(output, windowConfigJson, method)
    }

    internal fun probe(python: String, script: String, projectDir: String): ScreenshotWorker.Reply =
        selectWorker(python, script, projectDir).probe()

    /** 后台预热独立截图进程，失败仅记日志，不阻塞界面或任务启动。 */
    fun prewarm(expectedProjectDir: String? = null, expectedPythonPath: String? = null) {
        val logger = Logger.getInstance(ScreenshotWorkerService::class.java)
        try {
            if (project.isDisposed) return
            val projectDir = ScreenshotCapture.detectProjectDir(project)
            if (projectDir.isBlank() || !ProjectDirResolution.isExistingDirectory(projectDir)
                || (expectedProjectDir != null && expectedProjectDir != projectDir)) return
            val python = ScreenshotCapture.detectPythonPath(projectDir, project)
            if (expectedPythonPath != null && expectedPythonPath != python) return
            val script = Paths.get(PythonScriptLocator.findScriptDir(), "capture_game_window.py").toFile()
            if (!script.isFile) return
            val method = com.alicejump.okscripttoolkit.settings.OkScriptToolkitSettings.getInstance(project).captureMethod()
            val started = System.nanoTime()
            selectWorker(python, script.absolutePath, projectDir).prewarm(method).whenComplete { reply, error ->
                if (error != null || reply?.ok != true) {
                    logger.info("capture.warmup failed: ${error?.message ?: reply?.error}")
                } else logger.info("capture.warmupMs=" + ((System.nanoTime() - started) / 1_000_000) + " frameReady=${reply.frameReady}")
            }
        } catch (error: Exception) {
            logger.info("capture.warmup failed", error)
        }
    }

    private fun selectWorker(python: String, script: String, projectDir: String): ScreenshotWorker =
        synchronized(this) {
            check(!disposed) { "Screenshot service disposed" }
            val nextKey = listOf(python, script, projectDir)
            if (worker == null || key != nextKey) {
                worker?.dispose()
                key = nextKey
                val logger = Logger.getInstance(ScreenshotWorkerService::class.java)
                worker = ScreenshotWorker(python, script, projectDir, { logger.info("capture_game_window: $it") })
            }
            worker!!
        }

    @Synchronized
    override fun dispose() {
        disposed = true
        worker?.dispose()
        worker = null
    }
}
