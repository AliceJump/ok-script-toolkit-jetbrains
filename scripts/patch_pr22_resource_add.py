from pathlib import Path
p = Path('src/main/kotlin/com/alicejump/okscripttoolkit/ui/UnifiedAnnotationUi.kt')
text = p.read_text(encoding='utf-8')
replacements = {
    'if (pointEdits.isNotEmpty()) resourcePaths += pointCatalog.authoringPath() ?: return "point:path"': 'if (pointEdits.isNotEmpty()) resourcePaths.add(pointCatalog.authoringPath() ?: return "point:path")',
    'if (templateEdits.isNotEmpty()) resourcePaths += templateData.annotationFile ?: return "template:path"': 'if (templateEdits.isNotEmpty()) resourcePaths.add(templateData.annotationFile ?: return "template:path")',
    'if (rectEdits.isNotEmpty()) resourcePaths += boxCatalog.authoringPath() ?: return "rect:path"': 'if (rectEdits.isNotEmpty()) resourcePaths.add(boxCatalog.authoringPath() ?: return "rect:path")',
}
for old, new in replacements.items():
    if text.count(old) != 1:
        raise SystemExit(f'expected one occurrence: {old}')
    text = text.replace(old, new, 1)
p.write_text(text, encoding='utf-8')
