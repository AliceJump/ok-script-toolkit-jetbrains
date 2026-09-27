package com.alicejump.okscripttoolkit.core

import com.alicejump.okscripttoolkit.TestTmp
import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TemplateAssetDataServiceCocoTest {

    private fun serviceAt(root: java.io.File): TemplateAssetDataService {
        val project = Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java),
        ) { _, _, _ -> null } as Project
        return TemplateAssetDataService(project).also { it.load(root.absolutePath, "ok_templates") }
    }

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

    @Test
    fun `supercategory survives a round trip and new categories write empty string`() {
        val source = TemplateAssetDataService.parseCoco(
            com.fasterxml.jackson.databind.ObjectMapper().readTree(
                """
                {"images":[],"annotations":[],"categories":[
                  {"id":1,"name":"imported","supercategory":"ui"},
                  {"id":2,"name":"plain","supercategory":null}
                ]}
                """.trimIndent(),
            ),
        )
        assertEquals("ui", source.categories.first { it.name == "imported" }.supercategory)
        assertEquals("", source.categories.first { it.name == "plain" }.supercategory)

        val roundTripped = TemplateAssetDataService.parseCoco(TemplateAssetDataService.serializeCoco(source))
        assertEquals("ui", roundTripped.categories.first { it.name == "imported" }.supercategory)

        val fresh = CocoData()
        fresh.getOrCreateCategory("new_cat")
        val node = TemplateAssetDataService.serializeCoco(fresh)
        assertEquals("", node.get("categories")[0].get("supercategory").asText())
    }

    @Test
    fun `image lookup list and delete use the same normalized filename key`() {
        val root = TestTmp.create("ok-coco-key")
        val templates = root.resolve("ok_templates").apply { mkdirs() }
        val diskFile = templates.resolve("shot_001.png").apply { writeBytes(byteArrayOf()) }
        val coco = CocoData()
        val image = coco.addImage("Shot_001.PNG", 10, 20)
        val category = coco.getOrCreateCategory("icon")
        coco.addAnnotation(image.id, category.id, intArrayOf(1, 2, 3, 4))
        templates.resolve("coco_annotations.json")
            .writeText(TemplateAssetDataService.serializeCoco(coco).toPrettyString())

        // 这些方法只使用项目路径参数，不查询 Project；代理让测试走真实服务路径。
        val project = Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java),
        ) { _, _, _ -> null } as Project
        val service = TemplateAssetDataService(project)
        service.load(root.absolutePath, "ok_templates")

        assertEquals("shot_001", coco.filenameKey("nested\\SHOT_001.PNG"))
        assertEquals(image, service.getImageEntryForFile("shot_001.png"))
        assertEquals(image, service.getImageEntryForFile("shot_001"))
        assertEquals(image, service.addImageEntry("SHOT_001.jpg", 99, 99))
        val listed = service.listImages().single()
        assertEquals(10, listed.width, "大小写不一致时仍须读到 COCO 宽度")
        assertEquals(20, listed.height)
        assertEquals(1, listed.annotations.size)

        service.deleteImage(diskFile)
        service.save()
        assertTrue(!diskFile.exists())
        assertEquals(null, service.getImageEntryForFile("shot_001.png"))
        val afterDelete = com.fasterxml.jackson.databind.ObjectMapper().readTree(
            templates.resolve("coco_annotations.json"),
        )
        assertEquals(0, afterDelete.get("images").size())
        assertEquals(0, afterDelete.get("annotations").size())
        assertEquals(0, afterDelete.get("categories").size())
    }

    @Test
    fun `annotation edits save all images as one COCO update`() {
        val root = TestTmp.create("ok-coco-annotation-save")
        val service = serviceAt(root)
        val old = service.addImageEntry("old.png", 100, 80)
        assertTrue(service.saveAnnotationEdits(listOf(
            CocoAnnotationEdit("old.png", null, listOf("old-category" to intArrayOf(1, 2, 3, 4))),
            CocoAnnotationEdit("new.png", 50 to 40, listOf("new-category" to intArrayOf(5, 6, 7, 8))),
        )))

        val restored = serviceAt(root)
        assertNotNull(restored.getImageEntryForFile("old.png"))
        assertEquals(listOf(1, 2, 3, 4), restored.getAnnotationsForImage(old.id).single().bbox.toList())
        val newImage = restored.getImageEntryForFile("new.png")!!
        assertEquals(50 to 40, newImage.width to newImage.height)
        assertEquals(listOf(5, 6, 7, 8), restored.getAnnotationsForImage(newImage.id).single().bbox.toList())
    }

    @Test
    fun `failed annotation write leaves in-memory COCO unchanged`() {
        val root = TestTmp.create("ok-coco-annotation-failure")
        val service = serviceAt(root)
        val old = service.addImageEntry("old.png", 100, 80)
        val category = service.getOrCreateCategory("before")
        service.replaceAnnotationsForImage(old.id, listOf(category.id to intArrayOf(1, 2, 3, 4)))
        service.save()

        // 让目标路径变成目录：写入临时文件能成功，最终替换必然失败。
        val target = root.resolve("ok_templates/coco_annotations.json")
        assertTrue(target.delete())
        assertTrue(target.mkdir())
        assertFalse(service.saveAnnotationEdits(listOf(
            CocoAnnotationEdit("old.png", null, listOf("after" to intArrayOf(5, 6, 7, 8))),
        )))
        assertEquals(listOf("before"), service.categories().map { it.name })
        assertEquals(listOf(1, 2, 3, 4), service.getAnnotationsForImage(old.id).single().bbox.toList())
        assertTrue(target.isDirectory)
        assertTrue(root.resolve("ok_templates").listFiles()?.none { it.name.endsWith(".tmp") } == true)
    }

    @Test
    fun `new annotation image without dimensions is rejected without mutation`() {
        val root = TestTmp.create("ok-coco-annotation-size")
        val service = serviceAt(root)
        assertFalse(service.saveAnnotationEdits(listOf(
            CocoAnnotationEdit("missing.png", null, listOf("icon" to intArrayOf(1, 2, 3, 4))),
        )))
        assertEquals(null, service.getImageEntryForFile("missing.png"))
        assertTrue(service.categories().isEmpty())
    }
}
