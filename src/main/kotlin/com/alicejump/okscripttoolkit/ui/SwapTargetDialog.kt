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
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import javax.swing.ImageIcon
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/**
 * 「交换标注」的目标图片选择器。
 *
 * 只负责**选哪张图**；比例映射与落盘由统一标注器处理。
 * 缩略图必须后台解码，避免大图 ImageIO 阻塞 EDT。
 */
class SwapTargetDialog(
    project: Project,
    private val source: TemplateImage,
    candidates: List<TemplateImage>,
    private val thumbCache: ConcurrentHashMap<String, ImageIcon?>,
) : DialogWrapper(project) {

    private val list = JBList<TemplateImage>(CollectionListModel(candidates))

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

        return JPanel(BorderLayout(0, 6)).apply {
            add(hint, BorderLayout.NORTH)
            add(scroll, BorderLayout.CENTER)
        }
    }

    private fun loadThumbs(items: List<TemplateImage>) {
        for (item in items) {
            if (thumbCache.containsKey(item.file.absolutePath)) continue
            CompletableFuture
                .supplyAsync { decodeThumb(item.file) }
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

    private fun decodeThumb(file: File): ImageIcon? = runCatching {
        val image = ImageIO.read(file) ?: return@runCatching null
        val scale = minOf(
            ThumbGridPolicy.THUMB_HEIGHT.toDouble() / image.height,
            ThumbGridPolicy.CELL_WIDTH.toDouble() / image.width,
            1.0,
        )
        val width = (image.width * scale).toInt().coerceAtLeast(1)
        val height = (image.height * scale).toInt().coerceAtLeast(1)
        ImageIcon(image.getScaledInstance(width, height, java.awt.Image.SCALE_SMOOTH))
    }.getOrNull()

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
            cell.background = if (isSelected) UIUtil.getListSelectionBackground(true) else UIUtil.getListBackground()
            nameLabel.foreground = if (isSelected) UIUtil.getListSelectionForeground(true) else UIUtil.getListForeground()
            detailsLabel.foreground = if (isSelected) UIUtil.getListSelectionForeground(true) else UIUtil.getContextHelpForeground()
            return cell
        }
    }
}
