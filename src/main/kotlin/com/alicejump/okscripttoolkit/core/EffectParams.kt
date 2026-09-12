package com.alicejump.okscripttoolkit.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * 单个「效果引用」的可调参数，对应 VSCode `createEffectEditor` 里
 * value / duration / target / count 四个控件。
 *
 * 真实数据（ok-end-field，88 条强化产出效果）的形态：
 * - `value` 88/88 是 int
 * - `duration` 86/88 是 **null**，只有 2 条是字符串
 * - `target` 88/88 是字符串
 * - `count` 57 条 int / 1 条 null / 30 条干脆不写
 *
 * 所以空值时 duration 与 count 一律写 null，不要写空字符串——否则每次编辑
 * 都会把这 88 条改成 `"duration": ""`，污染整个文件。
 */
data class EffectParam(
    val effectId: String,
    val value: String = "0",
    val duration: String = "",
    val target: String = "enemy",
    val count: String = "1",
)

object EffectParamCodec {

    private val JSON = ObjectMapper()

    /** 编码成 JSON 数组字符串，交给 `CharacterDataMutations` 的 form 透传。 */
    fun encode(params: List<EffectParam>): String {
        val array = JSON.createArrayNode()
        for (param in params) {
            val node = array.addObject()
            node.put("effect_id", param.effectId)
            node.set<JsonNode>("value", numericOrZero(param.value))
            if (param.duration.isBlank()) node.putNull("duration") else node.put("duration", param.duration)
            node.put("target", param.target.ifBlank { "enemy" })
            if (param.count.isBlank()) node.putNull("count") else node.set<JsonNode>("count", numericOrZero(param.count))
        }
        return array.toString()
    }

    /** 只取 ID（触发依赖效果用，那边的元素是纯字符串）。 */
    fun ids(params: List<EffectParam>): String = params.joinToString(",") { it.effectId }

    /**
     * 解析 form 里的效果字段：既接受上面 encode 出的 JSON 数组，
     * 也接受「A,B,C」这种纯 ID 串（兼容旧表单与手写输入）。
     */
    fun parse(raw: String?): ArrayNode {
        val text = raw?.trim().orEmpty()
        val array = JSON.createArrayNode()
        if (text.isEmpty()) return array
        if (text.startsWith("[")) {
            val parsed = runCatching { JSON.readTree(text) }.getOrNull()
            if (parsed is ArrayNode) return parsed
        }
        for (id in text.split(',', ';', '\n', '\r', '，', '、')) {
            val trimmed = id.trim()
            if (trimmed.isNotEmpty()) array.addObject().put("effect_id", trimmed)
        }
        return array
    }

    private fun numericOrZero(raw: String): JsonNode {
        val text = raw.trim()
        text.toLongOrNull()?.let { return JSON.nodeFactory.numberNode(it) }
        val asDouble = text.toDoubleOrNull()
        if (asDouble != null && !asDouble.isNaN() && !asDouble.isInfinite()) {
            return JSON.nodeFactory.numberNode(asDouble)
        }
        return JSON.nodeFactory.numberNode(0)
    }
}
