package com.alicejump.okscripttoolkit.toolbox

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.alicejump.okscripttoolkit.core.PythonScriptLocator
import com.alicejump.okscripttoolkit.core.PythonScriptRunner
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

/**
 * 工具箱共享状态：调试浮层开关 + 游戏窗口连接，对应 VS Code 版 toolboxState.ts。
 * 任务启动器无感复用：任务启动时沿用浮层开关（OK_TOOLKIT_USE_OVERLAY），
 * 游戏连接由 connect_game.py 写入项目 configs/devices.json 供任务进程原生优先。
 *
 * 状态持久化在项目 .idea/ok-script-toolkit-toolbox.json（VS Code 版写在工作区
 * .vscode 下，结构相同：{ projects: { <projectDir>: { overlay, game } } }）。
 */
@Service(Service.Level.PROJECT)
class ToolboxService(private val project: Project) : Disposable {

    companion object {
        private val LOG = Logger.getInstance(ToolboxService::class.java)
        private val JSON = ObjectMapper()

        private const val TOOLBOX_FILE = ".idea/ok-script-toolkit-toolbox.json"
        /** 游戏未运行时会自动启动并等待窗口（脚本侧最长 150s），超时要留足余量 */
        private const val CONNECT_TIMEOUT_MS = 200_000L
        private const val DISCONNECT_TIMEOUT_MS = 30_000L
        private const val OVERLAY_HOST_READY_MARKER = "OK_TOOLKIT_OVERLAY_HOST_READY"
        private const val OVERLAY_HOST_EXIT_TIMEOUT_SECONDS = 10L

        fun getInstance(project: Project): ToolboxService = project.service()

        /** 解析 .idea/ok-script-toolkit-toolbox.json 的 projects 映射（纯函数，可独立单测） */
        fun parseStore(node: JsonNode): Map<String, ToolboxState> {
            val result = linkedMapOf<String, ToolboxState>()
            node.path("projects").fields().forEach { (dir, stateNode) ->
                val gameNode = stateNode.path("game")
                result[dir] = ToolboxState(
                    overlay = stateNode.path("overlay").asBoolean(false),
                    game = if (gameNode.isObject && !gameNode.isNull) {
                        GameConnection(
                            hwnd = gameNode.path("hwnd").asLong(0),
                            pid = gameNode.path("pid").asLong(0),
                            title = gameNode.path("title").asText(""),
                            exe = gameNode.path("exe").asText(""),
                            connectedAt = gameNode.path("connectedAt").asLong(0),
                        )
                    } else {
                        null
                    },
                )
            }
            return result
        }
    }

    /** 工具箱"连接游戏"建立的窗口连接（同时写入项目 configs/devices.json 供任务进程复用） */
    data class GameConnection(
        val hwnd: Long,
        val pid: Long,
        val title: String,
        val exe: String,
        val connectedAt: Long,
    )

    data class ToolboxState(
        val overlay: Boolean = false,
        val game: GameConnection? = null,
    )

    private val pythonRunner = PythonScriptRunner(project)
    private val storeLock = Any()

    /** projectDir -> 状态缓存；与磁盘文件内容保持一致（读写均持 storeLock） */
    private val cache = HashMap<String, ToolboxState>()

    private val stateListeners = CopyOnWriteArrayList<(ToolboxState, String) -> Unit>()
    private val statusListeners = CopyOnWriteArrayList<(String) -> Unit>()

    /** 运行中任务的 stdin 命令写入器（任务面板注册；返回 false 表示任务不可用） */
    @Volatile
    private var taskCommandWriter: ((String) -> Boolean)? = null

    @Volatile
    private var overlayHost: Process? = null
    @Volatile
    private var overlayHostProjectDir = ""

    /** 连接进行中标记（防重入；UI 据此禁用连接按钮） */
    private val connecting = AtomicBoolean(false)

    // ── State access ──────────────────────────────────────────────────

    /** 读取某项目的工具箱状态（无记录返回空状态） */
    fun loadState(projectDir: String): ToolboxState {
        if (projectDir.isBlank()) return ToolboxState()
        synchronized(storeLock) {
            cache[projectDir]?.let { return it }
            val state = readStoreFile()[projectDir] ?: ToolboxState()
            cache[projectDir] = state
            return state
        }
    }

    /** 订阅状态变化；回调在 EDT 上执行，参数为 (新状态, 项目目录) */
    fun addStateListener(listener: (ToolboxState, String) -> Unit) {
        stateListeners.add(listener)
    }

    fun removeStateListener(listener: (ToolboxState, String) -> Unit) {
        stateListeners.remove(listener)
    }

    /** 订阅瞬态状态文案（连接中/失败/浮层宿主就绪等；空串表示清除） */
    fun addStatusListener(listener: (String) -> Unit) {
        statusListeners.add(listener)
    }

    fun removeStatusListener(listener: (String) -> Unit) {
        statusListeners.remove(listener)
    }

    fun postStatus(text: String) {
        SwingUtilities.invokeLater {
            statusListeners.forEach { it(text) }
        }
    }

