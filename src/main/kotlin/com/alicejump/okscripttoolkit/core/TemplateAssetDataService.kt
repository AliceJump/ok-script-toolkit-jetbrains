package com.alicejump.okscripttoolkit.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import javax.imageio.ImageIO

private val JSON = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

// ── COCO data classes ────────────────────────────────────────────

data class CocoImage(
    val id: Int,
    val fileName: String,
    val width: Int,
    val height: Int,
)

data class CocoAnnotation(
    val id: Int,
    val imageId: Int,
    val categoryId: Int,
    val bbox: IntArray,
    val area: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CocoAnnotation) return false
        return id == other.id
    }
    override fun hashCode(): Int = id
}

data class CocoCategory(
    val id: Int,
    val name: String,
)

data class CocoData(
    val images: MutableList<CocoImage> = mutableListOf(),
    val annotations: MutableList<CocoAnnotation> = mutableListOf(),
    val categories: MutableList<CocoCategory> = mutableListOf(),
) {
    private var nextImageId = 1
    private var nextAnnotationId = 1
    private var nextCategoryId = 1

    fun initIds() {
        nextImageId = (images.maxOfOrNull { it.id } ?: 0) + 1
        nextAnnotationId = (annotations.maxOfOrNull { it.id } ?: 0) + 1
        nextCategoryId = (categories.maxOfOrNull { it.id } ?: 0) + 1
    }

    fun addImage(fileName: String, width: Int, height: Int): CocoImage {
        val img = CocoImage(nextImageId++, fileName, width, height)
        images.add(img)
        return img
    }

    fun getOrCreateCategory(name: String): CocoCategory {
        categories.find { it.name == name }?.let { return it }
        val cat = CocoCategory(nextCategoryId++, name)
        categories.add(cat)
        return cat
    }

    fun addAnnotation(imageId: Int, categoryId: Int, bbox: IntArray): CocoAnnotation {
        val area = bbox[2] * bbox[3]
        val ann = CocoAnnotation(nextAnnotationId++, imageId, categoryId, bbox, area)
        annotations.add(ann)
        return ann
    }

    fun removeImage(imageId: Int) {
        images.removeAll { it.id == imageId }
        annotations.removeAll { it.imageId == imageId }
    }

    fun annotationsForImage(imageId: Int): List<CocoAnnotation> =
        annotations.filter { it.imageId == imageId }

    fun setAnnotationsForImage(imageId: Int, annotations: List<CocoAnnotation>) {
        this.annotations.removeAll { it.imageId == imageId }
        this.annotations.addAll(annotations)
    }
}

data class TemplateImage(
    val name: String,
    val file: File,
    val width: Int,
    val height: Int,
    val annotations: List<CocoAnnotation>,
)

// ── Service ──────────────────────────────────────────────────────

@Service(Service.Level.PROJECT)
class TemplateAssetDataService(private val project: Project) {

    companion object {
        private val LOG = Logger.getInstance(TemplateAssetDataService::class.java)

        fun cocoPath(projectDir: String, assetsDir: String = "assets"): Path =
            Paths.get(projectDir, assetsDir, "coco_annotations.json")

        fun templateDir(projectDir: String, templatesDir: String): Path =
            Paths.get(projectDir, templatesDir)
    }

    @Volatile
    private var cocoData = CocoData()
    private var cocoFile: Path? = null
    private var templateFolder: Path? = null

    fun load(projectDir: String, templatesDir: String = "ok_templates"): CocoData {
        templateFolder = templateDir(projectDir, templatesDir)
        cocoFile = cocoPath(projectDir)

        cocoData = CocoData()
        val file = cocoFile?.toFile()
        if (file != null && file.exists()) {
            try {
                val root = JSON.readTree(file)
                // Parse images
                root.get("images")?.forEach { imgNode ->
                    cocoData.images.add(CocoImage(
                        imgNode.get("id").asInt(),
                        imgNode.get("file_name").asText(),
                        imgNode.get("width").asInt(),
                        imgNode.get("height").asInt(),
                    ))
                }
                // Parse categories
                root.get("categories")?.forEach { catNode ->
                    cocoData.categories.add(CocoCategory(
                        catNode.get("id").asInt(),
                        catNode.get("name").asText(),
                    ))
                }
                // Parse annotations
                root.get("annotations")?.forEach { annNode ->
                    val bboxNode = annNode.get("bbox")
                    val bbox = if (bboxNode != null && bboxNode.isArray && bboxNode.size() >= 4) {
                        intArrayOf(bboxNode[0].asInt(), bboxNode[1].asInt(), bboxNode[2].asInt(), bboxNode[3].asInt())
                    } else {
                        intArrayOf(0, 0, 0, 0)
                    }
                    cocoData.annotations.add(CocoAnnotation(
                        annNode.get("id").asInt(),
                        annNode.get("image_id").asInt(),
                        annNode.get("category_id").asInt(),
                        bbox,
                        annNode.get("area")?.asInt() ?: (bbox[2] * bbox[3]),
                    ))
                }
                cocoData.initIds()
            } catch (e: Exception) {
                LOG.warn("Failed to load COCO data", e)
            }
        }
        return cocoData
    }

