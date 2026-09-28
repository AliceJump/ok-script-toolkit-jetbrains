package com.alicejump.okscripttoolkit.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SwapRefreshGateTest {
    @Test
    fun `second swap waits for the saved data to reach the latest list`() {
        val gate = SwapRefreshGate()
        assertTrue(gate.begin())
        assertFalse(gate.begin())
        gate.waitForRefresh(3)
        assertFalse(gate.refreshed(2, 3), "a load started before the save must not release the gate")
        assertFalse(gate.refreshed(3, 4), "a superseded load must not release the gate")
        assertFalse(gate.begin())
        assertTrue(gate.refreshed(4, 4))
        assertTrue(gate.begin())
    }

    @Test
    fun `failed save releases gate while failed refresh keeps it closed`() {
        val gate = SwapRefreshGate()
        assertTrue(gate.begin())
        gate.saveFailed()
        assertTrue(gate.begin())
        gate.waitForRefresh(5)
        assertTrue(gate.awaitingRefresh)
        assertFalse(gate.refreshed(4, 4))
        assertFalse(gate.begin())
        assertTrue(gate.refreshed(6, 6), "a later successful manual refresh may release the gate")
    }
}
