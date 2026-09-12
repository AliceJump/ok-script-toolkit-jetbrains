package com.alicejump.okscripttoolkit.core

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 效果字典 `src/data/effects.py` 的改写（对齐 VSCode 版 mutateEffect）。
 *
 * effects.py 里同一个效果要同时落在两处：
 * 1. `class EffectType` 的枚举成员（按 `    # 分类` 注释分组）
 * 2. `EFFECT_DESCRIPTIONS` 里的 `    EffectType.XXX: "描述",`（同样按分类分组）
 *
 * 新增分类则要同时往这两处插入分类注释。四个结构锚点缺失时一律拒绝写入，
 * 避免把用户的 effects.py 改坏。
 */
object EffectDataMutations {

    class MutationException(message: String) : Exception(message)

    private val JSON = ObjectMapper()
    private val CATEGORY_LINE = Regex("^\\s{4}#\\s+")

    private class Document(val lines: MutableList<String>, val eol: String) {
        val text: String get() = lines.joinToString(eol)
    }

    private fun read(effectsFile: String): Document {
        val file = File(effectsFile)
        if (!file.exists()) {
            throw MutationException(OkScriptToolkitBundle.message("characterManager.effect.fileMissing", effectsFile))
        }
        val raw = file.readText(StandardCharsets.UTF_8)
        val eol = if (raw.contains("\r\n")) "\r\n" else "\n"
        return Document(raw.split(Regex("\\r?\\n")).toMutableList(), eol)
    }

    /** effects.py 的四个结构锚点。 */
    private class Anchors(lines: List<String>) {
        val classStart = lines.indexOfFirst { it.trim().startsWith("class ") && it.trim().contains("EffectType") }
        val descriptionsMarker = firstAfter(lines, classStart) { it.trim() == "# 效果描述映射" }
        val descriptionsStart = firstAfter(lines, descriptionsMarker) { it.trim().startsWith("EFFECT_DESCRIPTIONS") }
        val termsStart = firstAfter(lines, descriptionsStart) { it.trim().startsWith("# 效果术语映射") }

        init {
            if (classStart < 0 || descriptionsMarker < 0 || descriptionsStart < 0 || termsStart < 0) {
                throw MutationException(OkScriptToolkitBundle.message("characterManager.effect.brokenStructure"))
            }
        }

        private fun firstAfter(lines: List<String>, from: Int, predicate: (String) -> Boolean): Int {
            if (from < 0) return -1
            for (index in from + 1 until lines.size) {
                if (predicate(lines[index])) return index
            }
            return -1
        }
    }

    /** 在 (start, end) 区间里找 `    # category` 注释行。 */
    private fun findCategory(lines: List<String>, category: String, start: Int, end: Int): Int {
        if (start < 0) return -1
        val marker = "# $category"
        for (index in start + 1 until minOf(end, lines.size)) {
            if (lines[index].trim() == marker) return index
        }
        return -1
    }

    /** 插到该分类段落的末尾：下一个分类注释之前，且不留尾部空行。 */
    private fun insertAtCategoryEnd(lines: MutableList<String>, categoryLine: Int, end: Int, newLine: String) {
        var boundary = minOf(end, lines.size)
        for (index in categoryLine + 1 until boundary) {
            if (CATEGORY_LINE.containsMatchIn(lines[index])) {
                boundary = index
                break
            }
        }
        var insertAt = boundary
        while (insertAt > categoryLine + 1 && lines[insertAt - 1].trim().isEmpty()) insertAt--
        lines.add(insertAt, newLine)
    }

    /** 在 (from, to) 区间内从后往前找收尾花括号。 */
    private fun findClosingBrace(lines: List<String>, from: Int, to: Int): Int {
        if (from < 0) return -1
        for (index in to - 1 downTo from + 1) {
            if (lines[index].trim() == "}") return index
        }
        return -1
    }

