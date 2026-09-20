package com.alicejump.okscripttoolkit.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.alicejump.okscripttoolkit.settings.OkScriptToolkitSettings
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.util.ui.UIUtil
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * 游戏窗口截图采集（对齐 VSCode 版 handleScreenshot + probeWindowConfig）：
 * 优先用 probe_window_config.py 自动读取项目 config.py 的窗口匹配配置，
 * 失败回退手输标题正则；capture_game_window.py 截图到 ok_templates。
 */
class ScreenshotCapture(private val project: Project) {

    companion object {
        /** [captureInteractive] 的用户取消标记 */
        const val CANCELLED = "cancelled"

        private val LOG = Logger.getInstance(ScreenshotCapture::class.java)
        private val JSON = ObjectMapper()

        /** ok-script 项目根：设置优先，回退到含 src/config.py / config.py 的工作区。 */
        fun detectProjectDir(project: Project): String {
            val settings = OkScriptToolkitSettings.getInstance(project)
            // 谓词不必在这里写：便捷重载用真实文件系统判定，三个消费点共用同一份
            // （这里 / TaskLauncherToolWindowFactory / ProjectConventionConfig）。
            val dir = ProjectDirResolution.resolve(
                configured = settings.okScriptProjectPath(),
                basePath = project.basePath.orEmpty(),
                homeDir = System.getProperty("user.home").orEmpty(),
            )
            if (dir.isBlank()) {
                LOG.warn(
                    "No project dir found (settings='${settings.okScriptProjectPath()}', " +
                        "basePath='${project.basePath}')"
                )
            } else {
                LOG.info("Project dir resolved: $dir")
            }
            return dir
        }

        /** Python 解释器：设置 -> 项目 .venv -> PATH 上的 python */
        fun detectPythonPath(projectDir: String, project: Project): String {
            val settings = OkScriptToolkitSettings.getInstance(project)
            val configured = settings.okScriptPython()
            if (configured.isNotBlank()) {
                LOG.info("Python path from settings: $configured")
                return configured
            }
            if (projectDir.isNotBlank()) {
                val venvWin = Paths.get(projectDir, ".venv", "Scripts", "python.exe").toFile()
                if (venvWin.exists()) {
                    LOG.info("Python path from venv (win): ${venvWin.absolutePath}")
                    return venvWin.absolutePath
                }
                val venvUnix = Paths.get(projectDir, ".venv", "bin", "python").toFile()
                if (venvUnix.exists()) {
                    LOG.info("Python path from venv (unix): ${venvUnix.absolutePath}")
                    return venvUnix.absolutePath
                }
            }
            LOG.info("Python path fallback: python")
            return "python"
        }
    }

