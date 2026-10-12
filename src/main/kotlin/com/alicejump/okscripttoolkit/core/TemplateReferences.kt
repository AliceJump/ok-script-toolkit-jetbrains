package com.alicejump.okscripttoolkit.core

/**
 * 在 Python 源码里找模板 / 坐标引用（纯函数，与 VS Code `src/templateReferences.ts` 同规则）：
 * - `fL.name`（及项目配置的其他特征别名）→ feature
 * - `self.pos.name` / `self.pos.a.b.name` 末段 → position
 * - 字符串字面量 `"name"` / `'name'` → key（如 find_one("name")）
 */
object TemplateReferences {
    enum class Kind { FEATURE, POSITION, KEY }
    data class Hit(val offset: Int, val length: Int, val line: Int, val column: Int, val kind: Kind, val preview: String)

    fun find(text: String, name: String, aliases: List<String>): List<Hit> {
        if (name.isBlank()) return emptyList()
        val n = Regex.escape(name)
        val patterns = buildList {
            for (alias in aliases.filter { it.isNotBlank() }.distinct()) {
                add(Kind.FEATURE to Regex("(?<![\\w.])${Regex.escape(alias)}\\.($n)(?![\\w])"))
            }
            add(Kind.POSITION to Regex("(?<![\\w.])self\\.pos\\.(?:[A-Za-z_]\\w*\\.)*($n)(?![\\w])"))
            add(Kind.KEY to Regex("""(?<![\w])[rRuUbB]?(["'])($n)\1"""))
        }
        val lineStarts = buildList { add(0); text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) } }
        val seen = HashSet<Int>()
        val hits = mutableListOf<Hit>()
        for ((kind, regex) in patterns) {
            for (match in regex.findAll(text)) {
                val group = match.groups[if (kind == Kind.KEY) 2 else 1] ?: continue
                val offset = group.range.first
                if (!seen.add(offset)) continue
                val lineIndex = lineStarts.binarySearch(offset).let { if (it >= 0) it else -it - 2 }
                val start = lineStarts[lineIndex]
                val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
                hits += Hit(offset, name.length, lineIndex, offset - start, kind, text.substring(start, end).trim())
            }
        }
        return hits.sortedBy { it.offset }
    }
}
