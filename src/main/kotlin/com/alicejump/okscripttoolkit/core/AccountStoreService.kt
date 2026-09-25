package com.alicejump.okscripttoolkit.core

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.project.Project
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** `account_store.py get` 的数据。账号名到 ID 的映射及覆盖表保留原始 JSON 结构。 */
data class AccountStoreData(
    val accountListText: String,
    val registry: JsonNode,
    val accounts: JsonNode,
    val mapContents: JsonNode,
)

/**
 * 经项目自己的 account_scope_store 读写多账户配置，和 VS Code 的 runAccountStore 对齐。
 *
 * 所有操作按调用顺序排队，写入成功后再 get，UI 始终收到存储模块返回的最新数据。
 * 这里不使用 PythonScriptRunner，因为它会记录完整命令行，账号列表和覆盖值可能含凭据。
 * 返回的 Future 在后台完成，Swing 调用方须切回 EDT 更新界面。
 */
class AccountStoreService(private val project: Project) {
    private val mapper = ObjectMapper()
    private val queueLock = Any()
    private var tail: CompletableFuture<*> = CompletableFuture.completedFuture(Unit)

    fun load(projectDir: String): CompletableFuture<AccountStoreData> =
        enqueue(projectDir, listOf("get"), refreshAfterWrite = false)

    fun setListText(projectDir: String, text: String): CompletableFuture<AccountStoreData> =
        enqueue(projectDir, listOf("set_list", "--text", mapper.writeValueAsString(text)))

    /** account 为显示名称；storageName 来自探针 enabledTasks 的同名字段。 */
    fun setOverride(
        projectDir: String,
        account: String,
        storageName: String,
        params: Map<String, Any?>,
    ): CompletableFuture<AccountStoreData> = enqueue(
        projectDir,
        listOf(
            "set_override", "--account", account, "--task", storageName,
            "--values", mapper.writeValueAsString(params),
        ),
    )

    fun clearOverride(
        projectDir: String,
        account: String,
        storageName: String,
    ): CompletableFuture<AccountStoreData> = enqueue(
        projectDir,
        listOf("clear_override", "--account", account, "--task", storageName),
    )

    /** content 是原始地图文本；CLI 的 --content 需要 JSON 字符串字面量。 */
    fun setMapContent(
        projectDir: String,
        account: String,
        content: String,
    ): CompletableFuture<AccountStoreData> = enqueue(
        projectDir,
        listOf("set_map", "--account", account, "--content", mapper.writeValueAsString(content)),
    )

    private fun enqueue(
        projectDir: String,
        command: List<String>,
        refreshAfterWrite: Boolean = true,
    ): CompletableFuture<AccountStoreData> = synchronized(queueLock) {
        val next = tail.handle { _, _ -> Unit }.thenApplyAsync {
            val root = File(projectDir).absoluteFile
            require(projectDir.isNotBlank() && root.isDirectory) {
                OkScriptToolkitBundle.message("projectDir.invalid", projectDir)
            }
            val rootPath = root.canonicalPath
            val result = execute(rootPath, command)
            val data = if (refreshAfterWrite) execute(rootPath, listOf("get")) else result
            parseData(data)
        }
        tail = next
        next
    }

    /** Write commands return only a confirmation; get carries the data. */
    private fun execute(projectDir: String, command: List<String>): JsonNode {
        val script = File(PythonScriptLocator.findScriptDir(), "account_store.py")
        check(script.isFile) {
            OkScriptToolkitBundle.message("accountStore.scriptMissing", script.absolutePath)
        }
        val pythonPath = ScreenshotCapture.detectPythonPath(projectDir, project)
        val arguments = listOf(
            pythonPath, script.absolutePath, projectDir,
        ) + command + listOf("--run-dir", RunDir.forProject(projectDir))
        val builder = ProcessBuilder(arguments)
            .directory(File(projectDir))
            .redirectErrorStream(true)
        builder.environment()["PYTHONIOENCODING"] = "utf-8"
        builder.environment()["PYTHONUTF8"] = "1"

        // 合并 stdout/stderr 并立即读取，避免 Python 输出填满管道而阻塞退出。
        // 不记录完整命令或输出，二者可能包含账号数据。
        val process = builder.start()
        val output = StringBuilder()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { stream ->
                    stream.forEachLine { line -> synchronized(output) { output.append(line).append('\n') } }
                }
            }
        }.apply { isDaemon = true; start() }
        val finished = process.waitFor(60, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
        reader.join(2000)
        if (!finished) {
            throw IllegalStateException(OkScriptToolkitBundle.message("accountStore.timedOut", command.first()))
        }
        val payload = PythonScriptUtils.parseJsonFromStdout(synchronized(output) { output.toString() })
            ?: throw IllegalStateException(
                OkScriptToolkitBundle.message("accountStore.noJson", command.first(), process.exitValue()),
            )
        val data = mapper.readTree(payload)
        if (!data.path("ok").asBoolean(false)) {
            throw IllegalStateException(
                data.path("error").asText(OkScriptToolkitBundle.message("accountStore.failed", command.first())),
            )
        }
        if (process.exitValue() != 0) {
            throw IllegalStateException(
                OkScriptToolkitBundle.message("accountStore.exitCode", command.first(), process.exitValue()),
            )
        }
        return data
    }

    private fun parseData(data: JsonNode): AccountStoreData = AccountStoreData(
        accountListText = data.path("account_list_text").asText(""),
        registry = data.path("registry").takeIf { it.isObject } ?: mapper.createObjectNode(),
        accounts = data.path("accounts").takeIf { it.isObject } ?: mapper.createObjectNode(),
        mapContents = data.path("map_contents").takeIf { it.isObject } ?: mapper.createObjectNode(),
    )
}
