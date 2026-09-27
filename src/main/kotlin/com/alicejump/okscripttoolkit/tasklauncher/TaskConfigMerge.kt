package com.alicejump.okscripttoolkit.tasklauncher

import com.alicejump.okscripttoolkit.tasklauncher.TaskLauncherService.TaskConfig
import com.alicejump.okscripttoolkit.tasklauncher.TaskLauncherService.TaskConfigStore

/**
 * 任务配置的「读-改-写」合并规则。
 *
 * 抽成不依赖 IDE / Swing 的纯对象，因为这里藏着一条**看起来像赋值、其实是数据约定**的规则，
 * 而且它一旦错就极其隐蔽：用户的勾选只是"偶尔不见了"，不像崩溃那样留痕迹。
 *
 * 背景：`tasks.json` 里一个项目根下有两块独立的数据 ——
 * 1. `tasks`：每个任务（`module::Class`）的参数，参数面板改一下就写一次；
 * 2. `enabledTriggers`：用户勾选的触发任务集合，勾一下写一次。
 *
 * 早先 [TaskLauncherService.saveTaskConfig] 的实现是
 * `projectConfig.copy(tasks = tasks)` —— **只换 `tasks` 字段**的整对象替换。
 * 单线程顺序执行时它碰巧是对的（新任务与旧勾选都来自同一份快照），但只要两条写入路径
 * 交错就会出事：
 *
 * ```
 * 线程 A（保存参数）              线程 B（保存勾选）
 * store = load()  // 勾选 = [T1]
 *                                  store = load()  // 勾选 = [T1]
 *                                  save(勾选 = [T1, T2])
 * save(copy(tasks = ...))          // A 的快照里勾选仍是 [T1]
 * → 落盘勾选 = [T1]，用户刚勾的 T2 没了
 * ```
 *
 * 两个写入都跑在 `CompletableFuture.runAsync`（commonPool），而 `configStoreCache` 上的
 * `@Volatile` **只保证单次读/写可见，不保证读-改-写序列的原子性**。
 *
 * 所以这里有两条配套的修法，缺一不可：
 * 1. 合并规则收敛到本对象：写 `tasks` 时不碰 `enabledTriggers`，反之亦然（**语义上互相独立**）；
 * 2. `TaskLauncherService` 的读写序列整体上锁（**时序上互不交错**）。
 *
 * 本对象只负责 1，因此可以脱离 IDE 直测；锁由调用方持有。
 */
internal object TaskConfigMerge {

    /**
     * 在最新 store 上物化探针字段。首建/重探针的取值也必须在写入锁内判定。
     */
    fun withMaterializedTaskParams(
        store: TaskConfigStore,
        projectRoot: String,
        schemas: Map<String, TaskLauncherService.TaskSchema>,
    ): Pair<TaskConfigStore, Int> {
        val projectConfig = store.projects[projectRoot] ?: TaskConfigStore.ProjectConfig()
        val tasks = projectConfig.tasks.toMutableMap()
        var added = 0
        for ((taskKey, schema) in schemas) {
            if (schema.broken || schema.fields.isEmpty()) continue
            val existing = tasks[taskKey] ?: TaskConfig()
            val (params, taskAdded) = GlobalSnapshotRules.materialize(existing.params.orEmpty(), schema.fields)
            if (taskAdded == 0) continue
            // 探针 JSON 允许显式 null；TaskConfig 的旧签名使用 Any，运行时 Map 可保留 null。
            @Suppress("UNCHECKED_CAST")
            val snapshot = params as Map<String, Any>
            tasks[taskKey] = existing.copy(params = snapshot)
            added += taskAdded
        }
        if (added == 0) return store to 0
        val projects = store.projects.toMutableMap()
        projects[projectRoot] = projectConfig.copy(tasks = tasks)
        return store.copy(projects = projects) to added
    }

    /** 全局配置按最新 store 物化，以免晚到的探针覆盖用户在编辑器里保存的值。 */
    fun withMaterializedGlobalConfigs(
        store: TaskConfigStore,
        projectRoot: String,
        groups: List<TaskLauncherService.GlobalConfigGroup>,
    ): Pair<TaskConfigStore, Int> {
        val projectConfig = store.projects[projectRoot] ?: TaskConfigStore.ProjectConfig()
        val snapshots = projectConfig.globalConfigs.toMutableMap()
        var added = 0
        for (group in groups) {
            val (snapshot, count) = GlobalSnapshotRules.materialize(snapshots[group.name].orEmpty(), group.fields)
            if (count == 0) continue
            snapshots[group.name] = snapshot
            added += count
        }
        if (added == 0) return store to 0
        val projects = store.projects.toMutableMap()
        projects[projectRoot] = projectConfig.copy(globalConfigs = snapshots)
        return store.copy(projects = projects) to added
    }

