package com.alicejump.okscripttoolkit.core

import kotlin.math.roundToInt

/**
 * 标注交换的比例映射（纯逻辑，**不 import `com.intellij.*`**，因此可独立单测）。
 *
 * 与 VS Code 侧 `src/annotationSwapPure.ts` 是同一套语义，两端各自实现：
 * 标注管理里"把 A 图的标注整套搬到 B 图"是修正常见错误的操作，而标注框存的是
 * **绝对像素坐标**，`ok_templates` 里的图片尺寸又并不一致（截图与导入图混在一起）。
 *
 * 语义：`x' = x * W2 / W1`（y / w / h 同理），然后**钳制进目标图边界**（宽高至少 1px）。
 * - 同尺寸时比值恰为 1，结果与原框逐字段相同（同分辨率截图互换不漂一格）；
 * - 尺寸不同时保持"框在画面里的相对位置与相对大小"；
 * - 越界的框在画布上画不出来、点不到，只能手改 JSON 才能修 —— 那是最坏的一种"成功"。
 *
 * 框用 `IntArray[x, y, w, h]` 表示（与 `CocoAnnotation.bbox` 同形），
 * 分类名不在这里 —— 交换只是搬家，不产生新分类，名字由调用方原样带走。
 */
object AnnotationSwap {

    /** 图片尺寸。任一分量 <= 0 视为"读不出尺寸"。 */
    data class Size(val width: Int, val height: Int)

    /** 尺寸是否可用于映射。非正数意味着"读不出尺寸"，不能拿来当除数。 */
    fun isUsable(size: Size?): Boolean = size != null && size.width > 0 && size.height > 0

    /** 两侧都可用且逐分量相等才算"同尺寸"；任一侧不可用时为 false（不能据此宣称"尺寸相同"）。 */
    fun isSameSize(a: Size?, b: Size?): Boolean {
        if (!isUsable(a) || !isUsable(b)) return false
        return a!!.width == b!!.width && a.height == b.height
    }

    /**
     * 把框收进目标图边界：宽高至少 1px，左上角随之回退。
     *
     * 目标尺寸不可用、或框本身不是 4 元组时**原样返回副本** —— 宁可原样搬运，
     * 也不能在这里抛异常或写出 NaN：调用方是按"整套交换"来用的，一次异常会连带
     * 让另一张图的标注也写不进去。
     */
    fun clampBox(rect: IntArray, to: Size?): IntArray {
        if (!isUsable(to) || rect.size < 4) return rect.copyOf()
        val target = to!!
        val w = rect[2].coerceAtLeast(1).coerceAtMost(target.width)
        val h = rect[3].coerceAtLeast(1).coerceAtMost(target.height)
        return intArrayOf(
            rect[0].coerceIn(0, target.width - w),
            rect[1].coerceIn(0, target.height - h),
            w,
            h,
        )
    }

    /** 单个框：按 `from` → `to` 的比例映射，再钳制进 `to` 的边界。 */
    fun scaleBox(rect: IntArray, from: Size?, to: Size?): IntArray {
        val source = from?.takeIf { isUsable(it) }
        if (source == null || rect.size < 4) return clampBox(rect, to)
        val rx = axisRatio(source.width, to?.width ?: 0)
        val ry = axisRatio(source.height, to?.height ?: 0)
        return clampBox(
            intArrayOf(
                (rect[0] * rx).roundToInt(),
                (rect[1] * ry).roundToInt(),
                (rect[2] * rx).roundToInt(),
                (rect[3] * ry).roundToInt(),
            ),
            to,
        )
    }

    /** 一整张图的框：逐框映射（分类名由调用方带着走） */
    fun scaleBoxes(boxes: List<IntArray>, from: Size?, to: Size?): List<IntArray> =
        boxes.map { scaleBox(it, from, to) }

    /** 未声明的分类 ID 无法按原分类写回；拒绝交换以免生成新的字面分类。 */
    fun namedBoxes(
        annotations: List<CocoAnnotation>,
        categoryNames: Map<Int, String>,
    ): List<Pair<String, IntArray>>? {
        if (annotations.any { it.categoryId !in categoryNames }) return null
        return annotations.map { categoryNames.getValue(it.categoryId) to it.bbox }
    }

    /** Build both complete edits by destination image, so neither side can keep its own boxes. */
    fun editsForSwap(
        sourceFileName: String,
        sourceSize: Size,
        sourceBoxes: List<Pair<String, IntArray>>,
        targetFileName: String,
        targetSize: Size,
        targetBoxes: List<Pair<String, IntArray>>,
    ): List<CocoAnnotationEdit> = listOf(
        CocoAnnotationEdit(
            sourceFileName,
            sourceSize.width to sourceSize.height,
            targetBoxes.map { (name, rect) -> name to scaleBox(rect, targetSize, sourceSize) },
        ),
        CocoAnnotationEdit(
            targetFileName,
            targetSize.width to targetSize.height,
            sourceBoxes.map { (name, rect) -> name to scaleBox(rect, sourceSize, targetSize) },
        ),
    )

    /**
     * 单轴比例。任一侧不可用时**退化成 1**，而不是 0 / Infinity：
     * 读不出尺寸的图不该把整份数据写成 0 宽 0 高的框。
     */
    private fun axisRatio(from: Int, to: Int): Double =
        if (from > 0 && to > 0) to.toDouble() / from else 1.0
}
