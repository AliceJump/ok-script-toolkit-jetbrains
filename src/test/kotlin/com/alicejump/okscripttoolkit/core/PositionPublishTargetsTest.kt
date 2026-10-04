package com.alicejump.okscripttoolkit.core

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PositionPublishTargetsTest {
    private val json = ObjectMapper()

    @Test
    fun `project position convention parses both targets`() {
        val convention = PositionPublishConvention.parse(
            json.readTree(
                """{
                  "jsonPath": "generated/positions.json",
                  "pythonDirectory": "generated/scene"
                }""".trimIndent(),
            ),
        )

        assertEquals("generated/positions.json", convention.jsonResolved(null).value)
        assertEquals(ConventionLayer.PROJECT, convention.jsonResolved(null).layer)
        assertEquals("generated/scene", convention.pythonDirectoryResolved(null).value)
        assertEquals(ConventionLayer.PROJECT, convention.pythonDirectoryResolved(null).layer)
    }

    @Test
    fun `personal preference overrides project convention`() {
        val convention = PositionPublishConvention(
            jsonPath = "project/positions.json",
            pythonDirectory = "project/scene",
        )

        val jsonTarget = convention.jsonResolved("mine/positions.json")
        val pythonTarget = convention.pythonDirectoryResolved("mine/scene")

        assertEquals("mine/positions.json", jsonTarget.value)
        assertEquals(ConventionLayer.PERSONAL, jsonTarget.layer)
        assertEquals("mine/scene", pythonTarget.value)
        assertEquals(ConventionLayer.PERSONAL, pythonTarget.layer)
    }

    @Test
    fun `missing personal and project values use built in defaults`() {
        val convention = PositionPublishConvention()

        val jsonTarget = convention.jsonResolved(null)
        val pythonTarget = convention.pythonDirectoryResolved(null)

        assertEquals(PositionPublishDefaults.JSON_PATH, jsonTarget.value)
        assertEquals(ConventionLayer.BUILTIN, jsonTarget.layer)
        assertEquals(PositionPublishDefaults.PYTHON_DIRECTORY, pythonTarget.value)
        assertEquals(ConventionLayer.BUILTIN, pythonTarget.layer)
    }

    @Test
    fun `paths normalize like the existing convention chain`() {
        val convention = PositionPublishConvention(
            jsonPath = "./generated\\positions.json/",
            pythonDirectory = "./generated\\scene/",
        )

        assertEquals("generated/positions.json", convention.jsonResolved(null).value)
        assertEquals("generated/scene", convention.pythonDirectoryResolved(null).value)
    }

    @Test
    fun `invalid typed project values are ignored`() {
        val convention = PositionPublishConvention.parse(
            json.readTree("""{"jsonPath": 42, "pythonDirectory": false}"""),
        )

        assertEquals(ConventionLayer.BUILTIN, convention.jsonResolved(null).layer)
        assertEquals(ConventionLayer.BUILTIN, convention.pythonDirectoryResolved(null).layer)
    }
}
