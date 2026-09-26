package com.alicejump.okscripttoolkit.core

import com.alicejump.okscripttoolkit.settings.OkScriptToolkitSettings
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.util.ui.UIUtil
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

private val JSON = ObjectMapper()
private val LOCALE_ORDER = listOf("zh_CN", "zh_TW", "en_US", "ja_JP", "ko_KR", "es_ES")
private const val SCAN_THROTTLE_MS = 300L

data class LangNode(val value: String, val type: String)
data class LangEntry(val module: String, val key: String, val locales: Map<String, LangNode>)
data class FeatureTemplate(
    val name: String,
    val imagePath: Path,
    val bbox: IntArray,
    val width: Int,
    val height: Int,
)
data class EffectEntry(val id: String, val description: String, val category: String)

@Service(Service.Level.PROJECT)
class OkProjectDataService(private val project: Project) {
    private data class Snapshot(
        val modules: Map<String, Map<String, Map<String, LangNode>>>,
        val poDomains: Map<String, Map<String, Map<String, LangNode>>>,
        val features: Map<String, FeatureTemplate>,
        val effects: Map<String, EffectEntry>,
        val stamp: Long,
        val settingsStamp: Long,
    )

    @Volatile
    private var snapshot = Snapshot(emptyMap(), emptyMap(), emptyMap(), emptyMap(), -1L, -1L)
    private val fileStamps = ConcurrentHashMap<Path, Long>()
    private val lastRefreshAttempt = AtomicLong(0L)

    fun modules(): List<String> = current().modules.keys.sorted()

    fun keys(module: String): List<String> = current().modules[module]?.keys?.sorted().orEmpty()

    fun langEntry(module: String, key: String): LangEntry? {
        val values = current().modules[module]?.get(key)
            ?: current().modules[module]?.get(key.replace(Regex("\\s+"), ""))
            ?: return null
        return LangEntry(module, key, values)
    }

    fun poKeys(domain: String): List<String> = current().poDomains[domain]?.keys?.sorted().orEmpty()

    fun poEntry(domain: String, key: String): LangEntry? {
        val values = current().poDomains[domain]?.get(key)
            ?: current().poDomains[domain]?.get(key.replace(Regex("\\s+"), ""))
            ?: return null
        return LangEntry(domain, key, values)
    }

    fun features(): List<FeatureTemplate> = current().features.values.sortedBy { it.name }
    fun feature(name: String): FeatureTemplate? = current().features[name]

    private data class OkTemplateCocoResult(
        val imagePath: Path,
        val bbox: IntArray,
        val resolvedAt: Long,
    )

    private data class OkTemplateCocoKey(val root: Path, val templatesDir: String, val name: String)

    // 与 VSCode 版 findOkTemplateCocoEntry 对齐：从 ok_templates 素材库 COCO
    // 按模板名反查原图与原始 bbox，带 30 秒 TTL 缓存
    private val okTemplateCocoCache = ConcurrentHashMap<OkTemplateCocoKey, OkTemplateCocoResult>()
    private val okTemplateCocoMissCache = ConcurrentHashMap<OkTemplateCocoKey, Long>()

