package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.core.CharacterEffectView
import com.alicejump.okscripttoolkit.core.CharacterIssue
import com.alicejump.okscripttoolkit.core.CharacterView
import com.alicejump.okscripttoolkit.core.IssueSeverity

/** 空值占位：与 VSCode 版的 unknownStar / unknownElement / unknownProfession 对齐。 */
const val UNKNOWN_FILTER_VALUE = "—"

data class CharacterFilterCriteria(
    val query: String = "",
    val star: String = "",
    val element: String = "",
    val profession: String = "",
    val skillType: String = "",
    val enhancementOnly: Boolean = false,
    val issueOnly: Boolean = false,
)

enum class EffectUsageFilter { ALL, USED, UNUSED, UNKNOWN }

data class EffectFilterCriteria(
    val query: String = "",
    val category: String = "",
    val usage: EffectUsageFilter = EffectUsageFilter.ALL,
)

/** 角色 / 效果列表的过滤规则。纯逻辑，不碰 Swing，方便单测。 */
object CharacterFilters {

    /** 把角色的可检索字段拼成一个小写字符串（对齐 VSCode 的 characterHaystack）。 */
    fun haystack(char: CharacterView): String {
        val skillText = char.skills.joinToString(" ") { skill ->
            listOf(
                skill.skillId, skill.name, skill.skillType, skill.element, skill.description,
                *skill.effects.flatMap { listOf(it.effectId, it.displayName) }.toTypedArray(),
                *skill.enhancements.flatMap { enh ->
                    listOf(enh.name, enh.triggerText, enh.enhancementEffect) +
                        enh.triggerEffects.flatMap { listOf(it.effectId, it.displayName) } +
                        enh.effects.flatMap { listOf(it.effectId, it.displayName) }
                }.toTypedArray(),
            ).joinToString(" ")
        }
        return listOf(
            char.characterId, char.name, char.master?.en.orEmpty(),
            char.locales.values.joinToString(" "),
            char.element, char.profession, char.weaponType, skillText,
        ).joinToString(" ").lowercase()
    }

    fun matches(char: CharacterView, criteria: CharacterFilterCriteria): Boolean {
        val query = criteria.query.trim().lowercase()
        if (query.isNotEmpty() && query !in haystack(char)) return false
        if (criteria.star.isNotEmpty() && starValue(char) != criteria.star) return false
        if (criteria.element.isNotEmpty() && elementValue(char) != criteria.element) return false
        if (criteria.profession.isNotEmpty() && professionValue(char) != criteria.profession) return false
        if (criteria.skillType.isNotEmpty() &&
            char.skills.none { it.skillType.equals(criteria.skillType, ignoreCase = true) }
        ) return false
        if (criteria.enhancementOnly && char.skills.none { it.enhancements.isNotEmpty() }) return false
        if (criteria.issueOnly && char.issueCount == 0) return false
        return true
    }

    fun starValue(char: CharacterView): String = char.star.takeIf { it > 0 }?.toString() ?: UNKNOWN_FILTER_VALUE
    fun elementValue(char: CharacterView): String = char.element.ifBlank { UNKNOWN_FILTER_VALUE }
    fun professionValue(char: CharacterView): String = char.profession.ifBlank { UNKNOWN_FILTER_VALUE }

    fun values(extract: (CharacterView) -> String, characters: List<CharacterView>): List<String> =
        characters.map(extract).distinct().sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })

    // ── 效果 ────────────────────────────────────────────────────

    fun effectHaystack(effect: CharacterEffectView): String = listOf(
        effect.id, effect.displayName, effect.description, effect.category,
        *effect.usages.flatMap { listOf(it.characterName, it.skillName, it.enhancementName.orEmpty(), it.scope.name) }
            .toTypedArray(),
    ).joinToString(" ").lowercase()

    fun matches(effect: CharacterEffectView, criteria: EffectFilterCriteria): Boolean {
        val query = criteria.query.trim().lowercase()
        if (query.isNotEmpty() && query !in effectHaystack(effect)) return false
        if (criteria.category.isNotEmpty() && effect.category != criteria.category) return false
        return when (criteria.usage) {
            EffectUsageFilter.ALL -> true
            EffectUsageFilter.USED -> effect.usages.isNotEmpty()
            EffectUsageFilter.UNUSED -> effect.usages.isEmpty()
            // 「未定义」：技能里引用了，但 effects.py 里没有这个 ID
            EffectUsageFilter.UNKNOWN -> !effect.defined
        }
    }

    // ── 问题 ────────────────────────────────────────────────────

    /**
     * 问题的可检索字段。对齐 VSCode：除了 code / message，还要能按来源的
     * character_id / skill_id / effect_id 搜 —— 排查「某个效果没定义」时
     * 用户手里的线索往往就是这些 ID。
     */
    fun issueHaystack(issue: CharacterIssue): String = listOf(
        issue.code, issue.message,
        issue.source?.characterId.orEmpty(),
        issue.source?.skillId.orEmpty(),
        issue.source?.effectId.orEmpty(),
        issue.source?.fileName.orEmpty(),
    ).joinToString(" ").lowercase()

    fun issueMatches(issue: CharacterIssue, query: String, severityFilter: IssueSeverity?): Boolean {
        if (severityFilter != null && issue.severity != severityFilter) return false
        val q = query.trim().lowercase()
        if (q.isNotEmpty() && q !in issueHaystack(issue)) return false
        return true
    }
}
