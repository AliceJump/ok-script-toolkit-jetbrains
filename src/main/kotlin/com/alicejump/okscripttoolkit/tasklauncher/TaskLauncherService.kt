package com.alicejump.okscripttoolkit.tasklauncher

import com.alicejump.okscripttoolkit.core.PythonScriptLocator
import com.alicejump.okscripttoolkit.core.PythonScriptRunner
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
        private const val RUN_TASK_SCRIPT = "run_task.py"
        private const val TASKS_CONFIG_FILE = ".idea/ok-script-toolkit-tasks.json"
        private const val SCHEMA_CACHE_FILE = ".idea/ok-script-toolkit-schema.json"
    }

    private val pythonRunner = PythonScriptRunner(project)

    // ── Data classes ──────────────────────────────────────────────────

    data class TaskInfo(
        val module: String,
        @JsonProperty("class") val className: String,
        val displayName: String = className,
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
        @Suppress("UNCHECKED_CAST")
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

    data class SchemaProbeResult(
        val ok: Boolean,
        val error: String? = null,
        val schemas: Map<String, TaskSchema>? = null,
        val total: Int = 0,
        val projectDir: String? = null,
        val locale: String? = null,
        val configModule: String? = null,
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
        )
    }

    // ── Path helpers ──────────────────────────────────────────────────

    fun getProjectRoot(): String {
        return project.basePath ?: throw IllegalStateException("Project base path is null")
    }

    fun getPythonScriptDir(): String =
        PythonScriptLocator.findScriptDir()

    // ── Task list ─────────────────────────────────────────────────────

    fun parseConfigTasks(pythonPath: String, locale: String = "zh_CN"): TaskListResult {
        val projectDir = getProjectRoot()
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
                    ),
                )
            }
            parsed.get("trigger")?.forEach { node ->
                tasks.add(
                    TaskInfo(
                        module = node.get("module").asText(),
                        className = node.get("class").asText(),
                        displayName = node.get("name")?.asText(null) ?: node.get("class").asText(),
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

    fun probeTaskSchemas(
        pythonPath: String,
        locale: String = "zh_CN",
        poDirectory: String = "i18n",
    ): SchemaProbeResult {
        val projectDir = getProjectRoot()
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

    // ── Run command builder ───────────────────────────────────────────

    fun buildRunTaskCommand(
        task: TaskInfo,
        configModule: String = "src.config",
    ): List<String> {
        return pythonRunner.buildRunTaskCommand(
            pythonScriptDir = getPythonScriptDir(),
            taskClassName = task.className,
            taskModule = task.module,
            configModule = configModule,
        )
    }

    // ── Task config persistence ───────────────────────────────────────

    // getTaskConfig 在参数面板每次渲染/取值时都会被调用（EDT），
    // 缓存整份 store，写入时同步更新，避免每次都读盘
    @Volatile
    private var configStoreCache: TaskConfigStore? = null

    fun loadTaskConfigs(): TaskConfigStore {
        configStoreCache?.let { return it }
        val configFile = Paths.get(getProjectRoot(), TASKS_CONFIG_FILE).toFile()
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
                    params = taskNode.get("params")?.takeIf { it.isObject }?.let {
                        objectMapper.convertValue(it, Map::class.java) as? Map<String, Any>
                    },
                )
            }
            projects[projectDir] = TaskConfigStore.ProjectConfig(tasks = tasks)
        }
        return TaskConfigStore(projects = projects)
    }

    fun saveTaskConfigs(store: TaskConfigStore) {
        val configFile = Paths.get(getProjectRoot(), TASKS_CONFIG_FILE).toFile()
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
        return store.projects[getProjectRoot()]?.tasks?.get(taskKey) ?: TaskConfig()
    }

    fun saveTaskConfig(taskKey: String, config: TaskConfig) {
        val store = loadTaskConfigs()
        val projects = store.projects.toMutableMap()
        val projectConfig = projects[getProjectRoot()] ?: TaskConfigStore.ProjectConfig()
        val tasks = projectConfig.tasks.toMutableMap()
        tasks[taskKey] = config
        val updated = store.copy(projects = projects.apply { put(getProjectRoot(), projectConfig.copy(tasks = tasks)) })
        saveTaskConfigs(updated)
        configStoreCache = updated
    }

    // ── Schema cache ──────────────────────────────────────────────────

    fun loadSchemaCache(projectDir: String, locale: String): SchemaProbeResult {
        val cacheFile = Paths.get(getProjectRoot(), SCHEMA_CACHE_FILE).toFile()
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
        )
    }

    fun saveSchemaCache(projectDir: String, locale: String, result: SchemaProbeResult) {
        val cacheFile = Paths.get(getProjectRoot(), SCHEMA_CACHE_FILE).toFile()
        cacheFile.parentFile?.mkdirs()
        try {
            val enriched = result.copy(projectDir = projectDir, locale = locale)
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(cacheFile, enriched)
        } catch (e: Exception) {
            LOG.error("Failed to save schema cache", e)
            throw e
        }
    }

    fun parseExtraArgs(value: String?): List<String> = pythonRunner.parseExtraArgs(value)

    fun getProjectName(): String = project.name
}
