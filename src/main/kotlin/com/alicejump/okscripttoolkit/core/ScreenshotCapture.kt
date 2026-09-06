package com.alicejump.okscripttoolkit.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.alicejump.okscripttoolkit.settings.OkScriptToolkitSettings
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/**
 * 游戏窗口截图采集（对齐 VSCode 版 handleScreenshot + probeWindowConfig）：
 * 优先用 probe_window_config.py 自动读取项目 config.py 的窗口匹配配置，
 * 失败回退手输标题正则；capture_game_window.py 截图到 ok_templates。
 */
class ScreenshotCapture(private val project: Project) {

    companion object {
        private val LOG = Logger.getInstance(ScreenshotCapture::class.java)
        private val JSON = ObjectMapper()

        /** ok-script 项目根：设置优先，回退到含 src/config.py / config.py 的工作区 */
        fun detectProjectDir(project: Project): String {
            val settings = OkScriptToolkitSettings.getInstance(project)
            val configured = settings.okScriptProjectPath().replace("~", System.getProperty("user.home"))
            if (configured.isNotBlank() && Files.isDirectory(Paths.get(configured))) {
                return configured.trimEnd('/', '\\')
            }
            val basePath = project.basePath ?: ""
            if (basePath.isNotBlank() &&
                (Files.exists(Paths.get(basePath, "src", "config.py")) || Files.exists(Paths.get(basePath, "config.py")))
            ) {
                return basePath
            }
            return ""
        }

        /** Python 解释器：设置 -> 项目 .venv -> PATH 上的 python */
        fun detectPythonPath(projectDir: String, project: Project): String {
            val settings = OkScriptToolkitSettings.getInstance(project)
            val configured = settings.okScriptPython()
            if (configured.isNotBlank()) return configured
            if (projectDir.isNotBlank()) {
                val venvWin = Paths.get(projectDir, ".venv", "Scripts", "python.exe").toFile()
                if (venvWin.exists()) return venvWin.absolutePath
                val venvUnix = Paths.get(projectDir, ".venv", "bin", "python").toFile()
                if (venvUnix.exists()) return venvUnix.absolutePath
            }
            return "python"
        }
    }

    /**
     * 探测项目窗口匹配配置（AST 解析，不导入模块，10s 超时）。
     * 返回 null 表示不可用（脚本缺失/解析失败/ok=false）。
     */
    fun probeWindowConfig(projectDir: String, pythonPath: String): WindowConfig? {
        val scriptDir = PythonScriptLocator.findScriptDir()
        val script = Paths.get(scriptDir, "probe_window_config.py").toFile()
        if (!script.exists()) {
            LOG.warn("probe_window_config.py not found in $scriptDir")
            return null
        }
        return try {
            val process = ProcessBuilder(pythonPath, script.absolutePath, projectDir)
                .directory(File(projectDir.ifBlank { "." }))
                .redirectErrorStream(false)
                .start()
            val stdout = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val completed = process.waitFor(10, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                return null
            }
            val lastJson = stdout.lineSequence().lastOrNull { it.trim().startsWith("{") } ?: return null
            val node: JsonNode = JSON.readTree(lastJson)
            if (!node.path("ok").asBoolean(false)) return null
            WindowConfig(
                exe = node.path("exe")?.takeIf { it.isArray }?.map { it.asText() },
                title = node.path("title")?.takeIf { it.isTextual && it.asText().isNotBlank() }?.asText(),
                hwndClass = node.path("hwnd_class")?.takeIf { it.isTextual && it.asText().isNotBlank() }?.asText(),
            )
        } catch (e: Exception) {
            LOG.warn("probe_window_config failed", e)
            null
        }
    }

    /**
     * 执行截图：优先 --config-json（窗口配置），兼容旧式标题正则位置参数。
     * 成功返回输出文件，失败返回 null（原因写入 [error]）。
     */
    fun capture(
        projectDir: String,
        pythonPath: String,
        outputPath: Path,
        windowConfig: WindowConfig?,
        titleRegex: String?,
        error: StringBuilder,
    ): Path? {
        val scriptDir = PythonScriptLocator.findScriptDir()
        val script = Paths.get(scriptDir, "capture_game_window.py").toFile()
        if (!script.exists()) {
            error.append(OkScriptToolkitBundle0.message("screenshot.scriptNotFound"))
            return null
        }

        val args = mutableListOf(pythonPath, script.absolutePath, outputPath.toString())
        val configJson = if (windowConfig != null &&
            (!windowConfig.exe.isNullOrEmpty() || !windowConfig.title.isNullOrBlank() || !windowConfig.hwndClass.isNullOrBlank())
        ) {
            JSON.writeValueAsString(
                mapOf(
                    "exe" to windowConfig.exe,
                    "title" to windowConfig.title,
                    "hwnd_class" to windowConfig.hwndClass,
                ),
            )
        } else {
            null
        }
        if (configJson != null) {
            args.add("--config-json")
            args.add(configJson)
        } else if (!titleRegex.isNullOrBlank()) {
            args.add(titleRegex)
        }
        if (projectDir.isNotBlank()) {
            args.add("--project-dir")
            args.add(projectDir)
        }

        LOG.info("Capturing game window: ${args.joinToString(" ")}")
        return try {
            val process = ProcessBuilder(args)
                .directory(File(projectDir.ifBlank { "." }))
                .redirectErrorStream(false)
                .start()
            val stderrBuilder = StringBuilder()
            val stderrThread = Thread {
                runCatching {
                    process.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                        synchronized(stderrBuilder) { stderrBuilder.append(line).append('\n') }
                    }
                }
            }.apply { isDaemon = true; start() }
            process.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { /* 丢弃 stdout 日志 */ }
            val completed = process.waitFor(60, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                error.append("timeout")
                return null
            }
            stderrThread.join(1000)
            if (process.exitValue() != 0) {
                error.append(stderrBuilder.toString().ifBlank { "exit code ${process.exitValue()}" })
                return null
            }
            if (!Files.exists(outputPath)) {
                error.append("file not created")
                return null
            }
            outputPath
        } catch (e: Exception) {
            LOG.warn("capture_game_window failed", e)
            error.append(e.message ?: e.javaClass.simpleName)
            null
        }
    }
}

/** 避免与 ui 包互相依赖的最小 bundle 引用 */
private object OkScriptToolkitBundle0 {
    fun message(key: String): String =
        com.alicejump.okscripttoolkit.OkScriptToolkitBundle.message(key)
}

/** 窗口匹配配置（probe_window_config.py 的解析结果） */
data class WindowConfig(
    val exe: List<String>? = null,
    val title: String? = null,
    val hwndClass: String? = null,
) {
    fun describe(): String = listOfNotNull(
        exe?.takeIf { it.isNotEmpty() }?.let { "exe: ${it.joinToString(", ")}" },
        title?.let { "title: $it" },
        hwndClass?.let { "class: $it" },
    ).joinToString(" | ")
}
