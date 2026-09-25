package com.alicejump.okscripttoolkit.tasklauncher

import com.alicejump.okscripttoolkit.tasklauncher.TaskLauncherService.TaskParamField

/**
 * 全局配置快照的物化规则（对齐 VS Code 侧 consolePanel.ts 的
 * materializeTaskSnapshot / materializeGlobalSnapshot 语义）。
 *
 * 抽成不依赖 IDE / Swing 的纯对象，理由同 [TaskConfigMerge]：这是「配置接管」的
 * 数据约定 —— 快照里**已有的键永远保留**（孤儿键不删：探针没采集到的键可能是
 * 项目自建 store 的旧值，前端一旦删掉执行器侧就回退框架默认，静默丢配置），
 * 新键的取值取决于「这是不是第一次物化」。
 *
 * 首建（existing 为空）继承 `f.value`：用户当前项目里已经调好的值原样进快照，
 * 不能执行器一启动就把用户配置打回默认。
 *
 * 重探针（existing 非空）新键取 `f.defaultOrValue()`：探针新出现的键没有用户
 * 历史值，用 schema 默认值兜底；default 缺失时退回 value（与 VS Code 侧一致）。
 */
internal object GlobalSnapshotRules {

    /**
     * 物化一组字段进快照。
     *
     * @return 新快照（不可变副本）与新增键数（用于 UI 提示「物化了 N 个键」）
     */
    fun materialize(
        existing: Map<String, Any?>,
        fields: List<TaskParamField>,
    ): Pair<Map<String, Any?>, Int> {
        val isFirst = existing.isEmpty()
        val snapshot = existing.toMutableMap()
        var added = 0
        for (field in fields) {
            if (snapshot.containsKey(field.key)) continue
            snapshot[field.key] = if (isFirst) {
                field.value
            } else {
                field.defaultOrValue()
            }
            added++
        }
        return snapshot.toMap() to added
    }

    /**
     * 「恢复默认」：全部键取 `f.defaultOrValue()`（不管 existing 里是什么）。
     * 快照里不在 fields 中的孤儿键原样保留 —— 恢复默认只作用于 schema 已知键。
     */
    fun resetToDefaults(
        existing: Map<String, Any?>,
        fields: List<TaskParamField>,
    ): Map<String, Any?> {
        val snapshot = existing.toMutableMap()
        for (field in fields) {
            snapshot[field.key] = field.defaultOrValue()
        }
        return snapshot.toMap()
    }
}
