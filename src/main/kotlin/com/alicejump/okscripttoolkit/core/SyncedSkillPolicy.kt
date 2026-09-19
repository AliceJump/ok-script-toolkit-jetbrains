package com.alicejump.okscripttoolkit.core

/**
 * 同步技能的字段保护粒度（对齐 VSCode 的 `mutateCharacter` / `updateSkill`）。
 *
 * 「同步技能」指 `_ok_lang_hints_custom` 不为 `true` 的技能 —— 数据来自上游同步，
 * 本地改动会在下次同步时被覆盖，因此要保护其中的**标识与语义**字段。
 *
 * **保护粒度只到标识与语义，不到数值。** 倍率 / 失衡 / 冷却 / 技力消耗 / 效果
 * 是留给本地调参的，同步数据本身不负责这部分平衡性。这与 VSCode 一致：
 *
 * - `src/characterPanel.ts` 的 `updateSkill`：非 custom 时把
 *   `skill_id`/`name`/`skill_type`/`element`/`description` **还原为原值**，
 *   其余字段照常写入。
 * - `media/characterManager/app.js` 的 `showSkillEditor`：非 custom 时只把这五个
 *   渲染成 `readonlyControl` 并显示 `syncedSkillLocked` 提示，数值控件照常可编辑。
 *
 * 早先 JetBrains 侧是「更新同步技能就整个抛 `is synced and locked`」，
 * 于是同一个技能文件在 VSCode 能改数值字段、在 JetBrains 改不了 ——
 * 同一份数据两套规则，且用户无从得知差异来源（P3-6）。
 *
 * 之所以抽成不依赖 `Project` 的纯对象：本仓测试约定是纯 JVM 单测
 * （`src/test` 下无一处 `import com.intellij.*`），
 * `CharacterDataMutations` 无法直接构造，规则必须外置才可验证。
 */
internal object SyncedSkillPolicy {

    /**
     * 同步技能下受保护的字段：标识与语义。
     *
     * 数值（`damage_multiplier`/`stagger_value`/`cooldown`/`spirit_cost`）与
     * `effects` **故意不在此列** —— 它们正是同步技能唯一允许本地调整的部分。
     */
    val LOCKED_FIELDS: Set<String> = setOf(
        "skill_id",
        "name",
        "skill_type",
        "element",
        "description",
    )

    /** `_ok_lang_hints_custom` 缺失或非 `true` 即视为同步技能。 */
    fun isSynced(custom: Boolean): Boolean = !custom

    /**
     * 按同步状态裁剪表单。
     *
     * 同步技能丢弃 [LOCKED_FIELDS]，让这些字段**保持文件里的既有值**
     * （等价于 VSCode「先写入再还原」的效果，但少一次无谓写入）；
     * 非同步技能原样返回，不做任何裁剪。
     */
    fun filterForm(form: Map<String, String>, synced: Boolean): Map<String, String> =
        if (!synced) form else form.filterKeys { it !in LOCKED_FIELDS }
}
