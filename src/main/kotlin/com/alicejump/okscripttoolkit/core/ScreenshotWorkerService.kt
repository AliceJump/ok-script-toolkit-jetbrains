package com.alicejump.okscripttoolkit.core

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Path

/** 项目内所有截图入口共享解释器；切换目标或关闭项目时释放。 */
@Service(Service.Level.PROJECT)
class ScreenshotWorkerService : Disposable {
    private var key: List<String>? = null
    private var worker: ScreenshotWorker? = null
    private var disposed = false

    internal fun capture(python: String, script: String, projectDir: String, output: Path,
                         windowConfigJson: String?, method: String): ScreenshotWorker.Reply {
        val selected = synchronized(this) {
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
        return selected.capture(output, windowConfigJson, method)
    }

    @Synchronized
    override fun dispose() {
        disposed = true
        worker?.dispose()
        worker = null
    }
}
