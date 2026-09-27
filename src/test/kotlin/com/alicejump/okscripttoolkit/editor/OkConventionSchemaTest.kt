package com.alicejump.okscripttoolkit.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * 约定文件 JSON Schema 的打包与同步测试。
 *
 * schema 的**维护源头在父仓** `schemas/ok-script-toolkit.schema.json`（VS Code 侧的
 * jsonValidation 也指向它），子仓只经 copyConventionSchema 同步进 JAR。这里盯两件事：
 * 1. 资源在 classpath 上且内容是合法 JSON、含全部顶层约定字段 —— 打包链路断掉时第一时间红；
 * 2. 与父仓那份**逐字节一致** —— 两边各改各的会造成两端校验口径漂移
 *    （父仓 CI 使用相邻源码，子仓独立 CI 显式检出 v1.14.0 并传入源文件路径）。
 */
class OkConventionSchemaTest {

    private val schemaText: String?
        get() = OkConventionSchemaTest::class.java.classLoader
            .getResourceAsStream("schemas/ok-script-toolkit.schema.json")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }

    private val sourceSchema: java.io.File
        get() = java.io.File(System.getProperty("ok.convention.schema.source").orEmpty())

    @Test
    fun `convention schema is bundled and covers every top-level convention field`() {
        // 只运行单元测试且未检出父仓时可跳过；提供源文件的 CI 必须检验 JAR 资源。
        assumeTrue(sourceSchema.isFile, "未提供父仓 schema；可发布构建会在 Gradle 配置阶段失败")
        val text = assertNotNull(schemaText, "源文件存在，但 schema 未打包进 classpath")

        val parsed = com.fasterxml.jackson.databind.ObjectMapper().readTree(text)
        assertEquals("object", parsed.get("type").asText(), "schema 根必须是 object")

        val properties = parsed.get("properties")
        for (key in listOf("labelEnum", "executor", "templates", "i18n", "characters", "effects")) {
            assertTrue(properties.has(key), "schema 必须覆盖约定字段 $key（VS Code 侧同名文件对齐）")
        }
    }

    @Test
    fun `bundled schema stays in sync with the parent repo copy`() {
        assumeTrue(sourceSchema.isFile, "未提供父仓 schema；可发布构建会在 Gradle 配置阶段失败")
        val text = assertNotNull(schemaText, "源文件存在，但 schema 未打包进 classpath")

        assertEquals(
            sourceSchema.readText(Charsets.UTF_8).replace("\r\n", "\n"),
            text.replace("\r\n", "\n"),
            "子仓打包的 schema 与父仓不一致 —— 修改约定 schema 必须只改父仓那份，两端共用",
        )
    }
}
