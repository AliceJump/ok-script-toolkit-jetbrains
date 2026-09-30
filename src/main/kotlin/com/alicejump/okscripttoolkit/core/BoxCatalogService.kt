package com.alicejump.okscripttoolkit.core

import com.alicejump.okscripttoolkit.settings.OkScriptToolkitSettings
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** 框的名称校验和运行时导出适配；源数据的读取、保存、图片登记共用 COCO 层。 */
@Service(Service.Level.PROJECT)
class BoxCatalogService(private val project: Project) {
    val annotations = newBoxAnnotationData()

    @Synchronized
    private fun loadAnnotations(): CocoAnnotationData {
        root()?.let { annotations.load(it.toString(), templatesDirectory()) }
        return annotations
    }

    fun authoringPath(): Path? {
        val root = root() ?: return null
        return BoxResource.authoringFile(root.toString(), templatesDirectory())
    }

    fun runtimeWritePath(): Path? {
        if (root() == null) return null
        return BoxRuntimePath.writeTarget(project.service<OkProjectDataService>().boxRuntimePlan())
    }

    @Synchronized
    fun readAuthoring(): BoxResource.AuthoringFile = synchronized(annotations) {
        if (root() == null) return@synchronized BoxResource.AuthoringFile()
        BoxResource.authoringFromCoco(loadAnnotations().data)
    }

    fun authoringErrors(): List<String> = loadAnnotations().readErrors

    private fun headerSize(file: Path): AnnotationSwap.Size? {
        val image = project.service<TemplateAssetDataService>().readImageHeaderSize(file.toFile())
            ?: return null
        return AnnotationSwap.Size(image.first, image.second)
    }

    /** 真实图片头优先；读不到时才回退到 COCO 中的尺寸。 */
    fun imageSize(fileName: String): AnnotationSwap.Size? {
        val name = BoxResource.imageFileName(fileName)
        templatesDirPath()?.resolve(name)?.let { headerSize(it) }?.let { return it }
        return readAuthoring().images.firstOrNull { BoxResource.sameImageName(it.file, name) }?.let(BoxResource::usableImageSize)
    }

    fun readRuntime(): BoxResource.RuntimeFile = readRuntimeResult().file

    private fun readRuntimeResult(): BoxResource.ParseResult<BoxResource.RuntimeFile> {
        val plan = project.service<OkProjectDataService>().boxRuntimePlan()
        val path = BoxRuntimePath.effectiveFile(plan) { Files.isRegularFile(it) }
            ?: return BoxResource.ParseResult(BoxResource.RuntimeFile(), emptyList())
        val text = readFile(path) ?: return BoxResource.ParseResult(BoxResource.RuntimeFile(), listOf("read"))
        return BoxResource.parseRuntime(text)
    }

    private fun readFile(path: Path): String? = try {
        Files.readString(path, StandardCharsets.UTF_8)
    } catch (e: Exception) {
        LOG.warn("Failed to read $path", e)
        null
    }

    @Synchronized
    fun boxesForImage(fileName: String): List<BoxResource.AuthoringBox> =
        readAuthoring().boxes.filter { sameImage(it.image, fileName) }

