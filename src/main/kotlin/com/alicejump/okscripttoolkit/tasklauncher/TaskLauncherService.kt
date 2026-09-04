package com.alicejump.okscripttoolkit.tasklauncher

import com.alicejump.okscripttoolkit.core.PythonScriptRunner
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 任务启动器服务，对应 VS Code 版本的 taskLauncher.ts。
 * 负责解析任务列表、探测任务 schema、运行任务。
 */
class TaskLauncherService(private val project: Project) {

    companion object {
        private val LOG = Logger.getInstance(TaskLauncherService::class.java)
        private val objectMapper = ObjectMapper()

        private const val PYTHON_SCRIPT_DIR = "python"
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
    )

    data class TaskConfig(
        val extraArgs: String? = null,
        val env: Map<String, String>? = null,
        val timeout: Int? = null,
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

    fun getPythonScriptDir(): String {
        // 1. 项目自身目录
        val projectDir = Paths.get(getProjectRoot(), PYTHON_SCRIPT_DIR)
        if (projectDir.toFile().exists()) return projectDir.normalize().toString()

        // 2. 上级 / 上上级
        val parentChecks = listOf(
            Paths.get(getProjectRoot(), "..", PYTHON_SCRIPT_DIR),
            Paths.get(getProjectRoot(), "..", "..", PYTHON_SCRIPT_DIR),
        )
        for (path in parentChecks) {
            if (path.toFile().exists()) return path.normalize().toString()
        }

        // 3. 搜索同级目录（如 ok-script-toolkit 与 ok-end-field 同级）
        val parentDir = Paths.get(getProjectRoot(), "..").toFile()
        val sibling = parentDir.listFiles()
            ?.filter { it.isDirectory && it.name != File(getProjectRoot()).name }
            ?.map { Paths.get(it.absolutePath, PYTHON_SCRIPT_DIR) }
            ?.firstOrNull { it.toFile().exists() }
        if (sibling != null) return sibling.normalize().toString()

        // 4. 插件内置资源（打包后 python/ 在 classpath 内）：解压到临时目录
        val extracted = extractBundledPythonScripts()
        if (extracted != null) return extracted.normalize().toString()

        // 5. 回退
        return projectDir.normalize().toString()
    }

    /**
     * 从插件 JAR 的 classpath 中提取打包的 python/ 脚本到临时目录。
     * 解压后文件名带 hash 避免冲突，每次只解压一次。
     */
    private fun extractBundledPythonScripts(): Path? {
        return try {
            val scripts = listOf(PARSE_CONFIG_SCRIPT, PROBE_SCHEMA_SCRIPT, RUN_TASK_SCRIPT)
            // 检查 classpath 中是否有任意一个脚本
            val resource = TaskLauncherService::class.java.classLoader
                .getResourceAsStream("python/$PARSE_CONFIG_SCRIPT") ?: return null
            resource.close()

            val extractDir = Paths.get(
                System.getProperty("java.io.tmpdir"),
                "ok-script-toolkit-scripts",
            )
            // 如果已解压且脚本都在，直接返回
            if (scripts.all { Files.exists(extractDir.resolve(it)) }) return extractDir

            Files.createDirectories(extractDir)
            for (name in scripts) {
                val input = TaskLauncherService::class.java.classLoader
                    .getResourceAsStream("python/$name") ?: continue
                val target = extractDir.resolve(name)
                Files.copy(input, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                input.close()
            }
            LOG.info("Extracted bundled Python scripts to $extractDir")
            extractDir
        } catch (e: Exception) {
            LOG.warn("Failed to extract bundled Python scripts", e)
            null
        }
    }

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
                return TaskListResult(ok = false, error = parsed.get("error")?.asText() ?: "Unknown error")
            }
            val configModule = parsed.get("config_module")?.asText() ?: "src.config"
            val tasks = mutableListOf<TaskInfo>()
            parsed.get("onetime")?.forEach { node ->
                tasks.add(
                    TaskInfo(
                        module = node.get("module").asText(),
                        className = node.get("class").asText(),
                        displayName = node.get("name")?.asText() ?: node.get("class").asText(),
                    ),
                )
            }
            parsed.get("trigger")?.forEach { node ->
                tasks.add(
                    TaskInfo(
                        module = node.get("module").asText(),
                        className = node.get("class").asText(),
                        displayName = node.get("name")?.asText() ?: node.get("class").asText(),
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
                return SchemaProbeResult(ok = false, error = parsed.get("error")?.asText() ?: "Unknown error")
            }
            val total = parsed.get("total")?.asInt() ?: 0
            val schemas = mutableMapOf<String, TaskSchema>()
            parsed.get("schemas")?.fields()?.forEach { (key, schemaNode) ->
                val fields = mutableListOf<TaskParamField>()
                schemaNode.get("fields")?.forEach { fieldNode ->
                    fields.add(
                        TaskParamField(
                            key = fieldNode.get("key").asText(),
                            displayKey = fieldNode.get("displayKey")?.asText(),
                            default = fieldNode.get("default")?.let { objectMapper.convertValue(it, Any::class.java) },
                            value = fieldNode.get("value")?.let { objectMapper.convertValue(it, Any::class.java) },
                            type = fieldNode.get("type")?.takeIf { !it.isNull }?.let {
                                @Suppress("UNCHECKED_CAST")
                                objectMapper.convertValue(it, Map::class.java) as? Map<String, Any>
                            },
                            desc = fieldNode.get("desc")?.asText(),
                            displayDesc = fieldNode.get("displayDesc")?.asText(),
                        ),
                    )
                }
                schemas[key] = TaskSchema(
                    fields = fields,
                    broken = schemaNode.get("broken")?.asBoolean() ?: false,
                    error = schemaNode.get("error")?.asText(),
                    displayName = schemaNode.get("displayName")?.asText(),
                    description = schemaNode.get("description")?.asText(),
                    kind = schemaNode.get("kind")?.asText(),
                    configGroups = schemaNode.get("configGroups")?.takeIf { !it.isNull }?.let {
                        @Suppress("UNCHECKED_CAST")
                        objectMapper.convertValue(it, Map::class.java) as? Map<String, List<String>>
                    },
                    groupLabels = schemaNode.get("groupLabels")?.takeIf { !it.isNull }?.let {
                        @Suppress("UNCHECKED_CAST")
                        objectMapper.convertValue(it, Map::class.java) as? Map<String, String>
                    },
                    groupSelector = schemaNode.get("groupSelector")?.asText(),
                    locale = schemaNode.get("locale")?.asText(),
                )
            }
            SchemaProbeResult(ok = true, schemas = schemas, total = total)
        } catch (e: Exception) {
            LOG.error("Failed to probe task schemas", e)
            SchemaProbeResult(ok = false, error = e.message)
        }
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

    fun loadTaskConfigs(): TaskConfigStore {
        val configFile = Paths.get(getProjectRoot(), TASKS_CONFIG_FILE).toFile()
        if (!configFile.exists()) return TaskConfigStore()
        return try {
            objectMapper.readValue(configFile, TaskConfigStore::class.java)
        } catch (e: Exception) {
            LOG.warn("Failed to load task configs", e)
            TaskConfigStore()
        }
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

    fun getTaskConfig(projectName: String, taskKey: String): TaskConfig {
        val store = loadTaskConfigs()
        return store.projects[projectName]?.tasks?.get(taskKey) ?: TaskConfig()
    }

    fun saveTaskConfig(projectName: String, taskKey: String, config: TaskConfig) {
        val store = loadTaskConfigs()
        val projects = store.projects.toMutableMap()
        val projectConfig = projects[projectName] ?: TaskConfigStore.ProjectConfig()
        val tasks = projectConfig.tasks.toMutableMap()
        tasks[taskKey] = config
        projects[projectName] = projectConfig.copy(tasks = tasks)
        saveTaskConfigs(store.copy(projects = projects))
    }

    // ── Schema cache ──────────────────────────────────────────────────

    fun loadSchemaCache(): SchemaProbeResult {
        val cacheFile = Paths.get(getProjectRoot(), SCHEMA_CACHE_FILE).toFile()
        if (!cacheFile.exists()) return SchemaProbeResult(ok = false, error = "Schema cache not found")
        return try {
            objectMapper.readValue(cacheFile, SchemaProbeResult::class.java)
        } catch (e: Exception) {
            LOG.warn("Failed to load schema cache", e)
            SchemaProbeResult(ok = false, error = e.message)
        }
    }

    fun saveSchemaCache(result: SchemaProbeResult) {
        val cacheFile = Paths.get(getProjectRoot(), SCHEMA_CACHE_FILE).toFile()
        cacheFile.parentFile?.mkdirs()
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(cacheFile, result)
        } catch (e: Exception) {
            LOG.error("Failed to save schema cache", e)
            throw e
        }
    }

    fun parseExtraArgs(value: String?): List<String> = pythonRunner.parseExtraArgs(value)

    fun getProjectName(): String = project.name
}
