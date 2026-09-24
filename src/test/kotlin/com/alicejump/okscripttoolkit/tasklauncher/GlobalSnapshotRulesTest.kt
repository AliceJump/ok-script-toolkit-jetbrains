package com.alicejump.okscripttoolkit.tasklauncher

import com.alicejump.okscripttoolkit.tasklauncher.TaskLauncherService.TaskParamField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 全局配置快照物化规则的回归测试（#7 配置接管）。
 *
 * 规则对齐 VS Code 侧 consolePanel.ts 的 materializeGlobalSnapshot：
 * - 首建（existing 为空）→ 全部继承 f.value（用户项目里已调好的值原样进快照，
 *   不能执行器一启动就打回默认）；
 * - 重探针（existing 非空）→ 已有键保留（孤儿键不删），新键取 f.default ?: f.value；
 * - 「恢复默认」→ schema 已知键全部取 f.default ?: f.value，孤儿键原样保留。
 *
 * 抽成纯对象的原因同 TaskConfigMerge：不依赖 IDE 可直测（本仓库测试约定）。
 */
class GlobalSnapshotRulesTest {

    private fun field(key: String, default: Any?, value: Any?) =
        TaskParamField(key = key, default = default, value = value)

    @Test
    fun `first materialization inherits the probe's current values`() {
        val fields = listOf(
            field("dps", 50, 99),
            field("auto_battle", true, false),
        )

        val (snapshot, added) = GlobalSnapshotRules.materialize(emptyMap(), fields)

        assertEquals(
            mapOf("dps" to 99, "auto_battle" to false),
            snapshot,
            "首建必须继承 f.value —— 探针的 value 是项目当前值，不是 default",
        )
        assertEquals(2, added)
    }

    @Test
    fun `re-probe keeps existing keys and fills new keys with defaults`() {
        val fields = listOf(
            field("dps", 50, 99),
            field("new_key", "fallback", "fallback"),
        )
        val existing = mapOf<String, Any?>("dps" to 42)

        val (snapshot, added) = GlobalSnapshotRules.materialize(existing, fields)

        assertEquals(
            42,
            snapshot["dps"],
            "已有键必须原样保留 —— 用户改过的值不能被探针覆盖",
        )
        assertEquals(
            "fallback",
            snapshot["new_key"],
            "重探针的新键取 f.default，不是 f.value（新键没有用户历史值）",
        )
        assertEquals(1, added)
    }

    @Test
    fun `new key without default falls back to value`() {
        val fields = listOf(field("no_default", null, "current_value"))
        val existing = mapOf<String, Any?>("old" to 1)

        val (snapshot, _) = GlobalSnapshotRules.materialize(existing, fields)

        assertEquals(
            "current_value",
            snapshot["no_default"],
            "default 为 null 时退回 f.value（对齐 VS Code 侧 f.default ?? f.value）",
        )
    }

    @Test
    fun `orphan keys survive re-materialization`() {
        val fields = listOf(field("known", 1, 2))
        val existing = mapOf<String, Any?>(
            "orphan_from_project_store" to "keep-me",
            "known" to 42,
        )

        val (snapshot, added) = GlobalSnapshotRules.materialize(existing, fields)

        assertEquals(
            "keep-me",
            snapshot["orphan_from_project_store"],
            "孤儿键不删：探针没采集到的键可能是项目自建 store 的旧值，删了执行器侧就静默回退默认",
        )
        assertEquals(0, added, "全部键都已存在时不产生新增")
    }

    @Test
    fun `materialize never mutates its input`() {
        val existing = mutableMapOf<String, Any?>("dps" to 42)
        val fields = listOf(field("new_key", 7, 7))

        GlobalSnapshotRules.materialize(existing, fields)

        assertEquals(mapOf("dps" to 42), existing.toMap(), "入参必须原样不动")
    }

    @Test
    fun `empty fields produce an unchanged snapshot with zero additions`() {
        val existing = mapOf<String, Any?>("k" to 1)

        val (snapshot, added) = GlobalSnapshotRules.materialize(existing, emptyList())

        assertEquals(mapOf("k" to 1), snapshot)
        assertEquals(0, added)
    }

    // ── resetToDefaults ──────────────────────────────────────────────

    @Test
    fun `reset overwrites every known key with its default`() {
        val fields = listOf(
            field("dps", 50, 99),
            field("auto_battle", true, false),
        )
        val existing = mapOf<String, Any?>("dps" to 42, "auto_battle" to false)

        val snapshot = GlobalSnapshotRules.resetToDefaults(existing, fields)

        assertEquals(
            mapOf("dps" to 50, "auto_battle" to true),
            snapshot,
            "恢复默认必须覆盖全部 schema 已知键",
        )
    }

    @Test
    fun `reset keeps orphan keys`() {
        val fields = listOf(field("known", 1, 2))
        val existing = mapOf<String, Any?>("orphan" to "keep-me", "known" to 42)

        val snapshot = GlobalSnapshotRules.resetToDefaults(existing, fields)

        assertEquals("keep-me", snapshot["orphan"], "恢复默认只作用于 schema 已知键")
        assertEquals(1, snapshot["known"])
    }

    @Test
    fun `first materialization of an empty probe group is a no-op`() {
        val (snapshot, added) = GlobalSnapshotRules.materialize(emptyMap(), emptyList())

        assertTrue(snapshot.isEmpty())
        assertEquals(0, added)
    }
}
