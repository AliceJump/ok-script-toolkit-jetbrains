package com.alicejump.okscripttoolkit.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 缩略图缓存键的测试（`TemplateThumbCacheKey`）。
 *
 * 键的三项组成**一个都不能少**，每项漏掉的后果都是"读到别人的图"或"读到旧图"，
 * 而且都**不会报错** —— 只是面板里显示的图和实际不是一回事：
 *
 * | 漏掉 | 后果 |
 * |---|---|
 * | 内容 hash | 同路径换图后键不变 → 复用旧缩略图（父仓 `pngCrop` 踩过这个坑） |
 * | bbox | 同一张原图上不同模板互相串图 |
 * | 目标高度 | 改了预览尺寸后仍读到按旧高度渲染的图 |
 *
 * 所以这里对每一项都配了断言，并对"漏掉某一项"配了破坏性对照。
 */
class TemplateThumbCacheKeyTest {

    private val HASH = "a1b2c3d4e5f60718"
    private val BBOX = intArrayOf(10, 20, 30, 40)
    private val HEIGHT = 72

    private fun key(
        hash: String = HASH,
        bbox: IntArray = BBOX,
        height: Int = HEIGHT,
    ) = TemplateThumbCacheKey.keyOf(hash, bbox, height)

    // ── 三项都参与 ───────────────────────────────────────────────────

    @Test
    fun `the key contains all three parts`() {
        val k = key()
        assertTrue(k.contains(HASH), "键里含内容 hash")
        assertTrue(k.contains("10,20,30,40"), "键里含 bbox")
        assertTrue(k.endsWith("|$HEIGHT"), "键里含目标高度")
    }

    @Test
    fun `a different content hash yields a different key`() {
        assertTrue(
            key(hash = "ffffffffffffffff") != key(),
            "**内容变了键必须变** —— 否则同路径换图后会复用旧缩略图（面板的图和点开的不一致）",
        )
    }

    @Test
    fun `a different bbox yields a different key`() {
        assertTrue(
            key(bbox = intArrayOf(11, 20, 30, 40)) != key(),
            "**裁剪框变了键必须变** —— 否则同一张原图上不同模板会互相串图",
        )
    }

    @Test
    fun `a different target height yields a different key`() {
        assertTrue(
            key(height = 96) != key(),
            "**目标高度变了键必须变** —— 否则改了预览尺寸仍读到按旧高度渲染的图",
        )
    }

    @Test
    fun `bbox order matters`() {
        assertTrue(
            key(bbox = intArrayOf(40, 30, 20, 10)) != key(),
            "bbox 是有序四元组（x,y,w,h），换个顺序必须算出不同的键",
        )
    }

    // ── 分隔符不能产生歧义 ───────────────────────────────────────────

    @Test
    fun `the separator keeps concatenations unambiguous`() {
        // 若用 `-`/空串拼接，`1|23` 与 `12|3` 会撞
        val a = TemplateThumbCacheKey.keyOf("h", intArrayOf(1), 23)
        val b = TemplateThumbCacheKey.keyOf("h", intArrayOf(12), 3)
        assertTrue(a != b, "**拼接不能有歧义**：`1|23` 与 `12|3` 必须算出不同的键")
    }

    @Test
    fun `the file name is safe and keeps uniqueness`() {
        val name = TemplateThumbCacheKey.fileNameOf(HASH, BBOX, HEIGHT)
        assertTrue(name.endsWith(".png"), "缓存文件是 png")
        assertTrue(!name.contains('|'), "**文件名里不能有 `|`** —— 它在 Windows 上非法")
        assertTrue(name.contains(HASH) && name.contains("10,20,30,40"), "唯一性信息都在文件名里")
        assertEquals(
            name,
            TemplateThumbCacheKey.fileNameOf(HASH, BBOX, HEIGHT),
            "同样的输入必须得到同样的文件名（缓存才能命中）",
        )
    }

    @Test
    fun `different bboxes never map to the same file name`() {
        val names = listOf(
            TemplateThumbCacheKey.fileNameOf(HASH, intArrayOf(1, 2, 3, 4), HEIGHT),
            TemplateThumbCacheKey.fileNameOf(HASH, intArrayOf(1, 2, 3, 5), HEIGHT),
            TemplateThumbCacheKey.fileNameOf(HASH, intArrayOf(1, 2, 4, 4), HEIGHT),
            TemplateThumbCacheKey.fileNameOf(HASH, intArrayOf(1, 3, 3, 4), HEIGHT),
            TemplateThumbCacheKey.fileNameOf(HASH, intArrayOf(2, 2, 3, 4), HEIGHT),
        )
        assertEquals(names.size, names.toSet().size, "五个不同 bbox 必须给出五个不同文件名")
    }

    // ── 破坏性对照 ───────────────────────────────────────────────────

    /**
     * 对照：把某一项从键里拿掉，不同输入就会算出同一个键 —— 也就是"读到别人的图"。
     * 证明上面那三条"必须变"的断言确实在约束每一项都参与。
     */
    @Test
    fun `regression guard - dropping any part of the key causes collisions`() {
        // 漏掉目标高度
        val noHeight = { h: Int -> "$HASH|${TemplateThumbCacheKey.bboxToken(BBOX)}" }
        assertEquals(noHeight(72), noHeight(96), "对照：漏掉高度 → 两个尺寸撞成一个键")

        // 漏掉 bbox
        val noBbox = { b: IntArray -> "$HASH|$HEIGHT" }
        assertEquals(noBbox(BBOX), noBbox(intArrayOf(99, 99, 99, 99)), "对照：漏掉 bbox → 两个裁剪框撞成一个键")

        // 漏掉内容 hash
        val noHash = { h: String -> "${TemplateThumbCacheKey.bboxToken(BBOX)}|$HEIGHT" }
        assertEquals(noHash("aaa"), noHash("bbb"), "对照：漏掉内容 hash → 换了图还是同一个键")

        // 真实现三项齐全，上述三种撞法都不成立
        assertTrue(key(height = 72) != key(height = 96), "真实现：高度不同键不同")
        assertTrue(key(bbox = intArrayOf(99, 99, 99, 99)) != key(), "真实现：bbox 不同键不同")
        assertTrue(key(hash = "bbb") != key(hash = "aaa"), "真实现：内容不同键不同")
    }
}
