package com.alicejump.okscripttoolkit.tasklauncher

/**
 * 「快速首屏」用的 schema 合并：把缓存里的 schema 与刚从 `config.py` 解析出的任务列表对齐。
 *
 * 背景：全量采集（`probe_task_schemas.py`，要 import 整个项目）慢，
 * 所以先用**上次的缓存**把界面渲染出来，再后台采集覆盖。合并规则就是这中间的一步：
 *
 * - 缓存里有 schema 的任务 → 原样保留（能立刻显示真实名称与参数）
 * - **新增**的任务（缓存里没有）→ 只能给一个**桩**：`displayName` 取解析结果里的
 *   `displayName`，没有字段、没有分组
 * - 项目里已**删除**的任务 → 从结果里剔除，否则界面上会留下幽灵任务
 *
 * ⚠️ **桩的 `displayName` 实际是类名**：`parse_config_tasks.py` 只输出
 * `module`/`class`/`kind`，没有 `name`，真正的显示名来自 schema
 * （见 `TaskLauncherService.parseConfigTasks` 的映射）。
 * 所以一旦界面"只显示类名"，就说明**这一步的结果被当成了最终结果** ——
 * 正常流程必须随后无条件跑一次全量采集把它覆盖掉。
 * （2026-09-20 的缺陷正是如此：缓存有效时直接 return，采集被跳过。）
 */
internal object TaskSchemaMerge {

    /** 任务在 schema 里的键，与父仓 `taskLauncher.ts` 的 `taskKey()` 一致。 */
    fun keyOf(task: TaskLauncherService.TaskInfo): String = "${task.module}::${task.className}"

    /**
     * @param cached 缓存里的 schema（键为 [keyOf]）
     * @param tasks 本次解析出的任务列表
     * @param parseOk 解析是否成功；**失败时原样返回缓存**，避免把界面清空
     */
    fun merge(
        cached: Map<String, TaskLauncherService.TaskSchema>,
        tasks: List<TaskLauncherService.TaskInfo>,
        parseOk: Boolean,
    ): Map<String, TaskLauncherService.TaskSchema> {
        if (!parseOk) return cached
        val merged = cached.toMutableMap()
        for (task in tasks) {
            val key = keyOf(task)
            if (!merged.containsKey(key)) {
                merged[key] = TaskLauncherService.TaskSchema(displayName = task.displayName)
            }
        }
        val validKeys = tasks.mapTo(HashSet(tasks.size)) { keyOf(it) }
        return merged.filterKeys { it in validKeys }
    }
}
