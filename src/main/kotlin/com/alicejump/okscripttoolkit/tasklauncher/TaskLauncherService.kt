package com.alicejump.okscripttoolkit.tasklauncher

import com.alicejump.okscripttoolkit.core.PythonScriptLocator
import com.alicejump.okscripttoolkit.core.PythonScriptRunner
import com.alicejump.okscripttoolkit.core.RunDir
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import com.alicejump.okscripttoolkit.core.forEachField

/**
 * 任务启动器服务，对应 VS Code 版本的 taskLauncher.ts。
 * 负责解析任务列表、探测任务 schema、运行任务。
 */
class TaskLauncherService(private val project: Project) {

    companion object {
        private val LOG = Logger.getInstance(TaskLauncherService::class.java)
        private val objectMapper = ObjectMapper()

        private const val PARSE_CONFIG_SCRIPT = "parse_config_tasks.py"
        private const val PROBE_SCHEMA_SCRIPT = "probe_task_schemas.py"

        /** 常驻执行器：单进程连接 + 多触发任务轮询，取代旧的 run_task.py 单任务启动 */
        private const val EXECUTOR_SCRIPT = "run_executor.py"
        private const val TASKS_CONFIG_FILE = ".idea/ok-script-toolkit-tasks.json"
        private const val SCHEMA_CACHE_FILE = ".idea/ok-script-toolkit-schema.json"
    }

    private val pythonRunner = PythonScriptRunner(project)

    // ── Data classes ──────────────────────────────────────────────────

    data class TaskInfo(
        val module: String,
        @JsonProperty("class") val className: String,
        val displayName: String = className,
        /**
         * 任务类型：触发任务走「勾选启用 → 入列轮询」，一次性任务走「运行 → 入队执行一次」。
         * 由 parse_config_tasks.py 直接给出，不依赖 schema 采集完成。
         */
        val kind: String? = null,
    )

    data class TaskListResult(
        val ok: Boolean,
        val error: String? = null,
        val tasks: List<TaskInfo> = emptyList(),
        val configModule: String = "src.config",
    )

    data class TaskParamField(
        val key: String,
        val displayKey: String? = null,
        val default: Any? = null,
        val value: Any? = null,
        /** 字段类型描述（schema 原样透传给前端渲染器，不做校验） */
        val type: Map<String, Any>? = null,
        val desc: String? = null,
        val displayDesc: String? = null,
    )

    data class TaskSchema(
        val fields: List<TaskParamField> = emptyList(),
        val broken: Boolean = false,
        val error: String? = null,
        val displayName: String? = null,
        val description: String? = null,
        val kind: String? = null,
        val configGroups: Map<String, List<String>>? = null,
        val groupLabels: Map<String, String>? = null,
        val groupSelector: String? = null,
        val locale: String? = null,
    )

    /**
     * 全局配置组（框架 GlobalConfig 与项目自建 store 共用同一 payload 形状，
     * fields 与任务字段同构，[TaskParamField] 直接复用）。
     * 对应探针 globalConfigGroups 输出，见 python/probe_task_schemas.py
     * collect_global_config_groups / collect_project_store_groups。
     */
    data class GlobalConfigGroup(
        val name: String,
        val displayName: String? = null,
        val description: String? = null,
        val fields: List<TaskParamField> = emptyList(),
        /** framework（框架 GlobalConfig）| project_store（项目自建聚合 store） */
        val source: String? = null,
    )

    data class SchemaProbeResult(
        val ok: Boolean,
        val error: String? = null,
        val schemas: Map<String, TaskSchema>? = null,
        val total: Int = 0,
        val projectDir: String? = null,
        val locale: String? = null,
        val configModule: String? = null,
        /** 全局配置组快照源数据（#7 配置接管）；采集失败为空列表不影响任务 schema */
        val globalConfigGroups: List<GlobalConfigGroup> = emptyList(),
    )

    data class TaskConfig(
        val extraArgs: String? = null,
        val env: Map<String, String>? = null,
        val params: Map<String, Any>? = null,
    )

