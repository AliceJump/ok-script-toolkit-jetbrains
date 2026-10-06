package com.alicejump.okscripttoolkit.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class UnifiedAnnotationModeCycleTest {
    @Test
    fun `cycles template rect point and wraps`() {
        assertEquals(AnnotationKind.RECT, AnnotationKind.TEMPLATE.nextAnnotationKind())
        assertEquals(AnnotationKind.POINT, AnnotationKind.RECT.nextAnnotationKind())
        assertEquals(AnnotationKind.TEMPLATE, AnnotationKind.POINT.nextAnnotationKind())
    }
}
