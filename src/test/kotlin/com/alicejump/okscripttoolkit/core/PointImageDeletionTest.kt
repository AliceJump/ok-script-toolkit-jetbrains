package com.alicejump.okscripttoolkit.core

import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals

class PointImageDeletionTest {
    private val mapper = ObjectMapper()
    private val source = """{
      "images": [
        {"id": 1, "file_name": "folder/a.png", "width": 100, "height": 100},
        {"id": 7, "file_name": "a.jpg", "width": 100, "height": 100, "license": 42},
        {"id": 17, "file_name": "0017.png", "width": 100, "height": 100}
      ],
      "categories": [{"id": 3, "name": "screen.first"}, {"id": 9, "name": "screen.second"}],
      "annotations": [
        {"id": 4, "image_id": 1, "category_id": 3, "bbox": [10, 20, 0, 0]},
        {"id": 23, "image_id": 7, "category_id": 9, "bbox": [20.25, 30.75, 0, 0], "note": "preserve"}
      ],
      "info": {"description": "preserve document metadata"}
    }"""

    @Test fun `cleanup preserves unrelated registrations coordinates ids and metadata`() {
        val raw = mapper.readTree(source)
        val actual = removePointImageReferences(raw, "A.PNG")
        assertEquals(raw.path("images")[1], actual.path("images")[0])
        assertEquals(raw.path("images")[2], actual.path("images")[1])
        assertEquals(2, actual.path("images").size())
        assertEquals(raw.path("annotations")[1], actual.path("annotations")[0])
        assertEquals(1, actual.path("annotations").size())
        assertEquals(raw.path("categories"), actual.path("categories"))
        assertEquals(raw.path("info"), actual.path("info"))
        assertEquals(mapper.readTree(source), raw, "original snapshot remains intact")
    }

    @Test fun `unregistered image leaves the complete source unchanged`() {
        val raw = mapper.readTree(source)
        assertEquals(raw, removePointImageReferences(raw, "missing.png"))
    }
}