    data class TaskConfigStore(
        val projects: Map<String, ProjectConfig> = emptyMap(),
    ) {
        data class ProjectConfig(
            val tasks: Map<String, TaskConfig> = emptyMap(),
            /** 已勾选「启用」的触发任务 key（module::Class），重开工具窗 / IDE 自动入列 */
            val enabledTriggers: List<String> = emptyList(),
            /**
             * 全局配置快照：{组名: {配置键: 值}}（#7 配置接管）。
             * 执行器启动经 OK_TOOLKIT_GCONFIG 全量注入、运行中经 gparams 命令推送；
             * 物化规则见 [GlobalSnapshotRules]（首建继承当前值、重探针新键取默认、孤儿键保留）。
             */
            val globalConfigs: Map<String, Map<String, Any?>> = emptyMap(),
        )
    }

    // ── Path helpers ──────────────────────────────────────────────────

    /**
     * **工作区根**（`project.basePath`），用作 `.idea/` 数据文件（任务配置、schema 缓存）的落点。
     *
     * 与父仓 `taskLauncher.ts` 的 `dataFile()` 对齐 —— 那边明确用
     * `workspaceFolders[0]`，**与 `okScriptProjectPath` 设置无关**。
     *
     * ⚠️ 这**不是** ok-script 项目根。要跑脚本请用 [ProjectDirResolution] 解析出的目录，
     * 别拿这个函数的结果去跑 `parse_config_tasks.py`（曾经就是这么错的）。
     */
    fun getWorkspaceRoot(): String {
        return project.basePath ?: throw IllegalStateException("Project base path is null")
    }

    fun getPythonScriptDir(): String =
        PythonScriptLocator.findScriptDir()

    // ── Task list ─────────────────────────────────────────────────────

    /**
     * 解析任务注册表。
     *
     * [projectDir] 必须是 [ProjectDirResolution] 解析出的 **ok-script 项目根**，
     * 不能是工作区根 —— 两者在设置了 `okScriptProjectPath` 时会不同，
     * 拿错就会去别的目录找 `config.py`（父仓 `resolveProjectContext()` 同样是设置优先）。
     */
    fun parseConfigTasks(
        pythonPath: String,
        locale: String = "zh_CN",
        projectDir: String = getWorkspaceRoot(),
    ): TaskListResult {
        val scriptPath = Paths.get(getPythonScriptDir(), PARSE_CONFIG_SCRIPT).toString()
        if (!File(scriptPath).exists()) {
            return TaskListResult(ok = false, error = "Script not found: $scriptPath")
        }
        return try {
            val result = pythonRunner.runSync(
                pythonPath = pythonPath,
                scriptPath = scriptPath,
                args = listOf(projectDir),
                workingDir = File(projectDir),
                timeoutMs = 15000,
            )
            if (result.exitCode != 0) {
                return TaskListResult(ok = false, error = result.stderr.ifBlank { "Script failed with exit code ${result.exitCode}" })
            }
            val jsonOutput = pythonRunner.parseJsonFromStdout(result.stdout)
                ?: return TaskListResult(ok = false, error = "No JSON output from script")
            val parsed = objectMapper.readTree(jsonOutput)
            val ok = parsed.get("ok")?.asBoolean() ?: false
            if (!ok) {
                return TaskListResult(ok = false, error = parsed.get("error")?.asText(null) ?: "Unknown error")
            }
            val configModule = parsed.get("config_module")?.asText(null) ?: "src.config"
            val tasks = mutableListOf<TaskInfo>()
            parsed.get("onetime")?.forEach { node ->
                tasks.add(
                    TaskInfo(
                        module = node.get("module").asText(),
                        className = node.get("class").asText(),
                        displayName = node.get("name")?.asText(null) ?: node.get("class").asText(),
                        kind = "onetime",
                    ),
                )
            }
            parsed.get("trigger")?.forEach { node ->
                tasks.add(
                    TaskInfo(
                        module = node.get("module").asText(),
                        className = node.get("class").asText(),
                        displayName = node.get("name")?.asText(null) ?: node.get("class").asText(),
                        kind = "trigger",
                    ),
                )
            }
            TaskListResult(ok = true, tasks = tasks, configModule = configModule)
        } catch (e: Exception) {
            LOG.error("Failed to parse config tasks", e)
            TaskListResult(ok = false, error = e.message)
        }
    }

