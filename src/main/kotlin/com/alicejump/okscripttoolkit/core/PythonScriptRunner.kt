package com.alicejump.okscripttoolkit.core

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Python 脚本运行器，封装 ProcessBuilder 调用 Python 脚本。
 * 对应 VS Code 版本的 runPython 函数。
 */
class PythonScriptRunner(private val project: Project) {

    companion object {
        private val LOG = Logger.getInstance(PythonScriptRunner::class.java)
    }

    /**
     * 异步运行 Python 脚本。
     */
    fun runAsync(
        pythonPath: String,
        scriptPath: String,
        args: List<String> = emptyList(),
        workingDir: File,
        timeoutMs: Long = 15000,
    ): CompletableFuture<ScriptProcessOutput> {
        return CompletableFuture.supplyAsync {
            runSync(pythonPath, scriptPath, args, workingDir, timeoutMs)
        }
    }

    /**
     * 同步运行 Python 脚本。
     * 注意：必须先 waitFor(timeout) 再收集输出（读取放在后台线程），否则进程不退出时
     * readText() 会无限阻塞，timeout 形同虚设（曾导致任务 schema 探测卡死）。
     */
    fun runSync(
        pythonPath: String,
        scriptPath: String,
        args: List<String> = emptyList(),
        workingDir: File,
        timeoutMs: Long = 15000,
    ): ScriptProcessOutput {
        val command = mutableListOf(pythonPath, scriptPath)
        command.addAll(args)

        val processBuilder = ProcessBuilder(command)
            .directory(workingDir)
            .redirectErrorStream(false)

        processBuilder.environment()["PYTHONIOENCODING"] = "utf-8"
        processBuilder.environment()["PYTHONUTF8"] = "1"

        LOG.info("Running Python script: ${command.joinToString(" ")}")

        val process = processBuilder.start()
        val stdoutBuilder = StringBuilder()
        val stderrBuilder = StringBuilder()

        val stdoutThread = Thread {
            runCatching {
                process.inputStream.bufferedReader(StandardCharsets.UTF_8).forEachLine { line ->
                    synchronized(stdoutBuilder) { stdoutBuilder.append(line).append('\n') }
                }
            }
        }.apply { isDaemon = true; start() }
        val stderrThread = Thread {
            runCatching {
                process.errorStream.bufferedReader(StandardCharsets.UTF_8).forEachLine { line ->
                    synchronized(stderrBuilder) { stderrBuilder.append(line).append('\n') }
                }
            }
        }.apply { isDaemon = true; start() }

        val completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        val isTimeout = !completed
        if (isTimeout) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
        // 给读取线程一点时间收尾，但不再无限等待
        stdoutThread.join(2000)
        stderrThread.join(2000)

        val stdout = synchronized(stdoutBuilder) { stdoutBuilder.toString() }
        val stderr = synchronized(stderrBuilder) { stderrBuilder.toString() }
        val exitCode = if (completed) process.exitValue() else -1

        return ScriptProcessOutput(stdout, stderr, exitCode, isTimeout)
    }

    /**
     * 解析 JSON 输出：从 stdout 中提取最后一个 JSON 行（前面的输出可能是日志）。
     */
    fun parseJsonFromStdout(stdout: String): String? = PythonScriptUtils.parseJsonFromStdout(stdout)

    /**
     * 解析额外参数：优先接受 JSON 字符串数组，否则按 shell 风格引号拆分。
     */
    fun parseExtraArgs(value: String?): List<String> = PythonScriptUtils.parseExtraArgs(value)

    /**
     * 构建常驻执行器命令行。对应 VS Code 版本的 buildExecutorCommand 函数。
     *
     * 单进程模型：一次启动即连接游戏，随后由框架 TaskExecutor 循环轮询全部已启用的
     * 触发任务；一次性任务经 stdin 的 onetime_enqueue 命令入队，不再是启动参数。
     */
    fun buildExecutorCommand(
        pythonScriptDir: String,
        configModule: String = "src.config",
    ): List<String> {
        return listOf(
            "$pythonScriptDir/run_executor.py",
            "--config-module", configModule,
        )
    }
}

/**
 * Python 打包脚本的目录定位：项目内 python/ -> 父/祖目录 -> 同级目录 -> 从插件 JAR 解压。
 * 打包脚本全集见 [BUNDLED_SCRIPTS]（与 VSCode 版 python/ 目录一致）。
 */
object PythonScriptLocator {

    private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(PythonScriptLocator::class.java)

    /** 解压目录名。**不带时间戳** —— 见 [extractBundledScripts] 里关于旧实现的说明。 */
    const val SCRIPT_DIR_NAME = "ok-script-toolkit-scripts"

    /** 历史遗留目录的前缀（旧版把 lastModified 拼成了 `ok-script-toolkit-scripts-0` 之类）。 */
    private const val SCRIPT_DIR_PREFIX = "ok-script-toolkit-scripts"

    val BUNDLED_SCRIPTS = listOf(
        "parse_config_tasks.py",
        "probe_task_schemas.py",
        "run_executor.py",
        "run_task.py",
        "capture_game_window.py",
        "probe_window_config.py",
        "connect_game.py",
        "overlay_host.py",
    )

    /**
     * 定位插件自带的 python 脚本目录。
     * 只从插件 JAR 资源提取，不搜索项目文件系统（项目数据与插件脚本严格隔离）。
     * 找不到时抛异常，调用方无需兜底。
     */
    fun findScriptDir(): String {
        return extractBundledScripts()?.normalize()?.toString()
            ?: throw IllegalStateException(
                "Bundled Python scripts not found in plugin JAR. " +
                    "Ensure the plugin is built with copyPythonScripts task.",
            )
    }

