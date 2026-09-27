package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.alicejump.okscripttoolkit.core.TemplateImage
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.CollectionListModel
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import javax.swing.ImageIcon
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

/**
 * 「交换标注」的目标图片选择器。
 *
 * 只负责**选哪张图**：比例映射与落盘都在 `TemplateAssetPanel.swapAnnotationsWith` 里做
 * —— 对话框不该知道 COCO 的存在，否则它就没法脱离 IDE 单测（与标注编辑器的分层一致）。
 *
 * 为什么必须给缩略图：`ok_templates` 里的模板名就是数字序号（`1.png`、`2.png`…），
 * 只列名字等于让用户凭记忆选，选错的代价是两张图的标注一起被换掉。
 * 缩略图复用工具窗自己的 [thumbCache]，缺的在这里后台补齐（大图 `ImageIO.read`
 * 可达数秒，绝不能在 EDT 上同步解码 —— 那条路工具窗已经踩过一次 EDT 冻结）。
 */
class SwapTargetDialog(
    project: Project,
    private val source: TemplateImage,
    candidates: List<TemplateImage>,
    private val thumbCache: ConcurrentHashMap<String, ImageIcon?>,
) : DialogWrapper(project) {

    private val list = JBList<TemplateImage>(CollectionListModel(candidates))

    /** 用户选中的目标图；未选中为 null（调用方据此放弃）。 */
    val selected: TemplateImage?
        get() = list.selectedValue

    init {
        title = OkScriptToolkitBundle.message("templateAsset.swapTitle")
        setOKButtonText(OkScriptToolkitBundle.message("templateAsset.swapConfirm"))
        setCancelButtonText(OkScriptToolkitBundle.message("annotation.cancel"))
        init()
        if (candidates.isNotEmpty()) list.selectedIndex = 0
        loadThumbs(candidates)
    }

    override fun createCenterPanel(): JComponent {
        list.cellRenderer = SwapCellRenderer()
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.visibleRowCount = 6

        val hint = JBLabel(OkScriptToolkitBundle.message("templateAsset.swapHint", source.name))
        hint.font = hint.font.deriveFont(11f)

        val scroll = JBScrollPane(list)
        scroll.preferredSize = Dimension(520, 300)

        val root = JPanel(BorderLayout(0, 6))
        root.add(hint, BorderLayout.NORTH)
        root.add(scroll, BorderLayout.CENTER)
        return root
    }

    /** 缩略图后台补齐；只缓存成功的，失败保持占位（不反复重试）。 */
    private fun loadThumbs(items: List<TemplateImage>) {
        for (item in items) {
            if (thumbCache.containsKey(item.file.absolutePath)) continue
            CompletableFuture
                .supplyAsync { decodeTemplateThumb(item.file) }
                .thenAccept { icon ->
                    if (icon == null) return@thenAccept
                    SwingUtilities.invokeLater {
                        if (isDisposed) return@invokeLater
                        thumbCache[item.file.absolutePath] = icon
                        list.repaint()
                    }
                }
        }
    }

    /**
     * 两行式单元格：图标 + 名字 + 尺寸/标注数。
     *
     * 名字拼进 HTML 之前必须转义 —— 文件名里可以合法出现 `<`，而 `JBLabel` 的 HTML
     * 会把它当标签解析（显示错乱，甚至吞掉后半段）。
     */
    private inner class SwapCellRenderer : ListCellRenderer<TemplateImage> {
        private val label = JBLabel()

        override fun getListCellRendererComponent(
            list: JList<out TemplateImage>,
            value: TemplateImage,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val count = if (value.annotations.isEmpty()) {
                OkScriptToolkitBundle.message("templateAsset.swapNoBoxes")
            } else {
                OkScriptToolkitBundle.message("templateAsset.swapBoxes", value.annotations.size)
            }
            label.icon = thumbCache[value.file.absolutePath]
            label.iconTextGap = 8
            label.text = "<html><b>${escapeHtml(value.name)}</b><br>" +
                "<span style='color:${mutedColor()}'>${value.width}×${value.height} · $count</span></html>"
            label.border = JBUI.Borders.empty(4, 8)
            label.isOpaque = true
            label.background =
                if (isSelected) UIUtil.getListSelectionBackground(true) else UIUtil.getListBackground()
            label.foreground =
                if (isSelected) UIUtil.getListSelectionForeground(true) else UIUtil.getListForeground()
            return label
        }
    }

    private fun mutedColor(): String = String.format("#%06x", JBColor.GRAY.rgb and 0xFFFFFF)

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
