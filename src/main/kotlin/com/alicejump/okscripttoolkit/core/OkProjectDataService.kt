package com.alicejump.okscripttoolkit.core

import com.alicejump.okscripttoolkit.settings.OkScriptToolkitSettings
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
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

    // 与 VSCode 版 findOkTemplateCocoEntry 对齐：从 ok_templates 素材库 COCO
    // 按模板名反查原图与原始 bbox，带 30 秒 TTL 缓存
    private val okTemplateCocoCache = ConcurrentHashMap<String, OkTemplateCocoResult>()
    private val okTemplateCocoMissCache = ConcurrentHashMap<String, Long>()

    fun findOkTemplateCocoEntry(name: String): Pair<Path, IntArray>? {
        okTemplateCocoCache[name]?.let { cached ->
            if (System.currentTimeMillis() - cached.resolvedAt < 30_000L) {
                return cached.imagePath to cached.bbox
            }
        }
        okTemplateCocoMissCache[name]?.let { missAt ->
            if (System.currentTimeMillis() - missAt < 30_000L) return null
        }

        val root = rootPath() ?: return null
        val settings = settings()
        val cocoFiles = listOf(
            resolve(root, settings.okTemplatesDirectory()).resolve("coco_annotations.json"),
            root.resolve("ok_tasks").resolve(resolve(root, settings.okTemplatesDirectory()).fileName.toString())
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
                okTemplateCocoCache[name] = OkTemplateCocoResult(entry.first, entry.second, System.currentTimeMillis())
                okTemplateCocoMissCache.remove(name)
                return entry
            }
        }
        okTemplateCocoMissCache[name] = System.currentTimeMillis()
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

    fun rootPath(): Path? = project.basePath?.let(Paths::get)

    @Synchronized
    fun refresh(force: Boolean = false) {
        val root = rootPath() ?: return
        val settings = settings()
        val allFiles = watchedFiles(root, settings)
        val stamp = allFiles.maxOfOrNull(::fileStamp) ?: 0L
        val settingsStamp = settings.stateModificationCount
        if (!force && stamp == snapshot.stamp && settingsStamp == snapshot.settingsStamp) return

        val modules = loadLangModules(root.resolve(settings.langDirectory()))
        val po = if (settings.state.enablePoData) {
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
        for (coco in listOf(
            root.resolve("assets/coco_annotations.json"),
            root.resolve("ok_tasks/assets/coco_annotations.json"),
            resolve(root, settings.effectsFile()),
        )) {
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
        for (coco in listOf(root.resolve("assets/coco_annotations.json"), root.resolve("ok_tasks/assets/coco_annotations.json"))) {
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
