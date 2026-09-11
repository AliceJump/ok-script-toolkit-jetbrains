package com.alicejump.okscripttoolkit.ui

import java.util.Locale

/**
 * ok-script 归一化框坐标：`x,y,tox,toy`（左上 / 右下），按图片宽高归一化到 0..1，
 * 固定 4 位小数。对齐 VSCode 版 annotationPanel / tempScreenshots 的输出格式。
 *
 * 归一化只取比例，**与显示缩放无关**，因此在降采样预览、滚轮缩放的画布上框选
 * 都能得到同一结果 —— 这也是临时截图工具窗口能用小预览图精确取坐标的前提。
 */
object NormalizedBox {
    /** 输出小数位 */
    const val DECIMALS = 4

    /** 小于该像素尺寸的框视为误触，不产出坐标 */
    const val MIN_SIZE_PX = 3.0

    /**
     * 把图像坐标矩形换算成归一化文本；顺序无关（会自行取 min/max），
     * 越界自动 clamp 到 0..1，过小返回空串表示不产出。
     */
    fun format(
        x1: Double,
        y1: Double,
        x2: Double,
        y2: Double,
        imageWidth: Int,
        imageHeight: Int,
    ): String {
        if (imageWidth <= 0 || imageHeight <= 0) return ""
        val left = minOf(x1, x2)
        val top = minOf(y1, y2)
        val right = maxOf(x1, x2)
        val bottom = maxOf(y1, y2)
        if (right - left < MIN_SIZE_PX || bottom - top < MIN_SIZE_PX) return ""
        return listOf(
            left / imageWidth,
            top / imageHeight,
            right / imageWidth,
            bottom / imageHeight,
        ).joinToString(",") { formatValue(it) }
    }

    private fun formatValue(value: Double): String =
        String.format(Locale.ROOT, "%.${DECIMALS}f", value.coerceIn(0.0, 1.0))
}