    // ── Schema probing ────────────────────────────────────────────────

    /** 采集参数 schema。[projectDir] 同 [parseConfigTasks]，必须是 ok-script 项目根。 */
    fun probeTaskSchemas(
        pythonPath: String,
        locale: String = "zh_CN",
        poDirectory: String = "i18n",
        projectDir: String = getWorkspaceRoot(),
    ): SchemaProbeResult {
        val scriptPath = Paths.get(getPythonScriptDir(), PROBE_SCHEMA_SCRIPT).toString()
        if (!File(scriptPath).exists()) {
            return SchemaProbeResult(ok = false, error = "Script not found: $scriptPath")
        }
        return try {
            val result = pythonRunner.runSync(
                pythonPath = pythonPath,
                scriptPath = scriptPath,
                args = listOf(projectDir, locale, poDirectory),
                workingDir = File(projectDir),
                timeoutMs = 120000,
                // 探针要用它算 multiAccount.storePath（账号覆盖文件的落点）。不传的话
                // 探针会退回 VS Code 的历史默认值 `.vscode/...`，JetBrains 侧就会拿到
                // 一个既不存在也永不被读写的路径。见 RunDir。
                env = mapOf(RunDir.ENV to RunDir.forProject(projectDir)),
            )
            if (result.exitCode != 0) {
                return SchemaProbeResult(ok = false, error = result.stderr.ifBlank { "Script failed with exit code ${result.exitCode}" })
            }
            val jsonOutput = pythonRunner.parseJsonFromStdout(result.stdout)
                ?: return SchemaProbeResult(ok = false, error = "No JSON output from script")
            val parsed = objectMapper.readTree(jsonOutput)
            val ok = parsed.get("ok")?.asBoolean() ?: false
            if (!ok) {
                return SchemaProbeResult(ok = false, error = parsed.get("error")?.asText(null) ?: "Unknown error")
            }
            val total = parsed.get("total")?.asInt() ?: 0
            val configModule = parsed.get("config_module")?.asText(null)
            val schemas = parseSchemas(parsed)
            SchemaProbeResult(
                ok = true,
                schemas = schemas,
                total = total,
                projectDir = projectDir,
                locale = locale,
                configModule = configModule,
                globalConfigGroups = parseGlobalConfigGroups(parsed),
            )
        } catch (e: Exception) {
            LOG.error("Failed to probe task schemas", e)
            SchemaProbeResult(ok = false, error = e.message)
        }
    }

    private fun parseSchemas(parsed: JsonNode): MutableMap<String, TaskSchema> {
        val schemas = mutableMapOf<String, TaskSchema>()
        parsed.get("schemas")?.forEachField { key, schemaNode ->
            val fields = mutableListOf<TaskParamField>()
            schemaNode.get("fields")?.forEach { fieldNode ->
                fields.add(
                    TaskParamField(
                        key = fieldNode.get("key").asText(),
                        displayKey = fieldNode.get("displayKey")?.asText(null),
                        default = fieldNode.get("default")?.let { objectMapper.convertValue(it, Any::class.java) },
                        value = fieldNode.get("value")?.let { objectMapper.convertValue(it, Any::class.java) },
                        type = fieldNode.get("type")?.takeIf { !it.isNull }?.let {
                            @Suppress("UNCHECKED_CAST")
                            objectMapper.convertValue(it, Map::class.java) as? Map<String, Any>
                        },
                        desc = fieldNode.get("desc")?.asText(null),
                        displayDesc = fieldNode.get("displayDesc")?.asText(null),
                    ),
                )
            }
            schemas[key] = TaskSchema(
                fields = fields,
                broken = schemaNode.get("broken")?.asBoolean() ?: false,
                error = schemaNode.get("error")?.asText(null),
                displayName = schemaNode.get("displayName")?.asText(null),
                description = schemaNode.get("description")?.asText(null),
                kind = schemaNode.get("kind")?.asText(null),
                configGroups = schemaNode.get("configGroups")?.takeIf { !it.isNull }?.let {
                    @Suppress("UNCHECKED_CAST")
                    objectMapper.convertValue(it, Map::class.java) as? Map<String, List<String>>
                },
                groupLabels = schemaNode.get("groupLabels")?.takeIf { !it.isNull }?.let {
                    @Suppress("UNCHECKED_CAST")
                    objectMapper.convertValue(it, Map::class.java) as? Map<String, String>
                },
                groupSelector = schemaNode.get("groupSelector")?.asText(null),
                locale = schemaNode.get("locale")?.asText(null),
            )
        }
        return schemas
    }

