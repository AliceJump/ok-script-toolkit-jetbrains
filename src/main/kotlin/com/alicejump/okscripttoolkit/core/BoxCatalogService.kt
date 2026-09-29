package com.alicejump.okscripttoolkit.core

import com.alicejump.okscripttoolkit.settings.OkScriptToolkitSettings
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 框的两份文件：`<模板目录>/boxes.json` 给编辑，运行时文件给补全和发布。
 * 坐标换算与序列化在 [BoxResource]。
 */
@Service(Service.Level.PROJECT)
class BoxCatalogService(private val project: Project) {
    data class EditedBox(
        val path: String,
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int,
        val original: DoubleArray?,
        val unchanged: Boolean = false,
    )

    fun authoringPath(): Path? {
        val root = root() ?: return null
        return BoxResource.authoringFile(root.toString(), templatesDirectory())
    }

    fun runtimeWritePath(): Path? {
        if (root() == null) return null
        return BoxRuntimePath.writeTarget(project.service<OkProjectDataService>().boxRuntimePlan())
    }

    fun readAuthoring(): BoxResource.AuthoringFile = readAuthoringResult().file

    private fun readAuthoringResult(): BoxResource.ParseResult<BoxResource.AuthoringFile> {
        val path = authoringPath() ?: return BoxResource.ParseResult(BoxResource.AuthoringFile(), emptyList())
        if (!Files.isRegularFile(path)) return BoxResource.ParseResult(BoxResource.AuthoringFile(), emptyList())
        val text = readFile(path) ?: return BoxResource.ParseResult(BoxResource.AuthoringFile(), listOf("read"))
        return BoxResource.parseAuthoring(text)
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
        if (!Files.isRegularFile(path)) return AuthoringSnapshot(null)
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
    ): String {
        val snapshot = captureAuthoring() ?: return "boxes"
        if (!removeImage(fileName)) return "boxes"
        if (deleteImage()) return "ok"
        if (imageExists() && restoreAuthoring(snapshot)) return "boxes"
        return "image"
    }

    /** 删图时去掉它的框。标注文件还不存在就什么都不写。 */
    @Synchronized
    fun removeImage(fileName: String): Boolean {
        val target = authoringPath() ?: return false
        val parsed = readAuthoringResult()
        if (parsed.errors.isNotEmpty()) return false
        if (!Files.isRegularFile(target)) return true
        val next = parsed.file.boxes.filter { !sameImage(it.image, fileName) }
        if (next.size == parsed.file.boxes.size) return true
        return write(target, BoxResource.serializeAuthoring(BoxResource.AuthoringFile(boxes = next)))
    }

    /** 两张图的框整套对调。缺文件且没有框要搬走时不创建文件。 */
    @Synchronized
    fun swapImages(fileA: String, fileB: String): Boolean {
        val target = authoringPath() ?: return false
        val parsed = readAuthoringResult()
        if (parsed.errors.isNotEmpty()) return false
        val nameA = BoxResource.imageFileName(fileA)
        val nameB = BoxResource.imageFileName(fileB)
        var changed = false
        val next = parsed.file.boxes.map { box ->
            when {
                sameImage(box.image, nameA) -> {
                    changed = true
                    box.copy(image = nameB)
                }
                sameImage(box.image, nameB) -> {
                    changed = true
                    box.copy(image = nameA)
                }
                else -> box
            }
        }
        if (!changed) return true
        return write(target, BoxResource.serializeAuthoring(BoxResource.AuthoringFile(boxes = next)))
    }

    @Synchronized
    fun pathOwnersExcept(fileName: String): Map<String, String> =
        readAuthoring().boxes
            .filter { !sameImage(it.image, fileName) }
            .associate { it.path to it.image }

    /** 用这张图上的像素框替换标注资源里引用它的条目。成功返回 null。 */
    @Synchronized
    fun replaceImageBoxes(fileName: String, width: Int, height: Int, boxes: List<EditedBox>): String? =
        commitImageEdits(listOf(BoxResource.ImageReplacement(fileName, width, height, boxes.map { it.toReplacement() })))

    /**
     * 校验全部图片后再写一次标注文件。中途失败时磁盘上的 boxes.json 保持原样。
     */
    @Synchronized
    fun commitImageEdits(edits: List<BoxResource.ImageReplacement>): String? {
        if (edits.isEmpty()) return null
        val parsed = readAuthoringResult()
        if (parsed.errors.isNotEmpty()) return "parse"
        val merged = BoxResource.replaceAuthoringImages(parsed.file.boxes, edits)
        if (merged.error != null) return merged.error
        val text = BoxResource.serializeAuthoring(BoxResource.AuthoringFile(boxes = merged.boxes))
        return if (write(authoringPath(), text)) null else "write"
    }

    @Synchronized
    fun addBox(path: String, image: String, rect: DoubleArray): String? {
        if (!BoxResource.isStorableRect(rect)) return "rect"
        val pathError = BoxResource.pathError(path)
        if (pathError != null) return pathError
        val file = BoxResource.imageFileName(image)
        if (file.isEmpty()) return "image"
        val parsed = readAuthoringResult()
        if (parsed.errors.isNotEmpty()) return "parse"
        val current = parsed.file
        if (current.boxes.any { it.path == path }) return "duplicate"
        val next = current.copy(boxes = current.boxes + BoxResource.AuthoringBox(path, file, rect))
        return if (write(authoringPath(), BoxResource.serializeAuthoring(next))) null else "write"
    }

    fun runtimeOnlyPaths(): List<String> {
        val authoring = readAuthoring()
        val runtime = readRuntime()
        return BoxResource.publishStatus(authoring, runtime)
            .filter { it.status == BoxResource.PublishStatus.RUNTIME_ONLY }
            .map { it.path }
    }

    @Synchronized
    fun publish(): Boolean {
        val parsed = readAuthoringResult()
        if (parsed.errors.isNotEmpty()) return false
        if (readRuntimeResult().errors.isNotEmpty()) return false
        val target = runtimeWritePath() ?: return false
        val authoring = authoringPath()
        if (authoring != null && BoxRuntimePath.sameLocation(authoring, target)) return false
        val text = BoxResource.serializeRuntime(BoxResource.publish(parsed.file))
        return write(target, text)
    }

    private fun write(target: Path?, text: String): Boolean {
        if (target == null) return false
        var temp: Path? = null
        return try {
            Files.createDirectories(target.parent)
            val staged = Files.createTempFile(target.parent, ".boxes-", ".tmp")
            temp = staged
            Files.writeString(staged, text, StandardCharsets.UTF_8)
            try {
                Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING)
            }
            true
        } catch (e: Exception) {
            LOG.warn("Failed to write $target", e)
            false
        } finally {
            temp?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    private fun root() = project.service<OkProjectDataService>().rootPath()

    private fun templatesDirectory(): String = OkScriptToolkitSettings.getInstance(project).okTemplatesDirectory()

    companion object {
        private val LOG = Logger.getInstance(BoxCatalogService::class.java)

        fun sameImage(a: String, b: String): Boolean =
            BoxResource.imageFileName(a).equals(BoxResource.imageFileName(b), ignoreCase = true)
    }
}

private fun BoxCatalogService.EditedBox.toReplacement(): BoxResource.ReplacementBox =
    BoxResource.ReplacementBox(path, x, y, w, h, original, unchanged)
