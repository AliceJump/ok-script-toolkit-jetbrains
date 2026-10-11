package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.alicejump.okscripttoolkit.core.OkProjectDetection
import com.alicejump.okscripttoolkit.core.TemplateReferences
import com.alicejump.okscripttoolkit.editor.EditorReference
import com.alicejump.okscripttoolkit.editor.OkEditorSupport
import com.alicejump.okscripttoolkit.settings.OkScriptToolkitSettings
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.usageView.UsageInfo
import com.intellij.usages.Usage
import com.intellij.usages.UsageInfo2UsageAdapter
import com.intellij.usages.UsageTarget
import com.intellij.usages.UsageViewManager
import com.intellij.usages.UsageViewPresentation

/** 查找模板 / 坐标引用：扫描项目内全部 .py，结果进平台 Find 工具窗口（Usage View）。 */
internal object FindTemplateReferences {
    fun run(project: Project, name: String) {
        val aliases = OkScriptToolkitSettings.getInstance(project).featureAliases()
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, OkScriptToolkitBundle.message("findRefs.progress", name), true) {
            override fun run(indicator: ProgressIndicator) {
                val usages = ReadAction.compute<List<Usage>, RuntimeException> {
                    val psi = PsiManager.getInstance(project)
                    FilenameIndex.getAllFilesByExt(project, "py", GlobalSearchScope.projectScope(project)).flatMap { file ->
                        indicator.checkCanceled()
                        val psiFile = psi.findFile(file) ?: return@flatMap emptyList()
                        TemplateReferences.find(psiFile.text, name, aliases).map { hit ->
                            UsageInfo2UsageAdapter(UsageInfo(psiFile, hit.offset, hit.offset + hit.length))
                        }
                    }
                }
                ApplicationManager.getApplication().invokeLater({
                    if (usages.isEmpty()) {
                        Messages.showInfoMessage(project, OkScriptToolkitBundle.message("findRefs.none", name), OkScriptToolkitBundle.message("findRefs.title"))
                        return@invokeLater
                    }
                    val presentation = UsageViewPresentation().apply {
                        tabText = OkScriptToolkitBundle.message("findRefs.tab", name)
                        searchString = name
                        isOpenInNewTab = true
                    }
                    UsageViewManager.getInstance(project).showUsages(UsageTarget.EMPTY_ARRAY, usages.toTypedArray(), presentation)
                }, project.disposed)
            }
        })
    }
}

/** 编辑器右键 / 快捷入口：光标在 `fL.xxx` 或 `self.pos.xxx` 上时查找该名字的全部引用。 */
class FindTemplateReferencesAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = project != null && file?.extension == "py" && OkProjectDetection.isOkScriptProject(project)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)
        val reference = editor?.let { OkEditorSupport.referenceAt(it.document, it.caretModel.offset, project) }
            ?.takeIf { it.kind == EditorReference.Kind.FEATURE || it.kind == EditorReference.Kind.POS }
        val name = reference?.id?.substringAfterLast('.')
            ?: Messages.showInputDialog(project, OkScriptToolkitBundle.message("findRefs.prompt"), OkScriptToolkitBundle.message("findRefs.title"), null)
            ?: return
        if (name.isNotBlank()) FindTemplateReferences.run(project, name.trim())
    }
}