    /** 合并写入状态并通知订阅者（调用线程任意；回调统一切到 EDT） */
    private fun saveState(projectDir: String, patch: (ToolboxState) -> ToolboxState): ToolboxState {
        require(projectDir.isNotBlank()) { "projectDir must not be blank" }
        val next = synchronized(storeLock) {
            val current = cache[projectDir] ?: readStoreFile()[projectDir] ?: ToolboxState()
            val updated = patch(current)
            cache[projectDir] = updated
            // 合并写回整个 store，避免覆盖其它项目条目；写入失败不影响内存态
            val store = readStoreFile().toMutableMap()
            store[projectDir] = updated
            storeFile()?.let { file ->
                try {
                    file.parentFile?.mkdirs()
                    JSON.writerWithDefaultPrettyPrinter().writeValue(file, mapOf("projects" to store))
                } catch (e: Exception) {
                    LOG.warn("Failed to save toolbox state", e)
                }
            }
            updated
        }
        SwingUtilities.invokeLater {
            stateListeners.forEach { it(next, projectDir) }
        }
        return next
    }

    private fun storeFile(): File? {
        val basePath = project.basePath ?: return null
        return Paths.get(basePath, TOOLBOX_FILE).toFile()
    }

    private fun readStoreFile(): Map<String, ToolboxState> {
        val file = storeFile() ?: return emptyMap()
        if (!file.isFile) return emptyMap()
        return try {
            parseStore(JSON.readTree(file))
        } catch (e: Exception) {
            LOG.warn("Failed to read toolbox state, treating as empty", e)
            emptyMap()
        }
    }

    // ── Game connection ───────────────────────────────────────────────

    /**
     * 连接游戏窗口：搜索并写入 devices.json，任务进程启动时原生优先该窗口。
     * 游戏未运行时脚本会自动启动并等待窗口。完成后错误经状态文案上报。
     */
    fun connectGame(projectDir: String, pythonPath: String): CompletableFuture<Unit> {
        if (projectDir.isBlank()) {
            postStatus(OkScriptToolkitBundle.message("toolbox.noProject"))
            return CompletableFuture.completedFuture(Unit)
        }
        if (!connecting.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(Unit)
        }
        postStatus(OkScriptToolkitBundle.message("toolbox.connecting"))
        return CompletableFuture.supplyAsync {
            try {
                val script = Paths.get(PythonScriptLocator.findScriptDir(), "connect_game.py")
                val result = pythonRunner.runSync(
                    pythonPath = pythonPath,
                    scriptPath = script.toString(),
                    args = listOf(projectDir),
                    workingDir = File(projectDir),
                    timeoutMs = CONNECT_TIMEOUT_MS,
                )
                // 项目脚本约定：失败时往 stdout 末尾打印 {"ok": false, "error": "..."}；
                // 优先回传结构化错误，其次 stderr，最后才是通用命令失败信息。
                val parsed = pythonRunner.parseJsonFromStdout(result.stdout)
                    ?.let { runCatching { JSON.readTree(it) }.getOrNull() }
                if (parsed?.path("ok")?.asBoolean(false) != true) {
                    val error = parsed?.path("error")?.asText(null)
                        ?: result.stderr.trim().ifEmpty {
                            OkScriptToolkitBundle.message("toolbox.connectExit", result.exitCode)
                        }
                    throw IllegalStateException(error)
                }
                val game = GameConnection(
                    hwnd = parsed.path("hwnd").asLong(0),
                    pid = parsed.path("pid").asLong(0),
                    title = parsed.path("title").asText(""),
                    exe = parsed.path("exe").asText(""),
                    connectedAt = System.currentTimeMillis(),
                )
                saveState(projectDir) { it.copy(game = game) }
                postStatus("")
                // 调试浮层开启时拉起常驻宿主：无需启动任务即可 Alt+右键框选取坐标
                if (loadState(projectDir).overlay) {
                    startOverlayHost(projectDir, pythonPath)
                }
            } catch (e: Exception) {
                LOG.warn("connect_game.py failed", e)
                postStatus(OkScriptToolkitBundle.message("toolbox.connectFailed", e.message ?: "unknown"))
            } finally {
                connecting.set(false)
            }
        }
    }

    /**
     * 断开连接：清除 devices.json 里的窗口选中，任务进程恢复自动探测。
     * devices.json 缺失等场景按已断开处理。
     */
    fun disconnectGame(projectDir: String, pythonPath: String) {
        if (projectDir.isBlank()) return
        CompletableFuture.runAsync {
            try {
                val script = Paths.get(PythonScriptLocator.findScriptDir(), "connect_game.py")
                pythonRunner.runSync(
                    pythonPath = pythonPath,
                    scriptPath = script.toString(),
                    args = listOf(projectDir, "--disconnect"),
                    workingDir = File(projectDir),
                    timeoutMs = DISCONNECT_TIMEOUT_MS,
                )
            } catch (e: Exception) {
                LOG.warn("connect_game.py --disconnect failed", e)
            }
            stopOverlayHost()
            saveState(projectDir) { it.copy(game = null) }
            postStatus("")
        }
    }

