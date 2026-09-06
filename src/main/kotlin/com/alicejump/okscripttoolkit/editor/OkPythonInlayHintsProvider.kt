package com.alicejump.okscripttoolkit.editor

import com.alicejump.okscripttoolkit.settings.OkScriptToolkitSettings
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import java.util.concurrent.atomic.AtomicBoolean

class OkInlayHintsProvider : InlayHintsProvider {
    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? {
        if (!OkScriptToolkitSettings.getInstance(file.project).state.enableInlayHints) return null
        return Collector(editor)
    }

    private class Collector(
        private val editor: Editor,
    ) : SharedBypassCollector {
        // 平台对 SharedBypassCollector 会按 PSI 树逐元素回调；root 不一定是 PsiFile，
        // 因此用一次性标记保证每个 pass 只做一次全文档扫描。
        private val collected = AtomicBoolean(false)

        override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
            if (!collected.compareAndSet(false, true)) return
            val document = editor.document
            val project = editor.project ?: return
            val references = OkEditorSupport.references(document, 0, document.textLength, project)
            for (reference in references) {
                val hint = OkEditorSupport.hint(reference, project) ?: continue
                // 新版 InlayTreeSink：hasBackground 布尔参数被 HintFormat 取代；
                // Default.withColorKind(TextWithoutBackground) 与旧 hasBackground=false 等价
                sink.addPresentation(
                    InlineInlayPosition(reference.hintOffset, true),
                    null,
                    OkEditorSupport.tooltip(reference, project),
                    com.intellij.codeInsight.hints.declarative.HintFormat.default
                        .withColorKind(com.intellij.codeInsight.hints.declarative.HintColorKind.TextWithoutBackground),
                ) {
                    text(hint)
                }
            }
        }
    }
}