    fun findOkTemplateCocoEntry(name: String): Pair<Path, IntArray>? {
        val root = rootPath()?.toAbsolutePath()?.normalize() ?: return null
        val templatesDir = settings().okTemplatesDirectory()
        val key = OkTemplateCocoKey(root, templatesDir, name)
        okTemplateCocoCache[key]?.let { cached ->
            if (System.currentTimeMillis() - cached.resolvedAt < 30_000L) {
                return cached.imagePath to cached.bbox
            }
        }
        okTemplateCocoMissCache[key]?.let { missAt ->
            if (System.currentTimeMillis() - missAt < 30_000L) return null
        }

        val cocoFiles = listOf(
            resolve(root, templatesDir).resolve("coco_annotations.json"),
            root.resolve("ok_tasks").resolve(resolve(root, templatesDir).fileName.toString())
                .resolve("coco_annotations.json"),
        )
        for (coco in cocoFiles) {
            if (!coco.isRegularFile()) continue
            val entry = runCatching {
                val data = JSON.readTree(coco.toFile())
                val categories = data.path("categories").associate { it.path("id").asInt() to it.path("name").asText() }
                val images = data.path("images").associate { it.path("id").asInt() to it.path("file_name").asText() }
                var found: Pair<Path, IntArray>? = null
                for (ann in data.path("annotations")) {
                    if (categories[ann.path("category_id").asInt()] != name) continue
                    val imageFile = images[ann.path("image_id").asInt()] ?: continue
                    val bboxNode = ann.path("bbox")
                    if (!bboxNode.isArray || bboxNode.size() < 4) continue
                    val bbox = IntArray(4) { bboxNode[it].asDouble().toInt() }
                    if (bbox[2] <= 0 || bbox[3] <= 0) continue
                    found = coco.parent.resolve(imageFile).normalize() to bbox
                    break
                }
                found
            }.getOrNull()
            if (entry != null) {
                okTemplateCocoCache[key] = OkTemplateCocoResult(entry.first, entry.second, System.currentTimeMillis())
                okTemplateCocoMissCache.remove(key)
                return entry
            }
        }
        okTemplateCocoMissCache[key] = System.currentTimeMillis()
        return null
    }
    fun effectIds(): List<String> = current().effects.keys.sorted()
    fun effect(id: String): EffectEntry? = current().effects[id]

    fun pick(entry: LangEntry, locale: String = currentLocale()): LangNode? {
        entry.locales[locale]?.let { return it }
        entry.locales["zh_CN"]?.let { return it }
        for (candidate in LOCALE_ORDER) entry.locales[candidate]?.let { return it }
        return entry.locales.values.firstOrNull()
    }

    fun currentLocale(): String {
        val configured = settings().displayLocale()
        if (configured != "auto") return configured
        val locale = Locale.getDefault().toLanguageTag().lowercase(Locale.ROOT)
        return when {
            locale.startsWith("zh-tw") || locale.startsWith("zh-hant") -> "zh_TW"
            locale.startsWith("zh") -> "zh_CN"
            locale.startsWith("ja") -> "ja_JP"
            locale.startsWith("ko") -> "ko_KR"
            locale.startsWith("es") -> "es_ES"
            else -> "en_US"
        }
    }

    fun rootPath(): Path? = ScreenshotCapture.detectProjectDir(project)
        .takeIf { it.isNotBlank() }?.let(Paths::get)

    /* ---------------- 运行时模板库路径（config.py 的 coco_feature_json） ---------------- */

    /** 探到的 `coco_feature_json` + 它是为哪个项目根探的（换项目要重探）。 */
    @Volatile
    private var probedCoco: Pair<String, String?>? = null

    /** 后台探测是否在跑 —— 避免每次访问都拉起一个 Python 进程。 */
    private val cocoProbeRunning = AtomicBoolean(false)

    /**
     * 运行时模板库的候选计划（同步）。
     *
     * 取值链：**项目约定文件 `templates.cocoAnnotations` > `config.py` 的
     * `template_matching.coco_feature_json` > 依次探测两个候选（改动前的行为）**。
     * 见 [CocoFeaturePath]。
     */
    fun cocoFeaturePlan(): CocoFeaturePath.Plan {
        val root = rootPath()?.toString().orEmpty()
        // 第一次问的时候顺手在后台补齐（见 [ensureCocoFeatureProbed]）——
        // 这是唯一能既保持同步、又拿到异步探针结果的形状。
        ensureCocoFeatureProbed()
        val declared = ProjectConventionConfig.getInstance(project).load().templates.cocoAnnotationsOrNull()
        val fromPy = probedCoco?.takeIf { it.first == root }?.second
        return CocoFeaturePath.plan(root, declared, fromPy)
    }

    /** 运行时模板库实际要扫描的文件（已按存在性过滤）。 */
    fun cocoFeatureFiles(): List<Path> =
        CocoFeaturePath.effectiveFiles(cocoFeaturePlan()) { it.toFile().isFile }