    /** 用户编辑单个全局组时不覆盖其他组。 */
    fun withGlobalConfigGroup(
        store: TaskConfigStore,
        projectRoot: String,
        groupName: String,
        values: Map<String, Any?>,
    ): TaskConfigStore {
        val projectConfig = store.projects[projectRoot] ?: TaskConfigStore.ProjectConfig()
        val snapshots = projectConfig.globalConfigs.toMutableMap()
        snapshots[groupName] = values
        val projects = store.projects.toMutableMap()
        projects[projectRoot] = projectConfig.copy(globalConfigs = snapshots)
        return store.copy(projects = projects)
    }

    /** 表单只提交编辑过的键，合并进最新快照并保留其余参数与旧版运行字段。 */
    fun withUserTaskSnapshot(
        store: TaskConfigStore,
        projectRoot: String,
        taskKey: String,
        config: TaskConfig,
    ): TaskConfigStore {
        val latest = store.projects[projectRoot]?.tasks?.get(taskKey)
        val params = LinkedHashMap<String, Any>(latest?.params.orEmpty())
        params.putAll(config.params.orEmpty())
        val merged = (latest ?: config).copy(params = params.ifEmpty { null })
        return withTask(store, projectRoot, taskKey, merged)
    }

    /**
     * 写入某个任务的配置，**保留**该项目下其它任务的参数与用户的勾选集合。
     *
     * 项目根不存在时自动新建（首次改参数必然遇到）。
     */
    fun withTask(
        store: TaskConfigStore,
        projectRoot: String,
        taskKey: String,
        config: TaskConfig,
    ): TaskConfigStore {
        val projects = store.projects.toMutableMap()
        val projectConfig = projects[projectRoot] ?: TaskConfigStore.ProjectConfig()
        val tasks = projectConfig.tasks.toMutableMap()
        tasks[taskKey] = config
        // 只替换 tasks：enabledTriggers 必须原样带过来，不能取"新构造一份"的默认空列表
        projects[projectRoot] = projectConfig.copy(tasks = tasks)
        return store.copy(projects = projects)
    }

    /**
     * 替换某个项目根的勾选集合，**保留**该项目下所有任务的参数。
     *
     * `keys` 允许为空列表（用户取消全部勾选），这不是"无变更"。
     */
    fun withEnabledTriggers(
        store: TaskConfigStore,
        projectRoot: String,
        keys: List<String>,
    ): TaskConfigStore {
        val projects = store.projects.toMutableMap()
        val projectConfig = projects[projectRoot] ?: TaskConfigStore.ProjectConfig()
        projects[projectRoot] = projectConfig.copy(enabledTriggers = keys)
        return store.copy(projects = projects)
    }

    /**
     * 替换某个项目根的全局配置快照，**保留**任务参数与勾选集合。
     *
     * 三条写入路径（参数 / 勾选 / 全局快照）语义互相独立，各自只换自己的字段 ——
     * 物化与保存由调用方（[GlobalSnapshotRules] + TaskLauncherService.saveGlobalConfigs）负责。
     * `snapshots` 允许为空映射（用户清空了全部快照），这不是"无变更"。
     */
    fun withGlobalConfigs(
        store: TaskConfigStore,
        projectRoot: String,
        snapshots: Map<String, Map<String, Any?>>,
    ): TaskConfigStore {
        val projects = store.projects.toMutableMap()
        val projectConfig = projects[projectRoot] ?: TaskConfigStore.ProjectConfig()
        projects[projectRoot] = projectConfig.copy(globalConfigs = snapshots)
        return store.copy(projects = projects)
    }

    /**
     * 写入单个 UI 折叠键，保留任务参数、勾选集合与全局快照（第四条独立写入路径，
     * 与前三条同样互不干扰）。已有键被替换，其余键原样保留。
     */
    fun withUiState(
        store: TaskConfigStore,
        projectRoot: String,
        key: String,
        value: Boolean,
    ): TaskConfigStore {
        val projects = store.projects.toMutableMap()
        val projectConfig = projects[projectRoot] ?: TaskConfigStore.ProjectConfig()
        projects[projectRoot] = projectConfig.copy(uiState = projectConfig.uiState + (key to value))
        return store.copy(projects = projects)
    }
}