    /** 解析探针输出的 globalConfigGroups（缺键 / 非数组时静默为空列表，不影响任务 schema） */
    private fun parseGlobalConfigGroups(parsed: JsonNode): List<GlobalConfigGroup> {
        val groups = mutableListOf<GlobalConfigGroup>()
        parsed.get("globalConfigGroups")?.takeIf { it.isArray }?.forEach { groupNode ->
            val fields = mutableListOf<TaskParamField>()
            groupNode.get("fields")?.forEach { fieldNode ->
                fields.add(
                    TaskParamField(
                        key = fieldNode.get("key").asText(),
                        displayKey = fieldNode.get("displayKey")?.asText(null),
                        default = fieldNode.get("default")?.let { objectMapper.convertValue(it, Any::class.java) },
                        value = fieldNode.get("value")?.let { objectMapper.convertValue(it, Any::class.java) },
                        type = fieldNode.get("type")?.takeIf { !it.isNull }?.let {
                            @Suppress("UNCHECKED_CAST")
                            objectMapper.convertValue(it, Map::class.java) as? Map<String, Any>
                        },
                        desc = fieldNode.get("desc")?.asText(null),
                        displayDesc = fieldNode.get("displayDesc")?.asText(null),
                    ),
                )
            }
            groups.add(
                GlobalConfigGroup(
                    name = groupNode.get("name")?.asText(null) ?: return@forEach,
                    displayName = groupNode.get("displayName")?.asText(null),
                    description = groupNode.get("description")?.asText(null),
                    fields = fields,
                    source = groupNode.get("source")?.asText(null),
                ),
            )
        }
        return groups
    }

    // ── Run command builder ───────────────────────────────────────────

    /**
     * 常驻执行器命令行：单一进程连接游戏并轮询全部已启用的触发任务。
     *
     * 与旧 run_task.py 的差异见 python/run_executor.py 顶部说明 —— 旧路径走
     * `ok.run_task(config, task=<单个任务>)`，框架会把 executor.trigger_tasks
     * 收窄成单个任务并 disable 其余触发任务，因此无法多触发任务串连轮询。
     */
    fun buildExecutorCommand(configModule: String = "src.config"): List<String> {
        return pythonRunner.buildExecutorCommand(
            pythonScriptDir = getPythonScriptDir(),
            configModule = configModule,
        )
    }

    // ── Task config persistence ───────────────────────────────────────

    // getTaskConfig 在参数面板每次渲染/取值时都会被调用（EDT），
    // 缓存整份 store，写入时同步更新，避免每次都读盘
    @Volatile
    private var configStoreCache: TaskConfigStore? = null

    /**
     * 保护 `configStoreCache` 的「读 → 改 → 写 → 回填缓存」整条序列。
     *
     * `@Volatile` 只保证单次读 / 单次写的可见性，**不保证序列的原子性** ——
     * 而 [saveTaskConfig] 与 [saveEnabledTriggers] 都会从 `loadTaskConfigs()` 取快照再整体写回，
     * 且两者分别跑在 `CompletableFuture.runAsync`（commonPool）与 EDT 上，会真并发。
     * 不加锁时后写的一方会用陈旧快照覆盖先写一方刚提交的字段（用户表现为"勾选偶尔丢失"）。
     *
     * 合并规则本身在 [TaskConfigMerge]（纯对象、可直测）；本锁只负责让序列不交错。
     */
    private val storeLock = Any()

    fun loadTaskConfigs(): TaskConfigStore = synchronized(storeLock) { loadTaskConfigsLocked() }

