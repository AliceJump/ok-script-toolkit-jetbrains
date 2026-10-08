package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.AnnotationUiBundle
import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.alicejump.okscripttoolkit.core.LabelEnumGuard
import com.alicejump.okscripttoolkit.core.OkProjectDataService
import com.alicejump.okscripttoolkit.core.SaveToAssetsFlow
import com.alicejump.okscripttoolkit.core.ScreenshotCapture
import com.alicejump.okscripttoolkit.core.TemplateAssetDataService
import com.alicejump.okscripttoolkit.core.isPathInsideRoot
import com.alicejump.okscripttoolkit.core.labelEnumPathInputError
import com.alicejump.okscripttoolkit.core.normalizeLabelEnumFile
import com.alicejump.okscripttoolkit.settings.OkScriptToolkitSettings
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import java.nio.file.Files
import java.nio.file.Path

internal data class TemplatePublishPlan(val target: Path, val enumFile: String?)

/** 枚举设置、框架路径后备和覆盖确认共用实际发布链。 */
internal object TemplatePublishFlow {
    fun configure(project: Project): TemplatePublishPlan? {
        val root = project.service<OkProjectDataService>().rootPath() ?: return null
        val settings = OkScriptToolkitSettings.getInstance(project)
        val data = project.service<TemplateAssetDataService>()
        data.load(root.toString(), settings.okTemplatesDirectory())
        if (data.readErrors.isNotEmpty()) {
            Messages.showErrorDialog(project, message("templateAsset.sourceInvalid"), publishTitle())
            return null
        }
        if (data.listImages().none { it.annotations.isNotEmpty() }) {
            Messages.showWarningDialog(project, AnnotationUiBundle.message("publish.noTemplates"), publishTitle())
            return null
        }
        var enumPath = settings.labelEnumPath()
        if (enumPath.isBlank()) {
            if (!ProgressManager.getInstance().runProcessWithProgressSynchronously({
                val python = ScreenshotCapture.detectPythonPath(root.toString(), project)
                val discovered = ScreenshotCapture(project).probeWindowConfig(root.toString(), python)?.labelEnumRelativePath
                if (discovered != null && labelEnumPathInputError(discovered) == null) {
                    enumPath = normalizeLabelEnumFile(discovered).orEmpty()
                }
            }, AnnotationUiBundle.message("publish.probeEnum"), true, project)) return null
        }
        var decided = false
        val targets = listOf("assets", "ok_tasks/assets")
        var targetName: String
        while (true) {
            val options = SaveToAssetsFlow.options(targets,
                SaveToAssetsFlow.choiceLine(message("templateAsset.exportEnumPathChoice"), enumPath,
                    message("templateAsset.exportEnumPathNotSet")),
                SaveToAssetsFlow.choiceLine(message("templateAsset.exportEnumNameChoice"), settings.rawLabelEnumName(),
                    message("templateAsset.exportEnumNameDerived")))
            val index = ChooseDialog.show(project, AnnotationUiBundle.message("publish.templateTarget"),
                publishTitle(), options.labels) ?: return null
            when (options.roleOf(index)) {
                SaveToAssetsFlow.Choice.TARGET -> { targetName = targets[index]; break }
                SaveToAssetsFlow.Choice.ENUM_NAME -> {
                    val value = Messages.showInputDialog(project, message("templateAsset.exportEnumNamePrompt"),
                        publishTitle(), Messages.getQuestionIcon(), settings.rawLabelEnumName(), null) ?: continue
                    settings.setLabelEnumName(value.trim())
                }
                SaveToAssetsFlow.Choice.ENUM_PATH -> {
                    val value = inputPath(project, enumPath) ?: continue
                    if (labelEnumPathInputError(value) != null) { invalidPath(project); continue }
                    enumPath = normalizeLabelEnumFile(value).orEmpty()
                    settings.setLabelEnumPath(enumPath)
                    decided = true
                }
            }
        }
        if (SaveToAssetsFlow.needsEnumPathPrompt(enumPath.takeIf { it.isNotBlank() }, decided)) {
            val value = inputPath(project, SaveToAssetsFlow.derivedEnumPath(targetName)) ?: return null
            if (labelEnumPathInputError(value) != null) { invalidPath(project); return null }
            enumPath = normalizeLabelEnumFile(value).orEmpty()
            settings.setLabelEnumPath(enumPath)
        }
        val enumFile = enumPath.takeIf { it.isNotBlank() }?.let { root.resolve(it).normalize() }
        if (enumFile != null) {
            if (!isPathInsideRoot(root.toString(), enumFile.toString())) { invalidPath(project); return null }
            val existing = if (Files.exists(enumFile)) runCatching { Files.readString(enumFile) }.getOrDefault("") else null
            val nextName = LabelEnumGuard.writableClassName(settings.labelEnumClassName(enumFile.toString()))
            val impact = LabelEnumGuard.renameImpact(existing, nextName)
            if (impact != null) {
                var references = emptyList<String>()
                if (!ProgressManager.getInstance().runProcessWithProgressSynchronously({
                    references = scanEnumReferences(root, impact.existingClassName)
                }, message("labelEnum.rename.title"), true, project)) return null
                val answer = Messages.showYesNoDialog(project, LabelEnumGuard.renameMessage(impact, references),
                    message("labelEnum.rename.title"), message("labelEnum.rename.overwriteAnyway"),
                    message("annotation.cancel"), Messages.getWarningIcon())
                if (answer != Messages.YES) return null
            }
        }
        return TemplatePublishPlan(root.resolve(targetName), enumFile?.toString())
    }

    private fun inputPath(project: Project, value: String): String? = Messages.showInputDialog(project,
        message("templateAsset.exportEnumPathPrompt"), publishTitle(), Messages.getQuestionIcon(), value, null)
    private fun invalidPath(project: Project) = Messages.showWarningDialog(project,
        message("templateAsset.exportEnumPathInvalid"), publishTitle())
    private fun publishTitle() = AnnotationUiBundle.message("publish.title")
    private fun message(key: String) = OkScriptToolkitBundle.message(key)
}

/** 扫描项目源码，避免把依赖与 IDE 文件当作项目引用。 */
internal fun scanEnumReferences(root: Path, name: String): List<String> {
    if (name.isBlank()) return emptyList()
    val sources = mutableListOf<Pair<String, String>>()
    val excluded = setOf(".git", ".idea", ".vscode", ".venv", "venv", "node_modules", "__pycache__")
    Files.walkFileTree(root, object : java.nio.file.SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: java.nio.file.attribute.BasicFileAttributes) =
            if (dir != root && dir.fileName.toString() in excluded) java.nio.file.FileVisitResult.SKIP_SUBTREE
            else java.nio.file.FileVisitResult.CONTINUE
        override fun visitFile(file: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
            if (file.toString().endsWith(".py") && sources.size < 2000) {
                runCatching { Files.readString(file) }.getOrNull()?.let {
                    sources += root.relativize(file).toString().replace('\\', '/') to it
                }
            }
            return if (sources.size >= 2000) java.nio.file.FileVisitResult.TERMINATE else java.nio.file.FileVisitResult.CONTINUE
        }
        override fun visitFileFailed(file: Path, error: java.io.IOException) = java.nio.file.FileVisitResult.CONTINUE
    })
    return LabelEnumGuard.referencingFiles(sources, name)
}
