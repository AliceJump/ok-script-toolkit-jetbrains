package com.alicejump.okscripttoolkit.core

import com.alicejump.okscripttoolkit.TestTmp
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TemplateImageChangeTest {
    @Test
    fun `all import formats refresh relative and absolute template directories`() {
        val root = TestTmp.create("ok-template-image-change")
        val directory = CocoAnnotationData.templateDir(root.absolutePath, "ok_templates")
        val absolute = CocoAnnotationData.templateDir(root.absolutePath, directory.toString())
        for (extension in listOf("png", "jpg", "jpeg", "bmp", "JPG", "PNG")) {
            assertTrue(isTemplateImageChange(directory.resolve("1.$extension"), absolute))
        }
        assertFalse(isTemplateImageChange(directory.resolve("1.gif"), directory))
        assertFalse(isTemplateImageChange(root.toPath().resolve("1.jpg"), directory))
    }
}
