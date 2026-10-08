package com.alicejump.okscripttoolkit.core

import com.fasterxml.jackson.databind.ObjectMapper

/** 插件自己的失败保存草稿；保留原始分支，重新打开后仍走三方合并。 */
internal object AnnotationDraft {
    private val mapper = ObjectMapper()
    fun encode(state: AnnotationSessionSyncState): String {
        val node = mapper.createObjectNode().put("version", 1)
        node.set<com.fasterxml.jackson.databind.JsonNode>("base", mapper.valueToTree(state.base))
        node.set<com.fasterxml.jackson.databind.JsonNode>("local", mapper.valueToTree(state.local))
        if (state.revision != null) node.put("revision", state.revision)
        return mapper.writeValueAsString(node)
    }
    fun decode(raw: String): AnnotationSessionSyncState? = runCatching {
        val node = mapper.readTree(raw)
        require(node.path("version").asInt() == 1)
        fun shapes(field: String): List<MergeShape> {
            val values = node.path(field)
            require(values.isArray)
            return values.map { shape ->
                require(shape.path("name").isTextual)
                for (key in listOf("id", "x", "y", "w", "h")) require(shape.path(key).canConvertToInt())
                MergeShape(shape.path("id").asInt(), shape.path("name").asText(), shape.path("x").asInt(),
                    shape.path("y").asInt(), shape.path("w").asInt(), shape.path("h").asInt())
            }
        }
        AnnotationSessionSyncState(shapes("base"), shapes("local"), node.path("revision").takeIf { it.isTextual }?.asText(), dirty = true)
    }.getOrNull()
}

/** 成功写回后接受本地几何与编辑器 ID，避免自己的文件事件清空撤销栈。 */
internal fun acceptAnnotationSave(local: List<MergeShape>, revision: String?) =
    AnnotationSessionSyncState(local, local, revision, dirty = false)
