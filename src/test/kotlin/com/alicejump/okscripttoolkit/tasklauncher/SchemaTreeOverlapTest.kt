package com.alicejump.okscripttoolkit.tasklauncher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 参数树重合规则回归测试。
 *
 * 复现用户报告：ok-gf2 的「日常任务」开启「活动层」时，「喝水」「吃饭」两个输入框各渲染了两遍。
 * 根因是两个渲染来源重合 —— 组头字段自身的 `sub_configs` 与 `configGroups` 的 children
 * 是同一批 key，两个循环各画一遍。
 */
class SchemaTreeOverlapTest {

    /** 用户报告的场景：活动层 的 sub_configs 与 configGroups children 完全重合 */
    @Test
    fun `activity layer children must not render twice`() {
        val skip = SchemaTreeOverlap.inlineChildrenToSkip(
            headerField = "活动层",
            groupChildren = listOf("喝水", "吃饭"),
            inlineChildren = listOf("喝水", "吃饭"),
        )
        assertEquals(setOf("喝水", "吃饭"), skip)
    }

    /** 只有重合的部分才跳过，组头独有的行内子字段必须保留 */
    @Test
    fun `inline children outside the group are kept`() {
        val skip = SchemaTreeOverlap.inlineChildrenToSkip(
            headerField = "titleField",
            groupChildren = listOf("titleField", "plainChild"),
            inlineChildren = listOf("titleChild"),
        )
        assertTrue(skip.isEmpty(), "titleChild 不在 groupChildren 里，必须继续行内渲染")
        assertTrue(
            SchemaTreeOverlap.shouldRenderInlineChild("titleField", listOf("titleField", "plainChild"), "titleChild"),
        )
        assertFalse(
            SchemaTreeOverlap.shouldRenderInlineChild("titleField", listOf("titleField", "plainChild"), "plainChild"),
            "plainChild 已由 configGroups 渲染，行内必须跳过",
        )
    }

    /** 组头字段自身出现在 children 里时，不应把自己算作「需要跳过的行内子字段」 */
    @Test
    fun `header field itself is never treated as an inline child to skip`() {
        val skip = SchemaTreeOverlap.inlineChildrenToSkip(
            headerField = "活动层",
            groupChildren = listOf("活动层"),
            inlineChildren = listOf("活动层"),
        )
        assertTrue(skip.isEmpty())
    }

    /** 空 children / 空 inline 都不应出错 */
    @Test
    fun `empty inputs are safe`() {
        assertTrue(SchemaTreeOverlap.inlineChildrenToSkip("a", emptyList(), emptyList()).isEmpty())
        assertTrue(SchemaTreeOverlap.inlineChildrenToSkip("a", emptyList(), listOf("b")).isEmpty())
        assertTrue(SchemaTreeOverlap.inlineChildrenToSkip("a", listOf("b"), emptyList()).isEmpty())
    }

    /** 重复项（同一 key 出现在多个 choice 下）按集合去重，不产生重复跳过 */
    @Test
    fun `duplicate inline children collapse to a set`() {
        val skip = SchemaTreeOverlap.inlineChildrenToSkip(
            headerField = "sw",
            groupChildren = listOf("x"),
            inlineChildren = listOf("x", "x", "y"),
        )
        assertEquals(setOf("x"), skip)
    }

    /**
     * 破坏性对照：如果哪天有人把「跳过重合」的逻辑删掉（即回到 bug 版本），
     * 这条断言必须失败 —— 它锁死的是「重合子字段确实会被跳过」这一事实。
     */
    @Test
    fun `regression guard - removing the skip rule would duplicate rows`() {
        val headerField = "活动层"
        val groupChildren = listOf("喝水", "吃饭")
        val inlineChildren = listOf("喝水", "吃饭")

        // 修复后的行为：重合的都跳过 -> 每个字段只由 configGroups 渲染一次
        val renderedPerField = inlineChildren.count {
            SchemaTreeOverlap.shouldRenderInlineChild(headerField, groupChildren, it)
        } + groupChildren.count { it != headerField }
        assertEquals(2, renderedPerField, "每个子字段只应渲染一次")

        // 对照：若跳过规则失效（所有行内子字段都渲染），则每个字段会渲染两遍
        val buggyRenderedPerField = inlineChildren.size + groupChildren.count { it != headerField }
        assertEquals(4, buggyRenderedPerField, "跳过规则失效时必须是重复渲染，说明本测试确实能捕获该 bug")
    }
}
