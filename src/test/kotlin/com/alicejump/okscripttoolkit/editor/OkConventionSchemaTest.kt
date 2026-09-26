package com.alicejump.okscripttoolkit.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * 约定文件 JSON Schema 的打包与同步测试。
 *
 * schema 的**维护源头在父仓** `schemas/ok-script-toolkit.schema.json`（VS Code 侧的
 * jsonValidation 也指向它），子仓只经 copyConventionSchema 同步进 JAR。这里盯两件事：
 * 1. 资源在 classpath 上且内容是合法 JSON、含全部顶层约定字段 —— 打包链路断掉时第一时间红；
 * 2. 与父仓那份**逐字节一致** —— 两边各改各的会造成两端校验口径漂移
 *    （父仓 CI 带 submodules 检出，此断言完整执行；子仓独立 CI 没有父仓文件，跳过）。
 */
class OkConventionSchemaTest {

    private val schemaText: String?
        get() = OkConventionSchemaTest::class.java.classLoader
            .getResourceAsStream("schemas/ok-script-toolkit.schema.json")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }

    @Test
    fun `convention schema is bundled and covers every top-level convention field`() {
        val text = schemaText
        // 子仓独立 CI 不检父仓 → copyConventionSchema 空跑 → 资源缺失，跳过（父仓 CI 完整跑）
        assumeTrue(text != null, "classpath 上没有 schema —— 只检出了子仓库，父仓 CI 会完整跑")

        val parsed = com.fasterxml.jackson.databind.ObjectMapper().readTree(text)
        assertEquals("object", parsed.get("type").asText(), "schema 根必须是 object")

        val properties = parsed.get("properties")
        for (key in listOf("labelEnum", "executor", "templates", "i18n", "characters", "effects")) {
            assertTrue(properties.has(key), "schema 必须覆盖约定字段 $key（VS Code 侧同名文件对齐）")
        }
    }

    @Test
    fun `bundled schema stays in sync with the parent repo copy`() {
        val text = schemaText
        assumeTrue(text != null, "classpath 上没有 schema —— 只检出了子仓库，父仓 CI 会完整跑")

        // 从本仓库位置向上找父仓 schemas/（测试工作目录是仓库根的任一子路径都可能，取稳定锚点）
        val parentCopy = generateSequence(java.io.File(System.getProperty("user.dir"))) { it.parentFile }
            .take(4)
            .map { it.resolve("schemas/ok-script-toolkit.schema.json") }
            .firstOrNull { it.isFile }
        assumeTrue(parentCopy != null, "父仓 schemas/ 不可见（子仓独立 CI），漂移断言由父仓 CI 执行")

        assertEquals(
            parentCopy!!.readText(Charsets.UTF_8).replace("\r\n", "\n"),
            text!!.replace("\r\n", "\n"),
            "子仓打包的 schema 与父仓不一致 —— 修改约定 schema 必须只改父仓那份，两端共用",
        )
    }
}
