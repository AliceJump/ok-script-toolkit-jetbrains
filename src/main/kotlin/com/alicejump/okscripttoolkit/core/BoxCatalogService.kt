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
    )

    fun authoringPath(): Path? {
        val root = root() ?: return null
        return BoxResource.authoringFile(root.toString(), templatesDirectory())
    }

    fun runtimeWritePath(): Path? {
        if (root() == null) return null
        return BoxRuntimePath.writeTarget(project.service<OkProjectDataService>().boxRuntimePlan())
    }

    fun readAuthoring(): BoxResource.AuthoringFile {
        val path = authoringPath() ?: return BoxResource.AuthoringFile()
        if (!Files.isRegularFile(path)) return BoxResource.AuthoringFile()
        return BoxResource.parseAuthoring(Files.readString(path, StandardCharsets.UTF_8)).file
    }

    fun readRuntime(): BoxResource.RuntimeFile {
        val plan = project.service<OkProjectDataService>().boxRuntimePlan()
        val path = BoxRuntimePath.effectiveFile(plan) { Files.isRegularFile(it) } ?: return BoxResource.RuntimeFile()
        return BoxResource.parseRuntime(Files.readString(path, StandardCharsets.UTF_8)).file
    }

    fun boxesForImage(fileName: String): List<BoxResource.AuthoringBox> =
        readAuthoring().boxes.filter { sameImage(it.image, fileName) }

    fun pathOwnersExcept(fileName: String): Map<String, String> =
        readAuthoring().boxes
            .filter { !sameImage(it.image, fileName) }
            .associate { it.path to it.image }

    /** 用这张图上的像素框替换标注资源里引用它的条目。成功返回 null。 */
    fun replaceImageBoxes(fileName: String, width: Int, height: Int, boxes: List<EditedBox>): String? {
        val image = BoxResource.imageFileName(fileName)
        if (image.isEmpty() || width <= 0 || height <= 0) return "image"
        val kept = readAuthoring().boxes.filter { !sameImage(it.image, image) }
        val taken = kept.map { it.path }.toMutableSet()
        val next = mutableListOf<BoxResource.AuthoringBox>()
        for (box in boxes) {
            val pathError = BoxResource.pathError(box.path)
            if (pathError != null) return pathError
            if (!taken.add(box.path)) return "duplicate"
            val rect = BoxResource.rectForSave(
                box.original,
                BoxResource.PixelBox(box.x, box.y, box.w, box.h),
                width,
                height,
            ) ?: return "rect"
            next += BoxResource.AuthoringBox(box.path, image, rect)
        }
        return if (write(authoringPath(), BoxResource.serializeAuthoring(BoxResource.AuthoringFile(boxes = kept + next)))) {
            null
        } else {
            "write"
        }
    }

    fun addBox(path: String, image: String, rect: DoubleArray): String? {
        val pathError = BoxResource.pathError(path)
        if (pathError != null) return pathError
        val file = BoxResource.imageFileName(image)
        if (file.isEmpty()) return "image"
        val current = readAuthoring()
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

    fun publish(): Boolean {
        val text = BoxResource.serializeRuntime(BoxResource.publish(readAuthoring()))
        return write(runtimeWritePath(), text)
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
