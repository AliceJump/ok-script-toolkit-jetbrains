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
     * 构建运行任务的命令行参数。
     * 对应 VS Code 版本的 buildRunTaskCommand 函数。
     */
    fun buildRunTaskCommand(
        pythonScriptDir: String,
        taskClassName: String,
        taskModule: String,
        configModule: String = "src.config",
    ): List<String> {
        return listOf(
            "$pythonScriptDir/run_task.py",
            "--task", taskClassName,
            "--task-module", taskModule,
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

    val BUNDLED_SCRIPTS = listOf(
        "parse_config_tasks.py",
        "probe_task_schemas.py",
        "run_task.py",
        "capture_game_window.py",
        "probe_window_config.py",
    )

    /** 定位打包脚本目录；都找不到时回退 <projectRoot>/python。 */
    fun findScriptDir(projectRoot: String): String {
        val projectDir = java.nio.file.Paths.get(projectRoot, "python")
        if (projectDir.toFile().exists()) return projectDir.normalize().toString()

        for (path in listOf(
            java.nio.file.Paths.get(projectRoot, "..", "python"),
            java.nio.file.Paths.get(projectRoot, "..", "..", "python"),
        )) {
            if (path.toFile().exists()) return path.normalize().toString()
        }

        val parentDir = java.nio.file.Paths.get(projectRoot, "..").toFile()
        val sibling = parentDir.listFiles()
            ?.filter { it.isDirectory && it.name != java.io.File(projectRoot).name }
            ?.map { java.nio.file.Paths.get(it.absolutePath, "python") }
            ?.firstOrNull { it.toFile().exists() }
        if (sibling != null) return sibling.normalize().toString()

        extractBundledScripts()?.let { return it.normalize().toString() }
        return projectDir.normalize().toString()
    }

    /** 从插件 JAR 的 classpath 解压打包脚本到临时目录（带版本戳避免旧脚本残留）。 */
    fun extractBundledScripts(): java.nio.file.Path? {
        return try {
            val resource = PythonScriptLocator::class.java.classLoader
                .getResourceAsStream("python/${BUNDLED_SCRIPTS.first()}") ?: return null
            resource.close()

            val stamp = try {
                PythonScriptLocator::class.java.classLoader.getResource("python/${BUNDLED_SCRIPTS.first()}")
                    ?.openConnection()?.lastModified ?: 0L
            } catch (_: Exception) { 0L }
            val extractDir = java.nio.file.Paths.get(
                System.getProperty("java.io.tmpdir"),
                "ok-script-toolkit-scripts-$stamp",
            )
            if (BUNDLED_SCRIPTS.all { java.nio.file.Files.exists(extractDir.resolve(it)) }) return extractDir

            java.nio.file.Files.createDirectories(extractDir)
            for (name in BUNDLED_SCRIPTS) {
                val input = PythonScriptLocator::class.java.classLoader
                    .getResourceAsStream("python/$name") ?: continue
                java.nio.file.Files.copy(
                    input,
                    extractDir.resolve(name),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
                input.close()
            }
            LOG.info("Extracted bundled Python scripts to $extractDir")
            extractDir
        } catch (e: Exception) {
            LOG.warn("Failed to extract bundled Python scripts", e)
            null
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
