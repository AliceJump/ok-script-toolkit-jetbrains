package com.alicejump.okscripttoolkit.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale

private val JSON = com.fasterxml.jackson.databind.ObjectMapper()

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
    val iscrowd: Int = 0,
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
    /** COCO 标准字段：加载时原样保留，新建分类为空串（对齐 VS Code，导出保真） */
    val supercategory: String = "",
)

/** 归一化文件名 key：basename → 小写 → 去扩展名（对齐 VS Code filenameKey）。 */
fun cocoFilenameKey(name: String): String =
    name.substringAfterLast('/').substringAfterLast('\\')
        .lowercase(Locale.ROOT).replace(Regex("\\.[^.]+$"), "")

data class CocoData(
    val images: MutableList<CocoImage> = mutableListOf(),
    val annotations: MutableList<CocoAnnotation> = mutableListOf(),
    val categories: MutableList<CocoCategory> = mutableListOf(),
) {
    /**
     * 归一化文件名 key：basename → 小写 → 去扩展名（对齐 VS Code filenameKey）。
     * COCO 的 file_name 与磁盘实际大小写不一致（Windows 上很常见）时也要能对上。
     */
    fun filenameKey(name: String): String = cocoFilenameKey(name)

    /**
     * 按完整文件名找记录。大小写不同但指向同一文件时可以命中。
     * 不去掉扩展名：`shot.png` 和 `shot.jpg` 是两张图。
     */
    fun findImageByFileName(fileName: String): CocoImage? {
        val base = fileName.replace('\\', '/').substringAfterLast('/')
        images.find { it.fileName == base }?.let { return it }
        val lower = base.lowercase()
        return images.filter { it.fileName.lowercase() == lower }.singleOrNull()
    }

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
        val usedCategoryIds = annotations.mapTo(mutableSetOf()) { it.categoryId }
        categories.removeAll { it.id !in usedCategoryIds }
    }

    fun annotationsForImage(imageId: Int): List<CocoAnnotation> =
        annotations.filter { it.imageId == imageId }

    fun setAnnotationsForImage(imageId: Int, annotations: List<CocoAnnotation>) {
        this.annotations.removeAll { it.imageId == imageId }
        this.annotations.addAll(annotations)
    }
}

/** 一张图在标注对话框中待提交的完整框列表。 */
data class CocoAnnotationEdit(
    val fileName: String,
    val imageSize: Pair<Int, Int>?,
    val boxes: List<Pair<String, IntArray>>,
)

data class TemplateImage(
    val name: String,
    val file: File,
    val width: Int,
    val height: Int,
    val annotations: List<CocoAnnotation>,
)

