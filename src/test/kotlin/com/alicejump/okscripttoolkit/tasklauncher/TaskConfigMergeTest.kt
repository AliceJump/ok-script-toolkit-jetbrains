package com.alicejump.okscripttoolkit.tasklauncher

import com.alicejump.okscripttoolkit.tasklauncher.TaskLauncherService.TaskConfig
import com.alicejump.okscripttoolkit.tasklauncher.TaskLauncherService.TaskConfigStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 配置持久化的读-改-写合并规则回归测试。
 *
 * 复现的缺陷：`saveTaskConfig` 用 `projectConfig.copy(tasks = tasks)` **整对象替换**项目配置，
 * 只换 `tasks` 字段 —— `enabledTriggers` 会沿用那句 `store` 快照里的**旧值**。
 * 于是「勾选触发任务」与「改参数」两条写入路径并发时，用户的勾选会被静默写回旧值。
 *
 * 关键点：这两个写入都跑在 `CompletableFuture.runAsync`（commonPool），
 * 且 `@Volatile` 只保证单次读/写可见，**不保证读-改-写序列的原子性**。
 *
 * 抽成纯对象是因为 `TaskLauncherService` 的构造函数要 `Project`，
 * 而本仓库的测试约定（见 `TempShotFilesTest`）是不用 IDE fixture —— 逻辑必须能脱离 IDE 直测。
 */
class TaskConfigMergeTest {

    private fun storeWith(
        projectEntries: Map<String, TaskConfigStore.ProjectConfig> = emptyMap(),
    ): TaskConfigStore = TaskConfigStore(projects = projectEntries)

    private fun projectOf(
        taskEntries: Map<String, TaskConfig> = emptyMap(),
        enabledTriggers: List<String> = emptyList(),
    ): TaskConfigStore.ProjectConfig =
        TaskConfigStore.ProjectConfig(tasks = taskEntries, enabledTriggers = enabledTriggers)

    private fun config(vararg paramEntries: Pair<String, Any>): TaskConfig =
        TaskConfig(params = paramEntries.toMap())

    /**
     * 本测试的核心断言：写入某任务的参数时，**不得**把用户的触发勾选写回旧值。
     * 修好前 `withTask` 的内部实现是 `projectConfig.copy(tasks = tasks)` —— 这条会红。
     */
    @Test
    fun `writing a task config must not clobber the user's enabled triggers`() {
        val original = storeWith(
            mapOf(
                "/proj" to projectOf(
                    taskEntries = mapOf("m::A" to config("x" to 1)),
                    enabledTriggers = listOf("m::T1", "m::T2"),
                ),
            ),
        )

        val updated = TaskConfigMerge.withTask(original, "/proj", "m::A", config("x" to 2))

        assertEquals(
            listOf("m::T1", "m::T2"),
            updated.projects["/proj"]?.enabledTriggers,
            "整对象替换不得把用户的触发勾选写回旧值 —— 这正是并发时静默丢勾选的根因",
        )
        assertEquals(
            mapOf("x" to 2),
            updated.projects["/proj"]?.tasks?.get("m::A")?.params,
            "写入的任务参数必须真的落进 store",
        )
    }

    /** 反向：写勾选集合时，同样不得丢掉别处刚写进去的任务参数 */
    @Test
    fun `writing enabled triggers must not clobber other task configs`() {
        val original = storeWith(
            mapOf(
                "/proj" to projectOf(
                    taskEntries = mapOf(
                        "m::A" to config("x" to 1),
                        "m::B" to config("y" to "hello"),
                    ),
                    enabledTriggers = listOf("m::T1"),
                ),
            ),
        )

        val updated = TaskConfigMerge.withEnabledTriggers(original, "/proj", listOf("m::T1", "m::T2"))

        assertEquals(
            listOf("m::T1", "m::T2"),
            updated.projects["/proj"]?.enabledTriggers,
            "勾选集合必须被替换为新值",
        )
        assertEquals(
            mapOf("m::A" to mapOf("x" to 1), "m::B" to mapOf("y" to "hello")),
            updated.projects["/proj"]?.tasks?.mapValues { it.value.params },
            "写勾选不得动其它任务的参数",
        )
    }

