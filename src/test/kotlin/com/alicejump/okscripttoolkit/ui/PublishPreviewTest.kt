package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.core.CocoAnnotation
import com.alicejump.okscripttoolkit.core.CocoCategory
import com.alicejump.okscripttoolkit.core.CocoData
import kotlin.test.Test
import kotlin.test.assertEquals

class PublishPreviewTest {
    @Test
    fun `preview lists files and name-only template changes`() {
        val preview = PublishPreview.build(
            PublishPreview.TemplateInput("assets/templates/", "src/data/Labels.py", listOf("b", "a", "a"), listOf("a", "c")),
            listOf("src/data/positions.json"),
        )
        assertEquals(listOf("assets/templates/coco_annotations.json", "assets/templates/images/", "src/data/Labels.py", "src/data/positions.json"), preview.files.map { it.label })
        assertEquals(listOf("KEEP:a", "ADD:b", "REMOVE:c"), preview.templates.map { "${it.kind}:${it.label}" })
        assertEquals(PublishPreview.Counts(add = 1, keep = 1, remove = 1), preview.counts)
    }

    @Test
    fun `positions only preview has no template rows`() {
        val preview = PublishPreview.build(null, listOf("p.json"))
        assertEquals(listOf("FILE:p.json"), preview.files.map { "${it.kind}:${it.label}" })
        assertEquals(emptyList(), preview.templates)
    }

    @Test
    fun `draft names only count categories with annotations`() {
        val coco = CocoData(
            annotations = mutableListOf(CocoAnnotation(1, 1, 2, intArrayOf(0, 0, 1, 1), 1)),
            categories = mutableListOf(CocoCategory(1, "unused"), CocoCategory(2, "used")),
        )
        assertEquals(listOf("used"), PublishPreview.draftNames(coco))
        assertEquals(listOf("new"), PublishPreview.draftOnly(listOf("used", "new"), listOf("used")))
    }

    @Test
    fun `confirm runs after configuration and before any write`() {
        val steps = mutableListOf<String>()
        publishSelectedResources(true, true,
            { steps += "cfgT"; "t" }, { steps += "cfgP"; "p" },
            { steps += "writeP"; true }, { _: String -> steps += "writeT" }, { steps += "done" },
            confirm = { t, p -> steps += "confirm:$t$p"; false })
        assertEquals(listOf("cfgT", "cfgP", "confirm:tp"), steps)
    }
}
