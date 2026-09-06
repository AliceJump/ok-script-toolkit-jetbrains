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

// ── saveToAssets 内部结构 ────────────────────────────────────────

private class PackedPage(
    val imgIds: MutableList<Int> = mutableListOf(),
    val occupancy: MutableList<IntArray> = mutableListOf(),
)

private class Page(
    val W: Int,
    val H: Int,
    val items: MutableList<AnnotatedEntry> = mutableListOf(),
)

private data class AnnotatedEntry(val img: CocoImage, val ann: CocoAnnotation)

// ── Service ──────────────────────────────────────────────────────

@Service(Service.Level.PROJECT)
class TemplateAssetDataService(private val project: Project) {

    companion object {
        private val LOG = Logger.getInstance(TemplateAssetDataService::class.java)

        /** 序列化整份 COCO（可独立单测）。 */
        fun serializeCoco(coco: CocoData): ObjectNode {
            val root = JSON.createObjectNode()
            val imagesArray = JSON.createArrayNode()
            for (img in coco.images) {
                val imgNode = JSON.createObjectNode()
                imgNode.put("id", img.id)
                imgNode.put("file_name", img.fileName)
                imgNode.put("width", img.width)
                imgNode.put("height", img.height)
                imagesArray.add(imgNode)
            }
            root.set<JsonNode>("images", imagesArray)

            val categoriesArray = JSON.createArrayNode()
            for (cat in coco.categories) {
                val catNode = JSON.createObjectNode()
                catNode.put("id", cat.id)
                catNode.put("name", cat.name)
                categoriesArray.add(catNode)
            }
            root.set<JsonNode>("categories", categoriesArray)

            val annotationsArray = JSON.createArrayNode()
            for (ann in coco.annotations) {
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
            return root
        }

        /** 从 JSON 反序列化 COCO（可独立单测）。 */
        fun parseCoco(root: JsonNode): CocoData {
            val coco = CocoData()
            root.get("images")?.forEach { imgNode ->
                coco.images.add(
                    CocoImage(
                        imgNode.get("id").asInt(),
                        imgNode.get("file_name").asText(),
                        imgNode.get("width").asInt(),
                        imgNode.get("height").asInt(),
                    ),
                )
            }
            root.get("categories")?.forEach { catNode ->
                coco.categories.add(
                    CocoCategory(
                        catNode.get("id").asInt(),
                        catNode.get("name").asText(),
                    ),
                )
            }
            root.get("annotations")?.forEach { annNode ->
                val bboxNode = annNode.get("bbox")
                val bbox = if (bboxNode != null && bboxNode.isArray && bboxNode.size() >= 4) {
                    intArrayOf(bboxNode[0].asInt(), bboxNode[1].asInt(), bboxNode[2].asInt(), bboxNode[3].asInt())
                } else {
                    intArrayOf(0, 0, 0, 0)
                }
                coco.annotations.add(
                    CocoAnnotation(
                        annNode.get("id").asInt(),
                        annNode.get("image_id").asInt(),
                        annNode.get("category_id").asInt(),
                        bbox,
                        annNode.get("area")?.asInt() ?: (bbox[2] * bbox[3]),
                    ),
                )
            }
            coco.initIds()
            return coco
        }

        // 对齐 VSCode 版：素材面板的 COCO 是模板目录自己的 coco_annotations.json
        fun cocoPath(projectDir: String, templatesDir: String = "ok_templates"): Path =
            Paths.get(projectDir, templatesDir, "coco_annotations.json")

        fun templateDir(projectDir: String, templatesDir: String): Path =
            Paths.get(projectDir, templatesDir)
    }

    @Volatile
    private var cocoData = CocoData()
    private var cocoFile: Path? = null
    private var templateFolder: Path? = null

    fun load(projectDir: String, templatesDir: String = "ok_templates"): CocoData {
        templateFolder = templateDir(projectDir, templatesDir)
        cocoFile = cocoPath(projectDir, templatesDir)

        cocoData = CocoData()
        val file = cocoFile?.toFile()
        if (file != null && file.exists()) {
            try {
                cocoData = parseCoco(JSON.readTree(file))
            } catch (e: Exception) {
                LOG.warn("Failed to load COCO data", e)
                cocoData = CocoData()
            }
        }
        return cocoData
    }

    fun save() {
        val file = cocoFile?.toFile() ?: return
        try {
            file.parentFile?.mkdirs()
            // 直接写 root 节点；此前写 root.toPrettyString() 会把整份 COCO 当字符串
            // 再包一层引号，产生损坏的 JSON
            JSON.writerWithDefaultPrettyPrinter().writeValue(file, serializeCoco(cocoData))
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

    /** 只读图片头取尺寸（ImageReader 不解码像素），失败返回 null。 */
    fun readImageHeaderSize(file: File): Pair<Int, Int>? {
        return try {
            val iis = javax.imageio.ImageIO.createImageInputStream(file)
            val readers = javax.imageio.ImageIO.getImageReaders(iis)
            if (!readers.hasNext()) { iis.close(); return null }
            val reader = readers.next()
            reader.input = iis
            try {
                val w = reader.getWidth(0)
                val h = reader.getHeight(0)
                w to h
            } finally {
                reader.dispose()
                iis.close()
            }
        } catch (_: Exception) { null }
    }

    /**
     * saveToAssets 打包导出（对齐 VSCode 版与 ok-script FeatureSet.compress_coco 语义）：
     * 只处理有标注图片 -> 按原始尺寸分组 -> 组内 bbox 互不重叠的原图共享同一页
     * 画布（原坐标粘贴）-> 每页输出 images/<n>.png -> 重写 target/coco_annotations.json
     * （bbox 保留原坐标、清理无引用分类）-> 可选生成 LabelEnum.py。
     * 在后台线程调用；onProgress 汇报 (done, total)。
     */
    fun saveToAssets(
        targetFolder: String,
        generateEnum: Boolean,
        enumPath: String?,
        onProgress: (Int, Int) -> Unit,
    ) {
        val targetImagesDir = Paths.get(targetFolder, "images")

        // 清空目标目录中的旧图片（重新生成前清理）
        if (Files.isDirectory(targetImagesDir)) {
            Files.list(targetImagesDir).use { stream ->
                stream.forEach { runCatching { Files.deleteIfExists(it) } }
            }
        }
        Files.createDirectories(targetImagesDir)

        // 1. 只处理有标注的图片
        val annotatedImages = cocoData.images.filter { img ->
            cocoData.annotations.any { it.imageId == img.id }
        }

        // 2. 按原始尺寸分组（头读尺寸优先，失败回退 COCO 记录值）
        val dimGroups = linkedMapOf<String, MutableList<AnnotatedEntry>>()

        for (img in annotatedImages) {
            val srcFile = templateFolder?.resolve(img.fileName)?.toFile() ?: continue
            if (!srcFile.exists()) continue
            var imgW = img.width
            var imgH = img.height
            readImageHeaderSize(srcFile)?.let { (w, h) ->
                imgW = w
                imgH = h
            }
            for (ann in cocoData.annotationsForImage(img.id)) {
                val key = "${imgW}x${imgH}"
                dimGroups.getOrPut(key) { mutableListOf() }.add(AnnotatedEntry(img.copy(width = imgW, height = imgH), ann))
            }
        }

        // 3. 组内 bin-packing：bbox 互不重叠的原图共享同一页
        val pageList = mutableListOf<Page>()
        for ((_, entries) in dimGroups) {
            val W = entries.first().img.width
            val H = entries.first().img.height

            val imgRects = linkedMapOf<Int, MutableList<IntArray>>()
            for (e in entries) {
                imgRects.getOrPut(e.img.id) { mutableListOf() }.add(
                    intArrayOf(e.ann.bbox[0], e.ann.bbox[1], e.ann.bbox[0] + e.ann.bbox[2], e.ann.bbox[1] + e.ann.bbox[3]),
                )
            }

            val pages = mutableListOf<PackedPage>()
            for (imgId in imgRects.keys) {
                val rects = imgRects[imgId] ?: continue
                var assigned = false
                for (page in pages) {
                    val conflict = rects.any { cRect ->
                        page.occupancy.any { pRect ->
                            cRect[0] < pRect[2] && cRect[2] > pRect[0] && cRect[1] < pRect[3] && cRect[3] > pRect[1]
                        }
                    }
                    if (!conflict) {
                        page.imgIds.add(imgId)
                        page.occupancy.addAll(rects)
                        assigned = true
                        break
                    }
                }
                if (!assigned) {
                    pages.add(PackedPage(mutableListOf(imgId), rects.toMutableList()))
                }
            }

            for (page in pages) {
                val ids = page.imgIds.toHashSet()
                pageList.add(Page(W, H, entries.filter { it.img.id in ids }.toMutableList<AnnotatedEntry>()))
            }
        }

        // 4. 确定性命名：第 i 页 -> images/<i+1>.png；COCO 元数据先构建
        val newImages = mutableListOf<CocoImage>()
        val newAnnotations = mutableListOf<CocoAnnotation>()
        var nextAnnId = 1
        for ((pageIndex, page) in pageList.withIndex()) {
            val packedImgId = pageIndex + 1
            newImages.add(CocoImage(packedImgId, "images/$packedImgId.png", page.W, page.H))
            for (it in page.items) {
                val bw = it.ann.bbox[2]
                val bh = it.ann.bbox[3]
                newAnnotations.add(
                    CocoAnnotation(nextAnnId++, packedImgId, it.ann.categoryId, it.ann.bbox.copyOf(), bw * bh),
                )
            }
        }

        // 5. 渲染全部页：白底画布 + 原坐标粘贴（同源图多 bbox 只解码一次）
        val total = pageList.size
        var completed = 0
        onProgress(completed, total)
        for ((pageIndex, page) in pageList.withIndex()) {
            val canvas = java.awt.image.BufferedImage(page.W, page.H, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val canvasG = canvas.createGraphics()
            canvasG.color = java.awt.Color.WHITE
            canvasG.fillRect(0, 0, page.W, page.H)
            canvasG.dispose()

            val byImage = linkedMapOf<String, MutableList<IntArray>>()
            for (it in page.items) {
                byImage.getOrPut(it.img.fileName) { mutableListOf() }.add(it.ann.bbox.copyOf())
            }
            for ((fileName, rects) in byImage) {
                val srcFile = templateFolder?.resolve(fileName)?.toFile() ?: continue
                val decoded = runCatching { ImageIO.read(srcFile) }.getOrNull() ?: continue
                val g = canvas.createGraphics()
                for (r in rects) {
                    val x1 = r[0].coerceAtLeast(0)
                    val y1 = r[1].coerceAtLeast(0)
                    val x2 = (r[0] + r[2]).coerceAtMost(page.W)
                    val y2 = (r[1] + r[3]).coerceAtMost(page.H)
                    if (x2 > x1 && y2 > y1) {
                        g.drawImage(decoded.getSubimage(x1, y1, x2 - x1, y2 - y1), x1, y1, null)
                    }
                }
                g.dispose()
            }

            val outPath = targetImagesDir.resolve("${pageIndex + 1}.png")
            Files.createDirectories(outPath.parent)
            ImageIO.write(canvas, "png", outPath.toFile())
            completed++
            onProgress(completed, total)
        }

        // 6. 重写 target 的 coco_annotations.json（清理无引用分类）
        val usedCatIds = newAnnotations.map { it.categoryId }.toSet()
        val croppedCoco = CocoData(
            newImages.toMutableList(),
            newAnnotations.toMutableList(),
            cocoData.categories.filter { it.id in usedCatIds }.toMutableList(),
        )

        val cocoTarget = Paths.get(targetFolder, "coco_annotations.json").toFile()
        cocoTarget.parentFile?.mkdirs()
        JSON.writerWithDefaultPrettyPrinter().writeValue(cocoTarget, serializeCoco(croppedCoco))

        if (generateEnum) {
            val labels = croppedCoco.categories.map { it.name }.sorted()
            val enumFile = enumPath ?: Paths.get(targetFolder, "LabelEnum.py").toString()
            generateLabelEnum(enumFile, labels)
        }
    }

    /** 由分类标签生成 Python 枚举文件（对齐 VSCode 版 generateLabelEnum）。 */
    fun generateLabelEnum(filePath: String, labels: List<String>) {
        val file = File(filePath)
        file.parentFile?.mkdirs()
        val className = file.nameWithoutExtension
        val content = buildString {
            append("from enum import Enum\n\n\n")
            append("class ").append(className).append("(str, Enum):\n")
            for (label in labels) {
                append("    ").append(label).append(" = '").append(label).append("'\n")
            }
        }
        file.writeText(content, Charsets.UTF_8)
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

    /**
     * 下一个可用图片名：纯数字递增（对齐 VSCode 版 nextImageName），
     * 以 COCO 已有图片的基名为占位判断依据，模板名即数字序号。
     */
    fun nextImageName(): String {
        val existing = cocoData.images.map { it.fileName.substringBeforeLast('.') }.toSet()
        var i = 1
        while (i.toString() in existing) i++
        return i.toString()
    }

    /**
     * 批量导入图片到 ok_templates（对齐 VSCode 版 handleImport）：
     * 仅支持裁剪/打包管线的 PNG/JPEG/BMP，重命名为数字序号并注册进 COCO，
     * 全部完成后一次性 save；返回成功导入的数量（失败的文件跳过）。
     */
    fun importImages(sourceFiles: List<File>, targetDir: File): Int {
        targetDir.mkdirs()

        val extensions = setOf("png", "jpg", "jpeg", "bmp")
        var count = 0
        for (sourceFile in sourceFiles) {
            try {
                if (!sourceFile.exists()) continue
                val ext = sourceFile.extension.lowercase()
                if (ext !in extensions) continue

                val targetName = nextImageName() + "." + ext
                val targetFile = File(targetDir, targetName)
                if (targetFile.exists()) continue
                Files.copy(sourceFile.toPath(), targetFile.toPath())

                val (w, h) = readImageDimensions(targetFile)
                addImageEntry(targetName, w, h)
                count++
            } catch (_: Exception) {
                // 跳过失败的文件
            }
        }
        if (count > 0) save()
        return count
    }
}
