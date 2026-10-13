package com.alicejump.okscripttoolkit.core

import com.alicejump.okscripttoolkit.settings.OkScriptToolkitSettings
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.nio.file.Path

internal enum class ResourceKind { TEMPLATE, RECT, POINT }

/** Read-only projection, derived afresh from the current authoring data. */
internal data class AuthoringResource(val name: String, val kind: ResourceKind, val imagePath: Path, val bbox: IntArray) {
    val expression: String get() = "self.pos.$name" + if (kind == ResourceKind.RECT) ".to_box()" else ""
    val summary: String get() = if (kind == ResourceKind.POINT) "Point · (${bbox[0]}, ${bbox[1]})" else "Box · ${bbox[2]}×${bbox[3]}"
}

internal object ResourceAuthoring {
    fun positions(project: Project): List<AuthoringResource> {
        val root = project.service<OkProjectDataService>().rootPath() ?: return emptyList()
        val folder = root.resolve(OkScriptToolkitSettings.getInstance(project).okTemplatesDirectory())
        val boxes = project.service<BoxCatalogService>().readAuthoring()
        val points = project.service<PointCatalogService>().read()
        return boxes.boxes.map { AuthoringResource(it.path, ResourceKind.RECT, folder.resolve(it.image).normalize(), it.bbox) } +
            if (points.errors.isEmpty()) points.file.points.map {
                AuthoringResource(it.path, ResourceKind.POINT, folder.resolve(it.image).normalize(), intArrayOf(it.x, it.y, 0, 0))
            } else emptyList()
    }

    fun templates(project: Project): CocoAnnotationData? {
        val root = project.service<OkProjectDataService>().rootPath() ?: return null
        return CocoAnnotationData().apply { load(root.toString(), OkScriptToolkitSettings.getInstance(project).okTemplatesDirectory()) }
    }

    fun find(project: Project, kind: ResourceKind, name: String): AuthoringResource? {
        if (kind != ResourceKind.TEMPLATE) return positions(project).singleOrNull { it.kind == kind && it.name == name }
        val data = templates(project) ?: return null
        val names = data.data.categories.associate { it.id to it.name }
        val annotation = data.data.annotations.singleOrNull { names[it.categoryId] == name } ?: return null
        val image = data.data.images.firstOrNull { it.id == annotation.imageId } ?: return null
        return AuthoringResource(name, kind, (data.annotationFile?.parent ?: return null).resolve(image.fileName).normalize(), annotation.bbox)
    }

    fun normalized(project: Project, name: String): List<Double>? {
        val source = positions(project).singleOrNull { it.name == name } ?: return null
        val dimensions = if (source.kind == ResourceKind.RECT) {
            project.service<BoxCatalogService>().imageSize(source.imagePath.fileName.toString())?.let { it.width to it.height }
        } else project.service<PointCatalogService>().read().file.images
            .firstOrNull { PointCatalogService.sameImage(it.file, source.imagePath.fileName.toString()) }?.let { it.width to it.height }
        val (width, height) = dimensions ?: return null
        if (width <= 0 || height <= 0) return null
        val (x, y, w, h) = source.bbox
        return if (source.kind == ResourceKind.POINT) listOf(x.toDouble() / width, y.toDouble() / height)
        else listOf(x.toDouble() / width, y.toDouble() / height, (x + w).toDouble() / width, (y + h).toDouble() / height)
    }
}
