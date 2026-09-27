package com.alicejump.okscripttoolkit.tasklauncher

import com.alicejump.okscripttoolkit.tasklauncher.TaskLauncherService.TaskInfo
import com.alicejump.okscripttoolkit.tasklauncher.TaskLauncherService.TaskSchema

/**
 * 任务卡列表的编排规则（对齐 VS Code 侧 `media/console/taskCard.js` 的 renderTasks）。
 *
 * 抽成不依赖 IDE / Swing 的纯对象，理由同 [TaskRowState] / [SchemaTreeOverlap]：
 * 这里的「分组怎么排、搜索怎么滤」是看起来像 UI、其实是数据约定的东西，错一点
 * 界面就静默地少任务，值得脱离 IDE 直测。
 *
 * 编排规则（与 webview 逐条对齐）：
 * 1. 顶层按 `kind` 分两组：**触发任务在前、一次性任务在后**（webview 的
 *    `renderTasks` 先 `renderGroup('gTriggers')` 再 `renderGroupedOnetime`）；
 * 2. 一次性任务内按 `schema.groupName` 二次分组，桶序 = 首次出现顺序
 *    （webview 的 Map 插入序），组内保持 schema 注册序；空组名归「未分组」；
 * 3. 组头始终渲染并携带过滤后的计数（webview 的 group-head 是静态 DOM，
 *    空组也显示 "0 项"）；业务分组头只在有一次性任务时渲染；
 * 4. 搜索激活时过滤任务（匹配显示名 / 类名 / 模块 / 描述 / 分组名），
 *    无匹配的组自然只剩组头 —— 折叠是否被忽略由 UI 层决定（webview：
 *    `renderGroupedOnetime` 里 `searching` 时无视折叠）。
 */
internal object TaskListGrouping {

    /** 未分组桶的折叠键段（与 webview `renderGroupedOnetime` 的 `__ungrouped__` 一致） */
    const val UNGROUPED = "__ungrouped__"

    /** 折叠键前缀（对齐 webview taskCard.js 的 groupCollapseKey） */
    private const val COLLAPSE_PREFIX = "taskGroupCollapsed::"

    /** 顶层 kind 组的折叠键：taskGroupCollapsed::trigger / taskGroupCollapsed::onetime */
    fun kindFoldKey(kind: String): String = "$COLLAPSE_PREFIX$kind"

    /** 一次性业务分组的折叠键：taskGroupCollapsed::onetime::<name 或 __ungrouped__> */
    fun groupFoldKey(groupName: String?): String =
        "$COLLAPSE_PREFIX${TaskRowState.ONETIME}::${groupName ?: UNGROUPED}"

    /** 一次性业务分组的显示名：schema.groupName 原样展示（webview t() 缺键时同样回落原文） */
    fun groupNameOf(schema: TaskSchema?): String? = schema?.groupName?.takeIf { it.isNotBlank() }

    /** 展开后的列表行：UI 层把 Head 行渲染成可折叠组头，Card 行渲染成任务卡 */
    sealed class Row {
        abstract val count: Int

        /** 顶层 kind 组头（触发任务 / 一次性任务） */
        data class KindHead(val kind: String, override val count: Int) : Row()

        /** 一次性任务的业务分组头；groupName 为 null = 未分组 */
        data class GroupHead(val groupName: String?, override val count: Int) : Row()

        /** 任务卡 */
        data class Card(val task: TaskInfo) : Row() {
            override val count: Int get() = 0
        }
    }

    /**
     * 把任务列表展开成组头 / 卡片交替的行序列。
     *
     * @param tasks 已按 schema 注册序排好的任务（applyProbeResult 的产出顺序）
     * @param schemaOf 任务 → schema（搜索匹配与分组取 groupName 用）
     * @param kindOf 任务 → trigger / onetime（解析结果优先，schema 兜底）
     * @param query 搜索词（空白 = 不过滤）
     */
    fun rows(
        tasks: List<TaskInfo>,
        schemaOf: (TaskInfo) -> TaskSchema?,
        kindOf: (TaskInfo) -> String,
        query: String,
    ): List<Row> {
        val visible = if (query.isBlank()) tasks else tasks.filter { matches(it, schemaOf(it), query) }
        val triggers = visible.filter { kindOf(it) == TaskRowState.TRIGGER }
        val onetimes = visible.filter { kindOf(it) != TaskRowState.TRIGGER }

        val out = mutableListOf<Row>()
        // 触发任务组在前；组头永远渲染（空组显示 0 项，对齐 webview 的静态组头）
        out += Row.KindHead(TaskRowState.TRIGGER, triggers.size)
        for (task in triggers) out += Row.Card(task)
        out += Row.KindHead(TaskRowState.ONETIME, onetimes.size)
        if (onetimes.isEmpty()) return out

        val buckets = LinkedHashMap<String?, MutableList<TaskInfo>>()
        for (task in onetimes) {
            buckets.getOrPut(groupNameOf(schemaOf(task))) { mutableListOf() }.add(task)
        }
        for ((groupName, groupTasks) in buckets) {
            out += Row.GroupHead(groupName, groupTasks.size)
            for (task in groupTasks) out += Row.Card(task)
        }
        return out
    }

    /**
     * 搜索匹配（对齐 webview taskCard.js 的 matches）：显示名 / schema 名 / 类名 /
     * 模块 / 描述 / 分组名任一命中即匹配。大小写不敏感。
     */
    fun matches(task: TaskInfo, schema: TaskSchema?, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        val needle = q.lowercase()
        val haystack = listOfNotNull(
            task.displayName,
            schema?.displayName,
            task.className,
            task.module,
            schema?.description,
            groupNameOf(schema),
        )
        return haystack.any { it.lowercase().contains(needle) }
    }
}
