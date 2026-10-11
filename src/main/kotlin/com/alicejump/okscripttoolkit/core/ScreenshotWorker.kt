package com.alicejump.okscripttoolkit.core

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** 截图专用解释器，复用导入开销；每个请求有独立时限，闲置后释放进程。 */
internal class ScreenshotWorker(
    private val python: String,
    private val script: String,
    private val projectDir: String,
    private val log: (String) -> Unit,
    private val timeoutMs: Long = 30_000,
    private val idleMs: Long = 60_000,
    private val launch: (() -> Process)? = null,
) {
    data class Reply(val id: Long, val ok: Boolean, val error: String = "", val restart: Boolean = false,
                     val configJson: String? = null)
    private data class Session(val process: Process, val replies: LinkedBlockingQueue<Reply>)
    private val json = ObjectMapper()
    @Volatile private var session: Session? = null
    private var nextId = 0L
    @Volatile private var disposed = false
    private val scheduler = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "ok-script-capture-idle").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private var idle: ScheduledFuture<*>? = null

    fun capture(output: Path, windowConfigJson: String?, method: String): Reply = request(mapOf(
        "output_path" to output.toString(), "window_config" to windowConfigJson?.let { json.readTree(it) },
        "method" to method,
    ))

    fun probe(): Reply = request(mapOf("op" to "probe"))

    @Synchronized
    private fun request(payload: Map<String, Any?>): Reply {
        check(!disposed) { "Screenshot worker disposed" }
        idle?.cancel(false)
        try {
            repeat(2) {
                val current = session ?: start().also {
                    session = it
                    if (disposed) {
                        stop()
                        error("Screenshot worker disposed")
                    }
                }
                val id = ++nextId
                val request = json.writeValueAsString(payload + mapOf("id" to id, "project_dir" to projectDir))
                current.process.outputStream.write((request + "\n").toByteArray(Charsets.UTF_8))
                current.process.outputStream.flush()
                val reply = current.replies.poll(timeoutMs, TimeUnit.MILLISECONDS)
                if (reply == null) {
                    stop()
                    return Reply(id, false, "Screenshot worker timed out")
                }
                if (reply.restart) {
                    stop()
                } else {
                    if (reply.id != id) {
                        stop()
                        return Reply(id, false, reply.error.ifBlank { "Screenshot worker exited" })
                    }
                    return reply
                }
            }
            return Reply(nextId, false, "Screenshot sources changed repeatedly")
        } catch (e: Exception) {
            stop()
            if (e is InterruptedException) Thread.currentThread().interrupt()
            throw e
        } finally {
            if (!disposed && session != null) {
                try {
                    idle = scheduler.schedule({ synchronized(this) { stop() } }, idleMs, TimeUnit.MILLISECONDS)
                } catch (e: java.util.concurrent.RejectedExecutionException) {
                    if (!disposed) throw e
                }
            }
        }
    }

    fun dispose() {
        disposed = true
        idle?.cancel(false)
        val current = session
        session = null
        current?.process?.destroyForcibly()
        current?.replies?.offer(Reply(-1, false, "Screenshot worker disposed"))
        scheduler.shutdownNow()
    }

    private fun start(): Session {
        val builder = ProcessBuilder(python, script, "--serve").directory(File(projectDir.ifBlank { "." }))
        builder.environment()["PYTHONIOENCODING"] = "utf-8"
        builder.environment()["PYTHONUTF8"] = "1"
        val process = launch?.invoke() ?: builder.start()
        val replies = LinkedBlockingQueue<Reply>()
        Thread {
            try {
                runCatching {
                    process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                        reader.forEachLine { line ->
                            val node = runCatching { json.readTree(line) }.getOrNull()
                            if (node != null && node.has("capture_response")) {
                                replies.offer(Reply(node.path("capture_response").asLong(), node.path("ok").asBoolean(),
                                    node.path("error").asText(""), node.path("restart").asBoolean(),
                                    node.get("config")?.toString()))
                            } else log(line)
                        }
                    }
                }
            } finally {
                replies.offer(Reply(-1, false, "Screenshot worker exited"))
            }
        }.apply { name = "ok-script-capture-stdout"; isDaemon = true; start() }
        Thread {
            runCatching {
                process.errorStream.bufferedReader(Charsets.UTF_8).use { reader -> reader.forEachLine(log) }
            }
        }.apply { name = "ok-script-capture-stderr"; isDaemon = true; start() }
        return Session(process, replies)
    }

    private fun stop() {
        idle?.cancel(false)
        session?.process?.destroyForcibly()
        session = null
    }
}
