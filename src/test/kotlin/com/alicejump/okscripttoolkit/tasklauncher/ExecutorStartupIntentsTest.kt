package com.alicejump.okscripttoolkit.tasklauncher

import kotlin.test.Test
import kotlin.test.assertEquals

class ExecutorStartupIntentsTest {
    @Test
    fun `multiple one-time clicks survive startup and retain order`() {
        val intents = ExecutorStartupIntents()
        intents.queueOnetime("m::A")
        intents.queueOnetime("m::B")
        intents.queueOnetime("m::A")

        assertEquals(listOf("m::A", "m::B", "m::A"), intents.visibleOnetimeQueue(emptyList(), connecting = true))
        assertEquals(listOf("m::A", "m::B", "m::A"), intents.drainOnetimeCommands())
        assertEquals(emptyList(), intents.drainOnetimeCommands())
    }

    @Test
    fun `last trigger choice stays visible until the executor acknowledges it`() {
        val intents = ExecutorStartupIntents()
        intents.setTrigger("m::T", true)
        intents.setTrigger("m::T", false)
        intents.setTrigger("m::U", true)

        assertEquals(listOf("m::T" to false, "m::U" to true), intents.pendingTriggerCommands())
        assertEquals(listOf("m::U"), intents.visibleTriggers(listOf("m::T"), acknowledge = false))
        assertEquals(listOf("m::U"), intents.visibleTriggers(listOf("m::T"), acknowledge = true))
        assertEquals(2, intents.pendingTriggerCommands().size)
        assertEquals(listOf("m::U"), intents.visibleTriggers(listOf("m::U"), acknowledge = false))
        assertEquals(2, intents.pendingTriggerCommands().size, "缺少触发列表的快照不能算执行器确认")
        assertEquals(listOf("m::U"), intents.visibleTriggers(listOf("m::U"), acknowledge = true))
        assertEquals(emptyList(), intents.pendingTriggerCommands())
    }

    @Test
    fun `session reset drops unsent commands`() {
        val intents = ExecutorStartupIntents()
        intents.queueOnetime("m::A")
        intents.setTrigger("m::T", true)
        intents.clear()

        assertEquals(emptyList(), intents.drainOnetimeCommands())
        assertEquals(emptyList(), intents.pendingTriggerCommands())
    }
}
