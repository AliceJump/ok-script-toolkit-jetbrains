package com.alicejump.okscripttoolkit.ui

/**
 * 缩略图网格的统一规格（模板画廊与素材面板共用）：
 * 相同的预览图高度、相同的最小单元宽与响应式列数规则，列宽等分铺满可视区域。
 */
internal object ThumbGridPolicy {
    /** 预览图统一高度 */
    const val THUMB_HEIGHT = 72

    /** 最小单元宽度（决定列数下限） */
    const val CELL_WIDTH = 120

    /** 视口宽度预留（边框 + 垂直滚动条余量） */
    private const val VIEWPORT_MARGIN = 44

    private const val HGAP = 8

    const val HGAP_VALUE = HGAP

    /** 按可视宽度计算列数（响应式） */
    fun columnsFor(viewWidth: Int): Int =
        ((viewWidth - VIEWPORT_MARGIN) / (CELL_WIDTH + HGAP)).coerceIn(1, 12)
}
