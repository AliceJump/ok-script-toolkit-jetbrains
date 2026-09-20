package com.alicejump.okscripttoolkit.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 缩略图按源图分组的测试（`TemplateThumbBatch`）。
 *
 * 要防的缺陷很具体：缩略图是"从原图裁 bbox"，**一次裁剪必须先解码整张原图**。
 * 若按模板逐个处理，同一张图有几个模板就解码几次 ——
 * 实测 ok-end-field 是 **276 模板 / 16 张图（17:1）**，也就是每张原图被解 17 遍。
 *
 * 这里钉住两条：
 * 1. **同一张原图只出现在一个组里**（否则等于没分组）；
 * 2. **顺序稳定**（组间与组内都保持首次出现顺序）——
 *    顺序漂了会让"先渲染的先出现"失效，界面上缩略图会跳。
 */
class TemplateThumbBatchTest {

    private data class Item(val name: String, val image: String)

    private fun items(vararg pairs: Pair<String, String>) =
        pairs.map { Item(it.first, it.second) }

    private fun group(items: List<Item>) = TemplateThumbBatch.groupByImage(items) { it.image }

    @Test
    fun `templates sharing one source image end up in a single group`() {
        // 真实比例：一张图 17 个模板（ok-end-field 的实测值）
        val batch = items(*(1..17).map { "t$it" to "assets/images/a.png" }.toTypedArray())
        val groups = group(batch)
        assertEquals(
            1,
            groups.size,
            "**同一张原图必须只出现在一个组里** —— 否则每张图会被解码 17 次（这正是要修的缺陷）",
        )
        assertEquals(17, groups[0].items.size, "该组里的 17 个模板都留着，一个都不能丢")
        assertEquals("assets/images/a.png", groups[0].imagePath, "组的源图路径正确")
    }

    @Test
    fun `groups and items keep their first-appearance order`() {
        val batch = items(
            "a1" to "img/A.png",
            "b1" to "img/B.png",
            "a2" to "img/A.png",
            "c1" to "img/C.png",
            "b2" to "img/B.png",
        )
        val groups = group(batch)
        assertEquals(
            listOf("img/A.png", "img/B.png", "img/C.png"),
            groups.map { it.imagePath },
            "**组按首次出现的顺序排** —— 顺序漂了缩略图会跳",
        )
        assertEquals(
            listOf(listOf("a1", "a2"), listOf("b1", "b2"), listOf("c1")),
            groups.map { g -> g.items.map { it.name } },
            "组内也保持原顺序（不是按名字重排）",
        )
    }

    @Test
    fun `each source image appears exactly once across all groups`() {
        val batch = items(
            "a1" to "A", "b1" to "B", "a2" to "A", "b2" to "B", "a3" to "A",
        )
        val paths = group(batch).map { it.imagePath }
        assertEquals(
            paths.size,
            paths.toSet().size,
            "**组的数量 == 不同源图的数量** —— 出现重复的源图路径说明分组没生效",
        )
        assertEquals(2, paths.size, "A / B 两张图 → 2 组")
    }

    @Test
    fun `nothing is dropped`() {
        val batch = items(
            "a1" to "A", "b1" to "B", "a2" to "A", "c1" to "C", "b2" to "B", "c2" to "C",
        )
        val flat = group(batch).flatMap { it.items }
        assertEquals(batch.size, flat.size, "分组前后项数不变")
        assertEquals(
            batch.map { it.name }.sorted(),
            flat.map { it.name }.sorted(),
            "分组只改变**顺序**（按图聚拢），不丢项也不多项 —— " +
                "所以这里比集合而不是比序列：**分组本来就会重排**，直接比序列会假失败",
        )
    }

    @Test
    fun `an empty batch yields no groups`() {
        assertTrue(group(emptyList()).isEmpty(), "空输入返回空列表（不抛异常）")
    }

    @Test
    fun `one template per image still groups correctly`() {
        // ok-wuthering-waves 那种形态：281 模板 / 156 图 ≈ 1.8:1
        val batch = items("a" to "A", "b" to "B", "c" to "C")
        val groups = group(batch)
        assertEquals(3, groups.size, "一图一模板就是 3 组")
        assertTrue(groups.all { it.items.size == 1 }, "每组一项")
    }

    /**
     * 对照：**逐项处理**（每个模板自成一组）会让同一张原图出现 N 次 ——
     * 也就是修复前的行为。证明上面那条"只出现在一个组里"确实在约束东西。
     */
    @Test
    fun `regression guard - processing item by item repeats the source image`() {
        val batch = items(*(1..17).map { "t$it" to "assets/images/a.png" }.toTypedArray())

        val naive = batch.map { TemplateThumbBatch.Group(it.image, listOf(it)) }
        assertEquals(17, naive.size, "对照：逐项处理 → 17 组，同一张原图被解 17 次")
        assertEquals(
            17,
            naive.count { it.imagePath == "assets/images/a.png" },
            "对照：那张原图在 17 个组里各出现一次",
        )

        val fixed = group(batch)
        assertEquals(1, fixed.size, "真实现：分组后只有 1 组")
        assertTrue(
            fixed.size != naive.size,
            "对照与真实现的组数确实不同 —— 证明断言在约束「按图分组」这件事",
        )
    }
}