/** 模板和框共用的 COCO 源数据；文件名、名称校验和导出由各自入口决定。 */
open class CocoAnnotationData(
    private val fileName: String = "coco_annotations.json",
    private val decode: (String, (String) -> Pair<Int, Int>?) -> CocoReadResult = { text, _ -> parseCocoText(text) },
    private val validateNames: (CocoData, List<CocoAnnotationEdit>) -> String? = { _, _ -> null },
) {
    companion object {
        private val LOG = Logger.getInstance(CocoAnnotationData::class.java)
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
                catNode.put("supercategory", cat.supercategory)
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
                annNode.put("iscrowd", ann.iscrowd)
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
                        catNode.get("supercategory")?.takeIf { !it.isNull }?.asText("") ?: "",
                    ),
                )
            }
            root.get("annotations")?.forEach { annNode ->
                val bboxNode = annNode.get("bbox")
                val bbox = if (bboxNode != null && bboxNode.isArray && bboxNode.size() >= 4) {
                    AnnotationGeometry.roundBbox(bboxNode.map { it.takeIf { node -> node.isNumber }?.asDouble() }) ?: intArrayOf(0, 0, 0, 0)
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
                        annNode.get("iscrowd")?.asInt() ?: 0,
                    ),
                )
            }
            coco.initIds()
            return coco
        }

        // 对齐 VSCode 版：素材面板的 COCO 是模板目录自己的 coco_annotations.json
        //
        // `templatesDir` **刻意不给默认值**：目录名可配（取值链见
        // `OkScriptToolkitSettings.okTemplatesDirectory()`），留一个 `"ok_templates"`
        // 默认值等于给调用方留了一条**绕过取值链**的静默通道 —— 谁少传一个参数，
        // 就会去读一个可能不存在的目录，而界面只是"空列表"，看不出是配置没生效。
        fun cocoPath(projectDir: String, templatesDir: String): Path =
            templateDir(projectDir, templatesDir).resolve("coco_annotations.json")

        fun templateDir(projectDir: String, templatesDir: String): Path =
            Paths.get(projectDir).resolve(templatesDir).normalize()
    }

    @Volatile
    protected var cocoData = CocoData()
    protected var cocoFile: Path? = null
    protected var templateFolder: Path? = null

    var readErrors: List<String> = emptyList()
        private set
    var lastError: String? = null
        private set
    var revision: String? = null
        private set
    private var legacy = false
    val annotationFile: Path? get() = cocoFile
    val data: CocoData get() = cocoData

    private fun decodeText(text: String): CocoReadResult = decode(text) { name ->
        templateFolder?.resolve(name)?.toFile()?.let(::readImageHeaderSize)
    }

    private fun readSource(): Pair<String?, CocoReadResult> {
        val path = cocoFile ?: return null to CocoReadResult(errors = listOf("read"))
        return try {
            val text = Files.readString(path)
            text to decodeText(text)
        } catch (_: java.nio.file.NoSuchFileException) {
            null to CocoReadResult()
        } catch (e: Exception) {
            LOG.warn("Failed to read COCO data at $path", e)
            null to CocoReadResult(errors = listOf("read"))
        }
    }

    @Synchronized
    open fun load(projectDir: String, templatesDir: String): CocoData {
        templateFolder = templateDir(projectDir, templatesDir)
        cocoFile = templateFolder!!.resolve(fileName)

        val (text, parsed) = readSource()
        revision = text
        cocoData = parsed.data
        readErrors = parsed.errors
        legacy = parsed.legacy
        return cocoData
    }

    @Synchronized
    fun reload(): CocoData {
        val (text, parsed) = readSource()
        revision = text
        cocoData = parsed.data
        readErrors = parsed.errors
        legacy = parsed.legacy
        return cocoData
    }

    @Synchronized
    fun save(): Boolean = writeCoco(cocoData)

    protected fun copyCoco(): CocoData = parseCoco(serializeCoco(cocoData))

    /** 所有编辑作为一次写盘提交；失败时不改动服务中的 COCO 状态。 */
    @Synchronized
    fun saveAnnotationEdits(edits: List<CocoAnnotationEdit>): Boolean {
        if (edits.isEmpty()) return true
        lastError = null
        val (text, parsed) = readSource()
        if (parsed.errors.isNotEmpty() || readErrors.isNotEmpty()) { lastError = "parse"; return false }
        val existing = if (text == revision) cocoData else parsed.data
        validateNames(existing, edits)?.let { lastError = it; return false }
        val updated = parseCoco(serializeCoco(existing))
        for (edit in edits) {
            if (edit.fileName.isBlank()) { lastError = "image"; return false }
            val image = updated.findImageByFileName(edit.fileName) ?: run {
                val size = edit.imageSize ?: return false
                if (size.first <= 0 || size.second <= 0) return false
                updated.addImage(edit.fileName, size.first, size.second)
            }
            val size = edit.imageSize ?: (image.width to image.height)
            if (size.first <= 0 || size.second <= 0) { lastError = "image"; return false }
            updated.images[updated.images.indexOf(image)] = image.copy(width = size.first, height = size.second)
            updated.setAnnotationsForImage(image.id, emptyList())
            for ((categoryName, bbox) in edit.boxes) {
                if (categoryName.isBlank() || AnnotationGeometry.bboxError(bbox, AnnotationSwap.Size(size.first, size.second)) != null) {
                    lastError = "rect"; return false
                }
                val category = updated.getOrCreateCategory(categoryName)
                updated.addAnnotation(image.id, category.id, bbox.copyOf())
            }
        }
        legacy = parsed.legacy
        revision = text
        if (!writeCoco(updated)) return false
        cocoData = updated
        return true
    }

    enum class SwapSaveResult { SAVED, CHANGED, FAILED }

    /**
     * 交换用的真实尺寸：先读图片头，读不到再用 COCO 记录（与 VS Code `resolveImageSize` 同序）。
     * COCO 里的旧尺寸可能早已不是磁盘上那张图的尺寸。
     */
    @Synchronized
    fun swapImageSize(file: File): Pair<Int, Int>? {
        readImageHeaderSize(file)?.takeIf { it.first > 0 && it.second > 0 }?.let { return it }
        val entry = cocoData.findImageByFileName(file.name) ?: return null
        return if (entry.width > 0 && entry.height > 0) entry.width to entry.height else null
    }

    fun resolveImageSize(fileName: String): Pair<Int, Int>? =
        templateFolder?.resolve(fileName)?.toFile()?.let(::swapImageSize)

    /**
     * 交换写盘：在同一把锁内核对确认前的快照，再整体提交。
     * - 两张图仍在磁盘上，且尺寸与确认时一致（图片可能被外部删除或替换）；
     * - 磁盘 COCO 仍存在且与内存完全一致 —— 外部只改第三张图也不能被旧内存整份覆盖；
     * - 两张图的当前标注仍是确认前的快照（IDE 内其他编辑器经本服务写入的修改）。
     */
    @Synchronized
    fun saveSwapEdits(
        expected: Map<String, List<Pair<String, IntArray>>>,
        expectedSizes: Map<File, Pair<Int, Int>>,
        edits: List<CocoAnnotationEdit>,
    ): SwapSaveResult {
        // A deleted image would otherwise fall back to its COCO size and still pass.
        for ((file, size) in expectedSizes) {
            if (!file.isFile || swapImageSize(file) != size) return SwapSaveResult.CHANGED
        }
        val file = cocoFile?.toFile() ?: return SwapSaveResult.FAILED
        if (!file.isFile) return SwapSaveResult.CHANGED
        val parsed = readSource().second
        if (parsed.errors.isNotEmpty()) return SwapSaveResult.FAILED
        val disk = parsed.data
        if (serializeCoco(disk) != serializeCoco(cocoData)) return SwapSaveResult.CHANGED
        val names = cocoData.categories.associate { it.id to it.name }
        for ((fileName, boxes) in expected) {
            val current = cocoData.findImageByFileName(fileName)?.let { cocoData.annotationsForImage(it.id) }.orEmpty()
            val named = AnnotationSwap.namedBoxes(current, names) ?: return SwapSaveResult.CHANGED
            if (!AnnotationSwap.sameBoxes(named, boxes)) return SwapSaveResult.CHANGED
        }
        return if (saveAnnotationEdits(edits)) SwapSaveResult.SAVED else SwapSaveResult.FAILED
    }

    /** 截图登记一次性提交；补旧图片尺寸时保留原有 ID、分类和标注。 */
    @Synchronized
    fun registerImageAndSave(fileName: String, width: Int, height: Int): Boolean {
        val updated = copyCoco()
        val existing = updated.findImageByFileName(fileName)
        if (existing == null) {
            updated.addImage(fileName, width, height)
        } else if (existing.width == 0 || existing.height == 0) {
            val index = updated.images.indexOf(existing)
            updated.images[index] = existing.copy(width = width, height = height)
        } else {
            return true
        }
        if (!writeCoco(updated)) return false
        cocoData = updated
        return true
    }

    /** 临时文件与目标同目录，替换前的旧 COCO 始终保持完整。 */
    protected fun writeCoco(data: CocoData): Boolean {
        val target = cocoFile ?: return false
        if (readErrors.isNotEmpty()) return false
        val text = serializeCoco(data).toPrettyString() + "\n"
        try {
            if (legacy && Files.isRegularFile(target)) {
                Files.copy(target, target.resolveSibling(target.fileName.toString() + ".pre-coco." + java.util.UUID.randomUUID() + ".bak"))
            }
            if (!writeAnnotationText(target, text)) { lastError = "write"; return false }
            revision = text
            legacy = false
            cocoData = data
            AnnotationDataChanges.notify(target)
            return true
        } catch (e: Exception) {
            LOG.warn("Failed to save COCO data", e)
            lastError = "write"
            return false
        }
    }

    @Synchronized
    fun removeImageAnnotations(fileName: String): Boolean {
        if (readErrors.isNotEmpty()) return false
        val updated = copyCoco()
        val image = updated.findImageByFileName(fileName) ?: return true
        updated.removeImage(image.id)
        if (!writeCoco(updated)) return false
        cocoData = updated
        return true
    }

    fun listImages(): List<TemplateImage> {
        val dir = templateFolder?.toFile() ?: return emptyList()
        if (!dir.isDirectory) return emptyList()

        val extensions = setOf("png", "jpg", "jpeg", "bmp")
        return dir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in extensions }
            ?.sortedByDescending { it.lastModified() }
            ?.map { file ->
                val imgEntry = cocoData.findImageByFileName(file.name)
                val header = if (imgEntry == null || imgEntry.width <= 0 || imgEntry.height <= 0) {
                    readImageHeaderSize(file)
                } else {
                    null
                }
                val width = header?.first ?: imgEntry?.width ?: 0
                val height = header?.second ?: imgEntry?.height ?: 0
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
        cocoData.findImageByFileName(fileName)?.let { return it }
        return cocoData.addImage(fileName, width, height)
    }

    fun removeImageEntry(imageId: Int) {
        cocoData.removeImage(imageId)
    }

    fun getImageEntryForFile(fileName: String): CocoImage? =
        cocoData.findImageByFileName(fileName)

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

    @Synchronized
    fun deleteImage(file: File): Boolean {
        val updated = copyCoco()
        val hadEntry = updated.findImageByFileName(file.name)?.also { updated.removeImage(it.id) } != null
        val cocoExists = cocoFile?.let { Files.isRegularFile(it) } == true
        var staged: Path? = null
        try {
            if (file.exists()) {
                val temporary = Files.createTempFile(file.parentFile.toPath(), ".ok-delete-", ".tmp")
                staged = temporary
                Files.move(file.toPath(), temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            if (!hadEntry && !cocoExists) {
                staged?.let { temp ->
                    runCatching { Files.deleteIfExists(temp) }
                        .onFailure { LOG.warn("Failed to clean staged template image ${file.name}", it) }
                }
                return true
            }
            if (!writeCoco(updated)) {
                staged?.let { Files.move(it, file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                return false
            }
            cocoData = updated
            staged?.let { temp ->
                runCatching { Files.deleteIfExists(temp) }
                    .onFailure { LOG.warn("Failed to clean staged template image ${file.name}", it) }
            }
            return true
        } catch (e: Exception) {
            LOG.warn("Failed to delete template image ${file.name}", e)
            staged?.let { temp ->
                if (Files.exists(temp) && !file.exists()) {
                    runCatching { Files.move(temp, file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                }
            }
            return false
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

    fun readImageDimensions(file: File): Pair<Int, Int> {
        val imgEntry = cocoData.images.find { it.fileName == file.name }
        if (imgEntry != null && imgEntry.width > 0 && imgEntry.height > 0) {
            return imgEntry.width to imgEntry.height
        }
        return readImageHeaderSize(file) ?: ((imgEntry?.width ?: 0) to (imgEntry?.height ?: 0))
    }

    /**
     * 下一个可用图片名：纯数字递增（对齐 VSCode 版 nextImageName），
     * 以 COCO 已有图片的基名为占位判断依据，模板名即数字序号。
     */
    fun nextImageName(): String {
        val existing = cocoData.images.mapTo(mutableSetOf()) { it.fileName.substringBeforeLast('.') }
        templateFolder?.toFile()?.listFiles()?.forEach { if (it.isFile) existing += it.nameWithoutExtension }
        var i = 1
        while (i.toString() in existing) i++
        return i.toString()
    }

    /**
     * 批量导入图片到 ok_templates（对齐 VSCode 版 handleImport）：
     * 仅支持裁剪/打包管线的 PNG/JPEG/BMP，重命名为数字序号并注册进 COCO，
     * 全部完成后一次性 save；返回成功导入的数量（失败的文件跳过）。
     */
    @Synchronized
    fun importImages(sourceFiles: List<File>, targetDir: File): Int {
        if (!targetDir.isDirectory && !targetDir.mkdirs()) return 0

        val extensions = setOf("png", "jpg", "jpeg", "bmp")
        val updated = copyCoco()
        val copied = mutableListOf<File>()
        val occupiedNames = targetDir.listFiles()?.mapTo(mutableSetOf()) { it.nameWithoutExtension } ?: mutableSetOf()
        occupiedNames.addAll(updated.images.map { it.fileName.substringBeforeLast('.') })
        var count = 0
        for (sourceFile in sourceFiles) {
            try {
                if (!sourceFile.exists()) continue
                val ext = sourceFile.extension.lowercase()
                if (ext !in extensions) continue

                var next = 1
                while (next.toString() in occupiedNames) next++
                val targetName = "$next.$ext"
                val targetFile = File(targetDir, targetName)
                val staged = Files.createTempFile(targetDir.toPath(), ".ok-import-", ".tmp")
                try {
                    Files.copy(sourceFile.toPath(), staged, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    Files.move(staged, targetFile.toPath())
                } finally {
                    Files.deleteIfExists(staged)
                }
                copied.add(targetFile)
                occupiedNames.add(next.toString())

                val (w, h) = readImageHeaderSize(targetFile) ?: (0 to 0)
                updated.addImage(targetName, w, h)
                count++
            } catch (_: Exception) {
                // 跳过失败的文件
            }
        }
        if (count > 0 && !writeCoco(updated)) {
            copied.forEach { file ->
                runCatching { Files.deleteIfExists(file.toPath()) }
                    .onFailure { LOG.warn("Failed to roll back imported image ${file.name}", it) }
            }
            return 0
        }
        if (count > 0) cocoData = updated
        return count
    }
}
