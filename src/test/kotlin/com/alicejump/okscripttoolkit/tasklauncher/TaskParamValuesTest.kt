package com.alicejump.okscripttoolkit.tasklauncher

import com.alicejump.okscripttoolkit.tasklauncher.TaskLauncherService.TaskParamField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskParamValuesTest {
    @Test
    fun `explicit null does not fall back to project or factory value`() {
        val field = TaskParamField(key = "option", value = "project", default = "factory")
        assertEquals(null, TaskParamValues.resolve(mapOf("option" to null), field))
        assertEquals("project", TaskParamValues.resolve(emptyMap(), field))
        assertEquals("project", TaskParamValues.resolve(null, field))
    }

    @Test
    fun `editing another field retains null while editing this field can replace it`() {
        val params = mapOf<String, Any?>("nullable" to null, "other" to "value")
        assertTrue(TaskParamValues.keepUntouchedNull(params, "nullable", edited = false))
        assertFalse(TaskParamValues.keepUntouchedNull(params, "nullable", edited = true))
        assertFalse(TaskParamValues.keepUntouchedNull(params, "missing", edited = false))
    }
}
