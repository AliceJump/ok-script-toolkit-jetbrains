package com.alicejump.okscripttoolkit.core

import com.alicejump.okscripttoolkit.TestTmp
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CocoAnnotationDataTest {
    @Test
    fun `imports reserve missing images from both sources including legacy boxes`() {
        val standard = """{"images":[{"id":1,"file_name":"1.png","width":100,"height":80}],"annotations":[],"categories":[]}"""
        val legacyV1 = """{"version":1,"boxes":[{"path":"screen.old","image":"1.png","rect":[0,0,1,1]}]}"""
        val legacyV2 = """{"version":2,"images":[{"file":"1.png","width":100,"height":80}],"boxes":[]}"""
        for ((fileName, text) in listOf("coco_annotations.json" to standard, "boxes.json" to standard, "boxes.json" to legacyV1, "boxes.json" to legacyV2)) {
            val root = TestTmp.create("ok-coco-import-both")
            val directory = root.resolve("ok_templates").apply { mkdirs() }
            val reserved = directory.resolve(fileName).apply { writeText(text) }
            val incoming = root.resolve("incoming.png")
            ImageIO.write(BufferedImage(100, 80, BufferedImage.TYPE_INT_RGB), "png", incoming)
            val data = source(root, boxes = fileName == "coco_annotations.json")
            val before = CocoAnnotationData.serializeCoco(data.data)
            assertEquals("2", data.nextImageName())
            assertEquals(1, data.importImages(listOf(incoming), directory))
            assertTrue(directory.resolve("2.png").isFile)
            assertFalse(directory.resolve("1.png").exists())
            assertEquals(text, reserved.readText())
            assertEquals(before, CocoAnnotationData.serializeCoco(data.data))
        }
    }

    @Test
    fun `invalid template annotations remain visible but cannot be saved`() {
        val root = TestTmp.create("ok-coco-invalid-display")
        image(root, "a.png")
        val valid = """{"images":[{"id":1,"file_name":"a.png","width":100,"height":80}],"categories":[{"id":1,"name":"icon"}],"annotations":[{"id":1,"image_id":1,"category_id":1,"bbox":[1,2,3,4]}]}"""
        val file = root.resolve("ok_templates/coco_annotations.json")
        for (text in listOf(valid.replace("100", "100.0"), valid.replace("[1,2,3,4]", "[99,2,3,4]"), valid.replace("category_id\":1", "category_id\":99"))) {
            file.writeText(text)
            val templates = source(root, false)
            assertTrue(templates.readErrors.isNotEmpty())
            assertEquals(1, templates.listImages().single().annotations.size)
            assertEquals(1, templates.reload().annotations.size)
            assertTrue(templates.readErrors.isNotEmpty())
            assertFalse(templates.save())
            assertFalse(templates.saveAnnotationEdits(listOf(CocoAnnotationEdit("a.png", 100 to 80, emptyList()))))
            assertEquals(text, file.readText())
        }
    }

    @Test
    fun `failed write after external edits keeps revision and preserves edits on retry`() {
        val root = TestTmp.create("ok-coco-retry-external")
        image(root, "a.png")
        image(root, "b.png")
        val initial = source(root, false)
        fun edit(file: String, name: String) = CocoAnnotationEdit(file, 100 to 80, listOf(name to intArrayOf(1, 2, 3, 4)))
        assertTrue(initial.saveAnnotationEdits(listOf(edit("a.png", "old"))))
        val file = initial.annotationFile!!.toFile()
        val backup = file.resolveSibling("external.json")
        var failWrite = true
        val open = CocoAnnotationData(validateNames = { _, _ ->
            if (failWrite) {
                java.nio.file.Files.move(file.toPath(), backup.toPath())
                file.mkdir()
                file.resolve("blocker").writeText("block")
            }
            null
        }).also { it.load(root.absolutePath, "ok_templates") }
        val before = open.revision
        val other = source(root, false)
        assertTrue(other.saveAnnotationEdits(listOf(edit("b.png", "external"))))
        assertFalse(open.saveAnnotationEdits(listOf(edit("a.png", "new"))))
        assertEquals(before, open.revision)
        assertTrue(file.resolve("blocker").delete())
        assertTrue(file.delete())
        java.nio.file.Files.move(backup.toPath(), file.toPath())
        failWrite = false
        assertTrue(open.saveAnnotationEdits(listOf(edit("a.png", "new"))))
        val restored = source(root, false)
        val b = restored.getImageEntryForFile("b.png")!!
        assertEquals(1, restored.getAnnotationsForImage(b.id).size)
        assertEquals("external", restored.categories().first { it.id == restored.getAnnotationsForImage(b.id).single().categoryId }.name)
    }

    private fun source(root: File, boxes: Boolean = true): CocoAnnotationData =
        (if (boxes) newBoxAnnotationData() else CocoAnnotationData()).also { it.load(root.absolutePath, "ok_templates") }

    private fun image(root: File, name: String, width: Int = 100, height: Int = 80): File =
        root.resolve("ok_templates/$name").also { file ->
            file.parentFile.mkdirs()
            ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", file)
        }

    @Test
    fun `an empty box source is readable and publishing preserves existing runtime`() {
        val root = TestTmp.create("ok-box-coco-empty")
        val boxes = source(root)
        assertTrue(boxes.readErrors.isEmpty())
        assertTrue(boxes.data.images.isEmpty())
        assertFalse(root.resolve("ok_templates/boxes.json").exists())
        val runtime = root.resolve("runtime.json")
        assertEquals(listOf("empty"), publishBoxAnnotations(boxes, runtime.toPath()))
        assertFalse(runtime.exists())
        val text = """{"version":1,"boxes":[{"path":"screen.old","rect":[0,0,1,1]}]}"""
        runtime.writeText(text)
        assertEquals(listOf("empty"), publishBoxAnnotations(boxes, runtime.toPath()))
        assertEquals(text, runtime.readText())
    }

    @Test
    fun `first draw uses the same COCO as templates and keeps the sources independent`() {
        val root = TestTmp.create("ok-box-coco-first-draw")
        image(root, "1.png")
        val templates = source(root, false)
        assertTrue(templates.saveAnnotationEdits(listOf(CocoAnnotationEdit("1.png", 100 to 80, listOf("template_label" to intArrayOf(1, 2, 3, 4))))))
        val templateText = templates.annotationFile!!.toFile().readText()
        val boxes = source(root)
        assertEquals("2", boxes.nextImageName(), "An empty box source must not overwrite a template image")
        val notifications = mutableListOf<java.nio.file.Path>()
        AnnotationDataChanges.subscribe { notifications.add(it) }.use {
            assertNull(addBoxAnnotation(boxes, "screen.generated", "1.png", intArrayOf(10, 20, 30, 40)))
        }
        assertEquals(listOf(boxes.annotationFile), notifications)
        val text = boxes.annotationFile!!.toFile().readText()
        val tree = com.fasterxml.jackson.databind.ObjectMapper().readTree(text)
        assertEquals(setOf("images", "categories", "annotations"), tree.fieldNames().asSequence().toSet())
        assertEquals("1.png", tree.path("images")[0].path("file_name").asText())
        assertEquals(0, tree.path("annotations")[0].path("iscrowd").asInt())
        assertEquals(1200, tree.path("annotations")[0].path("area").asInt())
        assertTrue(parseCocoText(text).errors.isEmpty())
        assertEquals("screen.generated", source(root).categories().single().name)
        assertEquals(templateText, templates.annotationFile!!.toFile().readText())
        assertEquals("duplicate", addBoxAnnotation(boxes, "screen.generated", "1.png", intArrayOf(1, 1, 2, 2)))
        assertEquals("rect", addBoxAnnotation(boxes, "screen.outside", "1.png", intArrayOf(90, 0, 20, 20)))
        assertEquals(text, boxes.annotationFile!!.toFile().readText())
    }

    @Test
    fun `absolute directories and standard template COCO can be opened as box sources`() {
        val root = TestTmp.create("ok-box-coco-absolute")
        image(root, "1.png")
        val templates = source(root, false)
        assertTrue(templates.saveAnnotationEdits(listOf(CocoAnnotationEdit("1.png", 100 to 80, listOf("screen.region" to intArrayOf(1, 2, 3, 4))))))
        val expected = templates.annotationFile!!.toFile().readText()
        root.resolve("ok_templates/boxes.json").writeText(expected)
        val boxes = newBoxAnnotationData()
        boxes.load(root.absolutePath, root.resolve("ok_templates").absolutePath)
        assertEquals(root.resolve("ok_templates/boxes.json").toPath(), boxes.annotationFile)
        assertTrue(boxes.readErrors.isEmpty())
        assertEquals(CocoAnnotationData.serializeCoco(templates.data), CocoAnnotationData.serializeCoco(boxes.data))
        assertEquals(expected, boxes.annotationFile!!.toFile().readText(), "Opening never rewrites source data")
    }

    @Test
    fun `VS Code COCO fields round trip through the common reader`() {
        val text = """{"images":[{"id":1,"file_name":"1.png","width":100,"height":80}],"annotations":[{"id":1,"image_id":1,"category_id":1,"bbox":[1,2,3,4],"area":12,"iscrowd":1}],"categories":[{"id":1,"name":"screen.region","supercategory":"screen"}]}"""
        val parsed = BoxResource.parseBoxCoco(text)
        assertTrue(parsed.errors.isEmpty())
        assertEquals(com.fasterxml.jackson.databind.ObjectMapper().readTree(text), CocoAnnotationData.serializeCoco(parsed.data))
        assertEquals(listOf(2, 2, 3, 4), parseCocoText(text.replace("[1,2,3,4]", "[1.5,2,3,4]")).data.annotations.single().bbox.toList())
    }

    @Test
    fun `legacy v1 and v2 are read only until first edit creates a byte exact backup`() {
        for (version in listOf(1, 2)) {
            val root = TestTmp.create("ok-box-coco-legacy-$version")
            image(root, "1.png", 100, 100)
            val text = if (version == 1) """{"version":1,"boxes":[{"path":"screen.old","image":"1.png","rect":[0.1,0.2,0.4,0.6]}]}"""
                else """{"version":2,"images":[{"file":"1.png","width":100,"height":100}],"boxes":[{"path":"screen.old","image":"1.png","bbox":[10,20,30,40]}]}"""
            val file = root.resolve("ok_templates/boxes.json").apply { writeText(text) }
            val boxes = source(root)
            assertTrue(boxes.readErrors.isEmpty())
            assertEquals(listOf(10, 20, 30, 40), boxes.data.annotations.single().bbox.toList())
            assertEquals(text, file.readText())
            assertTrue(file.parentFile.listFiles()!!.none { it.name.endsWith(".bak") })
            assertNull(addBoxAnnotation(boxes, "screen.new", "1.png", intArrayOf(1, 2, 3, 4)))
            assertEquals(text, file.parentFile.listFiles()!!.single { it.name.endsWith(".bak") }.readText())
            assertTrue(parseCocoText(file.readText()).errors.isEmpty())
            assertEquals(2, source(root).data.annotations.size)
        }
    }

    @Test
    fun `all edited images share one name validation and atomic save transaction`() {
        val root = TestTmp.create("ok-box-coco-multiple")
        val boxes = source(root)
        fun edit(file: String, name: String) = CocoAnnotationEdit(file, 100 to 80, listOf(name to intArrayOf(1, 2, 3, 4)))
        assertTrue(boxes.saveAnnotationEdits(listOf(edit("1.png", "screen.a"), edit("2.png", "screen.b"))))
        val before = boxes.annotationFile!!.toFile().readText()
        assertFalse(boxes.saveAnnotationEdits(listOf(edit("1.png", "screen.duplicate"), edit("2.png", "screen.duplicate"))))
        assertEquals("duplicate", boxes.lastError)
        assertEquals(before, boxes.annotationFile!!.toFile().readText())
        assertTrue(boxes.saveAnnotationEdits(listOf(edit("1.png", "screen.b"), edit("2.png", "screen.a"))), "Names can move between both edited images together")
        assertFalse(boxes.saveAnnotationEdits(listOf(edit("1.png", "panels.reserved"))))
        assertEquals("reserved", boxes.lastError)
    }

    @Test
    fun `box swaps use the template COCO transaction and proportional geometry`() {
        val root = TestTmp.create("ok-box-coco-swap")
        val a = image(root, "a.png", 200, 100)
        val b = image(root, "b.png", 100, 50)
        val boxes = source(root)
        val rectA = listOf("screen.a" to intArrayOf(20, 10, 40, 20))
        val rectB = listOf("screen.b" to intArrayOf(30, 10, 20, 10))
        assertTrue(boxes.saveAnnotationEdits(listOf(CocoAnnotationEdit("a.png", 200 to 100, rectA), CocoAnnotationEdit("b.png", 100 to 50, rectB))))
        val edits = AnnotationSwap.editsForSwap("a.png", AnnotationSwap.Size(200, 100), rectA, "b.png", AnnotationSwap.Size(100, 50), rectB)
        assertEquals(CocoAnnotationData.SwapSaveResult.SAVED, boxes.saveSwapEdits(mapOf("a.png" to rectA, "b.png" to rectB), mapOf(a to (200 to 100), b to (100 to 50)), edits))
        val projection = BoxResource.authoringFromCoco(source(root).data)
        assertEquals(listOf(60, 20, 40, 20), projection.boxes.single { it.image == "a.png" }.bbox.toList())
        assertEquals("screen.b", projection.boxes.single { it.image == "a.png" }.path)
        assertEquals(listOf(10, 5, 20, 10), projection.boxes.single { it.image == "b.png" }.bbox.toList())
    }

    @Test
    fun `saving an open image preserves another editor's changes to other images`() {
        val root = TestTmp.create("ok-box-coco-external-save")
        image(root, "a.png")
        image(root, "b.png")
        val open = source(root)
        val other = source(root)
        assertNull(addBoxAnnotation(other, "screen.b", "b.png", intArrayOf(1, 2, 3, 4)))
        assertTrue(open.saveAnnotationEdits(listOf(CocoAnnotationEdit("a.png", 100 to 80, listOf("screen.a" to intArrayOf(2, 3, 4, 5))))))
        assertEquals(setOf("screen.a", "screen.b"), source(root).categories().map { it.name }.toSet())
    }

    @Test
    fun `broken source references and legacy entries refuse edits without data loss`() {
        val sources = listOf(
            """{"images":[],"categories":[{"id":1,"name":"screen.a"}],"annotations":[{"id":1,"image_id":999,"category_id":1,"bbox":[1,2,3,4]}]}""",
            """{"version":2,"images":[{"file":"1.png","width":100,"height":80}],"boxes":[{"path":"screen.a","image":"1.png","bbox":[90,0,30,20]}]}""",
        )
        for (text in sources) {
            val root = TestTmp.create("ok-box-coco-broken")
            image(root, "1.png")
            val file = root.resolve("ok_templates/boxes.json").apply { writeText(text) }
            val boxes = source(root)
            assertTrue(boxes.readErrors.isNotEmpty())
            assertEquals("parse", addBoxAnnotation(boxes, "screen.new", "1.png", intArrayOf(1, 2, 3, 4)))
            assertFalse(boxes.save())
            assertEquals(text, file.readText())
        }
    }

    @Test
    fun `publish reads actual image dimensions and protects source and broken runtime files`() {
        val root = TestTmp.create("ok-box-coco-publish")
        image(root, "1.png", 100, 100)
        val boxes = source(root)
        assertNull(addBoxAnnotation(boxes, "screen.a", "1.png", intArrayOf(10, 20, 30, 40)))
        val sourceText = boxes.annotationFile!!.toFile().readText()
        assertEquals(listOf("same"), publishBoxAnnotations(boxes, boxes.annotationFile!!))
        assertEquals(sourceText, boxes.annotationFile!!.toFile().readText())
        val runtime = root.resolve("runtime.json")
        runtime.writeText("{")
        assertEquals(listOf("runtimeRead"), publishBoxAnnotations(boxes, runtime.toPath()))
        assertEquals("{", runtime.readText())
        assertTrue(runtime.delete())
        image(root, "1.png", 200, 200)
        assertTrue(publishBoxAnnotations(boxes, runtime.toPath()).isEmpty())
        val parsed = BoxResource.parseRuntime(runtime.readText())
        assertTrue(parsed.errors.isEmpty())
        assertEquals(listOf(0.05, 0.1, 0.2, 0.3), parsed.file.boxes.single().rect.toList())
        assertFalse(runtime.readText().contains("image"))
        assertEquals(sourceText, boxes.annotationFile!!.toFile().readText())
    }
}
