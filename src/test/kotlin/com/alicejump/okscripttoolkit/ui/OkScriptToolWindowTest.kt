package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.core.OkProjectDetection
import com.alicejump.okscripttoolkit.core.TestRepoLayout
import com.alicejump.okscripttoolkit.tasklauncher.TaskRunnerService
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OkScriptToolWindowTest {
    private val pluginXml = TestRepoLayout.locate("src/main/resources/META-INF/plugin.xml").readText()

    @Test
    fun `only the unified tool window is visible and every legacy id redirects`() {
        val windows = Regex("""<toolWindow\s([^>]*)/>""").findAll(pluginXml).map { it.groupValues[1] }.toList()
        val ids = windows.map { Regex("""id="([^"]+)"""").find(it)!!.groupValues[1] }
        assertEquals(listOf(OK_SCRIPT_TOOL_WINDOW_ID) + OkScriptToolWindows.LEGACY_IDS.keys.sorted(), listOf(ids.first()) + ids.drop(1).sorted())
        windows.drop(1).forEach { attrs ->
            assertTrue("doNotActivateOnStart=\"true\"" in attrs, attrs)
            assertTrue("LegacyToolWindowAlias" in attrs || "ToolWindowAlias" in attrs, attrs)
        }
        assertEquals(OkTab.RUN to null, OkScriptToolWindows.LEGACY_IDS["ok-script Tasks"])
        assertEquals(OkTab.RESOURCES to ResourceSection.INBOX, OkScriptToolWindows.LEGACY_IDS["ok-script Temp Shots"])
    }

    @Test
    fun `capture shortcut avoids Ctrl+Alt+S`() {
        assertFalse(Regex("""first-keystroke="(control|meta) alt S"""").containsMatchIn(pluginXml))
        assertTrue("first-keystroke=\"control alt shift O\"" in pluginXml)
    }

    @Test
    fun `project detection follows the VS Code rule`() {
        val dir = Files.createTempDirectory("okdetect")
        try {
            assertFalse(OkProjectDetection.isOkScriptProject(dir.toString()))
            Files.createDirectories(dir.resolve("src")); Files.writeString(dir.resolve("src/config.py"), "config = {}")
            assertTrue(OkProjectDetection.isOkScriptProject(dir.toString()))
            assertFalse(OkProjectDetection.isOkScriptProject(""))
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test
    fun `status bar text summarizes executor state`() {
        val name: (String) -> String = { it.substringAfterLast("::") }
        assertTrue(executorWidgetText(TaskRunnerService.ExecutorState(), name).startsWith("ok-script"))
        val running = executorWidgetText(TaskRunnerService.ExecutorState(status = "running", current = "src.tasks::Daily", onetimeQueue = listOf("a", "b")), name)
        assertTrue("Daily" in running && "2" in running, running)
        val paused = executorWidgetText(TaskRunnerService.ExecutorState(status = "running", paused = true), name)
        assertTrue(paused != running)
    }

    @Test
    fun `global hotkey capture lands in TempScreenshotStore inbox`() {
        val src = TestRepoLayout.locate("src/main/kotlin/com/alicejump/okscripttoolkit/ui/GlobalScreenshotHotkeyService.kt").readText()
        assertTrue("TempScreenshotStore" in src)
        assertTrue("newFilePath(\"bmp\")" in src)
        assertFalse("okTemplatesDirectory()" in src)
        assertFalse("UNIFIED_ANNOTATION_TOOL_WINDOW_ID" in src)
    }

}
