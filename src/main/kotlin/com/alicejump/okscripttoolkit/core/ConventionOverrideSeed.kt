package com.alicejump.okscripttoolkit.core

/**
 * 老用户迁移：给"值不等于内置默认"的设置**补上** `overriddenKeys` 记账。
 *
 * 为什么需要：v1.8 之前这些设置**不进取值链**，用户改过的值没有记账。
 * 接上取值链后，`overriddenKeys` 里没有它们 → 个人偏好层不命中 → 用户改过的值
 * 会被项目约定文件顶掉。用户看到的是"升级之后我的设置突然不管用了"。
 *
 * 判据：**默认值不会被写成非默认值** —— 所以"值不等于内置默认"必然是用户自己改过的。
 *
 * 抽成纯对象（而不是留在 `SettingsState.init` 里）有两个原因，都与本仓库的既有约定一致：
 *
 * 1. 要 `Project` 的服务类测不了 —— `init` 在 `SimplePersistentStateComponent` 里，
 *    普通 JUnit 构造不出来；
 * 2. 这段逻辑**只在升级路径上跑一次**，手测几乎覆盖不到，而写错的后果
 *    （老用户的覆盖被静默顶掉）恰恰只在那一次出现。
 *
 * ⚠️ 它**只补记账、不改值** —— 所以无论判据多保守，都不会让用户的设置值突然变化。
 */
object ConventionOverrideSeed {

    /**
     * 算出需要补记账的键。
     *
     * @param overriddenKeys 已经记过的键（结果里会排除它们 —— 重复记账没有意义）
     * @param current 当前 state 里的值（键 → 值；缺键视为没设过）
     * @param defaults 各键的内置兜底
     * @return 需要新增的键，顺序与 [current] 的迭代顺序一致
     */
    fun keysToSeed(
        overriddenKeys: List<String>,
        current: Map<String, Any?>,
        defaults: Map<String, Any>,
    ): List<String> = current.keys
        .filter { it !in overriddenKeys }
        .filter { key -> !isUnset(current[key]) && current[key] != defaults[key] }
        .toList()

    /**
     * "没设过"的三种形态：`null`、全空白字符串、空集合。
     *
     * 空集合也要算没设过：`poDomains` 在 `init` 里被填成 `["ocr"]`，但用户手动清空
     * 之后 state 里就是空列表 —— 那表达的是"我没指定"，不是"我指定了一个空集合"。
     */
    private fun isUnset(value: Any?): Boolean = when (value) {
        null -> true
        is String -> value.isBlank()
        is Collection<*> -> value.isEmpty()
        else -> false
    }
}