    fun save() {
        val file = cocoFile?.toFile() ?: return
        try {
            file.parentFile?.mkdirs()
            val root = JSON.createObjectNode()

            val imagesArray = JSON.createArrayNode()
            for (img in cocoData.images) {
                val imgNode = JSON.createObjectNode()
                imgNode.put("id", img.id)
                imgNode.put("file_name", img.fileName)
                imgNode.put("width", img.width)
                imgNode.put("height", img.height)
                imagesArray.add(imgNode)
            }
            root.set<JsonNode>("images", imagesArray)

            val categoriesArray = JSON.createArrayNode()
            for (cat in cocoData.categories) {
                val catNode = JSON.createObjectNode()
                catNode.put("id", cat.id)
                catNode.put("name", cat.name)
                categoriesArray.add(catNode)
            }
            root.set<JsonNode>("categories", categoriesArray)

            val annotationsArray = JSON.createArrayNode()
            for (ann in cocoData.annotations) {
                val annNode = JSON.createObjectNode()
                annNode.put("id", ann.id)
                annNode.put("image_id", ann.imageId)
                annNode.put("category_id", ann.categoryId)
                val bboxArray = JSON.createArrayNode()
                bboxArray.add(ann.bbox[0])
                bboxArray.add(ann.bbox[1])
                bboxArray.add(ann.bbox[2])
                bboxArray.add(ann.bbox[3])
                annNode.set<JsonNode>("bbox", bboxArray)
                annNode.put("area", ann.area)
                annNode.put("iscrowd", 0)
                annotationsArray.add(annNode)
            }
            root.set<JsonNode>("annotations", annotationsArray)

            // 直接写 root 节点；此前写 root.toPrettyString() 会把整份 COCO 当字符串
            // 再包一层引号，产生损坏的 JSON
            JSON.writerWithDefaultPrettyPrinter().writeValue(file, root)
        } catch (e: Exception) {
            LOG.error("Failed to save COCO data", e)
        }
    }

    fun listImages(): List<TemplateImage> {
        val dir = templateFolder?.toFile() ?: return emptyList()
        if (!dir.isDirectory) return emptyList()

        val extensions = setOf("png", "jpg", "jpeg", "bmp")
        return dir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in extensions }
            ?.sortedByDescending { it.lastModified() }
            ?.map { file ->
                val imgEntry = cocoData.images.find { it.fileName == file.name }
                val width = imgEntry?.width ?: readImageWidth(file)
                val height = imgEntry?.height ?: readImageHeight(file)
                val annotations = if (imgEntry != null) {
                    cocoData.annotationsForImage(imgEntry.id)
                } else {
                    emptyList()
                }
                TemplateImage(file.nameWithoutExtension, file, width, height, annotations)
            }
            ?: emptyList()
    }

    fun addImageEntry(fileName: String, width: Int, height: Int): CocoImage {
        val existing = cocoData.images.find { it.fileName == fileName }
        if (existing != null) return existing
        return cocoData.addImage(fileName, width, height)
    }

    fun removeImageEntry(imageId: Int) {
        cocoData.removeImage(imageId)
    }

    fun getImageEntryForFile(fileName: String): CocoImage? =
        cocoData.images.find { it.fileName == fileName }

    fun getAnnotationsForImage(imageId: Int): List<CocoAnnotation> =
        cocoData.annotationsForImage(imageId)

    fun categories(): List<CocoCategory> = cocoData.categories.toList()

    fun getOrCreateCategory(name: String): CocoCategory = cocoData.getOrCreateCategory(name)

    /** 用新的 (categoryId, bbox) 列表整体替换一张图的标注（id 重新分配），随后调用 [save] 落盘。 */
    fun replaceAnnotationsForImage(imageId: Int, items: List<Pair<Int, IntArray>>) {
        cocoData.setAnnotationsForImage(imageId, emptyList())
        for ((categoryId, bbox) in items) {
            cocoData.addAnnotation(imageId, categoryId, bbox)
        }
    }

    fun setAnnotationsForImage(imageId: Int, annotations: List<CocoAnnotation>) {
        cocoData.setAnnotationsForImage(imageId, annotations)
    }

    fun deleteImage(file: File) {
        val imgEntry = cocoData.images.find { it.fileName == file.name }
        if (imgEntry != null) {
            cocoData.removeImage(imgEntry.id)
        }
        if (file.exists()) {
            file.delete()
        }
    }

    fun readImageDimensions(file: File): Pair<Int, Int> {
        val imgEntry = cocoData.images.find { it.fileName == file.name }
        if (imgEntry != null) return imgEntry.width to imgEntry.height
        return readImageWidth(file) to readImageHeight(file)
    }

    private fun readImageWidth(file: File): Int {
        return try {
            val img = ImageIO.read(file) ?: return 0
            img.width
        } catch (_: Exception) { 0 }
    }

    private fun readImageHeight(file: File): Int {
        return try {
            val img = ImageIO.read(file) ?: return 0
            img.height
        } catch (_: Exception) { 0 }
    }

    fun importImage(sourceFile: File, targetDir: File): TemplateImage? {
        if (!sourceFile.exists()) return null
        targetDir.mkdirs()

        val extensions = listOf("png", "jpg", "jpeg", "bmp")
        val ext = sourceFile.extension.lowercase()
        if (ext !in extensions) return null

        // Auto-number to avoid conflicts
        val baseName = sourceFile.nameWithoutExtension
        var targetName = sourceFile.name
        var counter = 1
        while (File(targetDir, targetName).exists()) {
            targetName = "${baseName}_${counter}.$ext"
            counter++
        }

        val targetFile = File(targetDir, targetName)
        Files.copy(sourceFile.toPath(), targetFile.toPath())

        val (w, h) = readImageDimensions(targetFile)
        val imgEntry = addImageEntry(targetName, w, h)
        save()

        return TemplateImage(targetName.removeSuffix(".$ext"), targetFile, w, h, emptyList())
    }
}
