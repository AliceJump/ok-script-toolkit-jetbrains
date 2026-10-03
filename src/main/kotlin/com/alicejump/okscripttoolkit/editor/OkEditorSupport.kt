package com.alicejump.okscripttoolkit.editor

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.alicejump.okscripttoolkit.core.AnnotatedSourcePreview
import com.alicejump.okscripttoolkit.core.BoxCatalogService
import com.alicejump.okscripttoolkit.core.EffectEntry
import com.alicejump.okscripttoolkit.core.FeatureTemplate
import com.alicejump.okscripttoolkit.core.LangEntry
import com.alicejump.okscripttoolkit.core.OkProjectDataService
import com.alicejump.okscripttoolkit.core.PointCatalogService
import com.alicejump.okscripttoolkit.settings.OkScriptToolkitSettings
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.regex.Pattern
import javax.imageio.ImageIO

data class EditorReference(
    val kind: Kind,
    val range: TextRange,
    val id: String,
    val module: String? = null,
    val hintOffset: Int = range.endOffset,
) {
    enum class Kind { LANG, FEATURE, EFFECT, OCR, POS }
}

object OkEditorSupport {
    private val langPattern = Pattern.compile("(?<![\\w.])self\\.lang\\.([\\p{L}\\p{N}_]+)\\.([\\p{L}\\p{N}_]+)")
    private val posCallPattern = Pattern.compile("(?<![\\w.])self\\.pos\\.((?:[A-Za-z_][A-Za-z0-9_]*\\.)*[A-Za-z_][A-Za-z0-9_]*)\\.to_box\\(\\)")
    private val posPathPattern = Pattern.compile("(?<![\\w.])self\\.pos\\.((?:[A-Za-z_][A-Za-z0-9_]*\\.)*[A-Za-z_][A-Za-z0-9_]*)(?![\\w.(])")
    private val effectTypePattern = Pattern.compile("\\bEffectType\\.([A-Z][A-Z0-9_]*)")
    private val effectStringPattern = Pattern.compile("r?['\"]([A-Z][A-Z0-9_]{2,})['\"]")
    private val ocrCallPattern = Pattern.compile("(?<![\\w.])self\\.(ocr|wait_ocr|wait_click_ocr|find_boxes)\\(")

    private data class PositionSource(
        val path: String,
        val kind: String,
        val imagePath: java.nio.file.Path,
        val bbox: IntArray,
        val normalized: List<Double>,
    )

    fun references(document: Document, startOffset: Int, endOffset: Int, project: Project): List<EditorReference> {
        val result = mutableListOf<EditorReference>()
        val resolvedPaths by lazy(LazyThreadSafetyMode.NONE) { positionPaths(project) }
        var line = document.getLineNumber(startOffset.coerceIn(0, document.textLength))
        val lastLine = document.getLineNumber(endOffset.coerceIn(0, document.textLength))
        while (line <= lastLine) {
            val lineStart = document.getLineStartOffset(line)
            val lineEnd = document.getLineEndOffset(line)
            val lineText = document.charsSequence.subSequence(lineStart, lineEnd).toString()
            result += referencesInLine(
                lineText,
                lineStart,
                project,
                if ("self.pos." in lineText) resolvedPaths else null,
            )
            line++
        }
        return result
    }

    fun referenceAt(document: Document, offset: Int, project: Project): EditorReference? {
        if (document.textLength == 0) return null
        val safeOffset = offset.coerceIn(0, document.textLength - 1)
        val line = document.getLineNumber(safeOffset)
        val lineStart = document.getLineStartOffset(line)
        val lineEnd = document.getLineEndOffset(line)
        return referencesInLine(
            document.charsSequence.subSequence(lineStart, lineEnd).toString(),
            lineStart,
            project,
        ).firstOrNull { safeOffset in it.range.startOffset..it.range.endOffset }
    }

