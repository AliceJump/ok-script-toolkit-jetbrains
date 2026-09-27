package com.alicejump.okscripttoolkit.tasklauncher

/** 保留执行器启动期的点击，并让触发开关在执行器确认前保持用户最后一次选择。 */
internal class ExecutorStartupIntents {
    private val onetimeKeys = mutableListOf<String>()
    private val triggerDesired = linkedMapOf<String, Boolean>()

    fun clear() {
        onetimeKeys.clear()
        triggerDesired.clear()
    }

    fun queueOnetime(key: String) {
        onetimeKeys.add(key)
    }

    fun setTrigger(key: String, enabled: Boolean) {
        triggerDesired[key] = enabled
    }

    fun pendingTriggerCommands(): List<Pair<String, Boolean>> = triggerDesired.toList()

    fun forgetTrigger(key: String, enabled: Boolean) {
        if (triggerDesired[key] == enabled) triggerDesired.remove(key)
    }

    fun drainOnetimeCommands(): List<String> = onetimeKeys.toList().also { onetimeKeys.clear() }

    fun visibleOnetimeQueue(actual: List<String>, connecting: Boolean): List<String> =
        if (connecting) actual + onetimeKeys else actual

    fun visibleTriggers(actual: List<String>, acknowledge: Boolean): List<String> {
        val visible = actual.toMutableSet()
        for ((key, desired) in triggerDesired.toMap()) {
            if (acknowledge && visible.contains(key) == desired) {
                triggerDesired.remove(key)
            } else if (desired) {
                visible.add(key)
            } else {
                visible.remove(key)
            }
        }
        return visible.toList()
    }
}
