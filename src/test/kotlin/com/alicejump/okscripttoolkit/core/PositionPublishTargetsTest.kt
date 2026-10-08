package com.alicejump.okscripttoolkit.core

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PositionPublishTargetsTest {
    private val json = ObjectMapper()

    @Test
    fun `typed targets reject absolute traversal and root but allow preference reset`() {
        for (input in listOf("", "   ", "./generated\\scene", "generated/positions.json")) {
            assertNull(positionPublishTargetInputError(input), input)
        }
        for (input in listOf("/outside", "C:\\outside", "\\\\server\\share")) {
            assertEquals("relative", positionPublishTargetInputError(input), input)
        }
        for (input in listOf(".", "./", "../outside", "generated/../positions.json")) {
            assertEquals("outside", positionPublishTargetInputError(input), input)
        }
    }

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

    @Test
    fun `existing custom json targets require explicit overwrite confirmation`() {
        assertFalse(jsonTargetRequiresOverwriteConfirmation(PositionPublishDefaults.JSON_PATH, exists = true))
        assertFalse(jsonTargetRequiresOverwriteConfirmation("generated/positions.json", exists = false))
        assertTrue(jsonTargetRequiresOverwriteConfirmation("generated/positions.json", exists = true))
        assertFalse(jsonTargetRequiresOverwriteConfirmation("./src\\scene/positions.json", exists = true))
    }

    @Test
    fun `path containment rejects output leaf symlink escaping project`(@TempDir root: Path) {
        val scene = Files.createDirectories(root.resolve("src/scene"))
        val outside = Files.createTempDirectory("position-publish-outside")
        val outsideFile = Files.writeString(outside.resolve("outside.py"), "outside")
        val symlink = scene.resolve("ScreenRatio.py")
        try {
            assumeTrue(runCatching { Files.createSymbolicLink(symlink, outsideFile) }.isSuccess)
            assertNull(pathInsideRoot(root, symlink))
            assertEquals(
                scene.resolve("PositionMap.py").toAbsolutePath().normalize(),
                pathInsideRoot(root, scene.resolve("PositionMap.py")),
            )
        } finally {
            runCatching { Files.deleteIfExists(symlink) }
            runCatching { Files.deleteIfExists(outsideFile) }
            runCatching { Files.deleteIfExists(outside) }
        }
    }
}