    fun referencesInLine(
        text: String,
        baseOffset: Int,
        project: Project,
        resolvedPositionPaths: Set<String>? = null,
    ): List<EditorReference> {
        val refs = mutableListOf<EditorReference>()
        langPattern.matcher(text).run {
            while (find()) refs += EditorReference(
                EditorReference.Kind.LANG,
                TextRange(baseOffset + start(), baseOffset + end()),
                group(2),
                group(1),
            )
        }

        val positionPaths = resolvedPositionPaths
            ?: if ("self.pos." in text) positionPaths(project) else emptySet()
        fun addPos(matcher: java.util.regex.Matcher) {
            while (matcher.find()) {
                val path = matcher.group(1)
                if (path !in positionPaths) continue
                refs += EditorReference(
                    EditorReference.Kind.POS,
                    TextRange(baseOffset + matcher.start(), baseOffset + matcher.end()),
                    path,
                )
            }
        }
        addPos(posCallPattern.matcher(text))
        addPos(posPathPattern.matcher(text))

        val aliases = OkScriptToolkitSettings.getInstance(project).featureAliases()
        if (aliases.isNotEmpty()) {
            val aliasPattern = aliases.joinToString("|") { Pattern.quote(it) }
            Pattern.compile("(?<![\\w.])(?:$aliasPattern)\\.([A-Za-z0-9_]+)").matcher(text).run {
                while (find()) refs += EditorReference(
                    EditorReference.Kind.FEATURE,
                    TextRange(baseOffset + start(), baseOffset + end()),
                    group(1),
                )
            }
        }

        effectTypePattern.matcher(text).run {
            while (find()) refs += EditorReference(
                EditorReference.Kind.EFFECT,
                TextRange(baseOffset + start(), baseOffset + end()),
                group(1),
            )
        }
        effectStringPattern.matcher(text).run {
            while (find()) {
                val before = text.substring(maxOf(0, start() - 40), start())
                if (Regex("self\\.lang\\.|match\\s*=|re\\.compile\\s*\\(").containsMatchIn(before)) continue
                refs += EditorReference(
                    EditorReference.Kind.EFFECT,
                    TextRange(baseOffset + start(), baseOffset + end()),
                    group(1),
                )
            }
        }
        refs += findOcrReferences(text, baseOffset)
        return refs
    }

    fun completionContext(document: Document, offset: Int, project: Project): CompletionContext? {
        val line = document.getLineNumber(offset.coerceIn(0, document.textLength))
        val lineStart = document.getLineStartOffset(line)
        val before = document.charsSequence.subSequence(lineStart, offset).toString()
        Regex("(?:effect_id|effect)[\"']?\\s*:\\s*r?['\"]([A-Za-z0-9_]*)$").find(before)?.let {
            return CompletionContext(CompletionKind.EFFECT, it.groupValues[1])
        }
        Regex("self\\.(?:ocr|wait_ocr|wait_click_ocr|find_boxes)\\([^)]*?(?:re\\.compile\\s*\\(\\s*|match\\s*=\\s*)r?['\"]([^'\"]*)$")
            .find(before)?.let { return CompletionContext(CompletionKind.OCR, it.groupValues[1]) }
        for (alias in OkScriptToolkitSettings.getInstance(project).featureAliases()) {
            val match = Regex("(?<![\\w.])${Regex.escape(alias)}\\.([A-Za-z0-9_]*)$").find(before)
            if (match != null) return CompletionContext(CompletionKind.FEATURE, match.groupValues[1])
        }
        Regex("(?<![\\w.])self\\.pos\\.((?:[A-Za-z_][A-Za-z0-9_]*\\.)*)([A-Za-z_][A-Za-z0-9_]*)?$").find(before)?.let {
            return CompletionContext(CompletionKind.POS, it.groupValues[2], it.groupValues[1].removeSuffix("."))
        }
        Regex("(?<![\\w.])self\\.lang\\.([\\p{L}\\p{N}_]+)\\.([\\p{L}\\p{N}_]*)$").find(before)?.let {
            return CompletionContext(CompletionKind.LANG_KEY, it.groupValues[2], it.groupValues[1])
        }
        Regex("(?<![\\w.])self\\.lang\\.([\\p{L}\\p{N}_]*)$").find(before)?.let {
            return CompletionContext(CompletionKind.LANG_MODULE, it.groupValues[1])
        }
        return null
    }

