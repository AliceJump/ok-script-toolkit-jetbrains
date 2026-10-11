package com.alicejump.okscripttoolkit.core

import com.alicejump.okscripttoolkit.editor.OkDocLinks
import com.alicejump.okscripttoolkit.editor.UnpublishedTemplateNotificationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TemplateReferencesTest {
    private val source = """
        |from src.data.FeatureList import FeatureList as fL
        |self.find_one(fL.start_btn)
        |box = self.pos.ui.start_btn
        |self.wait_feature("start_btn")
        |fL.start_btn_2  # not a match
        |x.fL.start_btn  # attribute chain, not a match
    """.trimMargin()

    @Test
    fun `finds feature, position and key references with line and column`() {
        val hits = TemplateReferences.find(source, "start_btn", listOf("fL"))
        assertEquals(listOf(TemplateReferences.Kind.FEATURE, TemplateReferences.Kind.POSITION, TemplateReferences.Kind.KEY), hits.map { it.kind })
        assertEquals(listOf(1, 2, 3), hits.map { it.line })
        assertEquals("self.find_one(fL.start_btn)", hits[0].preview)
        assertEquals(17, hits[0].column)
    }

    @Test
    fun `custom aliases are honored`() {
        assertEquals(1, TemplateReferences.find("Features.a\nfL.a", "a", listOf("Features")).size)
        assertEquals(emptyList(), TemplateReferences.find("anything", "", listOf("fL")))
    }

    @Test
    fun `doc links round trip names`() {
        val html = OkDocLinks.featureLinks("战斗 按钮")
        val href = Regex("href=\"([^\"]+)\"").findAll(html).map { it.groupValues[1] }.toList()
        assertEquals(OkDocLinks.REVEAL to "战斗 按钮", OkDocLinks.parse(href[0]))
        assertEquals(OkDocLinks.REFS to "战斗 按钮", OkDocLinks.parse(href[1]))
        assertNull(OkDocLinks.parse("psi_element://com.example.Foo"))
    }

    @Test
    fun `banner lists only unpublished templates referenced through an alias`() {
        assertEquals(listOf("start_btn"), UnpublishedTemplateNotificationProvider.referencedUnpublished(source, setOf("start_btn", "other"), listOf("fL")))
    }
}