    /**
     * 运行时模板库的**所有**候选相对路径（含首选与探测候选），用于监听与变更归属判定。
     * 不按存在性过滤 —— 要覆盖"文件还没创建"的情况。
     */
    fun cocoFeatureRelPaths(): List<String> =
        CocoFeaturePath.relPaths(cocoFeaturePlan(), rootPath()?.toString().orEmpty())

    /**
     * 按需在后台探测 `config.py` 的 `template_matching.coco_feature_json`。
     *
     * **为什么是"懒探测 + 后台补齐"**：值来自一次 Python 子进程调用（百毫秒级），
     * 而消费点（快照构建、变更归属判定）都是**同步**的 —— 不可能在那里 await。
     * 所以第一次问的时候按"没声明"返回（退回两个惯例位置 = 改动前的行为），
     * 同时后台拉起一次探测；探到之后作废快照并广播，下一次访问就用真实路径。
     *
     * 最坏情况只是"第一次拿到的是兜底路径"，不会坏掉 —— 这正是它能做成纯增量的原因。
     * 同项目根探过就不再探（[force] 用于 `config.py` 变化与设置变化）。
     */
    fun ensureCocoFeatureProbed(force: Boolean = false) {
        val root = rootPath()?.toString().orEmpty()
        if (root.isBlank()) return
        if (!force && probedCoco?.first == root) return
        if (!cocoProbeRunning.compareAndSet(false, true)) return

        val pythonPath = ScreenshotCapture.detectPythonPath(root, project)
        // `probeWindowConfig` 是**实例方法**（`detectPythonPath` / `detectProjectDir` 才是
        // companion 成员），所以这里要建一个实例 —— 相比它内部拉起的 Python 进程，
        // 这点开销可以忽略。
        val capture = ScreenshotCapture(project)
        CompletableFuture.supplyAsync {
            runCatching { capture.probeWindowConfig(root, pythonPath) }.getOrNull()
        }.whenComplete { config, _ ->
            probedCoco = root to config?.cocoFeatureJson
            cocoProbeRunning.set(false)
            if (project.isDisposed) return@whenComplete
            // 路径可能变了 → 作废快照并广播，让面板下一次访问拿到新库
            invalidate()
            UIUtil.invokeLaterIfNeeded {
                project.messageBus.syncPublisher(OkDataChangeService.TOPIC).dataChanged()
            }
        }
    }

    @Synchronized
    fun refresh(force: Boolean = false) {
        val root = rootPath() ?: return
        val settings = settings()
        val allFiles = watchedFiles(root, settings)
        val stamp = allFiles.maxOfOrNull(::fileStamp) ?: 0L
        val settingsStamp = settings.stateModificationCount
        if (!force && stamp == snapshot.stamp && settingsStamp == snapshot.settingsStamp) return

        val modules = loadLangModules(root.resolve(settings.langDirectory()))
        val po = if (settings.enablePoData()) {
            loadPoDomains(root.resolve(settings.poDirectory()), settings.poDomains().toSet())
        } else {
            emptyMap()
        }
        val features = loadFeatures(root)
        val effects = loadEffects(resolve(root, settings.effectsFile()))
        snapshot = Snapshot(modules, po, features, effects, stamp, settingsStamp)
    }

    fun invalidate() {
        snapshot = snapshot.copy(stamp = -1L)
    }

    private fun current(): Snapshot {
        // 与 VSCode 版的 ACCESSOR_SCAN_INTERVAL_MS 对齐：补全/提示/hover 每次访问都会走到这里，
        // 300ms 节流避免每个按键触发一轮全量目录遍历。
        val now = System.currentTimeMillis()
        val last = lastRefreshAttempt.get()
        if (snapshot.stamp != -1L && now - last < SCAN_THROTTLE_MS) return snapshot
        if (lastRefreshAttempt.compareAndSet(last, now)) refresh()
        return snapshot
    }

    private fun settings(): OkScriptToolkitSettings = OkScriptToolkitSettings.getInstance(project)

    private fun resolve(root: Path, value: String): Path {
        val candidate = Paths.get(value)
        return if (candidate.isAbsolute) candidate else root.resolve(candidate)
    }

