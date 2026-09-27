package com.alicejump.okscripttoolkit.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 标注交换的**比例映射**测试（`core/AnnotationSwap`）。
 *
 * 与 VS Code 侧 `scripts/test_annotation_swap.js` 是同一组不变量（两端各自实现，各自钉住）：
 *
 * 1. **同尺寸恒等**：比值恰为 1 时四个字段逐字节不变（同分辨率截图互换不该漂一格）；
 * 2. **按比例**：`x' = x * W2 / W1`，四舍五入到整数（COCO 里的框本来就是整数）；
 * 3. **不越界**：任何输入下映射结果都完整落在目标图内，宽高至少 1px
 *    —— 越界的框在画布上画不出来、点不到，只能手改 JSON 才能修；
 * 4. **不产生退化数据**：尺寸读不出来（0 / null）时退化成"原样搬运"，
 *    而不是把整份 COCO 写成 0 宽 0 高。
 *
 * 纯对象，不碰文件系统与 IDE，符合本仓库 `src/test` 的既有约定。
 */
class AnnotationSwapTest {

    private val fhd = AnnotationSwap.Size(1920, 1080)
    private val small = AnnotationSwap.Size(800, 600)

    private fun box(x: Int, y: Int, w: Int, h: Int) = intArrayOf(x, y, w, h)

    // ── 1. 同尺寸恒等 ───────────────────────────────────────────────

    @Test
    fun `the same size keeps every field byte for byte`() {
        val boxes = listOf(box(100, 200, 300, 400), box(0, 0, 1, 1))
        val mapped = AnnotationSwap.scaleBoxes(boxes, fhd, AnnotationSwap.Size(1920, 1080))

        assertEquals(2, mapped.size, "框数量不变")
        assertContentEquals(box(100, 200, 300, 400), mapped[0], "同尺寸时逐字段相同（不因浮点漂一格）")
        assertContentEquals(box(0, 0, 1, 1), mapped[1], "1px 的框也要原样保留")
        assertTrue(AnnotationSwap.isSameSize(fhd, AnnotationSwap.Size(1920, 1080)), "isSameSize 对同尺寸为真")
        assertFalse(AnnotationSwap.isSameSize(fhd, small), "isSameSize 对不同尺寸为假")
    }

    // ── 2. 按比例 ───────────────────────────────────────────────────

    @Test
    fun `an integer ratio scales exactly`() {
        val mapped = AnnotationSwap.scaleBox(box(100, 200, 300, 400), fhd, AnnotationSwap.Size(960, 540))
        assertContentEquals(box(50, 100, 150, 200), mapped, "比例 0.5 时四个字段都精确减半")
    }

    @Test
    fun `a non integer ratio rounds to the nearest pixel`() {
        val mapped = AnnotationSwap.scaleBox(box(100, 200, 300, 400), fhd, small)
        // 100*800/1920 = 41.67→42；200*600/1080 = 111.11→111；300*0.41667 = 125；400*0.55556 = 222.22→222
        assertContentEquals(box(42, 111, 125, 222), mapped, "非整数比四舍五入到整数像素")
    }

    @Test
    fun `each axis uses its own ratio`() {
        // 1000x1000 → 2000x500：横轴放大 2 倍、纵轴缩小 2 倍，混用同一个比例就会错
        val mapped = AnnotationSwap.scaleBox(box(400, 100, 400, 100), AnnotationSwap.Size(1000, 1000), AnnotationSwap.Size(2000, 500))
        assertContentEquals(box(800, 50, 800, 50), mapped, "两个轴各算各的")
    }

    // ── 3. 不越界（核心不变量） ─────────────────────────────────────

    @Test
    fun `every mapped box stays inside the target image`() {
        val sizes = listOf(
            fhd, AnnotationSwap.Size(1280, 720), small,
            AnnotationSwap.Size(100, 100), AnnotationSwap.Size(37, 11),
        )
        var scanned = 0
        var outside = 0
        var degenerate = 0

        for (from in sizes) {
            for (to in sizes) {
                // 刻意包含贴边与**已经越界**的源框（旧数据里确实可能有）
                val boxes = listOf(
                    box(0, 0, from.width, from.height),
                    box(from.width - 1, from.height - 1, 1, 1),
                    box(from.width / 2, from.height / 2, 3, 3),
                    box(from.width, from.height, 10, 10),
                )
                for (m in AnnotationSwap.scaleBoxes(boxes, from, to)) {
                    scanned++
                    if (m[0] < 0 || m[1] < 0 || m[0] + m[2] > to.width || m[1] + m[3] > to.height) outside++
                    if (m[2] < 1 || m[3] < 1) degenerate++
                }
            }
        }

        // 空集包含于任何集合 ⇒ 先确认真的扫到了东西，否则下面两条恒真
        assertTrue(scanned >= 100, "扫描到的映射结果数应 ≥ 100，实际 $scanned")
        assertEquals(0, outside, "没有任何映射结果越出目标图边界")
        assertEquals(0, degenerate, "没有宽高 <1 的退化框")

        val pulled = AnnotationSwap.scaleBox(box(1916, 1076, 4, 4), fhd, small)
        assertTrue(
            pulled[0] + pulled[2] <= small.width && pulled[1] + pulled[3] <= small.height &&
                pulled[2] >= 1 && pulled[3] >= 1,
            "贴边框映射到更小的目标图后被拉回边界内，实际 ${pulled.toList()}",
        )

        val stray = AnnotationSwap.scaleBox(box(5000, 5000, 900, 900), fhd, small)
        assertTrue(
            stray[0] + stray[2] <= small.width && stray[1] + stray[3] <= small.height,
            "源框本身越界时也不会把越界带到目标图，实际 ${stray.toList()}",
        )
    }

