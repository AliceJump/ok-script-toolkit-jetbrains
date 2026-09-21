package com.alicejump.okscripttoolkit.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「记账键」必须三处一致 —— **源码扫描测试**。
 *
 * 子仓靠 `SettingsState.overriddenKeys` 记账来区分「用户设过」与「只是默认值」。
 * 同一个键要在**三个地方**出现，少任何一处都是**静默**故障：
 *
 * | 位置 | 少了的后果 |
 * |---|---|
 * | `OkScriptToolkitSettings` 里的 `personal(KEY_X)` | 那一项的个人偏好层永不命中 —— 用户在设置界面改了值，却被项目约定文件顶掉 |
 * | `OkScriptToolkitConfigurable.apply()` 里的 `recordIfChanged(KEY_X)` | 同上（记账永远为空，等于没接链） |
 * | `init` 的播种 map | **老用户**的覆盖不被迁移 —— 升级后自己改过的值被项目声明顶掉 |
 *
 * 三种都表现为"界面一切正常、只是配置不生效"，肉眼审查基本抓不到 ——
 * 所以用源码扫描把"加新键时忘了补某一处"这个具体故障钉住。
 *
 * ⚠️ 扫的是**源码文本**，不是编译产物：只能断言"常量名出现过"，断不了语义。
 * 对这个故障足够；真要更强可以等有编译期注解处理时再说。
 *
 * ⚠️ **播种那一处只断言"是声明过的键"，不断言"覆盖全部键"**：
 * 播种是为了迁移**接链之前就已发布**的键；一个"新增且同时接链"的键从来没有老用户，
 * 不需要播种。要求它覆盖全部键会逼人写无意义的条目。
 */
class OverrideKeyParityTest {

    private val settingsPath = "com/alicejump/okscripttoolkit/settings/OkScriptToolkitSettings.kt"
    private val configurablePath = "com/alicejump/okscripttoolkit/settings/OkScriptToolkitConfigurable.kt"

    private fun settingsText(): String = TestRepoLayout.mainSource(settingsPath).readText()
    private fun configurableText(): String = TestRepoLayout.mainSource(configurablePath).readText()

    /** `const val KEY_X = "..."` —— 返回 名字 → 字符串值。 */
    private fun declaredKeys(): Map<String, String> =
        Regex("""const val (KEY_[A-Z0-9_]+)\s*=\s*"([^"]*)"""")
            .findAll(settingsText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    /** `personal(KEY_X)` —— 个人偏好层真正消费的键。 */
    private fun consumedKeys(): Set<String> =
        Regex("""personal\(\s*(KEY_[A-Z0-9_]+)""")
            .findAll(settingsText())
            .map { it.groupValues[1] }
            .toSet()

    /** `recordIfChanged(OkScriptToolkitSettings.KEY_X, …)` —— 设置面板记账的键。 */
    private fun recordedKeys(): Set<String> =
        Regex("""recordIfChanged\(\s*(?:OkScriptToolkitSettings\.)?(KEY_[A-Z0-9_]+)""")
            .findAll(configurableText())
            .map { it.groupValues[1] }
            .toSet()

    /**
     * 播种 map 里的键（`KEY_X to state.…` / `KEY_X to DEFAULT_…`）。
     *
     * 用"行首缩进 + KEY_X to "定位：`init` 里那两段 map 是这个形状，
     * 而别处（`conventionSources`）用的是命名参数 `xxx = personal(KEY_X)`，不会误命中。
     */
    private fun seededKeys(): Set<String> =
        Regex("""^\s*(KEY_[A-Z0-9_]+) to """, RegexOption.MULTILINE)
            .findAll(settingsText())
            .map { it.groupValues[1] }
            .toSet()

    // ── 前置：扫描本身有东西可扫 ─────────────────────────────────────

    @Test
    fun `the scan actually finds the declarations`() {
        assertTrue(
            declaredKeys().isNotEmpty(),
            "扫不到任何 KEY_ 常量 —— 要么常量被改名了、要么路径定位坏了。" +
                "**不能让扫描静默扫空**：空集与空集比较会恒真，整个测试就废了",
        )
        assertEquals(
            13,
            declaredKeys().size,
            "声明的记账键数量变了。如果是**新增**了设置项，请同步补 personal / recordIfChanged / 播种三处；" +
                "如果是**删除**，改这个数字",
        )
        assertTrue(consumedKeys().isNotEmpty(), "扫不到 personal(KEY_*) —— 同上，别让扫描静默扫空")
        assertTrue(recordedKeys().isNotEmpty(), "扫不到 recordIfChanged(KEY_*) —— 同上")
    }

    // ── 核心不变量 ───────────────────────────────────────────────────

    @Test
    fun `every declared key is consumed by the personal layer`() {
        assertEquals(
            declaredKeys().keys,
            consumedKeys(),
            "**声明了却没被 personal(KEY_*) 消费的键** = 死常量（记账了却没人读）；" +
                "**被消费却没声明的** = 用了一个不存在的常量名（编译就过不去，但扫描能提前发现）",
        )
    }

    @Test
    fun `every declared key can be recorded by the settings panel`() {
        assertEquals(
            declaredKeys().keys,
            recordedKeys(),
            "**少一个 recordIfChanged 的后果**：那一项的个人偏好层永不命中 —— " +
                "用户在设置界面改了值，却被项目约定文件静默顶掉。这是本次要防的主要故障",
        )
    }

    @Test
    fun `every seeded key is a declared one`() {
        val declared = declaredKeys().keys
        assertTrue(
            seededKeys().isNotEmpty(),
            "播种 map 扫不到任何键 —— 扫描方式可能失效了（它靠 `KEY_X to ` 这个形状）",
        )
        assertTrue(
            seededKeys().all { it in declared },
            "播种 map 里有**没声明过的键**：${seededKeys() - declared}。" +
                "这不会报错，只会给一个没人读的键记账 —— 老用户的迁移静默失效",
        )
    }

    @Test
    fun `key string values are unique`() {
        val values = declaredKeys().values
        assertEquals(
            values.size,
            values.toSet().size,
            "**键的字符串值重复了**：两个设置会共用同一个记账槽位 —— " +
                "在其中一个上「恢复为项目约定」会顺手把另一个也清掉（静默、且极难查）",
        )
    }
}
