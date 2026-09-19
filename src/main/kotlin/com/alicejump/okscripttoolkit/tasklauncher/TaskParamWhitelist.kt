package com.alicejump.okscripttoolkit.tasklauncher

import com.alicejump.okscripttoolkit.tasklauncher.TaskLauncherService.TaskParamField

/**
 * 参数覆盖投递给执行器前的 schema 白名单过滤。
 *
 * 抽成不依赖 IDE / Swing 的纯对象，因为这是一条**"看起来像传参、其实是安全边界"**的规则：
 * 它一旦缺失，表现不是崩溃，而是执行器那边出现一堆没人认识的键、或者旧字段悄悄复活。
 *
 * 背景：IntelliJ 侧把「全部任务的参数」整份投给常驻执行器（`OK_LANG_HINTS_INJECT`），
 * 执行器按任务各自取用。但持久化文件 `.idea/ok-script-toolkit-tasks.json` 是可以被手改、
 * 也可能是旧版本写下的 —— 里面的 `params` 未必对应当前 schema。
 *
 * VS Code 侧在 `taskLauncher.ts:379-410` 的 `sanitizeTaskConfig` 里做了白名单：
 * 只保留 `schema.fields` 里出现过的 key，其余一律丢弃。IntelliJ 侧原本**没有**这一步，
 * 是"全量投递"。这构成两端不对等，而且下游 `run_executor.py:355`
 * （`if key not in config: continue`）只是**碰巧**挡了一下 —— 那是"未知键跳过"，
 * 不是"故意设计的安全边界"，不能当作已有防护。
 *
 * 注意降级语义（与 VS Code 保持一致，别"顺手修正"）：
 * **schema 未就绪（fields 为空）时原样放行全部参数**。
 * 探测失败 / 首次打开时 schema 还没采集完，此时若按空白名单过滤会把用户的参数全删掉 ——
 * 宁可多传几个键，也不能误删用户数据。
 */
internal object TaskParamWhitelist {

    /**
     * @param params 持久化里的参数表（可能为 null）
     * @param schemaFields 该任务当前采集到的 schema 字段；为空表示 schema 未就绪
     * @return 只含 schema 声明的 key 的新表；schema 未就绪时原样返回
     */
    fun filter(
        params: Map<String, Any>?,
        schemaFields: List<TaskParamField>?,
    ): Map<String, Any> {
        if (params.isNullOrEmpty()) return emptyMap()
        if (schemaFields.isNullOrEmpty()) return params

        val allowed = schemaFields.mapTo(HashSet(schemaFields.size)) { it.key }
        val kept = LinkedHashMap<String, Any>(params.size)
        for ((key, value) in params) {
            if (key in allowed) kept[key] = value
        }
        return kept
    }
}