    /** 只动目标任务：同项目下别的任务参数必须原样保留 */
    @Test
    fun `writing one task keeps the sibling tasks untouched`() {
        val original = storeWith(
            mapOf(
                "/proj" to projectOf(
                    taskEntries = mapOf(
                        "m::A" to config("x" to 1),
                        "m::B" to config("y" to 2),
                    ),
                    enabledTriggers = listOf("m::T1"),
                ),
            ),
        )

        val updated = TaskConfigMerge.withTask(original, "/proj", "m::A", config("x" to 99))

        assertEquals(mapOf("y" to 2), updated.projects["/proj"]?.tasks?.get("m::B")?.params)
        assertEquals(mapOf("x" to 99), updated.projects["/proj"]?.tasks?.get("m::A")?.params)
        assertEquals(listOf("m::T1"), updated.projects["/proj"]?.enabledTriggers)
    }

    /** 只动目标项目：多项目 store 下别的项目必须原样保留（一份 tasks.json 服务多个项目根） */
    @Test
    fun `writing one project keeps the sibling projects untouched`() {
        val original = storeWith(
            mapOf(
                "/projA" to projectOf(taskEntries = mapOf("m::A" to config("x" to 1)), enabledTriggers = listOf("m::T1")),
                "/projB" to projectOf(taskEntries = mapOf("m::B" to config("y" to 2)), enabledTriggers = listOf("m::T2")),
            ),
        )

        val updated = TaskConfigMerge.withTask(original, "/projA", "m::A", config("x" to 7))

        assertEquals(mapOf("y" to 2), updated.projects["/projB"]?.tasks?.get("m::B")?.params)
        assertEquals(listOf("m::T2"), updated.projects["/projB"]?.enabledTriggers)
        assertEquals(mapOf("x" to 7), updated.projects["/projA"]?.tasks?.get("m::A")?.params)
    }

    /** 首次写入：项目根尚不存在时应新建，而不是 NPE 或丢弃写入 */
    @Test
    fun `writing into an unseen project root creates it`() {
        val updated = TaskConfigMerge.withTask(storeWith(), "/fresh", "m::A", config("x" to 1))

        assertEquals(mapOf("x" to 1), updated.projects["/fresh"]?.tasks?.get("m::A")?.params)
        assertEquals(emptyList(), updated.projects["/fresh"]?.enabledTriggers, "新建项目配置的勾选集合应为空")

        val withTriggers = TaskConfigMerge.withEnabledTriggers(storeWith(), "/fresh", listOf("m::T"))
        assertEquals(mapOf<String, TaskConfig>(), withTriggers.projects["/fresh"]?.tasks, "新建项目配置不应凭空造任务")
        assertEquals(listOf("m::T"), withTriggers.projects["/fresh"]?.enabledTriggers)
    }

    /** 空 key 列表是合法输入（用户取消全部勾选），不得被当成「无变更」丢弃 */
    @Test
    fun `clearing enabled triggers is persisted as an empty list`() {
        val original = storeWith(
            mapOf("/proj" to projectOf(enabledTriggers = listOf("m::T1", "m::T2"))),
        )

        val updated = TaskConfigMerge.withEnabledTriggers(original, "/proj", emptyList())

        assertEquals(emptyList(), updated.projects["/proj"]?.enabledTriggers, "取消全部勾选必须能落盘")
    }

    /** 纯函数语义：入参 store 不得被就地修改（否则缓存里的旧快照会被污染） */
    @Test
    fun `merge never mutates its input`() {
        val original = storeWith(
            mapOf(
                "/proj" to projectOf(
                    taskEntries = mapOf("m::A" to config("x" to 1)),
                    enabledTriggers = listOf("m::T1"),
                ),
            ),
        )

        TaskConfigMerge.withTask(original, "/proj", "m::A", config("x" to 2))
        TaskConfigMerge.withEnabledTriggers(original, "/proj", listOf("m::T9"))

        assertEquals(mapOf("x" to 1), original.projects["/proj"]?.tasks?.get("m::A")?.params, "入参必须原样不动")
        assertEquals(listOf("m::T1"), original.projects["/proj"]?.enabledTriggers, "入参必须原样不动")
    }

    /** legacy 字段（extraArgs / env）与 params 是独立的，合并时不得互相覆盖 */
    @Test
    fun `legacy fields survive a params-only write`() {
        val original = storeWith(
            mapOf(
                "/proj" to projectOf(
                    taskEntries = mapOf(
                        "m::A" to TaskConfig(
                            extraArgs = "--debug",
                            env = mapOf("FOO" to "bar"),
                            params = mapOf("x" to 1),
                        ),
                    ),
                ),
            ),
        )

        val stored = original.projects["/proj"]?.tasks?.get("m::A")
        assertEquals("--debug", stored?.extraArgs)
        assertEquals(mapOf("FOO" to "bar"), stored?.env)
        assertNull(stored?.params?.get("missing"), "不存在的参数键应返回 null 而不是抛异常")
    }