    private fun watchedFiles(root: Path, settings: OkScriptToolkitSettings): List<Path> {
        val files = mutableListOf<Path>()
        collectFiles(root.resolve(settings.langDirectory()), files) { it.toString().endsWith(".json", true) }
        collectFiles(root.resolve(settings.poDirectory()), files) { it.toString().endsWith(".po", true) }
        // 运行时模板库路径可配（项目约定 → config.py → 两个惯例位置），不能写死。
        for (coco in cocoFeatureFiles() + resolve(root, settings.effectsFile())) {
            if (coco.isRegularFile()) files.add(coco)
        }
        return files
    }

    private fun collectFiles(dir: Path, out: MutableList<Path>, accept: (Path) -> Boolean) {
        if (!dir.isDirectory()) return
        Files.walk(dir).use { stream -> stream.filter { it.isRegularFile() && accept(it) }.forEach(out::add) }
    }

    private fun fileStamp(file: Path): Long = fileStamps.compute(file) { _, old ->
        try {
            Files.getLastModifiedTime(file).toMillis() xor Files.size(file)
        } catch (_: Exception) {
            old ?: 0L
        }
    } ?: 0L

    private fun loadLangModules(dir: Path): Map<String, Map<String, Map<String, LangNode>>> {
        if (!dir.isDirectory()) return emptyMap()
        val result = linkedMapOf<String, Map<String, Map<String, LangNode>>>()
        Files.list(dir).use { files ->
            files.filter { it.isRegularFile() && it.toString().endsWith(".json", true) }
                .sorted()
                .forEach { file ->
                    runCatching {
                        result[file.name.substringBeforeLast('.')] = parseLangJson(JSON.readTree(file.toFile()))
                    }
                }
        }
        return result
    }

    private fun parseLangJson(root: JsonNode): Map<String, Map<String, LangNode>> {
        if (!root.isObject) return emptyMap()
        val result = linkedMapOf<String, Map<String, LangNode>>()
        root.forEachField { key, localesNode ->
            if (!localesNode.isObject) return@forEachField
            val locales = linkedMapOf<String, LangNode>()
            localesNode.forEachField { locale, node ->
                when {
                    node.path("string").isTextual -> locales[locale] = LangNode(node.path("string").asText(), "string")
                    node.path("pattern").isTextual -> locales[locale] = LangNode(node.path("pattern").asText(), "pattern")
                }
            }
            if (locales.isNotEmpty()) result[key] = locales
        }
        return result
    }

    private fun loadPoDomains(
        root: Path,
        acceptedDomains: Set<String>,
    ): Map<String, Map<String, Map<String, LangNode>>> {
        if (!root.isDirectory()) return emptyMap()
        val result = linkedMapOf<String, MutableMap<String, MutableMap<String, LangNode>>>()
        Files.list(root).use { locales ->
            locales.filter(Path::isDirectory).forEach { localeDir ->
                val messageDir = localeDir.resolve("LC_MESSAGES")
                if (!messageDir.isDirectory()) return@forEach
                Files.list(messageDir).use { files ->
                    files.filter { it.isRegularFile() && it.toString().endsWith(".po", true) }
                        .forEach { file ->
                            val domain = file.name.substringBeforeLast('.')
                            if (domain !in acceptedDomains) return@forEach
                            val domainMap = result.getOrPut(domain) { linkedMapOf() }
                            parsePo(file.readText(StandardCharsets.UTF_8)).forEach { (msgid, msgstr) ->
                                val value = msgstr.ifEmpty { msgid }
                                for (key in spacedKeys(msgid)) {
                                    domainMap.getOrPut(key) { linkedMapOf() }[localeDir.name] = LangNode(value, "string")
                                }
                            }
                        }
                }
            }
        }
        return result
    }

