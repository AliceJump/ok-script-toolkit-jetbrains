package com.alicejump.okscripttoolkit.core

import com.fasterxml.jackson.databind.JsonNode

/**
 * 按对象字段迭代 JSON 节点：Jackson 2.17 起弃用 [JsonNode.fields]，
 * 改用 [JsonNode.fieldNames] + [JsonNode.path]（新旧平台均可用的非弃用 API）。
 */
inline fun JsonNode.forEachField(action: (name: String, value: JsonNode) -> Unit) {
    val names = fieldNames()
    while (names.hasNext()) {
        val name = names.next()
        action(name, path(name))
    }
}

/**
 * 取字符串字段，**把显式 JSON `null` 视为缺失**（返回 Kotlin `null`）。
 *
 * 这是 Jackson 的一个坑，且很容易写成"看起来对"的代码：
 *
 * - 字段不存在 → `get()` 返回 Kotlin `null`
 * - 字段存在但值是 `null` → `get()` 返回 **`NullNode`**（不是 Kotlin `null`）
 * - `NullNode.asText()` **返回字面量字符串 `"null"`**，而不是 `null`
 *
 * 于是最常见的写法 `node.get(k)?.asText() ?: fallback` 在遇到 `"k": null` 时
 * 会得到 `"null"` 这个字符串 —— 既不触发 `?:` 兜底，还会把 "null" 当成真实值
 * 显示到界面上（2026-09-20 在技能 `element` 上实测到）。
 *
 * 对端没有这个问题：VSCode `src/characterData.ts` 用
 * `stringValue(v, fallback)`（内部 `typeof v === 'string'` 判断）与
 * `v == null ? '' : ...`（JS 的 `== null` 同时覆盖 `null`/`undefined`），
 * 两种缺失形态都会走兜底。本函数用于对齐该语义。
 */
fun JsonNode?.textOrNull(): String? {
    val node = this ?: return null
    return if (node.isNull) null else node.asText()
}

/** 取字符串字段，缺失或显式 `null` 时用 [fallback]。见 [textOrNull] 的说明。 */
fun JsonNode?.textOr(fallback: String): String = textOrNull() ?: fallback
