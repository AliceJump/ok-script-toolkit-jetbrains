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
