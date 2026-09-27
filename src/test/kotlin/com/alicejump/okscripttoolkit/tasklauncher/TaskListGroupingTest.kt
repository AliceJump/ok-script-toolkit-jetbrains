package com.alicejump.okscripttoolkit.tasklauncher

import com.alicejump.okscripttoolkit.tasklauncher.TaskLauncherService.TaskInfo
import com.alicejump.okscripttoolkit.tasklauncher.TaskLauncherService.TaskSchema
import com.alicejump.okscripttoolkit.tasklauncher.TaskListGrouping.Row.Card
import com.alicejump.okscripttoolkit.tasklauncher.TaskListGrouping.Row.GroupHead
import com.alicejump.okscripttoolkit.tasklauncher.TaskListGrouping.Row.KindHead
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 任务卡列表编排规则的回归保护（对齐 VS Code 侧 media/console/taskCard.js 的 renderTasks）。
 *
 * 这些都是「看起来像 UI、其实是数据约定」的规则，错一点界面就静默地少任务或错组：
 * 1. 触发任务组在前、一次性任务组在后，组内保持 schema 注册序；
 * 2. 一次性任务按 schema.groupName 二次分组（首次出现顺序），空组名归「未分组」；
 * 3. 组头始终渲染并携带过滤后的计数（空组显示 0 项）；
 * 4. 搜索只留命中任务，匹配范围含分组名；空白搜索不过滤。
 */
class TaskListGroupingTest {

    private fun task(
        className: String,
        module: String = "src.tasks",
        kind: String? = null,
        displayName: String = className,
    ): TaskInfo = TaskInfo(module = module, className = className, displayName = displayName, kind = kind)

    private fun schema(
        kind: String? = null,
        groupName: String? = null,
        displayName: String? = null,
        description: String? = null,
    ): TaskSchema = TaskSchema(kind = kind, groupName = groupName, displayName = displayName, description = description)

    /** 测试里 kind 直接由 TaskInfo.kind 给出（与工厂 taskKindOf 的「解析优先、schema 兜底」一致） */
    private fun kindOf(t: TaskInfo): String = t.kind ?: TaskRowState.ONETIME

    // ── 分组与排序 ───────────────────────────────────────────────────

    @Test
    fun `triggers come first, then onetime groups in first-encounter order`() {
        val tasks = listOf(
            task("OneA", kind = "onetime"),
            task("TrigA", kind = "trigger"),
            task("OneB", kind = "onetime"),
            task("TrigB", kind = "trigger"),
        )
        val schemas = mapOf(
            "src.tasks::OneA" to schema(groupName = "战斗"),
            "src.tasks::OneB" to schema(groupName = "采集"),
            "src.tasks::TrigA" to schema(),
            "src.tasks::TrigB" to schema(),
        )

        val rows = TaskListGrouping.rows(tasks, { schemas[TaskSchemaMerge.keyOf(it)] }, ::kindOf, "")

        // 触发组头 → 触发卡（注册序）→ 一次性组头 → 业务组头 → 组内卡
        assertEquals(TaskRowState.TRIGGER, (rows[0] as KindHead).kind)
        assertEquals(2, rows[0].count)
        assertEquals("TrigA", (rows[1] as Card).task.className)
        assertEquals("TrigB", (rows[2] as Card).task.className)
        assertEquals(TaskRowState.ONETIME, (rows[3] as KindHead).kind)
        val groupHeads = rows.filterIsInstance<GroupHead>()
        assertEquals(listOf("战斗", "采集"), groupHeads.map { it.groupName }, "业务分组按首次出现顺序")
        assertEquals(1, groupHeads[0].count)
    }

    @Test
    fun `blank group name falls into the ungrouped bucket`() {
        val tasks = listOf(task("Named", kind = "onetime"), task("Plain", kind = "onetime"))
        val schemas = mapOf(
            "src.tasks::Named" to schema(groupName = "战斗"),
            "src.tasks::Plain" to schema(groupName = ""),
        )

        val rows = TaskListGrouping.rows(tasks, { schemas[TaskSchemaMerge.keyOf(it)] }, ::kindOf, "")

        val groups = rows.filterIsInstance<GroupHead>().map { it.groupName }
        assertEquals(listOf("战斗", null), groups, "空组名与缺 groupName 都归未分组（null）")
    }