    private fun loadTaskConfigsLocked(): TaskConfigStore {
        configStoreCache?.let { return it }
        val configFile = Paths.get(getWorkspaceRoot(), TASKS_CONFIG_FILE).toFile()
        val store = if (!configFile.exists()) {
            TaskConfigStore()
        } else {
            try {
                parseTaskConfigStore(objectMapper.readTree(configFile))
            } catch (e: Exception) {
                LOG.warn("Failed to load task configs", e)
                TaskConfigStore()
            }
        }
        configStoreCache = store
        return store
    }

    private fun parseTaskConfigStore(node: JsonNode): TaskConfigStore {
        val projects = linkedMapOf<String, TaskConfigStore.ProjectConfig>()
        node.get("projects")?.forEachField { projectDir, projectNode ->
            val tasks = linkedMapOf<String, TaskConfig>()
            projectNode.get("tasks")?.forEachField { taskKey, taskNode ->
                tasks[taskKey] = TaskConfig(
                    extraArgs = taskNode.get("extraArgs")?.asText(null),
                    env = taskNode.get("env")?.takeIf { it.isObject }?.let { envNode ->
                        val env = linkedMapOf<String, String>()
                        // 对齐 VSCode：只保留合法环境变量键
                        envNode.forEachField { k, v ->
                            if (v.isTextual && k.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) env[k] = v.asText()
                        }
                        env
                    },
                    params = taskNode.get("params")?.takeIf { it.isObject }?.let { paramsNode ->
                        // 这里必须强转：Jackson 的 `convertValue(node, Map::class.java)` 只能给出
                        // 擦除后的 `Map<*, *>`，而 TaskConfig.params 的签名是 `Map<String, Any>`。
                        // 注解要贴在**发生强转的那个表达式**上 —— 只写在它外层函数/属性上是盖不住的
                        // （原先 @Suppress 挂在上面的 TaskParamField.type 上，编译告警一直存在）。
                        @Suppress("UNCHECKED_CAST")
                        objectMapper.convertValue(paramsNode, Map::class.java) as? Map<String, Any>
                    },
                )
            }
            projects[projectDir] = TaskConfigStore.ProjectConfig(
                tasks = tasks,
                enabledTriggers = projectNode.get("enabledTriggers")
                    ?.takeIf { it.isArray }
                    ?.mapNotNull { it.asText(null) }
                    ?: emptyList(),
                globalConfigs = parseGlobalConfigsNode(projectNode.get("globalConfigs")),
            )
        }
        return TaskConfigStore(projects = projects)
    }

