package com.alicejump.okscripttoolkit.core

import com.alicejump.okscripttoolkit.settings.OkScriptToolkitSettings
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** Rect authoring catalog; runtime publication belongs to the unified Position publisher. */
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

    @Synchronized
    fun readAuthoring(): BoxResource.AuthoringFile = synchronized(annotations) {
        if (root() == null) return@synchronized BoxResource.AuthoringFile()
        BoxResource.authoringFromCoco(loadAnnotations().data)
    }

    fun authoringErrors(): List<String> = loadAnnotations().readErrors

    private fun headerSize(file: Path): AnnotationSwap.Size? {
        val image = project.service<TemplateAssetDataService>().readImageHeaderSize(file.toFile()) ?: return null
        return AnnotationSwap.Size(image.first, image.second)
    }

    /** Real image header wins; COCO dimensions are only a fallback. */
    fun imageSize(fileName: String): AnnotationSwap.Size? {
        val name = BoxResource.imageFileName(fileName)
        templatesDirPath()?.resolve(name)?.let { headerSize(it) }?.let { return it }
        return readAuthoring().images
            .firstOrNull { BoxResource.sameImageName(it.file, name) }
            ?.let(BoxResource::usableImageSize)
    }

    @Synchronized
    fun boxesForImage(fileName: String): List<BoxResource.AuthoringBox> =
        readAuthoring().boxes.filter { sameImage(it.image, fileName) }

    /** One read, grouped by image name. */
    @Synchronized
    fun boxCounts(): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        for (box in readAuthoring().boxes) {
            val key = BoxResource.imageFileName(box.image).lowercase()
            counts[key] = (counts[key] ?: 0) + 1
        }
        return counts
    }

    /** Snapshot before image deletion. null text means boxes.json did not exist. */
    data class AuthoringSnapshot(val text: String?)

    @Synchronized
    fun captureAuthoring(): AuthoringSnapshot? {
        val path = authoringPath() ?: return null
        if (Files.notExists(path)) return AuthoringSnapshot(null)
        val text = readFile(path) ?: return null
        return AuthoringSnapshot(text)
    }

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

    /** Missing source / unregistered image is a no-op and does not create a file. */
    @Synchronized
    fun removeImage(fileName: String): Boolean = loadAnnotations().removeImageAnnotations(fileName)

    fun pathOwners(): Map<String, String> = readAuthoring().boxes.associate { it.path to it.image }

    @Synchronized
    fun pathOwnersExcept(fileName: String): Map<String, String> =
        readAuthoring().boxes
            .filter { !sameImage(it.image, fileName) }
            .associate { it.path to it.image }

    /** New rects are ordinary COCO annotations. */
    @Synchronized
    fun addBox(path: String, image: String, bbox: IntArray): String? = synchronized(annotations) {
        if (root() == null) return@synchronized "image"
        addBoxAnnotation(loadAnnotations(), path, image, bbox)
    }

    private fun readFile(path: Path): String? = try {
        Files.readString(path, StandardCharsets.UTF_8)
    } catch (e: Exception) {
        LOG.warn("Failed to read $path", e)
        null
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
