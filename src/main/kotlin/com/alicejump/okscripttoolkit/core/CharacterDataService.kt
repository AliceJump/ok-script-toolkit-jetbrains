package com.alicejump.okscripttoolkit.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant

private val JSON = ObjectMapper()

// ── Enums ────────────────────────────────────────────────────────

enum class IssueSeverity { ERROR, WARNING, INFO }
enum class SourceKind { CHARACTER, MASTER, LOCALE, EFFECTS }
enum class EffectUsageScope { SKILL, ENHANCEMENT_TRIGGER, ENHANCEMENT_EFFECT }

// ── Data classes ─────────────────────────────────────────────────

data class CharacterIssueSource(
    val kind: SourceKind,
    val characterId: String? = null,
    val skillId: String? = null,
    val effectId: String? = null,
    val fileName: String? = null,
)

data class CharacterIssue(
    val id: String,
    val severity: IssueSeverity,
    val code: String,
    val message: String,
    val source: CharacterIssueSource? = null,
)

data class CharacterEffectRef(
    val effectId: String,
    val displayName: String,
    val value: Any? = null,
    val duration: Any? = null,
    val target: String? = null,
    val count: Int? = null,
    val inferred: Boolean = false,
    val known: Boolean,
)

data class CharacterEnhancementView(
    val name: String,
    val triggerText: String,
    val triggerEffectMode: String,
    val triggerEffects: List<CharacterEffectRef>,
    val effects: List<CharacterEffectRef>,
    val enhancementEffect: String,
    val visiblePulse: Boolean,
)

data class CharacterSkillView(
    val skillId: String,
    val name: String,
    val skillType: String,
    val element: String,
    val description: String,
    val damageMultiplier: String,
    val staggerValue: Number,
    val cooldown: String,
    val spiritCost: Number,
    val hasEnhancement: Boolean,
    val effects: List<CharacterEffectRef>,
    val enhancements: List<CharacterEnhancementView>,
    val source: String,
)

data class CharacterMasterView(
    val key: String,
    val zh: String,
    val en: String,
    val stars: Int,
)

data class CharacterView(
    val characterId: String,
    val name: String,
    val star: Int,
    val element: String,
    val profession: String,
    val weaponType: String,
    val wikiItemId: String,
    val sourceFile: String? = null,
    val master: CharacterMasterView? = null,
    val locales: Map<String, String>,
    val skills: List<CharacterSkillView>,
    val issueCount: Int,
    val errorCount: Int,
)

data class CharacterEffectUsage(
    val characterId: String,
    val characterName: String,
    val skillId: String,
    val skillName: String,
    val skillType: String,
    val scope: EffectUsageScope,
    val enhancementName: String? = null,
)

data class CharacterEffectView(
    val id: String,
    val displayName: String,
    val description: String,
    val category: String,
    val defined: Boolean,
    val usages: List<CharacterEffectUsage>,
)

data class CharacterDataSummary(
    val masterCharacters: Int,
    val skillFiles: Int,
    val characters: Int,
    val skills: Int,
    val enhancements: Int,
    val effectReferences: Int,
    val definedEffects: Int,
    val unknownEffects: Int,
    val errors: Int,
    val warnings: Int,
    val infos: Int,
    val locales: List<String>,
)

data class CharacterManagerSnapshot(
    val projectDir: String,
    val loadedAt: String,
    val characters: List<CharacterView>,
    val effects: List<CharacterEffectView>,
    val effectCategories: List<String>,
    val issues: List<CharacterIssue>,
    val summary: CharacterDataSummary,
)

data class CharacterDataPaths(
    val projectDir: String,
    val masterFile: String,
    val skillsDir: String,
    val localeFile: String,
    val effectsFile: String,
    val effectNamesFile: String,
)

data class CharacterDataSources(
    val masterFile: String,
    val localeFile: String,
    val effectsFile: String,
    val effectNamesFile: String,
    val characterFiles: Map<String, String>,
    val characterFilesByName: Map<String, String>,
)

data class CharacterDataLoadResult(
    val snapshot: CharacterManagerSnapshot,
    val sources: CharacterDataSources,
)