    // ── Debug overlay ─────────────────────────────────────────────────

    /**
     * 调试浮层开关：持久化到工具箱状态；任务运行中即时下发，否则下次启动沿用。
     * 常驻宿主跟随开关：开启且已连接游戏时保持，关闭即停。
     */
    fun setOverlayEnabled(projectDir: String, pythonPath: String, enabled: Boolean) {
        if (projectDir.isBlank()) {
            // 无项目上下文：不改状态，回推当前空状态让 UI 复位（对齐 VS Code postToolboxState）
            SwingUtilities.invokeLater {
                stateListeners.forEach { it(ToolboxState(), projectDir) }
            }
            return
        }
        saveState(projectDir) { it.copy(overlay = enabled) }
        sendOverlayCommand(enabled)
        if (enabled && loadState(projectDir).game != null) {
            startOverlayHost(projectDir, pythonPath)
        } else if (!enabled) {
            stopOverlayHost()
        }
    }

    /**
     * 工具箱浮层开关 → 运行中任务即时生效（stdin overlay_on/off 命令）。
     * 无运行任务时返回 false，开关状态由工具箱直接持久化、下次启动沿用。
     */
    fun sendOverlayCommand(enabled: Boolean): Boolean {
        val writer = taskCommandWriter ?: return false
        return writer(if (enabled) "overlay_on" else "overlay_off")
    }

    /** 任务面板注册运行中任务的命令写入器（传 null 注销） */
    fun registerTaskCommandWriter(writer: ((String) -> Boolean)?) {
        taskCommandWriter = writer
    }

    /** 任务 stdout 的浮层标记行：以 run_task.py 确认为准回写共享状态 */
    fun onTaskOverlayMarker(projectDir: String, active: Boolean) {
        if (projectDir.isBlank()) return
        saveState(projectDir) { it.copy(overlay = active) }
    }

    // ── Persistent overlay host ───────────────────────────────────────

    /** 拉起常驻浮层宿主（同项目已运行则复用） */
    fun startOverlayHost(projectDir: String, pythonPath: String) {
        if (projectDir.isBlank()) return
        val existing = overlayHost
        if (existing != null && overlayHostProjectDir == projectDir && existing.isAlive) return
        stopOverlayHost()

        val scriptDir = try {
            PythonScriptLocator.findScriptDir()
        } catch (e: Exception) {
            LOG.warn("Failed to locate bundled scripts for overlay host", e)
            postStatus(OkScriptToolkitBundle.message("toolbox.overlayHostFailed", e.message ?: "unknown"))
            return
        }
        try {
            val script = Paths.get(scriptDir, "overlay_host.py")
            val builder = ProcessBuilder(pythonPath, script.toString(), projectDir)
                .directory(File(projectDir))
            builder.environment()["PYTHONIOENCODING"] = "utf-8"
            builder.environment()["PYTHONUTF8"] = "1"
            val child = builder.start()
            overlayHost = child
            overlayHostProjectDir = projectDir
            LOG.info("[overlay-host] spawned: $pythonPath overlay_host.py $projectDir")

            Thread {
                runCatching {
                    child.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                        LOG.info("[overlay-host] $line")
                        if (line.contains(OVERLAY_HOST_READY_MARKER)) {
                            postStatus(OkScriptToolkitBundle.message("toolbox.overlayHostReady"))
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
            Thread {
                runCatching {
                    child.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                        LOG.warn("[overlay-host] $line")
                    }
                }
            }.apply { isDaemon = true; start() }
            Thread {
                child.waitFor()
                if (overlayHost === child) overlayHost = null
            }.apply { isDaemon = true; start() }
        } catch (e: Exception) {
            LOG.warn("Failed to start overlay host", e)
            postStatus(OkScriptToolkitBundle.message("toolbox.overlayHostFailed", e.message ?: "unknown"))
        }
    }

    /** 停止常驻浮层宿主（若有） */
    fun stopOverlayHost() {
        val child = overlayHost ?: return
        overlayHost = null
        try {
            val pid = child.pid()
            if (pid > 0 && isWindows()) {
                // Windows 上 taskkill /F /T 直接结束宿主及其子进程
                ProcessBuilder("taskkill", "/F", "/T", "/PID", pid.toString())
                    .redirectErrorStream(true)
                    .start()
                    .waitFor(OVERLAY_HOST_EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } else {
                child.destroyForcibly()
            }
        } catch (e: Exception) {
            LOG.warn("Failed to stop overlay host", e)
            runCatching { child.destroyForcibly() }
        }
        if (overlayHostProjectDir.isNotEmpty()) {
            postStatus(OkScriptToolkitBundle.message("toolbox.overlayHostStopped"))
        }
        overlayHostProjectDir = ""
    }

    override fun dispose() {
        stopOverlayHost()
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase().contains("win")
}