    @Test
    fun `kind heads always render with counts even when a kind is empty`() {
        val tasks = listOf(task("Only", kind = "onetime"))

        val rows = TaskListGrouping.rows(tasks, { null }, ::kindOf, "")

        assertEquals(listOf<KindHead>(KindHead(TaskRowState.TRIGGER, 0), KindHead(TaskRowState.ONETIME, 1)), rows.filterIsInstance<KindHead>())
        // 业务分组头只在有一次性任务时渲染
        assertEquals(1, rows.filterIsInstance<GroupHead>().size)
    }

    @Test
    fun `cards keep the schema registration order inside each group`() {
        val tasks = listOf(
            task("B", kind = "onetime"),
            task("A", kind = "onetime"),
            task("C", kind = "onetime"),
        )

        val rows = TaskListGrouping.rows(tasks, { null }, ::kindOf, "")

        assertEquals(listOf("B", "A", "C"), rows.filterIsInstance<Card>().map { it.task.className })
    }

    // ── 搜索 ─────────────────────────────────────────────────────────

    @Test
    fun `search filters cards and head counts reflect the filtered list`() {
        val tasks = listOf(
            task("OneA", kind = "onetime"),
            task("OneB", kind = "onetime"),
            task("TrigA", kind = "trigger"),
        )
        val schemas = mapOf(
            "src.tasks::OneA" to schema(groupName = "战斗", displayName = "打怪"),
            "src.tasks::OneB" to schema(groupName = "采集"),
            "src.tasks::TrigA" to schema(),
        )

        val rows = TaskListGrouping.rows(tasks, { schemas[TaskSchemaMerge.keyOf(it)] }, ::kindOf, "战斗")

        assertEquals(listOf("OneA"), rows.filterIsInstance<Card>().map { it.task.className }, "按分组名命中")
        assertEquals(0, rows.filterIsInstance<KindHead>().first().count, "触发组头仍在且计数为 0")
    }

    @Test
    fun `search matches display name, class, module and description`() {
        val schemaOf = { t: TaskInfo ->
            when (t.className) {
                "BySchemaName" -> schema(displayName = "钓鱼大赏")
                "ByDescription" -> schema(description = "自动强化装备")
                else -> null
            }
        }
        val tasks = listOf(
            task("BySchemaName", displayName = "FishingTask"),
            task("ByDescription"),
            task("ModuleHit", module = "src.rare"),
            task("NoHit"),
        )

        assertTrue(TaskListGrouping.matches(tasks[0], schemaOf(tasks[0]), "钓鱼"))
        assertTrue(TaskListGrouping.matches(tasks[1], schemaOf(tasks[1]), "强化"))
        assertTrue(TaskListGrouping.matches(tasks[2], schemaOf(tasks[2]), "rare"))
        assertTrue(TaskListGrouping.matches(tasks[0], schemaOf(tasks[0]), "fishing"), "类名/显示名不区分大小写")
        assertFalse(TaskListGrouping.matches(tasks[3], schemaOf(tasks[3]), "钓鱼"))
        assertTrue(TaskListGrouping.matches(tasks[3], schemaOf(tasks[3]), "  "), "空白搜索不过滤")
    }

    // ── 折叠键（须与 VS Code webview 的 groupCollapseKey 一致）─────────

    @Test
    fun `fold keys match the webview uiState convention`() {
        assertEquals("taskGroupCollapsed::trigger", TaskListGrouping.kindFoldKey(TaskRowState.TRIGGER))
        assertEquals("taskGroupCollapsed::onetime", TaskListGrouping.kindFoldKey(TaskRowState.ONETIME))
        assertEquals("taskGroupCollapsed::onetime::战斗", TaskListGrouping.groupFoldKey("战斗"))
        assertEquals(
            "taskGroupCollapsed::onetime::__ungrouped__",
            TaskListGrouping.groupFoldKey(null),
            "未分组桶与 webview 的 __ungrouped__ 段一致",
        )
    }
}
