package com.alicejump.okscripttoolkit.core

import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `JsonNode?.textOrNull()` / `textOr()` 的契约。
 *
 * 这两个函数存在的唯一理由是 Jackson 的一个反直觉行为，所以测试也围绕它写：
 *
 * - 字段**不存在** → `get()` 返回 Kotlin `null`
 * - 字段存在但值为 `null` → `get()` 返回 **`NullNode`**（不是 Kotlin `null`）
 * - `NullNode.asText()` → **字面量字符串 `"null"`**
 *
 * 也就是说 `node.get(k)?.asText() ?: fallback` 这种常见写法**两种缺失只兜住一种**。
 * 对端 VSCode 没有这个问题（`stringValue` 走 `typeof` 判断、`== null` 同时覆盖
 * `null`/`undefined`），所以这里是子仓单侧的坑。
 */
class JsonNodeExtTest {

    private val json = ObjectMapper()

    private fun node(text: String) = json.readTree(text)

    /**
     * 先证明"朴素写法确实会踩坑"，再证明 helper 修好了它。
     *
     * 这条对照很重要：不写它的话，以后有人觉得 `?.asText() ?: fallback` 更简洁
     * 而把 helper 换掉，测试仍然是绿的（因为其它断言只测 helper 自己）。
     */
    @Test
    fun `naive asText fallback leaks the literal string null`() {
        val root = node("""{"element": null}""")

        assertEquals(
            "null", root.get("element")?.asText(),
            "朴素写法确实会拿到字符串 \"null\" —— 这正是 helper 要解决的问题",
        )
        assertNull(
            root.get("element").textOrNull(),
            "helper 必须把显式 null 归一成 Kotlin null，让 ?: 兜底生效",
        )
    }

    @Test
    fun `explicit null is treated as missing`() {
        val root = node("""{"a": null}""")

        assertNull(root.get("a").textOrNull(), "显式 null -> null")
        assertEquals("FALLBACK", root.get("a").textOr("FALLBACK"), "显式 null -> 用兜底值")
    }

    @Test
    fun `absent key is treated as missing`() {
        val root = node("""{"b": 1}""")

        assertNull(root.get("a").textOrNull(), "键不存在 -> null")
        assertEquals("FALLBACK", root.get("a").textOr("FALLBACK"), "键不存在 -> 用兜底值")
    }

    @Test
    fun `real values pass through unchanged`() {
        val root = node("""{"a": "火", "empty": ""}""")

        assertEquals("火", root.get("a").textOr("FALLBACK"), "真实值必须原样返回，不能被兜底吃掉")
        assertEquals("火", root.get("a").textOrNull())
        assertEquals(
            "", root.get("empty").textOr("FALLBACK"),
            "空串是**存在的值**，不是缺失 —— 不能被兜底替换（否则用户清空的字段会被悄悄填回）",
        )
    }

    /** 非文本节点（数字/布尔）走 `asText()` 的字符串化，与 Jackson 默认一致。 */
    @Test
    fun `non textual nodes are stringified`() {
        val root = node("""{"n": 12, "b": true}""")

        assertEquals("12", root.get("n").textOr("FALLBACK"))
        assertEquals("true", root.get("b").textOr("FALLBACK"))
    }
}
