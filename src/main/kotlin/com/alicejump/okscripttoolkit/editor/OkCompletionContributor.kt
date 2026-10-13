package com.alicejump.okscripttoolkit.editor

import com.alicejump.okscripttoolkit.core.OkProjectDataService
import com.alicejump.okscripttoolkit.core.ResourceAuthoring
import com.alicejump.okscripttoolkit.core.positionChoices
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.components.service
import com.intellij.openapi.util.TextRange

class OkPythonCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (parameters.completionType != CompletionType.BASIC) return
        val context = OkEditorSupport.completionContext(
            parameters.editor.document,
            parameters.offset,
            parameters.position.project,
        ) ?: return
        val project = parameters.position.project
        val data = project.service<OkProjectDataService>()
        val replacement = if (context.prefix.isEmpty()) result else result.withPrefixMatcher(context.prefix)

        when (context.kind) {
            CompletionKind.LANG_MODULE -> data.modules().forEach { module ->
                replacement.addElement(
                    LookupElementBuilder.create(module)
                        .withTypeText("${data.keys(module).size} keys", true),
                )
            }
            CompletionKind.LANG_KEY -> data.keys(context.module ?: return).forEach { key ->
                val entry = data.langEntry(context.module, key)
                val node = entry?.let(data::pick)
                replacement.addElement(
                    LookupElementBuilder.create(key)
                        .withTailText(node?.let { "  ${if (it.type == "pattern") "~${it.value}~" else "「${it.value}」"}" }, true),
                )
            }
            CompletionKind.POS -> {
                val resources = ResourceAuthoring.positions(project)
                val paths = resources.map { it.name }.distinct().sorted()
                OkEditorSupport.boxSegments(paths, context.module.orEmpty()).forEach { segment ->
                    val name = listOf(context.module.orEmpty(), segment).filter { it.isNotEmpty() }.joinToString(".")
                    val choices = positionChoices(resources, name).takeUnless { paths.any { path -> path.startsWith("$name.") } }.orEmpty()
                    val element = if (choices.isEmpty()) LookupElementBuilder.create(segment) else
                        LookupElementBuilder.create(EditorReference(EditorReference.Kind.POS, TextRange.EMPTY_RANGE, name), segment)
                            .withTypeText(choices.joinToString(" / ") { it.summary }, true)
                    replacement.addElement(element)
                }
            }
            CompletionKind.FEATURE -> data.features().forEach { feature ->
                val element = LookupElementBuilder.create(EditorReference(EditorReference.Kind.FEATURE, TextRange.EMPTY_RANGE, feature.name), feature.name)
                    .withTypeText("${feature.width}×${feature.height}", true)
                replacement.addElement(PrioritizedLookupElement.withPriority(element, 1000.0))
            }
            CompletionKind.EFFECT -> data.effectIds()
                .sortedWith(compareBy({ data.effect(it)?.category.orEmpty() }, { it }))
                .forEach { id ->
                    val effect = data.effect(id)
                    replacement.addElement(
                        LookupElementBuilder.create(id)
                            .withTypeText(effect?.category, true)
                            .withTailText(effect?.description?.let { "  $it" }, true),
                    )
                }
            CompletionKind.OCR -> data.poKeys("ocr").forEach { key ->
                val node = data.poEntry("ocr", key)?.let(data::pick)
                replacement.addElement(
                    LookupElementBuilder.create(key).withTailText(node?.value?.let { "  → $it" }, true),
                )
            }
        }
        if (context.prefix.isNotEmpty()) replacement.restartCompletionOnAnyPrefixChange()
    }
}

class OkJsonCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (parameters.completionType != CompletionType.BASIC) return
        val context = OkEditorSupport.completionContext(
            parameters.editor.document,
            parameters.offset,
            parameters.position.project,
        ) ?: return
        if (context.kind != CompletionKind.EFFECT) return
        val data = parameters.position.project.service<OkProjectDataService>()
        val replacement = if (context.prefix.isEmpty()) result else result.withPrefixMatcher(context.prefix)
        data.effectIds()
            .sortedWith(compareBy({ data.effect(it)?.category.orEmpty() }, { it }))
            .forEach { id ->
                val effect = data.effect(id)
                replacement.addElement(
                    LookupElementBuilder.create(id)
                        .withTypeText(effect?.category, true)
                        .withTailText(effect?.description?.let { "  $it" }, true),
                )
            }
    }
}