    fun documentation(reference: EditorReference, project: Project): String? {
        val data = project.service<OkProjectDataService>()
        return when (reference.kind) {
            EditorReference.Kind.LANG -> data.langEntry(reference.module ?: return null, reference.id)?.let {
                formatLang(it, data, "self.lang.${it.module}.${it.key}")
            }
            EditorReference.Kind.OCR -> data.poEntry("ocr", reference.id)?.let {
                formatLang(it, data, "match=re.compile(r\"${html(reference.id)}\")") +
                    "<p><i>${OkScriptToolkitBundle.message("documentation.ocrRuntime")}</i></p>"
            }
            EditorReference.Kind.FEATURE -> data.feature(reference.id)?.let(::formatFeature)
            EditorReference.Kind.POS -> formatPosition(project, reference.id)
            EditorReference.Kind.EFFECT -> data.effect(reference.id)?.let(::formatEffect)
        }
    }

    fun hint(reference: EditorReference, project: Project): String? {
        val data = project.service<OkProjectDataService>()
        return when (reference.kind) {
            EditorReference.Kind.LANG -> data.langEntry(reference.module ?: return null, reference.id)?.let(data::pick)?.let {
                if (it.type == "pattern") "~${it.value}~" else "「${it.value}」"
            }
            EditorReference.Kind.OCR -> data.poEntry("ocr", reference.id)?.let(data::pick)?.let { "→ ${it.value}" }
            EditorReference.Kind.EFFECT -> data.effect(reference.id)?.let { "「${it.description}」" }
            EditorReference.Kind.FEATURE -> data.feature(reference.id)?.let { "「${it.width}×${it.height}」" }
            EditorReference.Kind.POS -> positionSource(project, reference.id)?.normalized
                ?.joinToString(", ") { "%.4f".format(it) }
        }
    }

    fun tooltip(reference: EditorReference, project: Project): String? {
        val data = project.service<OkProjectDataService>()
        val body = when (reference.kind) {
            EditorReference.Kind.LANG -> data.langEntry(reference.module ?: return null, reference.id)?.let {
                formatLang(it, data, "self.lang.${it.module}.${it.key}")
            }
            EditorReference.Kind.OCR -> data.poEntry("ocr", reference.id)?.let {
                formatLang(it, data, "match=re.compile(r\"${html(reference.id)}\")")
            }
            EditorReference.Kind.EFFECT -> data.effect(reference.id)?.let(::formatEffect)
            EditorReference.Kind.FEATURE -> data.feature(reference.id)?.let {
                "<p><b>fL.${html(it.name)}</b></p><p><b>Size:</b> ${it.width} × ${it.height}</p>" +
                    "<p><b>Source:</b> <code>${html(it.imagePath.toString())}</code></p>"
            }
            EditorReference.Kind.POS -> formatPosition(project, reference.id)
        } ?: return null
        return "<html>$body</html>"
    }

    fun boxSegments(paths: List<String>, parent: String): List<String> {
        val prefix = if (parent.isEmpty()) "" else "$parent."
        return paths.mapNotNull { path ->
            if (!path.startsWith(prefix)) return@mapNotNull null
            path.removePrefix(prefix).substringBefore('.').takeIf { it.isNotEmpty() }
        }.distinct().sorted()
    }

    private fun positionPaths(project: Project): Set<String> {
        val rects = project.service<BoxCatalogService>().readAuthoring().boxes.map { it.path }
        val pointResult = project.service<PointCatalogService>().read()
        val points = if (pointResult.errors.isEmpty()) pointResult.file.points.map { it.path } else emptyList()
        return (rects + points).toSet()
    }

