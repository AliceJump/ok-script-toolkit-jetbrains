package com.alicejump.okscripttoolkit.ui

/**
 * 角色头像的模板匹配规则（对齐 VS Code characterPanel.ts 的 avatarTemplateIndex /
 * characterAvatars）。
 *
 * 抽成不依赖 IDE / Swing 的纯对象，理由同 [CharacterFilters]：匹配语义有四条
 * **看起来像查找、其实是数据约定**的规则，错了不崩溃但头像集体消失：
 *
 * 1. **key 归一化**：trim + 小写 + 去掉全部空白/下划线/连字符 —— 模板名带分隔符变体
 *    （`battle icon yvonne` / `battle-icon_yvonne`）时照样命中；
 * 2. **双候选**：先 master 表的 en 名、再 characterId，逐个尝试；
 * 3. **唯一后缀回退**：精确 key 未命中时，若归一化模板名以 key 结尾的模板**恰好一个**，
 *    取它 —— 名字带前缀项目（如 `avatar_yvonne_2`）不至于匹配不上，也不至于误配；
 * 4. **非法正则兜底**：用户手写正则编译失败时退回内置默认 —— 不能让一条配置把
 *    整个面板的头像打没（原先 runCatching 后直接全灭）。
 */
internal object AvatarMatcher {

    fun normalizedKey(value: String?): String =
        value?.trim()?.lowercase()?.replace(Regex("[\\s_-]+"), "") ?: ""

    /** 非法正则回退内置默认（对齐 VS Code avatarTemplateRegex 的 try/catch） */
    fun compile(configured: String, default: String): Regex =
        runCatching { Regex(configured, RegexOption.IGNORE_CASE) }
            .getOrElse { Regex(default, RegexOption.IGNORE_CASE) }

    /**
     * 从模板列表里为角色选头像模板。
     *
     * @param templates 全部模板
     * @param nameOf 模板名取值
     * @param regex 头像模板正则（应已带 IGNORE_CASE）
     * @param candidates 依次尝试的候选 key（master.en / characterId），null/空串跳过
     * @return 命中的模板；全部候选都未命中时 null
     */
    fun <T> select(
        templates: List<T>,
        nameOf: (T) -> String,
        regex: Regex,
        candidates: List<String?>,
    ): T? {
        // 索引：正则命中 group(1)（正则里括住名字部分），没有 group 时取匹配后的剩余段
        val byKey = linkedMapOf<String, T>()
        val matched = mutableListOf<T>()
        for (template in templates) {
            val name = nameOf(template)
            val match = regex.find(name) ?: continue
            matched.add(template)
            val remainder = name.substring(match.range.last + 1)
            val group = match.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() }
            val key = normalizedKey(group ?: remainder)
            if (key.isNotEmpty() && key !in byKey) byKey[key] = template
        }
        for (candidate in candidates) {
            val key = normalizedKey(candidate)
            byKey[key]?.let { return it }
            if (key.isEmpty()) continue
            val suffixMatches = matched.filter { normalizedKey(nameOf(it)).endsWith(key) }
            if (suffixMatches.size == 1) return suffixMatches[0]
        }
        return null
    }
}
