package com.alicejump.okscripttoolkit.tasklauncher

import com.alicejump.okscripttoolkit.tasklauncher.TaskLauncherService.TaskParamField
import kotlin.test.Test
import kotlin.test.assertEquals

class TaskParamValuesTest {
    @Test
    fun `explicit null does not fall back to project or factory value`() {
        val field = TaskParamField(key = "option", value = "project", default = "factory")
        assertEquals(null, TaskParamValues.resolve(mapOf("option" to null), field))
        assertEquals("project", TaskParamValues.resolve(emptyMap(), field))
        assertEquals("project", TaskParamValues.resolve(null, field))
    }
}
