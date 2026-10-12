package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import javax.swing.JComponent
import javax.swing.JList

/**
 * 发布预览（只读）：写盘前列出将写入的文件与模板集合变化，确认后才进入原有发布链。
 * 不改变 saveToAssets 的写入语义。与 VS Code 的发布预览 QuickPick 同一份信息。
 */
internal class PublishPreviewDialog(project: Project?, private val preview: PublishPreview.Preview) : DialogWrapper(project) {
    init {
        title = OkScriptToolkitBundle.message("publishPreview.title")
        setOKButtonText(OkScriptToolkitBundle.message("publishPreview.confirm"))
        init()
    }

    /** 测试用：渲染后的行文本。 */
    fun rowTexts(): List<String> = (preview.files + preview.templates).map { "${it.kind}:${it.label}" }

    public override fun createCenterPanel(): JComponent = panel {
        val counts = preview.counts
        row { label(OkScriptToolkitBundle.message("publishPreview.summary", counts.add, counts.keep, counts.remove)) }
        group(OkScriptToolkitBundle.message("publishPreview.files")) {
            row { cell(list(preview.files)).align(Align.FILL) }
        }
        if (preview.templates.isNotEmpty()) {
            group(OkScriptToolkitBundle.message("publishPreview.templates")) {
                row { cell(list(preview.templates)).align(Align.FILL) }.resizableRow()
            }
        }
        row { comment(OkScriptToolkitBundle.message("publishPreview.note")) }
    }.apply { preferredSize = JBUI.size(520, 420) }

    private fun list(rows: List<PublishPreview.Row>): JComponent {
        val list = JBList(rows).apply {
            cellRenderer = object : ColoredListCellRenderer<PublishPreview.Row>() {
                override fun customizeCellRenderer(list: JList<out PublishPreview.Row>, value: PublishPreview.Row?, index: Int, selected: Boolean, hasFocus: Boolean) {
                    value ?: return
                    icon = when (value.kind) {
                        PublishPreview.Kind.FILE -> AllIcons.Actions.MenuSaveall
                        PublishPreview.Kind.ADD -> AllIcons.General.Add
                        PublishPreview.Kind.KEEP -> AllIcons.Actions.Refresh
                        PublishPreview.Kind.REMOVE -> AllIcons.General.Remove
                    }
                    append(value.label)
                    val tag = when (value.kind) {
                        PublishPreview.Kind.ADD -> "publishPreview.add"
                        PublishPreview.Kind.KEEP -> "publishPreview.keep"
                        PublishPreview.Kind.REMOVE -> "publishPreview.remove"
                        PublishPreview.Kind.FILE -> null
                    }
                    if (tag != null) append("  " + OkScriptToolkitBundle.message(tag), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
            visibleRowCount = minOf(8, maxOf(1, rows.size))
            emptyText.text = OkScriptToolkitBundle.message("publishPreview.none")
        }
        return JBScrollPane(list)
    }
}