    /**
     * 从插件 JAR 的 classpath 解压打包脚本到临时目录。
     *
     * ⚠️ **每次调用都必须覆盖写出**，不能用「文件都在就跳过」的缓存判断。
     *
     * 曾经用 `classLoader.getResource(...).openConnection().lastModified` 当版本戳，
     * 并把它拼进目录名（`ok-script-toolkit-scripts-<stamp>`）。但 **JAR 内资源的
     * `lastModified` 实测恒为 0**，于是目录名恒为 `ok-script-toolkit-scripts-0`；
     * 再配合「8 个文件都在就直接 return」，后果是：
     * **一旦解压过，插件升级后再也不会重新解压 —— 用户会一直跑旧脚本。**
     *
     * 真实后果（2026-09-20 实测确认）：用户装的 1.7.1 里 `run_executor.py` 是
     * 「配置沙箱」提交（`eb4d00c`，09-19）**之前**的版本；即使装上含沙箱的新插件，
     * 临时目录里的旧 `run_executor.py` 仍会被继续使用，于是**沙箱完全没生效** ——
     * 执行器照样写目标项目的 `configs/`（实测：执行器运行期间项目 configs 被写、
     * 沙箱目录纹丝不动）。
     *
     * 覆盖写出的代价是 8 个小文件（合计约 90 KB），相对"跑错脚本"完全可以忽略。
     *
     * @param baseDir 临时根目录。做成参数是为了让单测指向自己的临时目录 ——
     *   否则测试一旦失败会在真实临时目录里留下损坏脚本，反而弄坏用户的插件。
     */
    fun extractBundledScripts(
        baseDir: java.io.File = java.io.File(System.getProperty("java.io.tmpdir")),
    ): java.nio.file.Path? {
        return try {
            val resource = PythonScriptLocator::class.java.classLoader
                .getResourceAsStream("python/${BUNDLED_SCRIPTS.first()}") ?: return null
            resource.close()

            val extractDir = baseDir.toPath().resolve(SCRIPT_DIR_NAME)
            java.nio.file.Files.createDirectories(extractDir)
            var written = 0
            for (name in BUNDLED_SCRIPTS) {
                val input = PythonScriptLocator::class.java.classLoader
                    .getResourceAsStream("python/$name") ?: continue
                input.use { stream ->
                    java.nio.file.Files.copy(
                        stream,
                        extractDir.resolve(name),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    )
                }
                written++
            }
            // 一个都没解出来说明 JAR 里确实没有脚本，交给调用方报错。
            if (written == 0) return null
            cleanupOldScriptDirs(extractDir, baseDir)
            extractDir
        } catch (e: Exception) {
            LOG.warn("Failed to extract bundled Python scripts", e)
            null
        }
    }

    /**
     * 清理历史遗留的脚本目录（旧版把时间戳拼进了目录名，形如
     * `ok-script-toolkit-scripts-0`），只保留当前使用的 [currentDir]。
     */
    private fun cleanupOldScriptDirs(currentDir: java.nio.file.Path, baseDir: java.io.File) {
        try {
            baseDir.listFiles()?.forEach { dir ->
                if (dir.isDirectory &&
                    dir.name.startsWith(SCRIPT_DIR_PREFIX) &&
                    dir.toPath() != currentDir
                ) {
                    try {
                        dir.deleteRecursively()
                        LOG.info("Cleaned up old script directory: ${dir.name}")
                    } catch (e: Exception) {
                        LOG.warn("Failed to delete old script directory: ${dir.name}", e)
                    }
                }
            }
        } catch (e: Exception) {
            LOG.warn("Failed to cleanup old script directories", e)
        }
    }
}

/**
 * 无平台依赖的 Python 脚本输出解析工具（可独立单测）。
 */
object PythonScriptUtils {

    /**
     * 解析 JSON 输出：从 stdout 中提取最后一个 JSON 行（前面的输出可能是日志）。
     */
    fun parseJsonFromStdout(stdout: String): String? {
        val lines = stdout.split("\n").filter { it.isNotBlank() }
        for (i in lines.size - 1 downTo 0) {
            val line = lines[i].trim()
            if (line.startsWith("{") && line.endsWith("}")) {
                return line
            }
        }
        return null
    }

    /**
     * 解析额外参数：优先接受 JSON 字符串数组，否则按 shell 风格引号拆分。
     */
    fun parseExtraArgs(value: String?): List<String> {
        val text = value?.trim() ?: return emptyList()
        if (text.isEmpty()) return emptyList()

        // 尝试 JSON 解析
        try {
            if (text.startsWith("[") && text.endsWith("]")) {
                val content = text.substring(1, text.length - 1)
                if (content.isEmpty()) return emptyList()
                return content.split(",").map { it.trim().removeSurrounding("\"") }
            }
        } catch (_: Exception) { }

        // 引号拆分逻辑
        val args = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char = '\u0000'
        var escaped = false

        for (char in text) {
            when {
                escaped -> {
                    current.append(char)
                    escaped = false
                }
                char == '\\' -> escaped = true
                quote != '\u0000' -> {
                    if (char == quote) quote = '\u0000'
                    else current.append(char)
                }
                char == '"' || char == '\'' -> quote = char
                char.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        args.add(current.toString())
                        current.clear()
                    }
                }
                else -> current.append(char)
            }
        }

        if (escaped) current.append('\\')
        if (quote != '\u0000') throw IllegalArgumentException("Extra arguments contain an unclosed quote")
        if (current.isNotEmpty()) args.add(current.toString())

        return args
    }
}

/**
 * ProcessOutput 数据类，包含命令执行结果。
 */
data class ScriptProcessOutput(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val isTimeout: Boolean,
)