    private fun positionSource(project: Project, path: String): PositionSource? {
        val root = project.service<OkProjectDataService>().rootPath() ?: return null
        val directory = OkScriptToolkitSettings.getInstance(project).okTemplatesDirectory()
        val rectCatalog = project.service<BoxCatalogService>()
        rectCatalog.readAuthoring().boxes.firstOrNull { it.path == path }?.let { rect ->
            val imagePath = root.resolve(directory).resolve(rect.image)
            val size = rectCatalog.imageSize(rect.image) ?: return null
            val width = size.width.toDouble()
            val height = size.height.toDouble()
            if (width <= 0 || height <= 0) return null
            val x = rect.bbox[0].toDouble()
            val y = rect.bbox[1].toDouble()
            val w = rect.bbox[2].toDouble()
            val h = rect.bbox[3].toDouble()
            return PositionSource(path, "Rect", imagePath, rect.bbox, listOf(x / width, y / height, (x + w) / width, (y + h) / height))
        }
        val pointResult = project.service<PointCatalogService>().read()
        if (pointResult.errors.isNotEmpty()) return null
        val point = pointResult.file.points.firstOrNull { it.path == path } ?: return null
        val image = pointResult.file.images.firstOrNull { PointCatalogService.sameImage(it.file, point.image) } ?: return null
        if (image.width <= 0 || image.height <= 0) return null
        val imagePath = root.resolve(directory).resolve(point.image)
        return PositionSource(
            path,
            "Point",
            imagePath,
            intArrayOf(point.x, point.y, 0, 0),
            listOf(point.x.toDouble() / image.width, point.y.toDouble() / image.height),
        )
    }

    private fun formatPosition(project: Project, path: String): String? {
        val source = positionSource(project, path) ?: return null
        val previewBox = if (source.kind == "Point") {
            val marker = 10
            intArrayOf((source.bbox[0] - marker / 2).coerceAtLeast(0), (source.bbox[1] - marker / 2).coerceAtLeast(0), marker, marker)
        } else source.bbox
        val preview = AnnotatedSourcePreview.fileFor(project, source.imagePath, previewBox)?.let {
            runCatching { Base64.getEncoder().encodeToString(java.nio.file.Files.readAllBytes(it)) }.getOrNull()
        }
        val values = source.normalized.joinToString(", ") { "%.6f".format(it) }
        return "<div class='definition'><code>self.pos.${html(path)}</code></div>" +
            "<div class='content'><p><b>${html(source.kind)}:</b> <code>$values</code></p>" +
            (preview?.let { "<p><img src='data:image/png;base64,$it' width='160'/></p>" } ?: "") +
            "<p><b>${html(OkScriptToolkitBundle.message("documentation.source"))}:</b> " +
            "<code>${html(source.imagePath.toString())}</code></p></div>"
    }

    private fun findOcrReferences(text: String, baseOffset: Int): List<EditorReference> {
        val result = mutableListOf<EditorReference>()
        val matcher = ocrCallPattern.matcher(text)
        while (matcher.find()) {
            val open = matcher.end() - 1
            val close = matchingClose(text, open, '(', ')') ?: continue
            val content = text.substring(open + 1, close)
            val matchValue = keywordArgument(content, "match") ?: continue
            val value = matchValue.first
            val valueStart = open + 1 + matchValue.second
            val compile = Regex("^re\\.compile\\s*\\((.*)\\)$", RegexOption.DOT_MATCHES_ALL).matchEntire(value)
            val searchable = compile?.groupValues?.get(1) ?: value
            stringLiterals(searchable).forEach { literal ->
                val start = if (compile != null) valueStart else valueStart + literal.second
                val end = if (compile != null) valueStart + value.length else start + literal.first.length + 2
                result += EditorReference(
                    EditorReference.Kind.OCR,
                    TextRange(baseOffset + start, baseOffset + end),
                    literal.first,
                    hintOffset = baseOffset + close + 1,
                )
            }
        }
        return result
    }

    private fun matchingClose(text: String, open: Int, openChar: Char, closeChar: Char): Int? {
        var depth = 0
        var quote: Char? = null
        var index = open
        while (index < text.length) {
            val char = text[index]
            if (quote != null) {
                if (char == '\\') index++ else if (char == quote) quote = null
            } else when (char) {
                '\'', '"' -> quote = char
                openChar -> depth++
                closeChar -> if (--depth == 0) return index
            }
            index++
        }
        return null
    }

