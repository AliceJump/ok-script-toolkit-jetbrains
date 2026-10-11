package com.alicejump.okscripttoolkit.core

import java.io.File
import java.net.URI
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertSame

class ScreenshotWorkerTest {
    private fun child(mode: String): Process {
        val executable = File(System.getProperty("java.home"), "bin/java.exe")
            .takeIf { it.exists() } ?: File(System.getProperty("java.home"), "bin/java")
        val classpath = listOf(ScreenshotWorkerChild::class.java, Unit::class.java).map { type ->
            val entry = type.name.replace('.', '/') + ".class"
            val resource = requireNotNull(type.getResource("/$entry"))
            if (resource.protocol == "jar") {
                File(URI(resource.toExternalForm().removePrefix("jar:").substringBefore("!/"))).absolutePath
            } else File(resource.toURI()).absolutePath.removeSuffix(entry.replace('/', File.separatorChar))
        }.distinct().joinToString(File.pathSeparator)
        return ProcessBuilder(executable.absolutePath, "-cp", classpath,
            ScreenshotWorkerChild::class.java.name, mode).start()
    }

    @Test
    fun `background warmup coalesces triggers and shares the process with the first shot`() {
        val launched = AtomicInteger()
        val worker = ScreenshotWorker("unused", "unused", "", {}, launch = {
            launched.incrementAndGet(); child("normal")
        })
        try {
            val warming = worker.prewarm("auto")
            assertSame(warming, worker.prewarm("auto"))
            val warmed = warming.get(5, TimeUnit.SECONDS)
            assertTrue(warmed.ok)
            assertTrue(warmed.frameReady)
            assertFalse(worker.prewarm("error").get(5, TimeUnit.SECONDS).ok)
            assertTrue(worker.capture(Paths.get("shot.bmp"), null, "auto").ok)
            assertEquals(1, launched.get())
        } finally { worker.dispose() }
    }

    @Test
    fun `idle warmup exits and reopening can prewarm a new process`() {
        val launched = AtomicInteger()
        val worker = ScreenshotWorker("unused", "unused", "", {}, idleMs = 50, launch = {
            launched.incrementAndGet(); child("normal")
        })
        try {
            assertTrue(worker.prewarm("auto").get(5, TimeUnit.SECONDS).ok)
            Thread.sleep(150)
            assertTrue(worker.prewarm("auto").get(5, TimeUnit.SECONDS).ok)
            assertEquals(2, launched.get())
        } finally { worker.dispose() }
    }

    @Test
    fun `blocked output cannot prevent timeout and the next request recovers`() {
        val launched = AtomicInteger()
        val worker = ScreenshotWorker("unused", "unused", "", {}, timeoutMs = 500,
            launch = { child(if (launched.incrementAndGet() == 1) "hang" else "normal") })
        try {
            val started = System.nanoTime()
            assertFalse(worker.prewarm("auto").get(5, TimeUnit.SECONDS).ok)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 4000)
            assertTrue(worker.capture(Paths.get("shot.png"), null, "auto").ok)
            assertEquals(2, launched.get())
        } finally { worker.dispose() }
    }

    @Test
    fun `full pipes are drained and consecutive shots share one process`() {
        val launched = AtomicInteger()
        val worker = ScreenshotWorker("unused", "unused", "", {},
            launch = { launched.incrementAndGet(); child("flood") })
        try {
            val probe = worker.probe()
            assertTrue(probe.ok)
            assertEquals("{\"ok\":true,\"exe\":[\"game.exe\"]}", probe.configJson)
            repeat(2) { assertTrue(worker.capture(Paths.get("shot.png"), null, "auto").ok) }
            assertEquals(1, launched.get())
        } finally { worker.dispose() }
    }

    @Test
    fun `source change restarts the interpreter and retries the same shot`() {
        val launched = AtomicInteger()
        val worker = ScreenshotWorker("unused", "unused", "", {},
            launch = { child(if (launched.incrementAndGet() == 1) "restart" else "normal") })
        try {
            assertTrue(worker.capture(Paths.get("shot.png"), null, "auto").ok)
            assertEquals(2, launched.get())
        } finally { worker.dispose() }
    }

    @Test
    fun `process failure returns promptly without a reply`() {
        val worker = ScreenshotWorker("unused", "unused", "", {}, launch = { child("exit") })
        try {
            val started = System.nanoTime()
            val reply = worker.capture(Paths.get("shot.png"), null, "auto")
            assertFalse(reply.ok)
            assertTrue(reply.error.contains("exited"))
            assertTrue((System.nanoTime() - started) / 1_000_000 < 4000)
        } finally { worker.dispose() }
    }

    @Test
    fun `closing a project cancels an in flight shot without waiting for its timeout`() {
        val started = CountDownLatch(1)
        val worker = ScreenshotWorker("unused", "unused", "", {}, launch = {
            child("hang").also { started.countDown() }
        })
        val done = CountDownLatch(1)
        val background = Thread {
            try { runCatching { worker.capture(Paths.get("shot.png"), null, "auto") } }
            finally { done.countDown() }
        }.apply { start() }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val before = System.nanoTime()
            worker.dispose()
            assertTrue((System.nanoTime() - before) / 1_000_000 < 1000)
            assertTrue(done.await(2, TimeUnit.SECONDS))
        } finally {
            worker.dispose()
            background.join(2000)
        }
    }
}

object ScreenshotWorkerChild {
    @JvmStatic
    fun main(args: Array<String>) {
        val mode = args.single()
        generateSequence(::readLine).forEach { line ->
            val id = Regex("\\\"id\\\":(\\d+)").find(line)!!.groupValues[1]
            when (mode) {
                "hang" -> Thread.sleep(30_000)
                "exit" -> kotlin.system.exitProcess(7)
                "restart" -> {
                    println("{\"capture_response\":$id,\"restart\":true}")
                    return
                }
                else -> {
                    if (line.contains("\"op\":\"warmup\"")) {
                        Thread.sleep(100)
                        val ok = !line.contains("\"method\":\"error\"")
                        println("{\"capture_response\":$id,\"ok\":$ok,\"frame_ready\":true,\"error\":\"warmup failed\"}")
                        return@forEach
                    }
                    if (mode == "flood") repeat(5000) {
                        System.err.println("e".repeat(100))
                        System.out.println("x".repeat(100))
                    }
                    if (line.contains("\"op\":\"probe\"")) {
                        println("{\"capture_response\":$id,\"ok\":true,\"config\":{\"ok\":true,\"exe\":[\"game.exe\"]}}")
                    } else println("{\"capture_response\":$id,\"ok\":true}")
                }
            }
        }
    }
}
