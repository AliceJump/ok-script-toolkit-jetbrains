package com.alicejump.okscripttoolkit.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 缩略图网格列数规则回归测试。
 *
 * 这是仓库里唯一一个**没有配套单测**的纯对象（其余抽取物都已覆盖，见 `SchemaTreeOverlap`、
 * `TaskRowState`、`NormalizedBox` 等），补上是为了让「抽取成纯对象」这条约定的收益完整：
 * 抽了却没测，等于只换了文件位置，不变量仍然没人守。
 *
 * 关键不变量：列数**永远**落在 1..12，且随可视宽度单调不减。
 * 前者防的是窄视口下算出 0 列（网格整片空白），后者防的是算错除数导致
 * 拉宽窗口反而列数变少（用户会觉得"卡住了"）。
 */
class ThumbGridPolicyTest {

    @Test
    fun `columns never drop below one even for a pathological viewport`() {
        for (width in listOf(Int.MIN_VALUE, 0, 1, 44, 100, 163)) {
            assertTrue(
                ThumbGridPolicy.columnsFor(width) >= 1,
                "可视宽度 $width 也算不出 0 列 —— 0 列会让整个网格空白，比挤在 1 列更糟",
            )
        }
    }

    @Test
    fun `columns are capped at twelve`() {
        for (width in listOf(4000, 100_000, Int.MAX_VALUE)) {
            assertEquals(
                12,
                ThumbGridPolicy.columnsFor(width),
                "超宽视口应被钉在 12 列上限（宽度 $width）",
            )
        }
    }

    @Test
    fun `columns grow monotonically with the viewport width`() {
        var previous = ThumbGridPolicy.columnsFor(0)
        for (width in 1..3000) {
            val current = ThumbGridPolicy.columnsFor(width)
            assertTrue(
                current >= previous,
                "列数必须随宽度单调不减：width=$width 时由 $previous 变成 $current",
            )
            previous = current
        }
    }

    @Test
    fun `a known width maps to the documented column count`() {
        // 单元宽 = CELL_WIDTH(120) + HGAP(8) = 128，视口先扣掉 VIEWPORT_MARGIN(44)。
        // 于是「n 列的下界」= n*128 + 44，即 172 / 300 / 428 / 556 …
        // 注意 172 是「1 列的下界」而不是「2 列的下界」：
        // (172-44)/128 = 1，所以 172 仍然只有 1 列，2 列要到 300 才出现。
        assertEquals(3, ThumbGridPolicy.columnsFor(428), "428 = 3*128+44，应恰好容下 3 列")
        assertEquals(2, ThumbGridPolicy.columnsFor(427), "差 1px 应掉到 2 列 —— 边界不得四舍五入")
        assertEquals(3, ThumbGridPolicy.columnsFor(429), "多 1px 仍应是 3 列")

        assertEquals(1, ThumbGridPolicy.columnsFor(299), "299 = 300-1，未达 2 列下界应保持 1 列")
        assertEquals(2, ThumbGridPolicy.columnsFor(300), "300 = 2*128+44，刚达 2 列下界")
    }

    @Test
    fun `shared constants stay in sync with the layout expectations`() {
        assertEquals(72, ThumbGridPolicy.THUMB_HEIGHT, "预览图高度是两端共用的视觉规格")
        assertEquals(120, ThumbGridPolicy.CELL_WIDTH)
        assertEquals(8, ThumbGridPolicy.HGAP_VALUE, "列间距暴露给渲染器用，必须与内部计算一致")
    }

    /**
     * 破坏性对照：证明上面那条「不得为 0」的断言真的有意义。
     * 若去掉 `coerceIn` 的下界，整数除法在 `viewWidth < 172` 时直接给出 0，
     * 网格会失去所有列 —— 这正是必须钉住的原因。
     */
    @Test
    fun `regression guard - without the lower clamp a narrow viewport yields zero columns`() {
        // 0 列的真实区间是 44..171（(w-44)/128 向下取整为 0），不是只有边界点。
        val unclampedAt171 = (171 - 44) / (120 + 8)
        assertEquals(0, unclampedAt171, "对照实现确实会算出 0 列，说明下界 clamp 是必需的")
        for (width in listOf(44, 100, 171)) {
            assertEquals(1, ThumbGridPolicy.columnsFor(width), "真实现必须在 $width 处兜到 1 列")
        }
    }
}
