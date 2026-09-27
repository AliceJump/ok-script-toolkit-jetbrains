package com.alicejump.okscripttoolkit.tasklauncher

import com.alicejump.okscripttoolkit.TestTmp
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskConfigPersistenceTest {
    @Test
    fun `external task config changes are reloaded and preserved by the next save`() {
        val workspace = TestTmp.create("task-config")
        val project = Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java),
        ) { _, method, _ -> if (method.name == "getBasePath") workspace.absolutePath else null } as Project
        val service = TaskLauncherService(project)
        val file = workspace.toPath().resolve(".idea/ok-script-toolkit-tasks.json")
        val mapper = ObjectMapper()

        service.saveTaskConfig("m::A", TaskLauncherService.TaskConfig(params = mapOf("count" to 1)), workspace.absolutePath)
        mapper.writeValue(file.toFile(), mapOf(
            "projects" to mapOf(workspace.absolutePath to mapOf(
                "tasks" to mapOf("m::A" to mapOf("params" to mapOf("count" to 723456))),
                "enabledTriggers" to listOf("m::T"),
            )),
        ))

        assertEquals(723456, service.getTaskConfig("m::A", workspace.absolutePath).params?.get("count"))
        service.saveUiStateValue("taskGroupCollapsed::trigger", true, workspace.absolutePath)

        val saved = mapper.readTree(file.toFile()).path("projects").path(workspace.absolutePath)
        assertEquals(723456, saved.path("tasks").path("m::A").path("params").path("count").asInt())
        assertEquals("m::T", saved.path("enabledTriggers").first().asText())
        assertTrue(saved.path("uiState").path("taskGroupCollapsed::trigger").asBoolean())
        assertTrue(Files.list(file.parent).use { files -> files.noneMatch { it.fileName.toString().endsWith(".tmp") } })
    }

    @Test
    fun `unreadable task config is never replaced by a later save`() {
        val workspace = TestTmp.create("invalid-task-config")
        val project = Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java),
        ) { _, method, _ -> if (method.name == "getBasePath") workspace.absolutePath else null } as Project
        val service = TaskLauncherService(project)
        val file = workspace.toPath().resolve(".idea/ok-script-toolkit-tasks.json")
        val mapper = ObjectMapper()

        service.saveTaskConfig("m::A", TaskLauncherService.TaskConfig(params = mapOf("count" to 4)), workspace.absolutePath)
        val invalidJson = "{\"projects\":"
        Files.writeString(file, invalidJson)

        assertEquals(4, service.getTaskConfig("m::A", workspace.absolutePath).params?.get("count"))
        assertNotNull(service.taskConfigReadError())
        assertFailsWith<Exception> {
            service.saveUiStateValue("taskGroupCollapsed::trigger", true, workspace.absolutePath)
        }
        assertEquals(invalidJson, Files.readString(file))

        val wrongShape = "{\"tasks\":{}}"
        Files.writeString(file, wrongShape)
        assertNotNull(service.taskConfigReadError())
        assertFailsWith<Exception> {
            service.saveUiStateValue("taskGroupCollapsed::trigger", true, workspace.absolutePath)
        }
        assertEquals(wrongShape, Files.readString(file))

        val malformed = listOf(
            "project" to mapOf("projects" to mapOf(workspace.absolutePath to emptyList<Any>())),
            "tasks" to mapOf("projects" to mapOf(workspace.absolutePath to mapOf("tasks" to emptyList<Any>()))),
            "params" to mapOf("projects" to mapOf(workspace.absolutePath to mapOf(
                "tasks" to mapOf("m::A" to mapOf("params" to emptyList<Any>())),
            ))),
            "globalConfigs" to mapOf("projects" to mapOf(workspace.absolutePath to mapOf(
                "globalConfigs" to mapOf("group" to emptyList<Any>()),
            ))),
            "enabledTriggers" to mapOf("projects" to mapOf(workspace.absolutePath to mapOf(
                "enabledTriggers" to listOf(1),
            ))),
        )
        malformed.forEachIndexed { index, (name, store) ->
            val text = mapper.writeValueAsString(store)
            Files.writeString(file, text)
            Files.setLastModifiedTime(file, FileTime.fromMillis(1_700_000_000_000L + index * 2_000L))
            assertEquals(4, service.getTaskConfig("m::A", workspace.absolutePath).params?.get("count"), name)
            assertNotNull(service.taskConfigReadError(), name)
            assertFailsWith<Exception>(name) {
                service.saveUiStateValue("taskGroupCollapsed::trigger", true, workspace.absolutePath)
            }
            assertEquals(text, Files.readString(file), name)
        }

        Files.writeString(file, "{\"projects\":{}}")
        assertNull(service.taskConfigReadError())
        service.saveUiStateValue("taskGroupCollapsed::trigger", true, workspace.absolutePath)
        assertTrue(Files.readString(file).contains("taskGroupCollapsed::trigger"))
    }
}
