package com.alicejump.okscripttoolkit.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TemplateAssetDataServiceCocoTest {

    @Test
    fun `serialize then parse round-trips coco data`() {
        val coco = CocoData()
        val img = coco.addImage("shot_001.png", 1920, 1080)
        val cat = coco.getOrCreateCategory("battle_icon")
        coco.addAnnotation(img.id, cat.id, intArrayOf(10, 20, 30, 40))
        val cat2 = coco.getOrCreateCategory("hp_bar")
        coco.addAnnotation(img.id, cat2.id, intArrayOf(1, 2, 3, 4))

        val restored = TemplateAssetDataService.parseCoco(TemplateAssetDataService.serializeCoco(coco))

        assertEquals(1, restored.images.size)
        assertEquals("shot_001.png", restored.images[0].fileName)
        assertEquals(1920, restored.images[0].width)
        assertEquals(listOf("battle_icon", "hp_bar"), restored.categories.map { it.name })
        assertEquals(2, restored.annotations.size)
        assertTrue(restored.annotations[0].bbox.contentEquals(intArrayOf(10, 20, 30, 40)))
        assertEquals(cat.id, restored.annotations[0].categoryId)
        assertEquals(img.id, restored.annotations[0].imageId)
        assertEquals(cat2.id, restored.annotations[1].categoryId)
        assertEquals(30 * 40, restored.annotations[0].area)
    }

    @Test
    fun `parse reinitializes next ids from max`() {
        val coco = CocoData()
        val img = coco.addImage("a.png", 10, 10)
        val cat = coco.getOrCreateCategory("c")
        val ann = coco.addAnnotation(img.id, cat.id, intArrayOf(0, 0, 5, 5))

        val restored = TemplateAssetDataService.parseCoco(TemplateAssetDataService.serializeCoco(coco))
        // 新增图片/标注的 id 应接在已有最大 id 之后，而不是与旧 id 冲突
        val img2 = restored.addImage("b.png", 10, 10)
        val ann2 = restored.addAnnotation(img.id, cat.id, intArrayOf(1, 1, 2, 2))
        assertEquals(img.id + 1, img2.id)
        assertEquals(ann.id + 1, ann2.id)
    }

    @Test
    fun `serialize writes structural keys for plain JSON tree`() {
        val coco = CocoData()
        val img = coco.addImage("a.png", 4, 4)
        val cat = coco.getOrCreateCategory("c")
        coco.addAnnotation(img.id, cat.id, intArrayOf(0, 0, 4, 4))
        val root = TemplateAssetDataService.serializeCoco(coco)
        assertTrue(root.get("images").isArray)
        assertTrue(root.get("categories").isArray)
        assertTrue(root.get("annotations").isArray)
        assertEquals(0, root.get("annotations")[0].get("iscrowd").asInt())
    }

    @Test
    fun `replaceAnnotations then save-load round trip keeps boxes`() {
        // 模拟标注编辑器的写回路径：整体替换某图标注后应完整保留
        val coco = CocoData()
        val img = coco.addImage("a.png", 100, 100)
        val cat = coco.getOrCreateCategory("c")
        coco.addAnnotation(img.id, cat.id, intArrayOf(0, 0, 1, 1))

        val restored = TemplateAssetDataService.parseCoco(TemplateAssetDataService.serializeCoco(coco))
        restored.setAnnotationsForImage(img.id, emptyList())
        restored.addAnnotation(img.id, cat.id, intArrayOf(5, 6, 7, 8))
        restored.addAnnotation(img.id, cat.id, intArrayOf(9, 10, 11, 12))

        val reloaded = TemplateAssetDataService.parseCoco(TemplateAssetDataService.serializeCoco(restored))
        val boxes = reloaded.annotationsForImage(img.id).map { it.bbox.toList() }
        assertEquals(listOf(listOf(5, 6, 7, 8), listOf(9, 10, 11, 12)), boxes)
    }
}