// ── Internal types ───────────────────────────────────────────────

private data class PendingUsage(
    val effectId: String,
    val usage: CharacterEffectUsage,
    val source: CharacterIssueSource,
)

private data class ParsedSkillCharacter(
    val characterId: String,
    val name: String,
    val star: Int,
    val element: String,
    val profession: String,
    val weaponType: String,
    val wikiItemId: String,
    val sourceFile: String,
    val skills: List<CharacterSkillView>,
)

// ── Service ──────────────────────────────────────────────────────

@Service(Service.Level.PROJECT)
class CharacterDataService(private val project: Project) {

    companion object {
        private val LOCALE_ORDER = listOf(
            "zh_CN", "zh_TW", "en_US", "ja_JP", "ko_KR", "es_ES",
            "de_DE", "fr_FR", "it_IT", "pt_BR", "ru_RU", "id_ID", "th_TH", "vi_VN",
        )
        private val SEVERITY_ORDER = mapOf(IssueSeverity.ERROR to 0, IssueSeverity.WARNING to 1, IssueSeverity.INFO to 2)

        fun configuredPaths(projectDir: String, settings: CharacterDataSettings? = null): CharacterDataPaths {
            fun resolve(configured: String?, default: String): String {
                val value = configured?.takeIf { it.isNotBlank() } ?: default
                val path = Paths.get(value)
                return if (path.isAbsolute) path.toString() else Paths.get(projectDir, value).toString()
            }
            return CharacterDataPaths(
                projectDir = projectDir,
                masterFile = resolve(settings?.masterFile, "assets/data/characters.json"),
                skillsDir = resolve(settings?.skillsDirectory, "assets/data/character_skills"),
                localeFile = resolve(settings?.localeFile, "assets/lang/characters.json"),
                effectsFile = resolve(settings?.effectsFile, "src/data/effects.py"),
                effectNamesFile = resolve(settings?.effectNamesFile, "assets/lang/effect_names.json"),
            )
        }

        fun load(paths: CharacterDataPaths, projectLocale: String = "zh_CN"): CharacterDataLoadResult {
            val issues = mutableListOf<CharacterIssue>()
            val pendingUsages = mutableListOf<PendingUsage>()
            val allEffectIds = mutableSetOf<String>()
            var issueCounter = 0

            fun nextIssueId() = "issue-${++issueCounter}"

            // ── Master table ─────────────────────────────────────
            val masterEntries = readJsonMap(paths.masterFile)
            if (masterEntries == null) {
                issues.add(CharacterIssue(nextIssueId(), IssueSeverity.ERROR, "missing-file",
                    "Master file not found: ${paths.masterFile}",
                    CharacterIssueSource(SourceKind.MASTER)))
            }

            // ── Effects ──────────────────────────────────────────
            val effectDefinitions = readEffectsFile(paths.effectsFile)
            if (effectDefinitions == null) {
                issues.add(CharacterIssue(nextIssueId(), IssueSeverity.ERROR, "missing-effects-file",
                    "Effects file not found: ${paths.effectsFile}",
                    CharacterIssueSource(SourceKind.EFFECTS)))
            }
            // 效果术语映射（"中文": EffectType.XXX），供触发文本推断效果 ID
            val effectTermMap = runCatching {
                val text = java.io.File(paths.effectsFile).readText(StandardCharsets.UTF_8)
                parseEffectTermMap(text)
            }.getOrDefault(emptyMap())
            effectDefinitions?.keys?.let { allEffectIds.addAll(it) }

            val effectCategories = extractEffectCategories(paths.effectsFile)

            // ── Effect names ─────────────────────────────────────
            val effectNames = readJsonMap(paths.effectNamesFile)

            // ── Locale ───────────────────────────────────────────
            val localeData = readJsonMap(paths.localeFile)

            // ── Skills directory ─────────────────────────────────
            val skillsDir = File(paths.skillsDir)
            val skillFiles = if (skillsDir.isDirectory) {
                skillsDir.listFiles()?.filter { it.extension == "json" }?.sortedBy { it.name } ?: emptyList()
            } else {
                issues.add(CharacterIssue(nextIssueId(), IssueSeverity.ERROR, "missing-skills-directory",
                    "Skills directory not found: ${paths.skillsDir}",
                    CharacterIssueSource(SourceKind.CHARACTER)))
                emptyList()
            }

            // ── Parse skill characters ───────────────────────────
            val parsedCharacters = mutableListOf<ParsedSkillCharacter>()
            val characterFileMap = mutableMapOf<String, String>()
            val characterFileByNameMap = mutableMapOf<String, String>()
            val seenCharacterIds = mutableSetOf<String>()
            val seenSkillIds = mutableSetOf<String>()

            for (file in skillFiles) {
                characterFileByNameMap[file.name] = file.absolutePath
                val root = readJsonFile(file.absolutePath)
                if (root == null) {
                    issues.add(CharacterIssue(nextIssueId(), IssueSeverity.ERROR, "invalid-json",
                        "Failed to parse ${file.name}",
                        CharacterIssueSource(SourceKind.CHARACTER, fileName = file.name)))
                    continue
                }

                var characterId = root.get("character_id")?.asText()?.takeIf { it.isNotBlank() }
                if (characterId == null) {
                    characterId = file.nameWithoutExtension
                    issues.add(CharacterIssue(nextIssueId(), IssueSeverity.ERROR, "missing-character-id",
                        "Skill file ${file.name} missing character_id, using filename",
                        CharacterIssueSource(SourceKind.CHARACTER, fileName = file.name)))
                }

                if (characterId in seenCharacterIds) {
                    issues.add(CharacterIssue(nextIssueId(), IssueSeverity.ERROR, "duplicate-character-id",
                        "Duplicate character_id '$characterId' in ${file.name}",
                        CharacterIssueSource(SourceKind.CHARACTER, characterId = characterId, fileName = file.name)))
                    continue
                }
                seenCharacterIds.add(characterId)
                characterFileMap[characterId] = file.absolutePath

                val name = root.get("name")?.asText() ?: characterId
                val star = root.get("star")?.asInt() ?: 0
                val element = root.get("element")?.asText() ?: ""
                val profession = root.get("profession")?.asText() ?: ""
                val weaponType = root.get("weapon_type")?.asText() ?: ""
                val wikiItemId = root.get("wiki_item_id")?.let { if (it.isNull) "" else it.asText() } ?: ""

                val skillsArray = root.get("skills")
                if (skillsArray == null || !skillsArray.isArray) {
                    issues.add(CharacterIssue(nextIssueId(), IssueSeverity.WARNING, "missing-skills-array",
                        "Skill file ${file.name} has no skills array",
                        CharacterIssueSource(SourceKind.CHARACTER, fileName = file.name)))
                    parsedCharacters.add(ParsedSkillCharacter(
                        characterId, name, star, element, profession, weaponType, wikiItemId,
                        file.absolutePath, emptyList()))
                    continue
                }

                val skills = mutableListOf<CharacterSkillView>()
                for ((idx, skillNode) in skillsArray.withIndex()) {
                    if (!skillNode.isObject) {
                        issues.add(CharacterIssue(nextIssueId(), IssueSeverity.WARNING, "invalid-skill-entry",
                            "Invalid skill entry at index $idx in ${file.name}",
                            CharacterIssueSource(SourceKind.CHARACTER, fileName = file.name)))
                        continue
                    }

                    var skillId = skillNode.get("skill_id")?.asText()?.takeIf { it.isNotBlank() }
                    if (skillId == null) {
                        skillId = "${characterId}_skill_${idx + 1}"
                        issues.add(CharacterIssue(nextIssueId(), IssueSeverity.ERROR, "missing-skill-id",
                            "Skill at index $idx in ${file.name} missing skill_id",
                            CharacterIssueSource(SourceKind.CHARACTER, fileName = file.name)))
                    }

                    if (skillId in seenSkillIds) {
                        issues.add(CharacterIssue(nextIssueId(), IssueSeverity.ERROR, "duplicate-skill-id",
                            "Duplicate skill_id '$skillId' in ${file.name}",
                            CharacterIssueSource(SourceKind.CHARACTER, skillId = skillId, fileName = file.name)))
                    }
                    seenSkillIds.add(skillId)

                    val skillName = skillNode.get("name")?.asText() ?: skillId
                    val skillType = skillNode.get("skill_type")?.asText() ?: "uncategorized"
                    val skillElement = skillNode.get("element")?.asText() ?: element
                    val description = skillNode.get("description")?.asText() ?: ""
                    val damageMultiplier = skillNode.get("damage_multiplier")?.let {
                        if (it.isNull) "" else it.asText()
                    } ?: ""
                    val staggerValue = skillNode.get("stagger_value")?.asInt() ?: 0
                    val cooldown = skillNode.get("cooldown")?.let {
                        if (it.isNull) "" else it.asText()
                    } ?: ""
                    val spiritCost = skillNode.get("spirit_cost")?.asInt() ?: 0
                    val hasEnhancement = skillNode.get("has_enhancement")?.asBoolean() ?: false
                    val isCustom = skillNode.get("_ok_lang_hints_custom")?.asBoolean() ?: false

                    // Parse effects from multiple arrays
                    val effectRefs = mutableListOf<CharacterEffectRef>()
                    for (arrayName in listOf("effects", "attach_effects", "status_effects", "clear_effects")) {
                        skillNode.get(arrayName)?.forEach { effectNode ->
                            parseEffectRef(effectNode)?.let { ref ->
                                effectRefs.add(ref)
                                pendingUsages.add(PendingUsage(
                                    ref.effectId,
                                    CharacterEffectUsage(characterId, name, skillId, skillName, skillType,
                                        EffectUsageScope.SKILL),
                                    CharacterIssueSource(SourceKind.CHARACTER, characterId, skillId, ref.effectId),
                                ))
                            }
                        }
                    }

                    // Parse enhancements
                    val enhancements = mutableListOf<CharacterEnhancementView>()
                    val enhancementsNode = skillNode.get("enhancements")
                    val legacyEnhancement = skillNode.get("enhancement")

                    if (enhancementsNode != null && enhancementsNode.isArray) {
                        for (enhNode in enhancementsNode) {
                            parseEnhancement(enhNode, characterId, name, skillId, skillName, skillType, pendingUsages, effectTermMap, effectDefinitions?.keys)
                                ?.let { enhancements.add(it) }
                        }
                    } else if (legacyEnhancement != null && legacyEnhancement.isObject) {
                        parseEnhancement(legacyEnhancement, characterId, name, skillId, skillName, skillType, pendingUsages, effectTermMap, effectDefinitions?.keys)
                            ?.let { enhancements.add(it) }
                    }

                    if (hasEnhancement && enhancements.isEmpty()) {
                        issues.add(CharacterIssue(nextIssueId(), IssueSeverity.WARNING, "missing-enhancement-data",
                            "Skill '$skillName' has has_enhancement=true but no enhancement data"))
                    }
                    if (!hasEnhancement && enhancements.isNotEmpty()) {
                        issues.add(CharacterIssue(nextIssueId(), IssueSeverity.WARNING, "unexpected-enhancement-data",
                            "Skill '$skillName' has enhancement data but has_enhancement=false"))
                    }

                    skills.add(CharacterSkillView(
                        skillId, skillName, skillType, skillElement, description,
                        damageMultiplier, staggerValue, cooldown, spiritCost,
                        hasEnhancement, effectRefs, enhancements,
                        if (isCustom) "custom" else "synced",
                    ))
                }

                parsedCharacters.add(ParsedSkillCharacter(
                    characterId, name, star, element, profession, weaponType, wikiItemId,
                    file.absolutePath, skills))
            }

            // ── Cross-reference with master ──────────────────────
            val masterCharacterIds = masterEntries?.keys ?: emptySet()
            for (masterId in masterCharacterIds) {
                if (masterId !in seenCharacterIds) {
                    val entry = masterEntries?.get(masterId)
                    val zhName = entry?.get("zh")?.asText() ?: ""
                    issues.add(CharacterIssue(nextIssueId(), IssueSeverity.WARNING, "missing-skill-file",
                        "Master entry '$masterId' ($zhName) has no corresponding skill file",
                        CharacterIssueSource(SourceKind.MASTER, characterId = masterId)))
                }
            }

            // ── Resolve locales and build CharacterView ──────────
            val characters = mutableListOf<CharacterView>()
            for (parsed in parsedCharacters) {
                val masterEntry = masterEntries?.get(parsed.characterId)
                if (masterEntry != null) {
                    val masterZh = masterEntry.get("zh")?.asText() ?: ""
                    if (masterZh.isNotBlank() && masterZh != parsed.name) {
                        issues.add(CharacterIssue(nextIssueId(), IssueSeverity.WARNING, "character-name-mismatch",
                            "Character '${parsed.characterId}': master name '$masterZh' != skill name '${parsed.name}'",
                            CharacterIssueSource(SourceKind.CHARACTER, characterId = parsed.characterId)))
                    }
                    val masterStars = masterEntry.get("stars")?.asInt() ?: 0
                    if (masterStars > 0 && masterStars != parsed.star) {
                        issues.add(CharacterIssue(nextIssueId(), IssueSeverity.WARNING, "character-star-mismatch",
                            "Character '${parsed.characterId}': master stars $masterStars != skill star ${parsed.star}",
                            CharacterIssueSource(SourceKind.CHARACTER, characterId = parsed.characterId)))
                    }
                } else {
                    issues.add(CharacterIssue(nextIssueId(), IssueSeverity.WARNING, "missing-master-entry",
                        "Skill file for '${parsed.characterId}' has no master table entry",
                        CharacterIssueSource(SourceKind.CHARACTER, characterId = parsed.characterId)))
                }

                val locales = mutableMapOf<String, String>()
                val localeEntry = localeData?.get(parsed.characterId)
                if (localeEntry != null && localeEntry.isObject) {
                    localeEntry.forEachField { localeCode, node ->
                        val value = node.get("string")?.asText()?.takeIf { it.isNotBlank() }
                            ?: node.get("pattern")?.asText()?.takeIf { it.isNotBlank() }
                        if (value != null) locales[localeCode] = value
                    }
                }
                if (locales.isEmpty()) {
                    issues.add(CharacterIssue(nextIssueId(), IssueSeverity.WARNING, "missing-character-locales",
                        "Character '${parsed.characterId}' has no locale entries",
                        CharacterIssueSource(SourceKind.LOCALE, characterId = parsed.characterId)))
                }

                val master = masterEntry?.let {
                    CharacterMasterView(
                        parsed.characterId,
                        it.get("zh")?.asText() ?: "",
                        it.get("en")?.asText() ?: "",
                        it.get("stars")?.asInt() ?: 0,
                    )
                }

                // 真实的问题数在全部 issue 收集完之后回填（见下方 issueIndex）
                characters.add(CharacterView(
                    parsed.characterId, parsed.name, parsed.star, parsed.element,
                    parsed.profession, parsed.weaponType, parsed.wikiItemId,
                    parsed.sourceFile, master, locales, parsed.skills,
                    0, 0,
                ))
            }

            // ── Orphan locale entries ────────────────────────────
            localeData?.forEach { (charId, _) ->
                if (charId !in seenCharacterIds && charId !in masterCharacterIds) {
                    issues.add(CharacterIssue(nextIssueId(), IssueSeverity.INFO, "orphan-locale-entry",
                        "Locale entry for '$charId' has no corresponding character",
                        CharacterIssueSource(SourceKind.LOCALE, characterId = charId)))
                }
            }

            // ── Resolve effect usages and build effect views ─────
            val effectUsageMap = mutableMapOf<String, MutableList<CharacterEffectUsage>>()
            for (pending in pendingUsages) {
                effectUsageMap.getOrPut(pending.effectId) { mutableListOf() }.add(pending.usage)

                if (pending.effectId !in allEffectIds) {
                    issues.add(CharacterIssue(nextIssueId(), IssueSeverity.ERROR, "unknown-effect-id",
                        "Unknown effect ID '${pending.effectId}' referenced in skill '${pending.usage.skillName}'",
                        pending.source))
                }
            }

            val effectViews = allEffectIds.map { effectId ->
                val entry = effectDefinitions?.get(effectId)
                val displayName = resolveEffectDisplayName(effectId, effectNames, projectLocale)
                CharacterEffectView(
                    id = effectId,
                    displayName = displayName,
                    description = entry?.description ?: "",
                    category = entry?.category ?: "",
                    defined = entry != null,
                    usages = effectUsageMap[effectId] ?: emptyList(),
                )
            }.sortedBy { it.id }

            // ── 回填每个角色的问题数（对齐 VSCode：issueCount / errorCount）──
            // 必须在所有 issue 收集完之后——未知效果 ID 的问题是在这一刻才补全的。
            val issueIndex = issues.groupBy { it.source?.characterId }
            val finalCharacters = characters.map { char ->
                val related = issueIndex[char.characterId].orEmpty()
                char.copy(
                    issueCount = related.size,
                    errorCount = related.count { it.severity == IssueSeverity.ERROR },
                )
            }

            // ── Summary ──────────────────────────────────────────
            val allLocales = finalCharacters.flatMap { it.locales.keys }.distinct().sortedWith(
                compareBy<String> { LOCALE_ORDER.indexOf(it).let { idx -> if (idx < 0) Int.MAX_VALUE else idx } }
                    .thenBy { it }
            )

            val summary = CharacterDataSummary(
                masterCharacters = masterEntries?.size ?: 0,
                skillFiles = skillFiles.size,
                characters = finalCharacters.size,
                skills = finalCharacters.sumOf { it.skills.size },
                enhancements = finalCharacters.sumOf { c -> c.skills.sumOf { s -> s.enhancements.size } },
                effectReferences = pendingUsages.size,
                definedEffects = effectDefinitions?.size ?: 0,
                unknownEffects = pendingUsages.count { it.effectId !in allEffectIds },
                errors = issues.count { it.severity == IssueSeverity.ERROR },
                warnings = issues.count { it.severity == IssueSeverity.WARNING },
                infos = issues.count { it.severity == IssueSeverity.INFO },
                locales = allLocales,
            )

            val sortedIssues = issues.sortedWith(
                compareBy<CharacterIssue> { SEVERITY_ORDER[it.severity] ?: 3 }
                    .thenBy { it.message }
            )

            val snapshot = CharacterManagerSnapshot(
                projectDir = paths.projectDir,
                loadedAt = Instant.now().toString(),
                characters = finalCharacters.sortedBy { it.name },
                effects = effectViews,
                effectCategories = effectCategories,
                issues = sortedIssues,
                summary = summary,
            )

            val sources = CharacterDataSources(
                masterFile = paths.masterFile,
                localeFile = paths.localeFile,
                effectsFile = paths.effectsFile,
                effectNamesFile = paths.effectNamesFile,
                characterFiles = characterFileMap,
                characterFilesByName = characterFileByNameMap,
            )

            return CharacterDataLoadResult(snapshot, sources)
        }

        // ── effects.py parsing ──────────────────────────────────

        fun parseEffects(text: String): Map<String, EffectEntry> {
            val members = linkedMapOf<String, Pair<String, String>>()
            val descriptions = linkedMapOf<String, String>()
            var category = ""
            text.lineSequence().forEach { raw ->
                val line = raw.trim()
                Regex("^#\\s*(.+?)\\s*$").matchEntire(line)?.let { match ->
                    val candidate = match.groupValues[1].trim()
                    if (isCategoryLine(candidate)) {
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

        private fun isCategoryLine(candidate: String): Boolean {
            if (candidate.isBlank()) return false
            if (candidate.contains("。")) return false
            if (candidate == "效果类型") return false
            if (candidate.startsWith("效果ID系统")) return false
            return true
        }

        private fun extractEffectCategories(effectsFile: String): List<String> {
            val file = File(effectsFile)
            if (!file.exists()) return emptyList()
            return try {
                val text = file.readText(StandardCharsets.UTF_8)
                val classStart = text.indexOf("class EffectType")
                val descStart = text.indexOf("# 效果描述映射")
                if (classStart < 0) return emptyList()
                val range = if (descStart > classStart) text.substring(classStart, descStart) else text.substring(classStart)
                Regex("^\\s{4}#\\s*(.+?)\\s*$", RegexOption.MULTILINE)
                    .findAll(range)
                    .map { it.groupValues[1].trim() }
                    .filter { isCategoryLine(it) }
                    .toList()
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun parseEffectTermMap(text: String): Map<String, String> {
            val result = linkedMapOf<String, String>()
            Regex("\"([^\"]+)\"\\s*:\\s*EffectType\\.([A-Z][A-Z0-9_]+)\\s*,?")
                .findAll(text).forEach { match ->
                    result[match.groupValues[1]] = match.groupValues[2]
                }
            return result
        }

        fun inferEffectIds(text: String, termMap: Map<String, String>): List<String> {
            if (termMap.isEmpty() || text.isBlank()) return emptyList()
            val sortedTerms = termMap.keys.sortedByDescending { it.length }
            val result = mutableListOf<String>()
            var remaining = text
            for (term in sortedTerms) {
                val idx = remaining.indexOf(term)
                if (idx >= 0) {
                    termMap[term]?.let { result.add(it) }
                    remaining = remaining.removeRange(idx, idx + term.length)
                }
            }
            return result
        }

        // ── JSON helpers ────────────────────────────────────────

        private fun readJsonFile(path: String): JsonNode? {
            return try {
                val file = File(path)
                if (!file.exists()) return null
                JSON.readTree(file)
            } catch (_: Exception) {
                null
            }
        }

        private fun readJsonMap(path: String): Map<String, JsonNode>? {
            val root = readJsonFile(path) ?: return null
            if (!root.isObject) return null
            val result = linkedMapOf<String, JsonNode>()
            root.forEachField { key, value -> result[key] = value }
            return result
        }

        private fun readEffectsFile(path: String): Map<String, EffectEntry>? {
            val file = File(path)
            if (!file.exists()) return null
            return try {
                parseEffects(file.readText(StandardCharsets.UTF_8))
            } catch (_: Exception) {
                null
            }
        }

        private fun parseEffectRef(node: JsonNode): CharacterEffectRef? {
            if (node.isTextual) {
                val effectId = node.asText()
                if (effectId.isBlank()) return null
                return CharacterEffectRef(effectId, effectId, known = false)
            }
            if (!node.isObject) return null
            val effectId = node.get("effect_id")?.asText()?.takeIf { it.isNotBlank() } ?: return null
            return CharacterEffectRef(
                effectId = effectId,
                displayName = effectId,
                value = node.get("value")?.let { if (it.isNull) null else JSON.convertValue(it, Any::class.java) },
                duration = node.get("duration")?.let { if (it.isNull) null else JSON.convertValue(it, Any::class.java) },
                target = node.get("target")?.asText(),
                count = node.get("count")?.asInt(),
                known = false,
            )
        }

        private fun parseEnhancement(
            node: JsonNode,
            characterId: String,
            characterName: String,
            skillId: String,
            skillName: String,
            skillType: String,
            pendingUsages: MutableList<PendingUsage>,
            effectTermMap: Map<String, String>,
            definedEffectIds: Set<String>?,
        ): CharacterEnhancementView? {
            val name = node.get("name")?.asText() ?: return null
            val enhancementEffect = node.get("enhancement_effect")?.asText() ?: ""
            val visiblePulse = node.get("enhancement_visible_pulse")?.asBoolean() ?: false

            val triggerCondition = node.get("trigger_condition")
            var triggerText = ""
            var triggerEffectMode = "all"
            val triggerEffects = mutableListOf<CharacterEffectRef>()

            if (triggerCondition != null) {
                if (triggerCondition.isTextual) {
                    triggerText = triggerCondition.asText()
                } else if (triggerCondition.isObject) {
                    triggerText = triggerCondition.get("text")?.asText() ?: ""
                    val effectsNode = triggerCondition.get("effects")
                    if (effectsNode != null) {
                        if (effectsNode.isArray) {
                            effectsNode.forEach { effNode ->
                                parseEffectRef(effNode)?.let { ref ->
                                    triggerEffects.add(ref)
                                    pendingUsages.add(PendingUsage(
                                        ref.effectId,
                                        CharacterEffectUsage(characterId, characterName, skillId, skillName, skillType,
                                            EffectUsageScope.ENHANCEMENT_TRIGGER, name),
                                        CharacterIssueSource(SourceKind.CHARACTER, characterId, skillId, ref.effectId),
                                    ))
                                }
                            }
                        } else if (effectsNode.isObject) {
                            val allNode = effectsNode.get("all")
                            val anyNode = effectsNode.get("any")
                            if (anyNode != null && anyNode.isArray) {
                                triggerEffectMode = "any"
                                anyNode.forEach { effNode ->
                                    parseEffectRef(effNode)?.let { ref ->
                                        triggerEffects.add(ref)
                                        pendingUsages.add(PendingUsage(
                                            ref.effectId,
                                            CharacterEffectUsage(characterId, characterName, skillId, skillName, skillType,
                                                EffectUsageScope.ENHANCEMENT_TRIGGER, name),
                                            CharacterIssueSource(SourceKind.CHARACTER, characterId, skillId, ref.effectId),
                                        ))
                                    }
                                }
                            }
                            if (allNode != null && allNode.isArray) {
                                allNode.forEach { effNode ->
                                    parseEffectRef(effNode)?.let { ref ->
                                        triggerEffects.add(ref)
                                        pendingUsages.add(PendingUsage(
                                            ref.effectId,
                                            CharacterEffectUsage(characterId, characterName, skillId, skillName, skillType,
                                                EffectUsageScope.ENHANCEMENT_TRIGGER, name),
                                            CharacterIssueSource(SourceKind.CHARACTER, characterId, skillId, ref.effectId),
                                        ))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 对齐 VSCode 版：触发文本非空且无显式效果时，按术语映射推断效果 ID（标 inferred）
            var inferredIds: List<String> = emptyList()
            if (triggerEffects.isEmpty() && triggerText.isNotBlank() && effectTermMap.isNotEmpty()) {
                inferredIds = inferEffectIds(triggerText, effectTermMap)
                for (inferId in inferredIds) {
                    triggerEffects.add(
                        CharacterEffectRef(
                            effectId = inferId,
                            displayName = inferId,
                            known = definedEffectIds?.contains(inferId) == true,
                            inferred = true,
                        ),
                    )
                    pendingUsages.add(PendingUsage(
                        inferId,
                        CharacterEffectUsage(characterId, characterName, skillId, skillName, skillType,
                            EffectUsageScope.ENHANCEMENT_TRIGGER, name),
                        CharacterIssueSource(SourceKind.CHARACTER, characterId, skillId, inferId),
                    ))
                }
            }

            val effects = mutableListOf<CharacterEffectRef>()
            node.get("effects")?.forEach { effNode ->
                parseEffectRef(effNode)?.let { ref ->
                    effects.add(ref)
                    pendingUsages.add(PendingUsage(
                        ref.effectId,
                        CharacterEffectUsage(characterId, characterName, skillId, skillName, skillType,
                            EffectUsageScope.ENHANCEMENT_EFFECT, name),
                        CharacterIssueSource(SourceKind.CHARACTER, characterId, skillId, ref.effectId),
                    ))
                }
            }

            return CharacterEnhancementView(
                name, triggerText, triggerEffectMode, triggerEffects, effects,
                enhancementEffect, visiblePulse,
            )
        }

        private fun resolveEffectDisplayName(effectId: String, effectNames: Map<String, JsonNode>?, projectLocale: String): String {
            val entry = effectNames?.get(effectId) ?: return effectId
            if (!entry.isObject) return effectId
            val localeCandidates = listOf(projectLocale, "zh_CN", "en_US").distinct()
            for (locale in localeCandidates) {
                val node = entry.get(locale) ?: continue
                val value = node.get("string")?.asText()?.takeIf { it.isNotBlank() }
                    ?: node.get("pattern")?.asText()?.takeIf { it.isNotBlank() }
                if (value != null) return value
            }
            return effectId
        }
    }
}

// ── Settings helper ──────────────────────────────────────────────

data class CharacterDataSettings(
    val masterFile: String? = null,
    val skillsDirectory: String? = null,
    val localeFile: String? = null,
    val effectsFile: String? = null,
    val effectNamesFile: String? = null,
)