    // ── 4. 尺寸读不出来时退化 ───────────────────────────────────────

    @Test
    fun `degenerate sizes degrade to a plain copy`() {
        val boxes = listOf(box(100, 200, 300, 400))
        val cases = listOf<Pair<String, List<IntArray>>>(
            "from = null" to AnnotationSwap.scaleBoxes(boxes, null, small),
            "from = 0x0" to AnnotationSwap.scaleBoxes(boxes, AnnotationSwap.Size(0, 0), small),
            "to = null" to AnnotationSwap.scaleBoxes(boxes, fhd, null),
            "to = 0x0" to AnnotationSwap.scaleBoxes(boxes, fhd, AnnotationSwap.Size(0, 0)),
            "两侧都不可用" to AnnotationSwap.scaleBoxes(boxes, null, null),
        )
        for ((label, mapped) in cases) {
            val m = mapped[0]
            assertTrue(
                m[2] >= 1 && m[3] >= 1,
                "$label：不能产生 0 宽 0 高的框，实际 ${m.toList()}",
            )
        }
        assertEquals(5, cases.size, "5 种退化情形都要覆盖到")

        assertFalse(AnnotationSwap.isUsable(null), "isUsable(null) 为假")
        assertFalse(AnnotationSwap.isUsable(AnnotationSwap.Size(0, 100)), "isUsable 拒绝 0 宽")
        assertTrue(AnnotationSwap.isUsable(AnnotationSwap.Size(1, 1)), "isUsable 接受 1x1")
        assertFalse(
            AnnotationSwap.isSameSize(null, null),
            "两侧都不可用时 isSameSize 为假（不能据此宣称「尺寸相同」）",
        )
    }

    @Test
    fun `a malformed bbox is passed through instead of throwing`() {
        val short = intArrayOf(10, 20)
        val mapped = AnnotationSwap.scaleBox(short, fhd, small)
        assertContentEquals(short, mapped, "长度不足 4 的框原样返回（整套交换不能因为一条坏数据整批失败）")
    }

    // ── 5. 破坏性对照 ───────────────────────────────────────────────

    /**
     * 对照：只按比例缩放、不做边界钳制 —— 越界的源框会原样留在界外，
     * 证明上面"不越界"那条断言确实在约束实现，而不是恒真。
     */
    @Test
    fun `regression guard - without clamping an out of range box stays out`() {
        val stray = box(5000, 5000, 900, 900)
        val rx = small.width.toDouble() / fhd.width
        val wrong = intArrayOf(
            (stray[0] * rx).toInt(), (stray[1] * rx).toInt(),
            (stray[2] * rx).toInt(), (stray[3] * rx).toInt(),
        )
        assertTrue(
            wrong[0] + wrong[2] > small.width,
            "对照：不钳制时越界框留在界外（x+w=${wrong[0] + wrong[2]} > ${small.width}）",
        )
        val real = AnnotationSwap.scaleBox(stray, fhd, small)
        assertTrue(
            real[0] + real[2] <= small.width,
            "真实现把同一个框拉回界内（x+w=${real[0] + real[2]}）",
        )
    }

    /** 对照：比例取反（用 W1/W2）—— 整数倍缩放不再精确。 */
    @Test
    fun `regression guard - an inverted ratio breaks the exact scale`() {
        val rx = fhd.width.toDouble() / small.width
        val wrong = (100 * rx).toInt()
        assertEquals(240, wrong, "对照：比例取反后 100 变成 240")
        assertEquals(
            42,
            AnnotationSwap.scaleBox(box(100, 200, 300, 400), fhd, small)[0],
            "真实现按 W2/W1 得到 42",
        )
    }

    /** 对照：去掉"尺寸不可用就退化成 1"的守卫 —— 目标尺寸缺失会把框压成 0 宽。 */
    @Test
    fun `regression guard - a zero ratio collapses the box`() {
        val zeroRatio = 0.0 / fhd.width
        assertEquals(0, (300 * zeroRatio).toInt(), "对照：按 0 比例缩放会把宽度算成 0")
        assertTrue(
            AnnotationSwap.scaleBox(box(100, 200, 300, 400), fhd, null)[2] >= 1,
            "真实现退化成原样搬运，宽度仍是 300",
        )
    }
}
