package com.alicejump.okscripttoolkit.editor

import com.alicejump.okscripttoolkit.core.AuthoringResource
import com.alicejump.okscripttoolkit.core.ResourceKind
import com.alicejump.okscripttoolkit.core.normalizedPosition
import com.alicejump.okscripttoolkit.core.positionChoices
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PositionReferenceTest {
    private val rect = AuthoringResource("screen.shared", ResourceKind.RECT, Paths.get("rect.png"), intArrayOf(10, 20, 30, 40))
    private val point = AuthoringResource("screen.shared", ResourceKind.POINT, Paths.get("point.png"), intArrayOf(90, 80, 0, 0))

    @Test
    fun `same-name expressions preserve their resource type`() {
        val kinds = mapOf("screen.shared" to setOf(ResourceKind.RECT, ResourceKind.POINT))
        val refs = OkEditorSupport.positionReferencesInLine("self.pos.screen.shared.to_box() + self.pos.screen.shared", 7, kinds)
        assertEquals(listOf(ResourceKind.RECT, ResourceKind.POINT), refs.map { it.resourceKind })
        assertEquals(7, refs.first().range.startOffset)
        assertEquals(listOf(rect), positionChoices(listOf(rect, point), refs[0].id, refs[0].resourceKind))
        assertEquals(listOf(point), positionChoices(listOf(rect, point), refs[1].id, refs[1].resourceKind))
        assertEquals(listOf(0.01, 0.02, 0.04, 0.06), normalizedPosition(rect, 1000, 1000))
        assertEquals(listOf(0.09, 0.08), normalizedPosition(point, 1000, 1000))
    }

    @Test
    fun `completion without a type preserves both choices and rejects duplicate annotations of one type`() {
        assertEquals(listOf(rect, point), positionChoices(listOf(rect, point), "screen.shared"))
        assertEquals(listOf(point), positionChoices(listOf(rect, rect.copy(imagePath = Paths.get("duplicate.png")), point), "screen.shared"))
        assertNull(normalizedPosition(point, 0, 1000))
    }

    @Test
    fun `bare box stays supported and point cannot be converted to a box`() {
        val kinds = mapOf("screen.rect" to setOf(ResourceKind.RECT), "screen.point" to setOf(ResourceKind.POINT))
        val refs = OkEditorSupport.positionReferencesInLine("self.pos.screen.rect, self.pos.screen.point.to_box()", 0, kinds)
        assertEquals(listOf("screen.rect"), refs.map { it.id })
        assertEquals(ResourceKind.RECT, refs.single().resourceKind)
    }
}
