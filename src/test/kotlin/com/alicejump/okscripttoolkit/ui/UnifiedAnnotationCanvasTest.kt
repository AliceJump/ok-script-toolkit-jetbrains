package com.alicejump.okscripttoolkit.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class UnifiedAnnotationCanvasTest {
    @Test
    fun `canvas fit preserves image aspect ratio inside host`() {
        assertEquals(
            AnnotationCanvasFit(scale = 2.25, width = 900, height = 488),
            annotationCanvasFit(sourceWidth = 400, sourceHeight = 217, availableWidth = 900, availableHeight = 560),
        )
        assertEquals(
            AnnotationCanvasFit(scale = 1.0, width = 1920, height = 1080),
            annotationCanvasFit(sourceWidth = 1920, sourceHeight = 1080, availableWidth = 1920, availableHeight = 1200),
        )
    }

    @Test
    fun `invalid dimensions collapse to minimal safe canvas`() {
        assertEquals(
            AnnotationCanvasFit(scale = 1.0, width = 1, height = 1),
            annotationCanvasFit(sourceWidth = 0, sourceHeight = 217, availableWidth = 900, availableHeight = 560),
        )
    }
}
