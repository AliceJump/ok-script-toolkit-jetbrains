package com.alicejump.okscripttoolkit.tasklauncher

import com.alicejump.okscripttoolkit.tasklauncher.TaskLauncherService.TaskParamField

/** A stored null is a value, not a missing parameter. */
internal object TaskParamValues {
    fun resolve(params: Map<String, Any?>?, field: TaskParamField): Any? =
        if (params?.containsKey(field.key) == true) params[field.key] else field.value ?: field.default

    fun keepUntouchedNull(params: Map<String, Any?>, key: String, edited: Boolean): Boolean =
        !edited && params.containsKey(key) && params[key] == null
}
