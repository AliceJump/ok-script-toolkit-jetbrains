package com.alicejump.okscripttoolkit.core

import java.util.Locale
import kotlin.math.abs

/**
 * 裸四元归一化坐标的两种用户习惯。
 *
 * 插件自己复制的带名 JSON `bbox` 永远是 [XYWH]，只有没有格式元数据的四元组才需要这里判定。
 */
enum class CoordinateTupleFormat { XYXY, XYWH }

data class NormalizedAnnotationRect(
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double,
) {
    val x2: Double get() = x + w
    val y2: Double get() = y + h
    val isPoint: Boolean get() = w == 0.0 && h == 0.0
    fun centerPoint(): NormalizedAnnotationRect =
        if (isPoint) this else NormalizedAnnotationRect(x + w / 2.0, y + h / 2.0, 0.0, 0.0)
}

data class CoordinateTupleResult(
    val rect: NormalizedAnnotationRect,
    val format: CoordinateTupleFormat,
    val ambiguous: Boolean,
)

object CoordinateTuple {
    private const val EPS = 1e-6

    /**
     * 同时尝试 XYWH 与 XYXY：唯一合法的解释自动采用；两者都合法时才使用 [preferred]。
     */
    fun interpret(values: List<Double>, preferred: CoordinateTupleFormat): CoordinateTupleResult? {
        if (values.size != 4 || values.any { !it.isFinite() }) return null
        val xywh = asXywh(values)
        val xyxy = asXyxy(values)
        return when {
            xywh != null && xyxy == null -> CoordinateTupleResult(xywh, CoordinateTupleFormat.XYWH, false)
            xywh == null && xyxy != null -> CoordinateTupleResult(xyxy, CoordinateTupleFormat.XYXY, false)
            xywh != null && xyxy != null -> {
                val rect = if (preferred == CoordinateTupleFormat.XYWH) xywh else xyxy
                CoordinateTupleResult(rect, preferred, true)
            }
            else -> null
        }
    }

    fun asXywh(values: List<Double>): NormalizedAnnotationRect? {
        if (values.size != 4) return null
        val (x, y, w, h) = values
        if (!inside(x) || !inside(y) || w < -EPS || h < -EPS) return null
        val cleanW = if (abs(w) <= EPS) 0.0 else w
        val cleanH = if (abs(h) <= EPS) 0.0 else h
        if ((cleanW == 0.0) != (cleanH == 0.0)) return null
        if (x + cleanW > 1.0 + EPS || y + cleanH > 1.0 + EPS) return null
        return NormalizedAnnotationRect(clamp(x), clamp(y), clamp(cleanW), clamp(cleanH))
    }

    fun asXyxy(values: List<Double>): NormalizedAnnotationRect? {
        if (values.size != 4) return null
        val (x1, y1, x2, y2) = values
        if (!inside(x1) || !inside(y1) || !inside(x2) || !inside(y2)) return null
        if (x2 < x1 - EPS || y2 < y1 - EPS) return null
        val w = if (abs(x2 - x1) <= EPS) 0.0 else x2 - x1
        val h = if (abs(y2 - y1) <= EPS) 0.0 else y2 - y1
        if ((w == 0.0) != (h == 0.0)) return null
        return NormalizedAnnotationRect(clamp(x1), clamp(y1), clamp(w), clamp(h))
    }

    fun format(rect: NormalizedAnnotationRect, format: CoordinateTupleFormat, decimals: Int = 4, separator: String = ", "): String {
        val values = if (format == CoordinateTupleFormat.XYWH) {
            listOf(rect.x, rect.y, rect.w, rect.h)
        } else {
            listOf(rect.x, rect.y, rect.x2, rect.y2)
        }
        return values.joinToString(separator) { String.format(Locale.ROOT, "%.${decimals}f", it) }
    }

    private fun inside(value: Double): Boolean = value >= -EPS && value <= 1.0 + EPS
    private fun clamp(value: Double): Double = value.coerceIn(0.0, 1.0)
}