    /** 一次读盘，按图片文件名汇总框数量。网格渲染不要对每张图各读一遍。 */
    @Synchronized
    fun boxCounts(): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        for (box in readAuthoring().boxes) {
            val key = BoxResource.imageFileName(box.image).lowercase()
            counts[key] = (counts[key] ?: 0) + 1
        }
        return counts
    }

    /** 删图前的 boxes.json。text 为 null 表示当时没有这个文件。读失败返回 null。 */
    data class AuthoringSnapshot(val text: String?)

    @Synchronized
    fun captureAuthoring(): AuthoringSnapshot? {
        val path = authoringPath() ?: return null
        if (Files.notExists(path)) return AuthoringSnapshot(null)
        val text = readFile(path) ?: return null
        return AuthoringSnapshot(text)
    }

    /** 图片还在时写回删图前的框文件。快照为空则去掉这次新写出的文件。 */
    @Synchronized
    fun restoreAuthoring(snapshot: AuthoringSnapshot): Boolean {
        val path = authoringPath() ?: return false
        val text = snapshot.text
        if (text == null) {
            if (!Files.isRegularFile(path)) return true
            return try {
                Files.deleteIfExists(path)
                AnnotationDataChanges.notify(path)
                true
            } catch (e: Exception) {
                LOG.warn("Failed to remove boxes file created during a failed delete: $path", e)
                false
            }
        }
        return write(path, text)
    }

    /**
     * 去掉这张图的框，再删文件。整段持有同一把锁。
     * 删除失败且图片还在时，写回进入时的快照；锁没放开前，别的保存进不来。
     */
    @Synchronized
    fun removeImageForDeletion(
        fileName: String,
        imageExists: () -> Boolean,
        deleteImage: () -> Boolean,
    ): String = synchronized(annotations) {
        val snapshot = captureAuthoring() ?: return@synchronized "boxes"
        if (!removeImage(fileName)) return@synchronized "boxes"
        val deleted = try {
            deleteImage()
        } catch (e: Exception) {
            LOG.warn("Image delete threw after boxes were removed for $fileName", e)
            false
        }
        if (deleted) return@synchronized "ok"
        if (imageExists() && restoreAuthoring(snapshot)) return@synchronized "boxes"
        "image"
    }

    /** 没有源文件或未登记该图片时不创建文件。 */
    @Synchronized
    fun removeImage(fileName: String): Boolean = loadAnnotations().removeImageAnnotations(fileName)

    /** 全项目框 path 占用表：path → 所属图片。含当前图 —— 「生成框」要拦住和已有框重名。 */
    fun pathOwners(): Map<String, String> =
        readAuthoring().boxes.associate { it.path to it.image }

    @Synchronized
    fun pathOwnersExcept(fileName: String): Map<String, String> =
        readAuthoring().boxes
            .filter { !sameImage(it.image, fileName) }
            .associate { it.path to it.image }

    /** 首次绘制和模板生成都提交普通 COCO 标注，并自动登记原图尺寸。 */
    @Synchronized
    fun addBox(path: String, image: String, bbox: IntArray): String? = synchronized(annotations) {
        if (root() == null) return@synchronized "image"
        addBoxAnnotation(loadAnnotations(), path, image, bbox)
    }

    fun runtimeOnlyPaths(): List<String> {
        val authoring = readAuthoring()
        val runtime = readRuntime()
        return BoxResource.publishStatus(authoring, runtime)
            .filter { it.status == BoxResource.PublishStatus.RUNTIME_ONLY }
            .map { it.path }
    }

    /**
     * 发布：Pixel → normalized 的转换在 [BoxResource.publish]。
     * 返回错误列表（`size:<path>` / `parse` / `runtimeRead` / `same` / `write`），空列表即成功。
     */
    @Synchronized
    fun publish(): List<String> = synchronized(annotations) {
        val source = loadAnnotations()
        if (source.readErrors.isNotEmpty()) return@synchronized listOf("parse")
        if (source.data.annotations.isEmpty()) return@synchronized listOf("empty")
        val target = runtimeWritePath() ?: return@synchronized listOf("write")
        publishBoxAnnotations(source, target, readRuntimeResult().errors)
    }

    private fun write(target: Path?, text: String): Boolean {
        if (target == null || !writeAnnotationText(target, text)) return false
        AnnotationDataChanges.notify(target)
        return true
    }

    private fun root() = project.service<OkProjectDataService>().rootPath()

    private fun templatesDirectory(): String = OkScriptToolkitSettings.getInstance(project).okTemplatesDirectory()

    private fun templatesDirPath(): Path? = root()?.let { it.resolve(templatesDirectory()) }

    companion object {
        private val LOG = Logger.getInstance(BoxCatalogService::class.java)

        fun sameImage(a: String, b: String): Boolean =
            BoxResource.imageFileName(a).equals(BoxResource.imageFileName(b), ignoreCase = true)
    }
}
