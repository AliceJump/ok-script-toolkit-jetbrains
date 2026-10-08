package com.alicejump.okscripttoolkit.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class ResourcePublishStepsTest {
    private fun run(template: Boolean = true, positions: Boolean = true,
                    templatePlan: String? = "template", positionPlan: String? = "positions",
                    positionResult: Boolean = true): List<String> {
        val effects = mutableListOf<String>()
        publishSelectedResources(template, positions,
            { effects += "configure template"; templatePlan },
            { effects += "configure positions"; positionPlan },
            { effects += "write positions"; positionResult },
            { effects += "write template" },
            { effects += "complete" })
        return effects
    }

    @Test fun `cancelling either configuration writes neither resource`() {
        assertEquals(listOf("configure template"), run(templatePlan = null))
        assertEquals(listOf("configure template", "configure positions"), run(positionPlan = null))
    }
    @Test fun `refusing overwrite or failing positions never starts template write`() {
        assertEquals(listOf("configure template", "configure positions", "write positions"), run(positionResult = false))
    }
    @Test fun `both decisions precede writes and selected resources alone are published`() {
        assertEquals(listOf("configure template", "configure positions", "write positions", "write template"), run())
        assertEquals(listOf("configure positions", "write positions", "complete"), run(template = false))
        assertEquals(listOf("configure template", "write template"), run(positions = false))
    }
}
