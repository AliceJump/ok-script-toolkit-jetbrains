package com.alicejump.okscripttoolkit.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「导出到 assets」纯逻辑测试（`SaveToAssetsFlow`）。
 *
 * 背景：枚举路径的输入框此前**每次导出都弹**。改成"项目约定文件声明过就不再问"之后，
 * 多出两条必须钉住的不变量 —— 它们都属于**改错也看不出来**的那类：
 *
 * 1. **跳过弹框 ⇒ 必须留一个改的口子。** 跳过它而不给替代入口，用户就再也改不了枚举路径；
 * 2. **不跳过 ⇒ 不能多出那一项。** 否则变成"既问了又给入口"，出现两个都能改路径的地方。
 *
 * 所以「改路径」项必须**当且仅当**跳过弹框时出现 —— 这条双向的"当且仅当"是本文件的主要断言。
 */
class SaveToAssetsFlowTest {

    private val TARGETS = listOf("assets", "ok_tasks/assets")
    private val CHANGE = "Change LabelEnum.py path..."

    private fun options(declared: String?) = SaveToAssetsFlow.options(TARGETS, declared, CHANGE)

    /** 列表里有没有「改路径」那一项 —— 用长度判断，与实现同一套口径。 */
    private fun hasChangeEntry(list: List<String>) = list.size > TARGETS.size

    // ── 目标永远都在 ─────────────────────────────────────────────────

    @Test
    fun `the export targets are always present in order`() {
        for ((name, declared) in listOf("没声明" to null, "声明过" to "src/data/LabelEnum.py")) {
            val list = options(declared)
            assertEquals(TARGETS, list.take(TARGETS.size), "$name：两个目标都在，且顺序不变")
        }
    }

    // ── 跳过弹框 ⇔ 提供改路径的入口 ──────────────────────────────────

    @Test
    fun `skipping the prompt comes with an escape hatch, and only then`() {
        val declaredList = options("src/data/LabelEnum.py")
        assertTrue(
            hasChangeEntry(declaredList),
            "**声明过 → 会跳过弹框，所以必须给出「改路径」项** —— 否则用户再也改不了枚举路径",
        )
        assertEquals(CHANGE, declaredList.last(), "那一项追加在末尾，不插进目标之间")

        val undeclaredList = options(null)
        assertTrue(
            !hasChangeEntry(undeclaredList),
            "**没声明 → 仍然会问，所以不能多出「改路径」项** —— 否则出现两个都能改路径的地方",
        )
        assertEquals(TARGETS, undeclaredList, "没声明时列表里只有目标")
    }

    @Test
    fun `needsEnumPathPrompt agrees with the escape hatch in both directions`() {
        for (declared in listOf<String?>(null, "src/data/LabelEnum.py", "src/label_enum.py")) {
            val list = options(declared)
            assertEquals(
                !SaveToAssetsFlow.needsEnumPathPrompt(declared),
                hasChangeEntry(list),
                "declared=$declared：有「改路径」项 ⇔ 会跳过弹框（两个方向都要成立）",
            )
        }
    }

    @Test
    fun `the prompt is only skipped when the project declared a path`() {
        assertTrue(SaveToAssetsFlow.needsEnumPathPrompt(null), "没声明 → 必须问（否则用户没机会改路径）")
        assertTrue(
            !SaveToAssetsFlow.needsEnumPathPrompt("src/data/LabelEnum.py"),
            "**声明了 → 不问**（团队约定好的值，每次导出都确认一遍是纯噪音）",
        )
    }

    // ── 下标判定 ─────────────────────────────────────────────────────

    @Test
    fun `the change path entry is recognised by index, not by label`() {
        // 用下标区间判断而不是比较文案：文案是本地化的，比较文案会在换语言时静默失效。
        assertTrue(!SaveToAssetsFlow.isChangePathChoice(0, TARGETS.size), "第 0 项是第一个目标")
        assertTrue(!SaveToAssetsFlow.isChangePathChoice(1, TARGETS.size), "第 1 项是第二个目标")
        assertTrue(SaveToAssetsFlow.isChangePathChoice(2, TARGETS.size), "第 2 项（= targets.size）才是「改路径」")
        assertTrue(
            SaveToAssetsFlow.isChangePathChoice(2, TARGETS.size) !=
                SaveToAssetsFlow.isChangePathChoice(0, TARGETS.size),
            "边界两边必须给出不同结论，否则下标判定形同虚设",
        )
    }

    // ── 破坏性对照 ───────────────────────────────────────────────────

    /**
     * 对照：把候选列表写死成"永远带改路径项"或"永远不带"，分别对应两种真实故障。
     * 用纯对象构造出那两个错误形态，证明上面那组断言确实在约束"当且仅当"。
     */
    @Test
    fun `regression guard - a hardcoded option list breaks the invariant in one direction`() {
        // ① 永远带：没声明时也多一项 —— 与「没声明 → 不问」矛盾，用户看到两个改路径的地方
        val alwaysAdd = TARGETS + CHANGE
        assertTrue(
            hasChangeEntry(alwaysAdd) && SaveToAssetsFlow.needsEnumPathPrompt(null),
            "对照：既在问、又给了入口 —— 第 2 组的「没声明时列表里只有目标」会抓到它",
        )

        // ② 永远不带：声明过时没有入口 —— 用户再也改不了枚举路径（功能静默丢失）
        val neverAdd = TARGETS
        assertTrue(
            !hasChangeEntry(neverAdd) && !SaveToAssetsFlow.needsEnumPathPrompt("src/data/LabelEnum.py"),
            "对照：跳过了弹框却没有入口 —— 第 2 组的「声明过时必须有那一项」会抓到它",
        )
    }
}