    fun addCategory(effectsFile: String, category: String) {
        val name = category.trim()
        if (name.isBlank()) {
            throw MutationException(OkScriptToolkitBundle.message("characterManager.effect.categoryRequired"))
        }
        if (name.contains('\n') || name.contains('\r') || name.contains('#')) {
            throw MutationException(OkScriptToolkitBundle.message("characterManager.effect.categoryInvalid"))
        }
        val doc = read(effectsFile)
        val lines = doc.lines
        val anchors = Anchors(lines)
        if (findCategory(lines, name, anchors.classStart, anchors.descriptionsMarker) >= 0) {
            throw MutationException(OkScriptToolkitBundle.message("characterManager.effect.categoryExists", name))
        }

        // 1) 枚举区：贴着「# 效果描述映射」之前插入分类注释
        var enumInsert = anchors.descriptionsMarker
        while (enumInsert > anchors.classStart + 1 && lines[enumInsert - 1].trim().isEmpty()) enumInsert--
        lines.add(enumInsert, "")
        lines.add(enumInsert + 1, "    # $name")

        // 2) 描述映射区：在 EFFECT_DESCRIPTIONS 的收尾 `}` 前插入分类注释。
        //    枚举区已插入 2 行，行号整体后移，必须重新定位锚点。
        val after = Anchors(lines)
        val mapEnd = findClosingBrace(lines, after.descriptionsStart, after.termsStart)
        if (mapEnd < 0) {
            throw MutationException(OkScriptToolkitBundle.message("characterManager.effect.noDescriptionsEnd"))
        }
        var mapInsert = mapEnd
        while (mapInsert > after.descriptionsStart + 1 && lines[mapInsert - 1].trim().isEmpty()) mapInsert--
        lines.add(mapInsert, "")
        lines.add(mapInsert + 1, "    # $name")

        atomicWriteText(effectsFile, doc.text)
    }

    fun addEffect(effectsFile: String, effectId: String, description: String, category: String) {
        val id = effectId.trim().uppercase()
        if (description.isBlank()) {
            throw MutationException(OkScriptToolkitBundle.message("characterManager.effect.descriptionRequired"))
        }
        if (!Regex("^[A-Z][A-Z0-9_]*$").matches(id)) {
            throw MutationException(OkScriptToolkitBundle.message("characterManager.effect.idInvalid"))
        }
        val doc = read(effectsFile)
        val lines = doc.lines
        val anchors = Anchors(lines)
        if (Regex("^\\s*$id\\s*=", RegexOption.MULTILINE).containsMatchIn(doc.text)) {
            throw MutationException(OkScriptToolkitBundle.message("characterManager.effect.idExists", id))
        }

        // 1) 枚举成员
        val enumCategory = findCategory(lines, category, anchors.classStart, anchors.descriptionsMarker)
        if (enumCategory < 0) {
            throw MutationException(OkScriptToolkitBundle.message("characterManager.effect.categoryMissing", category))
        }
        insertAtCategoryEnd(lines, enumCategory, anchors.descriptionsMarker, "    $id = \"$id\"")

        // 2) 描述映射（上面插入后行号已变，重新定位）
        val currentDescriptionsStart = lines.indexOfFirst { it.trim().startsWith("EFFECT_DESCRIPTIONS") }
        val currentTermsStart = Anchors(lines).termsStart
        val currentMapEnd = findClosingBrace(lines, currentDescriptionsStart, currentTermsStart)
        if (currentMapEnd < 0) {
            throw MutationException(OkScriptToolkitBundle.message("characterManager.effect.noDescriptionsEnd"))
        }
        val mapCategory = findCategory(lines, category, currentDescriptionsStart, currentMapEnd)
        if (mapCategory < 0) {
            throw MutationException(OkScriptToolkitBundle.message("characterManager.effect.noDescriptionGroup", category))
        }
        val escaped = JSON.writeValueAsString(description)
        insertAtCategoryEnd(lines, mapCategory, currentMapEnd, "    EffectType.$id: $escaped,")

        atomicWriteText(effectsFile, doc.text)
    }

    /** 原子写文本：.bak 备份 -> tmp -> rename。 */
    fun atomicWriteText(path: String, content: String) {
        val target = File(path)
        val backup = File(target.parentFile, target.name + ".bak")
        if (target.exists()) {
            Files.copy(target.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        val tmp = File(target.parentFile, target.name + ".ok-script-toolkit.tmp")
        tmp.writeText(content, StandardCharsets.UTF_8)
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }
}
