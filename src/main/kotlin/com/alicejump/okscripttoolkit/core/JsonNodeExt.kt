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

/**
 * 取**严格意义**上的字符串字段：非 JSON 字符串（数字、布尔、数组、对象）与显式 `null`
 * 一律视为缺失，空串/全空白也算缺失。
 *
 * 与 [textOrNull] 的区别只在"严格程度"：
 *
 * | 值 | `textOrNull()` | `stringOrNull()` |
 * |---|---|---|
 * | `"x"` | `"x"` | `"x"` |
 * | `null` | `null` | `null` |
 * | `42` | **`"42"`** | `null` |
 * | `["x"]` | `""` | `null` |
 *
 * [textOrNull] 面向"已知这个字段是字符串，只是可能被写成显式 null"的读取（如技能 `element`）；
 * 本函数面向**手写的配置文件**：用户把 `"path": 42` 写错了，应当作没写，
 * 而不是让它变成一个叫 `42` 的路径。
 *
 * 对端 VSCode 的 `nonEmpty()`（`src/projectConfigPure.ts`）用 `typeof value === 'string'`
 * 判断，语义与这里一致 —— 两端读同一份 `ok-script-toolkit.json` 时必须给出同样的结论。
 */
fun JsonNode?.stringOrNull(): String? =
    if (this?.isTextual == true) asText().takeIf { it.isNotBlank() } else null

/** 取严格字符串字段，非字符串或缺失时用 [fallback]。见 [stringOrNull]。 */
fun JsonNode?.stringOr(fallback: String): String = stringOrNull() ?: fallback
