package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.core.CocoData

/**
 * 发布预览（纯逻辑，与 VS Code `src/publishPreview.ts` 同语义）：
 * 写盘前列出「会写哪些文件、模板集合怎么变」。
 *
 * 只按名字比较：发布会把草稿裁切 + 装箱成新的运行时 COCO（saveToAssets 整体替换），
 * 草稿与已发布的 bbox 坐标系不同，比较坐标会把所有模板误报成「已修改」。
 */
internal object PublishPreview {
    enum class Kind { FILE, ADD, KEEP, REMOVE }
    data class Row(val kind: Kind, val label: String)
    data class Counts(val add: Int, val keep: Int, val remove: Int)
    data class Preview(val files: List<Row>, val templates: List<Row>, val counts: Counts)

    data class TemplateInput(val targetLabel: String, val enumPath: String?, val draftNames: List<String>, val publishedNames: List<String>)

    /** 有至少一个标注的类别名 = 草稿里的模板名。 */
    fun draftNames(coco: CocoData): List<String> {
        val used = coco.annotations.map { it.categoryId }.toSet()
        return coco.categories.filter { it.id in used }.map { it.name }.distinct()
    }

    fun draftOnly(draft: List<String>, published: List<String>): List<String> {
        val have = published.toSet()
        return draft.distinct().filter { it !in have }.sorted()
    }

    fun build(template: TemplateInput?, positionFiles: List<String>): Preview {
        val files = mutableListOf<Row>()
        val templates = mutableListOf<Row>()
        var add = 0; var keep = 0; var remove = 0
        if (template != null) {
            val target = template.targetLabel.trimEnd('/', '\\')
            files += Row(Kind.FILE, "$target/coco_annotations.json")
            files += Row(Kind.FILE, "$target/images/")
            template.enumPath?.takeIf { it.isNotBlank() }?.let { files += Row(Kind.FILE, it) }
            val published = template.publishedNames.toSet()
            val draft = template.draftNames.distinct().sorted()
            for (name in draft) {
                if (name in published) { keep++; templates += Row(Kind.KEEP, name) } else { add++; templates += Row(Kind.ADD, name) }
            }
            for (name in published.sorted()) if (name !in draft) { remove++; templates += Row(Kind.REMOVE, name) }
        }
        positionFiles.forEach { files += Row(Kind.FILE, it) }
        return Preview(files, templates, Counts(add, keep, remove))
    }
}
