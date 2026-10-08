package com.alicejump.okscripttoolkit.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class UnifiedPublishToolWindowsTest {
    @Test
    fun `publish availability only enables resources with actual annotations`() {
        assertEquals(
            PublishAvailability(template = false, rect = false, point = false),
            publishAvailability(templateAnnotations = 0, rectAnnotations = 0, pointAnnotations = 0),
        )
        assertEquals(
            PublishAvailability(template = true, rect = false, point = true),
            publishAvailability(templateAnnotations = 2, rectAnnotations = 0, pointAnnotations = 1),
        )
        assertEquals(
            PublishAvailability(template = false, rect = true, point = false),
            publishAvailability(templateAnnotations = 0, rectAnnotations = 3, pointAnnotations = 0),
        )
    }
}
