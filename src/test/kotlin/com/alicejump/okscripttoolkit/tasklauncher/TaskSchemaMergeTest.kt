package com.alicejump.okscripttoolkit.tasklauncher

import com.alicejump.okscripttoolkit.tasklauncher.TaskLauncherService.TaskInfo
import com.alicejump.okscripttoolkit.tasklauncher.TaskLauncherService.TaskSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「快速首屏」schema 合并的回归测试。
 *
 * 这段逻辑决定：用上次缓存渲染时，**新任务**能拿到什么。
 * 2026-09-20 的缺陷（JetBrains 侧任务"只显示类名"）根因不在合并本身，
 * 而在于合并结果被当成了**最终**结果 —— 缓存有效时跳过了全量采集。
 * 这里把"新任务只能拿到桩、桩的显示名就是类名"钉下来，
 * 好让这个降级状态一眼可辨：**看到类名就说明采集没跑或没跑成**。
 */
class TaskSchemaMergeTest {

    private fun task(module: String, cls: String, displayName: String = cls) =
        TaskInfo(module = module, className = cls, displayName = displayName)

    private fun schema(name: String?) = TaskSchema(displayName = name)

    @Test
    fun `key format matches the parent repo`() {
        assertEquals(
            "src.tasks.test.MinimapNavigateToPoint::MinimapNavigateToPoint",
            TaskSchemaMerge.keyOf(task("src.tasks.test.MinimapNavigateToPoint", "MinimapNavigateToPoint")),
            "键必须是 module::Class —— 父仓 taskLauncher.ts 的 taskKey() 与 " +
                "probe_task_schemas.py 的 f\"{module}::{class}\" 都用这个格式，错一个字符就查不到 schema",
        )
    }

    @Test
    fun `cached schemas are kept for tasks that still exist`() {
        val key = "src.tasks.trigger.AutoCombatTask::AutoCombatTask"
        val merged = TaskSchemaMerge.merge(
            cached = mapOf(key to schema("自动战斗")),
            tasks = listOf(task("src.tasks.trigger.AutoCombatTask", "AutoCombatTask")),
            parseOk = true,
        )

        assertEquals("自动战斗", merged[key]?.displayName, "缓存里的真实显示名不能被覆盖")
    }

    /**
     * 新任务只能拿到**桩**，且桩的显示名就是**类名**。
     *
     * 这不是"实现不够好"，而是数据决定的：`parse_config_tasks.py` 不输出 `name`。
     * 所以这条断言的作用是**把降级状态变成可识别的信号** ——
     * 界面上出现类名 ⇒ 全量采集没有生效。
     */
    @Test
    fun `a brand new task only gets a class-name stub`() {
        val cls = "MinimapNavigateToPoint"
        val key = "src.tasks.test.MinimapNavigateToPoint::$cls"

        val merged = TaskSchemaMerge.merge(
            cached = emptyMap(),
            tasks = listOf(task("src.tasks.test.MinimapNavigateToPoint", cls)),
            parseOk = true,
        )

        assertEquals(cls, merged[key]?.displayName, "新任务的桩只能拿到类名（parse 结果里没有 name）")
        assertTrue(merged[key]?.fields.isNullOrEmpty(), "桩没有字段 —— 参数表单会是空的")
        assertTrue(
            merged[key]?.configGroups.isNullOrEmpty(),
            "桩也没有分组 —— 这正是「只显示类名 + 无参数」的成因",
        )
    }

    /** 项目里删掉的任务不能留在结果里，否则界面会出现幽灵任务。 */
    @Test
    fun `tasks removed from the project are dropped`() {
        val gone = "src.tasks.test.DeletedTask::DeletedTask"
        val alive = "src.tasks.test.AliveTask::AliveTask"

        val merged = TaskSchemaMerge.merge(
            cached = mapOf(gone to schema("已删除"), alive to schema("还在")),
            tasks = listOf(task("src.tasks.test.AliveTask", "AliveTask")),
            parseOk = true,
        )

        assertEquals(setOf(alive), merged.keys, "缓存里有、但项目里已不存在的任务必须剔除")
    }

    /** 解析失败时不能把界面清空 —— 宁可显示上一次的缓存。 */
    @Test
    fun `failed parse leaves the cache untouched`() {
        val cached = mapOf("a::B" to schema("旧"))

        assertEquals(
            cached,
            TaskSchemaMerge.merge(cached, tasks = emptyList(), parseOk = false),
            "解析失败（如 python 环境不对）时必须原样返回缓存，否则界面会突然全空",
        )
    }

    /**
     * 破坏性对照：证明"桩只有类名"这条不是空过。
     *
     * 若合并时**错误地**给新任务编一个显示名（比如取模块最后一段），
     * 界面上就看不出这是降级状态了 —— 用户会以为任务正常、只是参数少。
     */
    @Test
    fun `regression guard - the stub must not invent a friendly display name`() {
        val cls = "MinimapRegionCheck"
        val module = "src.tasks.test.MinimapRegionCheck"

        val real = TaskSchemaMerge.merge(emptyMap(), listOf(task(module, cls)), parseOk = true)
        val invented = cls.removeSuffix("Check").replace(Regex("(?<!^)(?=[A-Z])"), " ").trim()

        assertEquals(
            cls, real["$module::$cls"]?.displayName,
            "桩必须老实使用解析给的 displayName（= 类名），不能自己编一个看起来正常的名字",
        )
        assertEquals("Minimap Region", invented, "对照：编出来的名字会像真的，反而掩盖了降级状态")
    }
}
