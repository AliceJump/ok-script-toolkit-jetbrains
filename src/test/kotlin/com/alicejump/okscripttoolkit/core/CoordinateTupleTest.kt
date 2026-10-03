package com.alicejump.okscripttoolkit.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoordinateTupleTest {
    @Test
    fun `XYWH only tuple ignores XYXY preference`() {
        val result = CoordinateTuple.interpret(listOf(0.8, 0.8, 0.1, 0.1), CoordinateTupleFormat.XYXY)!!
        assertEquals(CoordinateTupleFormat.XYWH, result.format)
        assertFalse(result.ambiguous)
        assertEquals(NormalizedAnnotationRect(0.8, 0.8, 0.1, 0.1), result.rect)
    }

    @Test
    fun `XYXY only tuple ignores XYWH preference`() {
        val result = CoordinateTuple.interpret(listOf(0.2, 0.2, 0.9, 0.9), CoordinateTupleFormat.XYWH)!!
        assertEquals(CoordinateTupleFormat.XYXY, result.format)
        assertFalse(result.ambiguous)
        assertEquals(NormalizedAnnotationRect(0.2, 0.2, 0.7, 0.7), result.rect)
    }

    @Test
    fun `ambiguous tuple follows user preference`() {
        val values = listOf(0.1, 0.1, 0.2, 0.2)
        val xyxy = CoordinateTuple.interpret(values, CoordinateTupleFormat.XYXY)!!
        val xywh = CoordinateTuple.interpret(values, CoordinateTupleFormat.XYWH)!!
        assertTrue(xyxy.ambiguous)
        assertTrue(xywh.ambiguous)
        assertEquals(NormalizedAnnotationRect(0.1, 0.1, 0.1, 0.1), xyxy.rect)
        assertEquals(NormalizedAnnotationRect(0.1, 0.1, 0.2, 0.2), xywh.rect)
    }

    @Test
    fun `zero size XYWH is a point while one dimensional degeneracy is rejected`() {
        val point = CoordinateTuple.interpret(listOf(0.3, 0.4, 0.0, 0.0), CoordinateTupleFormat.XYXY)!!
        assertTrue(point.rect.isPoint)
        assertEquals(CoordinateTupleFormat.XYWH, point.format)
        assertNull(CoordinateTuple.interpret(listOf(0.3, 0.4, 0.0, 0.2), CoordinateTupleFormat.XYWH))
    }

    @Test
    fun `point mode can project a regular box to its center`() {
        val rect = CoordinateTuple.interpret(listOf(0.4, 0.4, 0.2, 0.1), CoordinateTupleFormat.XYWH)!!.rect
        assertEquals(NormalizedAnnotationRect(0.5, 0.45, 0.0, 0.0), rect.centerPoint())
    }
}
