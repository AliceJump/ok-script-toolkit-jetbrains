package com.alicejump.okscripttoolkit.tasklauncher

import com.alicejump.okscripttoolkit.tasklauncher.TaskLauncherService.TaskParamField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 参数覆盖白名单回归测试。
 *
 * 复现的不对等：VS Code 的 `sanitizeTaskConfig` 会按 schema 字段过滤 params，
 * IntelliJ 侧却把整份 params 投给执行器。持久化文件是可手改 / 可跨版本残留的，
 * 里面的键未必对应当前 schema。
 *
 * 另一半测试锁的是**降级语义**：schema 未就绪时必须原样放行。
 * 这条很容易被"顺手修正"成空白名单过滤，那会在首次打开（schema 还没采集完）时
 * 把用户已保存的参数全部吞掉 —— 测试就是防这个的。
 */
class TaskParamWhitelistTest {

    private fun field(key: String) = TaskParamField(key = key)

    @Test
    fun `keys not present in the schema are dropped`() {
        val kept = TaskParamWhitelist.filter(
            params = mapOf("喝水" to true, "removedInNewVersion" to "x"),
            schemaFields = listOf(field("喝水"), field("吃饭")),
        )
        assertEquals(mapOf("喝水" to true), kept, "schema 里没有的键必须被丢掉 —— 旧版本残留的字段不该继续投给执行器")
    }

    @Test
    fun `order is preserved so the payload stays stable`() {
        val kept = TaskParamWhitelist.filter(
            params = linkedMapOf("c" to 3, "a" to 1, "b" to 2),
            schemaFields = listOf(field("a"), field("b"), field("c")),
        )
        assertEquals(listOf("c", "a", "b"), kept.keys.toList(), "保持原表顺序，避免每次推送的 JSON 抖动")
    }

    /**
     * 降级语义（与 VS Code 对齐，见 `taskLauncher.ts:395-397`）：
     * schema 未就绪时原样放行，宁可多传也不能误删用户的参数。
     */
    @Test
    fun `empty schema passes everything through`() {
        val params = mapOf("anything" to 1, "somethingElse" to "v")
        assertEquals(params, TaskParamWhitelist.filter(params, emptyList()), "schema 未就绪不得过滤")
        assertEquals(params, TaskParamWhitelist.filter(params, null), "schema 缺失不得过滤")
    }

    @Test
    fun `null or empty params yield an empty map`() {
        assertTrue(TaskParamWhitelist.filter(null, listOf(field("a"))).isEmpty())
        assertTrue(TaskParamWhitelist.filter(emptyMap(), listOf(field("a"))).isEmpty())
    }

    /** schema 字段重复（同一 key 出现两次）不应出问题 */
    @Test
    fun `duplicate schema keys collapse`() {
        val kept = TaskParamWhitelist.filter(
            params = mapOf("a" to 1),
            schemaFields = listOf(field("a"), field("a")),
        )
        assertEquals(mapOf("a" to 1), kept)
    }

    /** 全部被过滤掉时返回空表而不是 null，调用方靠 isEmpty 判断要不要推送 */
    @Test
    fun `all filtered out returns an empty map`() {
        val kept = TaskParamWhitelist.filter(
            params = mapOf("stale" to 1),
            schemaFields = listOf(field("fresh")),
        )
        assertTrue(kept.isEmpty(), "全被过滤返回空表 —— 调用方据此跳过推送，而不是发一个空对象")
    }

    /**
     * 破坏性对照：若把"schema 未就绪"分支删掉（改成一律按白名单过滤），
     * 空 schema 时 `allowed` 为空集 -> 用户的参数会被全部静默删除。
     * 本断言证明那条分支确实是必需的，不是冗余的防御。
     */
    @Test
    fun `regression guard - filtering on an empty schema would erase user params`() {
        val userParams = mapOf("喝水" to true, "吃饭" to false)
        // 模拟"删掉降级分支"后的行为：空白名单一律过滤
        val buggy = userParams.filterKeys { key -> emptyList<TaskParamField>().any { it.key == key } }
        assertTrue(buggy.isEmpty(), "对照实现会把用户参数全删光 —— 这正是降级分支存在的理由")
        assertEquals(userParams, TaskParamWhitelist.filter(userParams, emptyList()), "真实现必须原样放行")
    }
}
