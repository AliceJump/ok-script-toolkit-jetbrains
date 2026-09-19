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
}