    fun saveTaskConfigs(store: TaskConfigStore) {
        val configFile = Paths.get(getWorkspaceRoot(), TASKS_CONFIG_FILE).toFile()
        configFile.parentFile?.mkdirs()
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(configFile, store)
        } catch (e: Exception) {
            LOG.error("Failed to save task configs", e)
            throw e
        }
    }

    fun getTaskConfig(taskKey: String): TaskConfig {
        val store = loadTaskConfigs()
        return store.projects[getWorkspaceRoot()]?.tasks?.get(taskKey) ?: TaskConfig()
    }

    fun saveTaskConfig(taskKey: String, config: TaskConfig) {
        synchronized(storeLock) {
            val root = getWorkspaceRoot()
            val updated = TaskConfigMerge.withTask(loadTaskConfigsLocked(), root, taskKey, config)
            saveTaskConfigs(updated)
            configStoreCache = updated
        }
    }

    // ── 触发任务启用集合 ──────────────────────────────────────────────

    /** 已勾选的触发任务 key 列表（module::Class） */
    fun loadEnabledTriggers(): List<String> =
        loadTaskConfigs().projects[getWorkspaceRoot()]?.enabledTriggers ?: emptyList()

    fun saveEnabledTriggers(keys: List<String>) {
        synchronized(storeLock) {
            val root = getWorkspaceRoot()
            val updated = TaskConfigMerge.withEnabledTriggers(loadTaskConfigsLocked(), root, keys)
            saveTaskConfigs(updated)
            configStoreCache = updated
        }
    }

    // ── 全局配置快照（#7 配置接管） ───────────────────────────────────

    /** 当前项目的全局配置快照：{组名: {配置键: 值}} */
    fun loadGlobalConfigs(): Map<String, Map<String, Any?>> =
        loadTaskConfigs().projects[getWorkspaceRoot()]?.globalConfigs ?: emptyMap()

    /** 整体替换当前项目的全局配置快照（tasks 与 enabledTriggers 不受影响） */
    fun saveGlobalConfigs(snapshots: Map<String, Map<String, Any?>>) {
        synchronized(storeLock) {
            val root = getWorkspaceRoot()
            val updated = TaskConfigMerge.withGlobalConfigs(loadTaskConfigsLocked(), root, snapshots)
            saveTaskConfigs(updated)
            configStoreCache = updated
        }
    }

    /** 解析 tasks.json 里的 globalConfigs：{组名: {键: 值}}。值保留原始 JSON 类型。 */
    private fun parseGlobalConfigsNode(node: JsonNode?): Map<String, Map<String, Any?>> {
        if (node == null || !node.isObject) return emptyMap()
        val groups = linkedMapOf<String, Map<String, Any?>>()
        node.forEachField { groupName, groupNode ->
            if (!groupNode.isObject) return@forEachField
            @Suppress("UNCHECKED_CAST")
            val values = objectMapper.convertValue(groupNode, Map::class.java) as? Map<String, Any?>
            if (values != null) groups[groupName] = values
        }
        return groups
    }

    // ── Schema cache ──────────────────────────────────────────────────

    fun loadSchemaCache(projectDir: String, locale: String): SchemaProbeResult {
        val cacheFile = Paths.get(getWorkspaceRoot(), SCHEMA_CACHE_FILE).toFile()
        if (!cacheFile.exists()) return SchemaProbeResult(ok = false, error = "Schema cache not found")
        return try {
            // 手动从 JsonNode 解析：SchemaProbeResult 是 Kotlin data class，没有 Jackson 需要的
            // Creator，readValue 会直接抛 "no Creators"（缓存此前从未加载成功过）。
            val cached = parseSchemaProbeResult(objectMapper.readTree(cacheFile))
            val cachedLocale = cached.locale
                ?: cached.schemas?.values?.firstOrNull()?.locale
            if (cached.projectDir == projectDir && cachedLocale == locale) {
                cached
            } else {
                SchemaProbeResult(ok = false, error = "Schema cache stale (projectDir or locale changed)")
            }
        } catch (e: Exception) {
            LOG.warn("Failed to load schema cache", e)
            SchemaProbeResult(ok = false, error = e.message)
        }
    }

    private fun parseSchemaProbeResult(node: JsonNode): SchemaProbeResult {
        return SchemaProbeResult(
            ok = node.get("ok")?.asBoolean() ?: false,
            error = node.get("error")?.asText(null),
            schemas = node.get("schemas")?.takeIf { it.isObject && it.size() > 0 }?.let { parseSchemas(node) },
            total = node.get("total")?.asInt() ?: 0,
            projectDir = node.get("projectDir")?.asText(null),
            locale = node.get("locale")?.asText(null),
            configModule = node.get("configModule")?.asText(null),
            // 缓存里旧版本没有该键 → 空列表（物化规则对空输入零操作，安全）
            globalConfigGroups = parseGlobalConfigGroups(node),
        )
    }

    fun saveSchemaCache(projectDir: String, locale: String, result: SchemaProbeResult) {
        val cacheFile = Paths.get(getWorkspaceRoot(), SCHEMA_CACHE_FILE).toFile()
        cacheFile.parentFile?.mkdirs()
        try {
            val enriched = result.copy(projectDir = projectDir, locale = locale)
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(cacheFile, enriched)
        } catch (e: Exception) {
            LOG.error("Failed to save schema cache", e)
            throw e
        }
    }

    // 注：原来的 parseExtraArgs 已移除 —— 常驻执行器把全部任务跑在同一进程里，
    // 进程级额外参数无法再按任务区分，UI 早已不再提供该入口。

    fun getProjectName(): String = project.name
}