    private fun loadFeatures(root: Path): Map<String, FeatureTemplate> {
        val result = linkedMapOf<String, FeatureTemplate>()
        // 运行时模板库路径可配，不能写死（见 [cocoFeaturePlan]）。
        for (coco in cocoFeatureFiles()) {
            if (!coco.isRegularFile()) continue
            runCatching {
                val data = JSON.readTree(coco.toFile())
                val images = data.path("images").associate { it.path("id").asInt() to it.path("file_name").asText() }
                val categories = data.path("categories").associate { it.path("id").asInt() to it.path("name").asText() }
                data.path("annotations").forEach { ann ->
                    val name = categories[ann.path("category_id").asInt()] ?: return@forEach
                    val image = images[ann.path("image_id").asInt()] ?: return@forEach
                    val bboxNode = ann.path("bbox")
                    if (!bboxNode.isArray || bboxNode.size() < 4) return@forEach
                    val bbox = IntArray(4) { bboxNode[it].asDouble().toInt() }
                    if (bbox[2] <= 0 || bbox[3] <= 0) return@forEach
                    result[name] = FeatureTemplate(
                        name,
                        coco.parent.resolve(image).normalize(),
                        bbox,
                        bbox[2],
                        bbox[3],
                    )
                }
            }
        }
        return result
    }

    private fun loadEffects(file: Path): Map<String, EffectEntry> {
        if (!file.isRegularFile()) return emptyMap()
        return parseEffects(file.readText(StandardCharsets.UTF_8))
    }

    companion object {
        fun parsePo(text: String): List<Pair<String, String>> {
            val entries = mutableListOf<Pair<String, String>>()
            var msgid: String? = null
            var msgstr: String? = null
            var section = ""

            fun flush() {
                val id = msgid
                val value = msgstr
                if (!id.isNullOrEmpty() && value != null) entries += id to value
                msgid = null
                msgstr = null
                section = ""
            }

            text.lineSequence().forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith('#')) {
                    if (msgid != null && msgstr != null) flush()
                    return@forEach
                }
                when {
                    line.startsWith("msgid ") -> {
                        if (msgid != null && msgstr != null) flush()
                        msgid = unquotePo(line.removePrefix("msgid "))
                        msgstr = ""
                        section = "msgid"
                    }
                    line.startsWith("msgstr ") -> {
                        msgstr = unquotePo(line.removePrefix("msgstr "))
                        section = "msgstr"
                    }
                    line.startsWith('"') -> {
                        val value = unquotePo(line)
                        if (section == "msgstr") msgstr = msgstr.orEmpty() + value
                        else if (section == "msgid") msgid = msgid.orEmpty() + value
                    }
                }
            }
            if (msgid != null && msgstr != null) flush()
            return entries
        }

        fun parseEffects(text: String): Map<String, EffectEntry> {
            val members = linkedMapOf<String, Pair<String, String>>()
            val descriptions = linkedMapOf<String, String>()
            var category = ""
            text.lineSequence().forEach { raw ->
                val line = raw.trim()
                Regex("^#\\s*(.+?)\\s*$").matchEntire(line)?.let { match ->
                    val candidate = match.groupValues[1].trim()
                    if (candidate.isNotBlank() && !candidate.contains('。') && candidate != "效果类型" && !candidate.startsWith("效果ID系统")) {
                        category = candidate
                    }
                    return@forEach
                }
                Regex("^([A-Z][A-Z0-9_]*)\\s*=\\s*\"([A-Z0-9_]+)\"\\s*(?:#.*)?$")
                    .matchEntire(line)
                    ?.takeIf { category.isNotBlank() }
                    ?.let { members[it.groupValues[1]] = it.groupValues[2] to category }
                Regex("^EffectType\\.([A-Z][A-Z0-9_]*)\\s*:\\s*\"([^\"]*)\",?\\s*$")
                    .matchEntire(line)
                    ?.let { descriptions[it.groupValues[1]] = it.groupValues[2] }
            }
            return members.mapNotNull { (name, member) ->
                val description = descriptions[name] ?: return@mapNotNull null
                member.first to EffectEntry(member.first, description, member.second)
            }.toMap(linkedMapOf())
        }

        private fun spacedKeys(value: String): List<String> {
            val noSpaces = value.replace(Regex("\\s+"), "")
            return if (noSpaces.isNotEmpty() && noSpaces != value) listOf(value, noSpaces) else listOf(value)
        }

        private fun unquotePo(value: String): String {
            val text = value.trim()
            if (text.length < 2 || text.first() != '"' || text.last() != '"') return ""
            return text.substring(1, text.length - 1)
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\r", "\r")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        }
    }
}
