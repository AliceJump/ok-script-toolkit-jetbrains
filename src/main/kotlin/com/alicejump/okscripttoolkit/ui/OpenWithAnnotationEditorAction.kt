package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.alicejump.okscripttoolkit.core.OkProjectDataService
import com.alicejump.okscripttoolkit.core.OkProjectDetection
import com.alicejump.okscripttoolkit.core.TemplateAssetDataService
import com.alicejump.okscripttoolkit.settings.OkScriptToolkitSettings
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import java.io.File

/** 图片扩展名白名单（与 VS Code explorer/context 的 resourceExtname 条件一致）。 */
internal val ANNOTATABLE_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")

/**
 * Project 视图右键「用标注编辑器打开」：只在单个图片文件上出现。
 * 已在草稿里的图直接打开；否则确认后导入草稿再打开。
 */
class OpenWithAnnotationEditorAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        val file = files?.singleOrNull()
        e.presentation.isEnabledAndVisible = project != null && file != null && !file.isDirectory &&
            file.extension?.lowercase() in ANNOTATABLE_IMAGE_EXTENSIONS && OkProjectDetection.isOkScriptProject(project)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val target = e.getData(CommonDataKeys.VIRTUAL_FILE)?.let { File(it.path) } ?: return
        val root = project.service<OkProjectDataService>().rootPath() ?: return
        val directory = OkScriptToolkitSettings.getInstance(project).okTemplatesDirectory()
        val data = project.service<TemplateAssetDataService>()
        data.load(root.toString(), directory)
        fun indexOf(images: List<com.alicejump.okscripttoolkit.core.TemplateImage>, file: File) =
            images.indexOfFirst { runCatching { it.file.canonicalFile == file.canonicalFile }.getOrDefault(false) }
        var images = data.listImages()
        var index = indexOf(images, target)
        if (index < 0) {
            val answer = Messages.showYesNoDialog(project, OkScriptToolkitBundle.message("openAnnotate.importPrompt", target.name),
                OkScriptToolkitBundle.message("openAnnotate.title"), Messages.getQuestionIcon())
            if (answer != Messages.YES) return
            val before = images.map { it.file.canonicalPath }.toSet()
            if (data.importImages(listOf(target), root.resolve(directory).toFile()) <= 0) {
                Messages.showErrorDialog(project, OkScriptToolkitBundle.message("openAnnotate.importFailed", target.name), OkScriptToolkitBundle.message("openAnnotate.title"))
                return
            }
            data.reload()
            images = data.listImages()
            index = images.indexOfFirst { it.file.canonicalPath !in before }.takeIf { it >= 0 } ?: (images.size - 1)
        }
        if (index >= 0) openUnifiedAnnotationEditor(project, images, index)
    }
}
