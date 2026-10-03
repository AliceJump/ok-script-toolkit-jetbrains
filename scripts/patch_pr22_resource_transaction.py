from pathlib import Path

p = Path("src/main/kotlin/com/alicejump/okscripttoolkit/ui/UnifiedAnnotationUi.kt")
text = p.read_text(encoding="utf-8")

import_anchor = "import com.alicejump.okscripttoolkit.core.PositionPublisherService\n"
if import_anchor not in text:
    raise SystemExit("missing import anchor")
text = text.replace(
    import_anchor,
    import_anchor + "import com.alicejump.okscripttoolkit.core.ResourceFileTransaction\n",
    1,
)

old = '''        pointCatalog.savePoints(pointEdits)?.let { return "point:$it" }
        if (templateEdits.isNotEmpty() && !templateData.saveAnnotationEdits(templateEdits)) {
            return "template:${templateData.lastError ?: "write"}"
        }
        if (rectEdits.isNotEmpty() && !boxCatalog.annotations.saveAnnotationEdits(rectEdits)) {
            return "rect:${boxCatalog.annotations.lastError ?: "write"}"
        }
        return null
'''
new = '''        val resourcePaths = mutableListOf<Path>()
        if (pointEdits.isNotEmpty()) resourcePaths += pointCatalog.authoringPath() ?: return "point:path"
        if (templateEdits.isNotEmpty()) resourcePaths += templateData.annotationFile ?: return "template:path"
        if (rectEdits.isNotEmpty()) resourcePaths += boxCatalog.authoringPath() ?: return "rect:path"
        val transaction = ResourceFileTransaction.capture(resourcePaths) ?: return "snapshot"

        fun rollback(error: String): String {
            val restored = transaction.rollback()
            if (templateEdits.isNotEmpty()) templateData.reload()
            if (rectEdits.isNotEmpty()) boxCatalog.annotations.reload()
            return if (restored) error else "$error:rollback"
        }

        pointCatalog.savePoints(pointEdits)?.let { return rollback("point:$it") }
        if (templateEdits.isNotEmpty() && !templateData.saveAnnotationEdits(templateEdits)) {
            return rollback("template:${templateData.lastError ?: "write"}")
        }
        if (rectEdits.isNotEmpty() && !boxCatalog.annotations.saveAnnotationEdits(rectEdits)) {
            return rollback("rect:${boxCatalog.annotations.lastError ?: "write"}")
        }
        return null
'''
if text.count(old) != 1:
    raise SystemExit(f"expected one save block, found {text.count(old)}")
p.write_text(text.replace(old, new, 1), encoding="utf-8")