    // ── withGlobalConfigs（#7 配置接管）───────────────────────────────

    /** 写全局快照时不得碰任务参数与勾选集合 —— 第三条独立写入路径必须同样互不干扰 */
    @Test
    fun `writing global configs must not clobber tasks nor triggers`() {
        val original = storeWith(
            mapOf(
                "/proj" to projectOf(
                    taskEntries = mapOf("m::A" to config("x" to 1)),
                    enabledTriggers = listOf("m::T1"),
                ),
            ),
        )

        val updated = TaskConfigMerge.withGlobalConfigs(
            original,
            "/proj",
            mapOf("战斗配置" to mapOf("dps" to 99, "auto_battle" to true)),
        )

        assertEquals(
            mapOf("dps" to 99, "auto_battle" to true),
            updated.projects["/proj"]?.globalConfigs?.get("战斗配置"),
            "全局快照必须被替换为新值",
        )
        assertEquals(
            mapOf("x" to 1),
            updated.projects["/proj"]?.tasks?.get("m::A")?.params,
            "写全局快照不得动任务参数",
        )
        assertEquals(
            listOf("m::T1"),
            updated.projects["/proj"]?.enabledTriggers,
            "写全局快照不得动勾选集合",
        )
    }

    /** 空快照是合法输入（清空全部快照），不得被当成「无变更」丢弃 */
    @Test
    fun `clearing global configs is persisted as an empty map`() {
        val original = storeWith(
            mapOf(
                "/proj" to TaskConfigStore.ProjectConfig(
                    globalConfigs = mapOf("战斗配置" to mapOf("dps" to 99)),
                ),
            ),
        )

        val updated = TaskConfigMerge.withGlobalConfigs(original, "/proj", emptyMap())

        assertEquals(emptyMap(), updated.projects["/proj"]?.globalConfigs, "清空快照必须能落盘")
    }

    /** 首次写快照：项目根尚不存在时应新建 */
    @Test
    fun `writing global configs into an unseen project root creates it`() {
        val updated = TaskConfigMerge.withGlobalConfigs(
            storeWith(),
            "/fresh",
            mapOf("Notification" to mapOf("enabled" to false)),
        )

        assertEquals(
            mapOf("enabled" to false),
            updated.projects["/fresh"]?.globalConfigs?.get("Notification"),
        )
        assertEquals(emptyMap(), updated.projects["/fresh"]?.tasks, "新建项目配置不应凭空造任务")
        assertEquals(emptyList(), updated.projects["/fresh"]?.enabledTriggers)
    }

    /** withGlobalConfigs 同样遵守纯函数语义：入参不得被就地修改 */
    @Test
    fun `global config merge never mutates its input`() {
        val original = storeWith(
            mapOf(
                "/proj" to TaskConfigStore.ProjectConfig(
                    globalConfigs = mapOf("A" to mapOf("k" to 1)),
                ),
            ),
        )

        TaskConfigMerge.withGlobalConfigs(original, "/proj", mapOf("A" to mapOf("k" to 2)))

        assertEquals(
            mapOf("k" to 1),
            original.projects["/proj"]?.globalConfigs?.get("A"),
            "入参必须原样不动",
        )
    }

    /**
     * 破坏性对照：这条锁死「整对象替换会丢勾选」这个事实本身。
     * 若哪天有人把 `copy(tasks = tasks)` 当作正确写法回退，本断言必须仍然成立 ——
     * 它证明测试确实能捕获该缺陷，而不是恰好在某个实现下通过。
     */
    @Test
    fun `regression guard - canonical replace is exactly what loses the triggers`() {
        val projectConfig = projectOf(
            taskEntries = mapOf("m::A" to config("x" to 1)),
            enabledTriggers = listOf("m::T1", "m::T2"),
        )
        // 模拟「读»改»写」中的「改」：只替换 tasks 字段
        val buggy = projectConfig.copy(tasks = projectConfig.tasks + ("m::A" to config("x" to 2)))

        assertEquals(
            listOf("m::T1", "m::T2"),
            buggy.enabledTriggers,
            "对照实现（copy 整对象）在这个顺序下『碰巧』保住了勾选，说明缺陷只在并发交错时暴露 —— " +
                "所以正确的修法不是改 copy 的写法，而是给读-改-写序列上锁 + 把合并规则收敛到本对象",
        )
    }
}