    private fun keywordArgument(content: String, name: String): Pair<String, Int>? {
        val match = Regex("(?:^|[,\\s])${Regex.escape(name)}\\s*=\\s*").find(content) ?: return null
        val rawStart = match.range.last + 1
        var index = rawStart
        var depth = 0
        var quote: Char? = null
        while (index < content.length) {
            val char = content[index]
            if (quote != null) {
                if (char == '\\') index++ else if (char == quote) quote = null
            } else when (char) {
                '\'', '"' -> quote = char
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) break
            }
            index++
        }
        val raw = content.substring(rawStart, index)
        val leading = raw.length - raw.trimStart().length
        return raw.trim() to rawStart + leading
    }

    private fun stringLiterals(value: String): List<Pair<String, Int>> {
        val result = mutableListOf<Pair<String, Int>>()
        val matcher = Pattern.compile("r?(['\"])((?:[^\\\\]|\\\\.)*?)\\1").matcher(value)
        while (matcher.find()) result += matcher.group(2).replace("\\\\", "\\") to matcher.start()
        return result
    }

    private fun formatLang(entry: LangEntry, data: OkProjectDataService, title: String): String {
        val current = data.currentLocale()
        val rows = listOf("zh_CN", "zh_TW", "en_US", "ja_JP", "ko_KR", "es_ES").joinToString("") { locale ->
            val node = entry.locales[locale]
            "<tr><td>${html(locale)}${if (locale == current) " <b>←</b>" else ""}</td>" +
                "<td>${html(node?.type ?: "—")}</td><td><code>${html(node?.value ?: "—")}</code></td></tr>"
        }
        return "<div class='definition'><code>${html(title)}</code></div>" +
            "<div class='content'><table><tr><th>Language</th><th>Type</th><th>Value</th></tr>$rows</table></div>"
    }

    private fun formatFeature(feature: FeatureTemplate): String {
        val thumbHtml = cropThumbnailBase64(feature)
        return "<div class='definition'><code>fL.${html(feature.name)}</code></div>" +
            "<div class='content'>" +
            (if (thumbHtml != null) "<p><img src='data:image/png;base64,$thumbHtml' width='120'/></p>" else "") +
            "<p><b>Template name:</b> <code>${html(feature.name)}</code></p>" +
            "<p><b>Size:</b> ${feature.width} × ${feature.height}</p>" +
            "<p><b>Source:</b> <code>${html(feature.imagePath.toString())}</code></p>" +
            "<p><b>bbox:</b> <code>${feature.bbox.joinToString(", ")}</code></p></div>"
    }

    private fun cropThumbnailBase64(feature: FeatureTemplate): String? {
        return try {
            val file = feature.imagePath.toFile()
            if (!file.exists()) return null
            val original: BufferedImage = ImageIO.read(file) ?: return null
            val x = feature.bbox[0].coerceIn(0, original.width - 1)
            val y = feature.bbox[1].coerceIn(0, original.height - 1)
            val w = feature.bbox[2].coerceAtMost(original.width - x)
            val h = feature.bbox[3].coerceAtMost(original.height - y)
            if (w <= 0 || h <= 0) return null
            val crop = original.getSubimage(x, y, w, h)
            val targetH = 96
            val targetW = (w * targetH.toDouble() / h).toInt().coerceIn(1, 240)
            val thumb = BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB)
            thumb.createGraphics().useGraphics { it.drawImage(crop, 0, 0, targetW, targetH, null) }
            val baos = ByteArrayOutputStream()
            ImageIO.write(thumb, "png", baos)
            Base64.getEncoder().encodeToString(baos.toByteArray())
        } catch (_: Exception) {
            null
        }
    }

    private inline fun <T : java.awt.Graphics> T.useGraphics(block: (T) -> Unit) {
        try { block(this) } finally { dispose() }
    }

    private fun formatEffect(effect: EffectEntry): String =
        "<div class='definition'><code>${html(effect.id)}</code></div>" +
            "<div class='content'><p><b>Category:</b> ${html(effect.category)}</p>" +
            "<p><b>Description:</b> ${html(effect.description)}</p></div>"

    private fun html(value: String): String = StringUtil.escapeXmlEntities(value)
}

enum class CompletionKind { LANG_MODULE, LANG_KEY, FEATURE, EFFECT, OCR, POS }
data class CompletionContext(val kind: CompletionKind, val prefix: String, val module: String? = null)
