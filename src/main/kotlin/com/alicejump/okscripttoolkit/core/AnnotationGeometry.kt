package com.alicejump.okscripttoolkit.core

import kotlin.math.roundToInt

/** 模板、框源文件和编辑保存共用的像素矩形规则。 */
object AnnotationGeometry {
    fun roundBbox(values: List<Double?>): IntArray? {
        if (values.size != 4 || values.any { it == null || !it.isFinite() || it < Int.MIN_VALUE || it > Int.MAX_VALUE }) return null
        return values.map { it!!.roundToInt() }.toIntArray()
    }

    fun bboxError(bbox: IntArray, size: AnnotationSwap.Size?): String? {
        if (bbox.size != 4 || bbox[0] < 0 || bbox[1] < 0 || bbox[2] < 1 || bbox[3] < 1) return "rect"
        if (AnnotationSwap.isUsable(size) &&
            (bbox[0].toLong() + bbox[2] > size!!.width || bbox[1].toLong() + bbox[3] > size.height)) return "rect"
        return null
    }
}
