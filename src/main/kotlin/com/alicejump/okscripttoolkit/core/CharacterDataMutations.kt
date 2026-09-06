package com.alicejump.okscripttoolkit.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 角色技能数据的写回（对齐 VSCode 版 mutateCharacter）：
 * 原子写入（.bak 备份 -> tmp -> 回读校验 -> rename）、技能增删改、
 * 自定义技能标记 _ok_lang_hints_custom（同步技能锁定不可删）。
 */
object CharacterDataMutations {

    private val LOG = Logger.getInstance(CharacterDataMutations::class.java)
    private val JSON = ObjectMapper()

    class MutationException(message: String) : Exception(message)

    /** 原子写 JSON：.bak 备份 -> tmp 写入 -> 回读校验 -> 原子替换。 */
    fun atomicWriteJson(path: Path, root: JsonNode) {
        val target = path.toFile()
        val backup = File(target.parentFile, target.name + ".bak")
        if (target.exists()) {
            Files.copy(target.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        val tmp = File(target.parentFile, target.name + ".ok-script-toolkit.tmp")
        JSON.writerWithDefaultPrettyPrinter().writeValue(tmp, root)
        // 回读校验
        JSON.readTree(tmp)
        Files.move(
            tmp.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
        )
        LOG.info("Atomically wrote ${target.absolutePath}")
    }

    /** 校验技能 ID 唯一性（排除自身），冲突抛 MutationException。 */
    private fun ensureSkillIdFree(skills: ArrayNode, skillId: String, excludeSelf: String?) {
        for (skill in skills) {
            val id = skill.get("skill_id")?.asText()
            if (id == skillId && id != excludeSelf) {
                throw MutationException("Duplicate skill_id: $skillId")
            }
        }
    }

    private fun findSkill(skills: ArrayNode, skillId: String): ObjectNode? =
        skills.firstOrNull { it.get("skill_id")?.asText() == skillId } as? ObjectNode

    private fun skillFile(path: String): Triple<ObjectNode, ArrayNode, File> {
        val file = File(path)
        if (!file.exists()) throw MutationException("Skill file not found: $path")
        val root = JSON.readTree(file) as? ObjectNode
            ?: throw MutationException("Skill file root is not an object: $path")
        val skills = root.get("skills") as? ArrayNode
            ?: throw MutationException("Skill file has no skills array: $path")
        return Triple(root, skills, file)
    }

    /** 新增技能：表单字段写入 skills 数组并标记 _ok_lang_hints_custom。 */
    fun addSkill(
        path: String,
        skillId: String,
        form: Map<String, String>,
    ) {
        val (root, skills, file) = skillFile(path)
        ensureSkillIdFree(skills, skillId, excludeSelf = null)
        val node = skills.addObject()
        node.put("skill_id", skillId)
        for ((k, v) in form) {
            if (v.isNotBlank()) node.put(k, v)
        }
        node.put("_ok_lang_hints_custom", true)
        atomicWriteJson(file.toPath(), root)
    }

    /** 更新技能：仅允许修改自定义技能（_ok_lang_hints_custom=true）。 */
    fun updateSkill(
        path: String,
        skillId: String,
        form: Map<String, String>,
    ) {
        val (root, skills, file) = skillFile(path)
        val skill = findSkill(skills, skillId)
            ?: throw MutationException("Skill not found: $skillId")
        if (skill.get("_ok_lang_hints_custom")?.asBoolean(false) != true) {
            throw MutationException("Skill '$skillId' is synced and locked")
        }
        for ((k, v) in form) {
            if (v.isNotBlank()) skill.put(k, v) else skill.remove(k)
        }
        skill.put("_ok_lang_hints_custom", true)
        atomicWriteJson(file.toPath(), root)
    }

    // ── 强化组编辑（enhancement 增删改，兼容单数/复数字段）────────────

    /** form 键：name / trigger_text / enhancement_effect。 */
    private fun enhancementNode(form: Map<String, String>): ObjectNode {
        val node = JSON.createObjectNode()
        node.put("name", form["name"] ?: "")
        node.put("enhancement_effect", form["enhancement_effect"] ?: "")
        node.put("enhancement_visible_pulse", false)
        val trigger = JSON.createObjectNode()
        trigger.put("text", form["trigger_text"] ?: "")
        val effects = JSON.createArrayNode()
        (form["effects"] ?: "").lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { effects.add(it as JsonNode) }
        trigger.set<JsonNode>("effects", effects)
        node.set<JsonNode>("trigger_condition", trigger)
        return node
    }

    fun addEnhancement(path: String, skillId: String, form: Map<String, String>) {
        val (root, skills, file) = skillFile(path)
        val skill = findSkill(skills, skillId)
            ?: throw MutationException("Skill not found: $skillId")
        val enhancements = skill.get("enhancements") as? ArrayNode
        if (enhancements != null) {
            enhancements.add(enhancementNode(form))
        } else {
            skill.putNull("enhancement")
            skill.set<JsonNode>("enhancements", JSON.createArrayNode().add(enhancementNode(form)))
            skill.remove("enhancement")
        }
        skill.put("has_enhancement", true)
        atomicWriteJson(file.toPath(), root)
    }

    fun updateEnhancement(path: String, skillId: String, index: Int, form: Map<String, String>) {
        val (root, skills, file) = skillFile(path)
        val skill = findSkill(skills, skillId)
            ?: throw MutationException("Skill not found: $skillId")
        val enhancements = skill.get("enhancements") as? ArrayNode
        if (enhancements == null || index < 0 || index >= enhancements.size()) {
            throw MutationException("Invalid enhancement index: $index")
        }
        enhancements.set(index, enhancementNode(form))
        skill.put("has_enhancement", true)
        atomicWriteJson(file.toPath(), root)
    }

    fun deleteEnhancement(path: String, skillId: String, index: Int) {
        val (root, skills, file) = skillFile(path)
        val skill = findSkill(skills, skillId)
            ?: throw MutationException("Skill not found: $skillId")
        val enhancements = skill.get("enhancements") as? ArrayNode
        if (enhancements == null || index < 0 || index >= enhancements.size()) {
            throw MutationException("Invalid enhancement index: $index")
        }
        enhancements.remove(index)
        if (enhancements.isEmpty) skill.put("has_enhancement", false)
        atomicWriteJson(file.toPath(), root)
    }

    /** 删除技能：仅允许删除自定义技能。 */
    fun deleteSkill(path: String, skillId: String) {
        val (root, skills, file) = skillFile(path)
        val skill = findSkill(skills, skillId)
            ?: throw MutationException("Skill not found: $skillId")
        if (skill.get("_ok_lang_hints_custom")?.asBoolean(false) != true) {
            throw MutationException("Skill '$skillId' is synced and cannot be deleted")
        }
        val idx = skills.indexOf(skill)
        if (idx < 0) throw MutationException("Skill not found: $skillId")
        skills.remove(idx)
        atomicWriteJson(file.toPath(), root)
    }
}