    /**
     * 探测项目窗口匹配配置（AST 解析，不导入模块，10s 超时）。
     * 对齐 VSCode 版 probeWindowConfig。
     */
    fun probeWindowConfig(projectDir: String, pythonPath: String): WindowConfig? {
        val scriptDir = PythonScriptLocator.findScriptDir()
        val script = Paths.get(scriptDir, "probe_window_config.py").toFile()
        if (!script.exists()) {
            LOG.warn("probe_window_config.py not found in $scriptDir")
            return null
        }
        LOG.info("Probing window config: python=$pythonPath, script=${script.absolutePath}, projectDir=$projectDir")
        return try {
            val process = ProcessBuilder(pythonPath, script.absolutePath, projectDir)
                .directory(File(projectDir.ifBlank { "." }))
                .redirectErrorStream(false)
                .start()

            // 必须**先 waitFor 再读流**。原先的顺序是先 readText() 再 waitFor(timeout)：
            // readText() 会一直阻塞到子进程关闭管道，也就是"超时上限根本管不住它"——
            // 脚本卡住时这里会永久挂住调用线程，10s 超时形同虚设。
            // 现在改成：并发起两个抽干线程（避免大输出把管道写满导致双向死锁）-> waitFor -> 收集。
            val stdoutFuture = CompletableFuture.supplyAsync {
                process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
            val stderrFuture = CompletableFuture.supplyAsync {
                process.errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }

            val completed = process.waitFor(10, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                // 给进程一点时间真正退出，再取消读取线程，避免读到半截内容
                process.waitFor(5, TimeUnit.SECONDS)
                stdoutFuture.cancel(true)
                stderrFuture.cancel(true)
                LOG.warn("probe_window_config timed out")
                return null
            }

            val stdout = runCatching { stdoutFuture.get(5, TimeUnit.SECONDS) }.getOrDefault("")
            val stderr = runCatching { stderrFuture.get(5, TimeUnit.SECONDS) }.getOrDefault("")
            if (process.exitValue() != 0) {
                LOG.warn("probe_window_config failed (exit=${process.exitValue()}): $stderr")
                return null
            }
            val lastJson = stdout.lineSequence().lastOrNull { it.trim().startsWith("{") }
            if (lastJson == null) {
                LOG.warn("probe_window_config: no JSON in stdout: $stdout")
                return null
            }
            LOG.info("probe_window_config output: $lastJson")
            val node: JsonNode = JSON.readTree(lastJson)
            if (!node.path("ok").asBoolean(false)) {
                LOG.warn("probe_window_config returned ok=false: $lastJson")
                return null
            }
            WindowConfig(
                exe = node.path("exe")?.let { n ->
                    when {
                        n.isArray -> n.map { it.asText() }
                        n.isTextual && n.asText().isNotBlank() -> listOf(n.asText())
                        else -> null
                    }
                },
                title = node.path("title")?.takeIf { it.isTextual && it.asText().isNotBlank() }?.asText(),
                hwndClass = node.path("hwnd_class")?.takeIf { it.isTextual && it.asText().isNotBlank() }?.asText(),
                // 探针解不出来时给的是 JSON `null`（AST 里掺了变量），`asText()` 会把它变成字面量
                // 字符串 "null" —— 所以必须用 `isTextual` 判，不能只看非空。
                cocoFeatureJson = node.path("coco_feature_json")
                    ?.takeIf { it.isTextual && it.asText().isNotBlank() }?.asText(),
            ).also { LOG.info("Detected window config: ${it.describe()}") }
        } catch (e: Exception) {
            LOG.warn("probe_window_config failed", e)
            null
        }
    }

    /**
     * 执行截图（对齐 VSCode 版 captureWithScript + runCaptureScript）。
     * 截图方式由 [methodOverride]（面板临时指定）或设置项 captureMethod 决定，
     * 以 --method 传给 capture_game_window.py；不传时脚本默认 auto。
     * 成功返回输出文件，失败返回 null（原因写入 [error]）。
     */

    fun capture(
        projectDir: String,
        pythonPath: String,
        outputPath: Path,
        windowConfig: WindowConfig?,
        titleRegex: String?,
        error: StringBuilder,
        /** 非 null 时本次截图强制走该方式（面板「硬前台」勾选传 foreground），覆盖设置项 */
        methodOverride: String? = null,
    ): Path? {
        LOG.info("========== Screenshot capture BEGIN ==========")
        LOG.info("capture.projectDir=$projectDir")
        LOG.info("capture.pythonPath=$pythonPath")
        LOG.info("capture.outputPath=$outputPath")
        LOG.info("capture.outputPath.absolute=${outputPath.toAbsolutePath()}")
        LOG.info("capture.windowConfig=$windowConfig")
        LOG.info("capture.windowConfig.null=${windowConfig == null}")
        LOG.info("capture.titleRegex=$titleRegex")
        LOG.info("capture.titleRegex.blank=${titleRegex.isNullOrBlank()}")

        val scriptDir = PythonScriptLocator.findScriptDir()
        LOG.info("capture.scriptDir=$scriptDir")

        val script = Paths.get(scriptDir, "capture_game_window.py").toFile()
        LOG.info("capture.script=$script")
        LOG.info("capture.script.absolute=${script.absolutePath}")
        LOG.info("capture.script.exists=${script.exists()}")
        LOG.info("capture.script.isFile=${script.isFile}")

        if (!script.exists()) {
            error.append(
                OkScriptToolkitBundle0.message("screenshot.scriptNotFound")
            )
            LOG.warn("capture.ABORT: capture_game_window.py not found in $scriptDir")
            LOG.info("========== Screenshot capture END (script missing) ==========")
            return null
        }

        // ------------------------------------------------------------
        // 1. Resolve window configuration
        // ------------------------------------------------------------

        val exeNames = windowConfig?.exe
        val title = windowConfig?.title ?: titleRegex
        val hwndClass = windowConfig?.hwndClass

        LOG.info("capture.window.resolve:")
        LOG.info("  windowConfig.present=${windowConfig != null}")
        LOG.info("  windowConfig.exe=$exeNames")
        LOG.info("  windowConfig.title=${windowConfig?.title}")
        LOG.info("  windowConfig.hwndClass=${windowConfig?.hwndClass}")
        LOG.info("  resolved.exe=$exeNames")
        LOG.info("  resolved.title=$title")
        LOG.info("  resolved.hwndClass=$hwndClass")

        // ------------------------------------------------------------
        // 2. Build --config-json
        // ------------------------------------------------------------

        val windowConfigJson =
            if (
                !exeNames.isNullOrEmpty() ||
                !title.isNullOrBlank() ||
                !hwndClass.isNullOrBlank()
            ) {
                JSON.writeValueAsString(
                    mapOf(
                        "exe" to exeNames,
                        "title" to title,
                        "hwnd_class" to hwndClass,
                    )
                )
            } else {
                null
            }

        LOG.info("capture.config:")
        LOG.info("  configJson.present=${windowConfigJson != null}")
        LOG.info("  configJson=$windowConfigJson")

        if (windowConfigJson != null) {
            LOG.info(
                "capture.configJson.length=${windowConfigJson.length}"
            )
        }

        // ------------------------------------------------------------
        // 3. Build process arguments
        // ------------------------------------------------------------

        val args = mutableListOf(
            pythonPath,
            script.absolutePath,
            outputPath.toString()
        )

        if (windowConfigJson != null) {
            LOG.info("capture.args.mode=CONFIG_FILE")

            val tempConfig = Files.createTempFile("ok-window-config-", ".json")
            Files.writeString(tempConfig, windowConfigJson, Charsets.UTF_8)
            tempConfig.toFile().deleteOnExit()

            LOG.info("capture.args.add=--config-file")
            LOG.info("capture.args.configFile=${tempConfig.toAbsolutePath()}")

            args.add("--config-file")
            args.add(tempConfig.toAbsolutePath().toString())
        } else if (!titleRegex.isNullOrBlank()) {
            LOG.info("capture.args.mode=LEGACY_TITLE_REGEX")
            LOG.info("capture.args.add=titleRegex")
            args.add(titleRegex)
        } else {
            LOG.info("capture.args.mode=NO_WINDOW_CONFIG")
        }

        if (projectDir.isNotBlank()) {
            LOG.info("capture.args.add=--project-dir")
            LOG.info("capture.args.projectDir=$projectDir")
            args.add("--project-dir")
            args.add(projectDir)
        } else {
            LOG.info("capture.args.projectDir=<blank>")
        }

        // --method：显式覆盖优先，其次设置项，最后 auto（与 capture_game_window.py 的默认值一致）
        val method = methodOverride
            ?.takeIf { it.isNotBlank() }
            ?.let { OkScriptToolkitSettings.normalizeCaptureMethod(it) }
            ?: OkScriptToolkitSettings.getInstance(project).captureMethod()
        args.add("--method")
        args.add(method)
        LOG.info("capture.args.method=$method (override=$methodOverride)")

        // ------------------------------------------------------------
        // 4. Log arguments individually
        // ------------------------------------------------------------

        LOG.info("capture.process:")
        LOG.info("  executable=$pythonPath")
        LOG.info("  argc=${args.size}")

        args.forEachIndexed { index, arg ->
            LOG.info("  argv[$index]=${arg}")
        }

        LOG.info(
            "capture.process.command=${
                args.joinToString(" ") { arg ->
                    "\"${arg.replace("\"", "\\\"")}\""
                }
            }"
        )

        val processDirectory = File(projectDir.ifBlank { "." })
        LOG.info("capture.process.directory=${processDirectory.absolutePath}")
        LOG.info("capture.process.directory.exists=${processDirectory.exists()}")

        // ------------------------------------------------------------
        // 5. Start process
        // ------------------------------------------------------------

        LOG.info("capture.process.starting=true")

        return try {
            val startTime = System.nanoTime()

            val process = ProcessBuilder(args)
                .directory(processDirectory)
                .redirectErrorStream(false)
                .start()

            LOG.info(
                "capture.process.started=true pid=${
                    runCatching {
                        process.pid()
                    }.getOrNull()
                }"
            )

            LOG.info("capture.process.isAlive=${process.isAlive}")

            // --------------------------------------------------------
            // 6. stderr reader
            // --------------------------------------------------------

            val stderrBuilder = StringBuilder()

            val stderrThread = Thread {
                LOG.info("capture.stderr.thread.started")

                runCatching {
                    process.errorStream
                        .bufferedReader(Charsets.UTF_8)
                        .forEachLine { line ->
                            synchronized(stderrBuilder) {
                                stderrBuilder
                                    .append(line)
                                    .append('\n')
                            }

                            LOG.info(
                                "capture_game_window[stderr]: $line"
                            )
                        }
                }.onFailure {
                    LOG.warn(
                        "capture.stderr.read.failed",
                        it
                    )
                }

                LOG.info("capture.stderr.thread.finished")
            }.apply {
                name = "ok-script-capture-stderr"
                isDaemon = true
                start()
            }

            // --------------------------------------------------------
            // 7. stdout reader
            // --------------------------------------------------------

            LOG.info("capture.stdout.reading=true")

            process.inputStream
                .bufferedReader(Charsets.UTF_8)
                .forEachLine { line ->
                    LOG.info(
                        "capture_game_window[stdout]: $line"
                    )
                }

            LOG.info("capture.stdout.reading.finished")
            LOG.info("capture.process.isAlive.afterStdout=${process.isAlive}")

            // --------------------------------------------------------
            // 8. Wait for process
            // --------------------------------------------------------

            LOG.info("capture.process.waiting.timeout=60s")

            val completed = process.waitFor(
                60,
                TimeUnit.SECONDS
            )

            LOG.info("capture.process.completed=$completed")
            LOG.info("capture.process.isAlive.afterWait=${process.isAlive}")

            if (!completed) {
                LOG.warn(
                    "capture.process.timeout=true pid=${
                        runCatching {
                            process.pid()
                        }.getOrNull()
                    }"
                )

                process.destroyForcibly()

                LOG.warn("capture.process.destroyForcibly=true")

                error.append("timeout")

                LOG.info(
                    "capture.durationMs=" +
                            ((System.nanoTime() - startTime) / 1_000_000)
                )

                LOG.info(
                    "========== Screenshot capture END (timeout) =========="
                )

                return null
            }

            // --------------------------------------------------------
            // 9. stderr completion
            // --------------------------------------------------------

            LOG.info("capture.stderr.thread.joining.timeout=1000ms")

            stderrThread.join(1000)

            LOG.info(
                "capture.stderr.thread.alive=${stderrThread.isAlive}"
            )

            val stderr = synchronized(stderrBuilder) {
                stderrBuilder.toString()
            }

            LOG.info(
                "capture.process.stderr.length=${stderr.length}"
            )

            if (stderr.isNotBlank()) {
                LOG.info(
                    "capture.process.stderr.content:\n$stderr"
                )
            }

            // --------------------------------------------------------
            // 10. Exit code
            // --------------------------------------------------------

            val exitCode = process.exitValue()

            LOG.info("capture.process.exitCode=$exitCode")

            if (exitCode != 0) {
                LOG.warn(
                    "capture.process.failed=true exitCode=$exitCode"
                )

                error.append(
                    stderr.ifBlank {
                        "exit code $exitCode"
                    }
                )

                LOG.info(
                    "capture.durationMs=" +
                            ((System.nanoTime() - startTime) / 1_000_000)
                )

                LOG.info(
                    "========== Screenshot capture END (process failed) =========="
                )

                return null
            }

            // --------------------------------------------------------
            // 11. Output validation
            // --------------------------------------------------------

            LOG.info("capture.output.check.path=$outputPath")
            LOG.info(
                "capture.output.exists=${Files.exists(outputPath)}"
            )

            if (Files.exists(outputPath)) {
                LOG.info(
                    "capture.output.isRegularFile=" +
                            Files.isRegularFile(outputPath)
                )

                LOG.info(
                    "capture.output.size=" +
                            runCatching {
                                Files.size(outputPath)
                            }.getOrElse {
                                -1L
                            }
                )

                LOG.info(
                    "capture.output.absolute=" +
                            outputPath.toAbsolutePath()
                )
            }

            if (!Files.exists(outputPath)) {
                error.append("file not created")

                LOG.warn(
                    "capture.output.missing=true path=$outputPath"
                )

                LOG.info(
                    "capture.durationMs=" +
                            ((System.nanoTime() - startTime) / 1_000_000)
                )

                LOG.info(
                    "========== Screenshot capture END (output missing) =========="
                )

                return null
            }

            LOG.info(
                "capture.success=true output=$outputPath"
            )

            LOG.info(
                "capture.durationMs=" +
                        ((System.nanoTime() - startTime) / 1_000_000)
            )

            LOG.info("========== Screenshot capture END (SUCCESS) ==========")

            outputPath
        } catch (e: Exception) {
            LOG.warn("capture.exception.type=${e.javaClass.name}")
            LOG.warn("capture.exception.message=${e.message}")
            LOG.warn("capture failed", e)

            error.append(
                e.message ?: e.javaClass.simpleName
            )

            LOG.info("========== Screenshot capture END (EXCEPTION) ==========")

            null
        }
    }

    /**
     * 一次完整的交互式采集：后台探测窗口配置 → 无可用配置时提示手输标题正则 → 截图落盘。
     *
     * 供素材面板与临时截图面板共用（对齐 VSCode 版 screenshotCapture.ts 的抽取）。
     * **可在后台线程调用**：需要弹输入框时内部会切到 EDT。
     *
     * @param onProbed 探测到可用窗口配置时回调（已切到 EDT），供调用方更新状态栏
     * @return 成功返回 null；用户取消返回 [CANCELLED]；失败返回错误描述
     */
    fun captureInteractive(
        outputPath: Path,
        /** 非 null 时本次截图强制走该方式（面板「硬前台」勾选传 foreground），覆盖设置项 */
        methodOverride: String? = null,
        onProbed: ((WindowConfig) -> Unit)? = null,
    ): String? {
        val projectDir = detectProjectDir(project)
        val pythonPath = detectPythonPath(projectDir, project)
        val probed = probeWindowConfig(projectDir, pythonPath)
        val usable = probed != null &&
            (!probed.exe.isNullOrEmpty() || !probed.title.isNullOrBlank() || !probed.hwndClass.isNullOrBlank())

        var config: WindowConfig? = null
        var titleRegex: String? = null
        if (usable) {
            config = probed
            onProbed?.let { callback -> UIUtil.invokeLaterIfNeeded { callback(probed!!) } }
        } else {
            val input = UIUtil.invokeAndWaitIfNeeded<String?> {
                Messages.showInputDialog(
                    project,
                    OkScriptToolkitBundle0.message("templateAsset.screenshotPrompt"),
                    OkScriptToolkitBundle0.message("templateAsset.screenshot"),
                    Messages.getInformationIcon(),
                    "",
                    null,
                )
            }
            if (input == null) return CANCELLED
            titleRegex = input.trim()
        }

        val projectRoot = projectDir.ifBlank { project.basePath ?: "" }
        if (projectRoot.isBlank()) return OkScriptToolkitBundle0.message("taskLauncher.noProject")

        val error = StringBuilder()
        val result = capture(projectRoot, pythonPath, outputPath, config, titleRegex, error, methodOverride)
        return if (result == null) {
            error.toString().ifBlank { OkScriptToolkitBundle0.message("templateAsset.screenshotFailed", "") }
        } else {
            null
        }
    }

}

/** 避免与 ui 包互相依赖的最小 bundle 引用 */
private object OkScriptToolkitBundle0 {
    /** 单参数与带占位符两种调用形态都支持（MessageFormat 参数） */
    fun message(key: String, vararg params: Any): String =
        com.alicejump.okscripttoolkit.OkScriptToolkitBundle.message(key, *params)
}

/** 窗口匹配配置（probe_window_config.py 的解析结果） */
data class WindowConfig(
    val exe: List<String>? = null,
    val title: String? = null,
    val hwndClass: String? = null,
    /**
     * `config.py` 的 `template_matching.coco_feature_json` —— **运行时模板库**路径
     * （ok 框架自己也是读这一项）。相对项目根或绝对路径，原样返回、不做归一化。
     * 见 [CocoFeaturePath]：它和素材面板的 `<模板目录>/coco_annotations.json` 是两个不同的文件。
     */
    val cocoFeatureJson: String? = null,
) {
    fun describe(): String = listOfNotNull(
        exe?.takeIf { it.isNotEmpty() }?.let { "exe: ${it.joinToString(", ")}" },
        title?.let { "title: $it" },
        hwndClass?.let { "class: $it" },
    ).joinToString(" | ")
}
