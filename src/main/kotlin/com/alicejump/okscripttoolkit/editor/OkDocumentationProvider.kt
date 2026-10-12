package com.alicejump.okscripttoolkit.editor

import com.alicejump.okscripttoolkit.ui.FindTemplateReferences
import com.alicejump.okscripttoolkit.ui.OkScriptToolWindows
import com.alicejump.okscripttoolkit.ui.ResourceSection
import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager

class OkDocumentationProvider : AbstractDocumentationProvider() {
    override fun getCustomDocumentationElement(
        editor: Editor,
        file: PsiFile,
        contextElement: PsiElement?,
        targetOffset: Int,
    ): PsiElement? {
        return if (OkEditorSupport.referenceAt(editor.document, targetOffset, file.project) != null) {
            file.findElementAt(targetOffset.coerceAtMost(file.textLength - 1))
        } else {
            null
        }
    }

    override fun generateDoc(element: PsiElement, originalElement: PsiElement?): String? {
        val source = originalElement ?: element
        val file = source.containingFile ?: return null
        val reference = OkEditorSupport.referenceAt(file.viewProvider.document, source.textOffset, file.project) ?: return null
        val doc = OkEditorSupport.documentation(reference, file.project) ?: return null
        return if (reference.kind == EditorReference.Kind.FEATURE || reference.kind == EditorReference.Kind.POS) {
            doc + OkDocLinks.featureLinks(reference.id.substringAfterLast('.'))
        } else doc
    }

    override fun generateHoverDoc(element: PsiElement, originalElement: PsiElement?): String? =
        generateDoc(element, originalElement)

    /** 截获本插件的动作链接：在 EDT 上执行，不返回任何 PSI（文档弹窗保持原样）。 */
    override fun getDocumentationElementForLink(psiManager: PsiManager, link: String, context: PsiElement?): PsiElement? {
        val (action, name) = OkDocLinks.parse(link) ?: return null
        val project = psiManager.project
        ApplicationManager.getApplication().invokeLater({
            when (action) {
                OkDocLinks.REVEAL -> OkScriptToolWindows.withResources(project, ResourceSection.PUBLISHED) { it.reveal(name) }
                OkDocLinks.REFS -> FindTemplateReferences.run(project, name)
            }
        }, project.disposed)
        return null
    }
}
