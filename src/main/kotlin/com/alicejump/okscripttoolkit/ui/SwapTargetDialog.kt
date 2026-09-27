package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.alicejump.okscripttoolkit.core.TemplateImage
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.CollectionListModel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.GridLayout
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import javax.swing.ImageIcon
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.SwingConstants

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

    /** Native two-line cell: thumbnail, filename, dimensions, and box count. */
    private inner class SwapCellRenderer : ListCellRenderer<TemplateImage> {
        private val thumbnail = JBLabel().apply {
            preferredSize = Dimension(ThumbGridPolicy.CELL_WIDTH, ThumbGridPolicy.THUMB_HEIGHT)
            horizontalAlignment = SwingConstants.CENTER
        }
        private val nameLabel = JBLabel().apply { font = font.deriveFont(Font.BOLD) }
        private val detailsLabel = JBLabel()
        private val text = JPanel(GridLayout(2, 1, 0, 2)).apply {
            isOpaque = false
            add(nameLabel)
            add(detailsLabel)
        }
        private val cell = JPanel(BorderLayout(8, 0)).apply {
            border = JBUI.Borders.empty(4, 8)
            add(thumbnail, BorderLayout.WEST)
            add(text, BorderLayout.CENTER)
        }

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
            thumbnail.icon = thumbCache[value.file.absolutePath]
            nameLabel.text = value.name
            detailsLabel.text = "${value.width}×${value.height} · $count"
            cell.background =
                if (isSelected) UIUtil.getListSelectionBackground(true) else UIUtil.getListBackground()
            nameLabel.foreground =
                if (isSelected) UIUtil.getListSelectionForeground(true) else UIUtil.getListForeground()
            detailsLabel.foreground =
                if (isSelected) UIUtil.getListSelectionForeground(true) else UIUtil.getContextHelpForeground()
            return cell
        }
    }
}
