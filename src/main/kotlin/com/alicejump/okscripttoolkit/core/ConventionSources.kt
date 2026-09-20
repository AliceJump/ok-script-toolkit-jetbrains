package com.alicejump.okscripttoolkit.core

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle

/**
 * 溯源面板的一行：某项设置的**生效值**、它**来自哪一层**，以及"恢复"所需的上下文。
 *
 * 存在的理由（`docs/project-config.md` §3）：取值链把**个人偏好**排最高
 * （项目文件是团队开箱默认，我改过就用我的），代价是一旦手动改过，
 * 项目声明的那一项就对我**永久失效** —— 界面上毫无提示。
 * 这个面板就是那个缓冲：把来源显式呈现出来，并允许一键回到项目约定。
 *
 * 与 VS Code 侧 `conventionSources.ts` 的 `ConventionSourceRow` 一一对应。
 */
data class ConventionSourceRow(
    /** 设置键名，如 `featureAliases` */
    val key: String,
    /** 生效值（展示用） */
    val effective: String,
    /** 生效值来自哪一层 */
    val layer: ConventionLayer,
    /** 项目约定文件里声明的值；没声明为 `null`（展示用） */
    val declared: String?,
    /** 内置兜底（展示用：让用户知道"恢复"之后会回到什么） */
    val builtin: String,
) {
    /**
     * 是否有个人覆盖 —— **由 [layer] 推出，不比对值**。
     *
     * 刻意做成派生属性而不是字段：这样"界面说来源是我的设置"与"能点恢复"
     * 永远同时成立，不会出现"来源写着我的设置、却没有恢复按钮"这类自相矛盾的界面。
     */
    val overridden: Boolean get() = layer == ConventionLayer.PERSONAL
}

/**
 * 溯源面板的**个人偏好输入**：每一项传"用户真正设过的值"，没设过给 `null` / 空列表。
 *
 * 为什么单独一个类型：`SettingsState` 里这些字段**都带非空默认值**，
 * 直接读 state 会让个人偏好层永远命中、项目声明永远不生效（静默）。
 * 调用方（`OkScriptToolkitSettings.conventionSources()`）负责按 `overriddenKeys`
 * 记账把"没设过"归一成 `null`，这里只消费归一后的结果。
 *
 * 与 VS Code 侧 `conventionSources()` 里逐个 `ideSetting(...)` 的取值一一对应。
 */
data class ConventionPersonal(
    val templatesDirectory: String? = null,
    val featureAliases: List<String> = emptyList(),
    val i18nEnabled: Boolean? = null,
    val langDirectory: String? = null,
    val poDirectory: String? = null,
    val poDomains: List<String> = emptyList(),
    val characterProjectPath: String? = null,
    val characterMasterFile: String? = null,
    val characterSkillsDirectory: String? = null,
    val characterLocaleFile: String? = null,
    val characterAvatarTemplateRegex: String? = null,
    val effectsFile: String? = null,
)

/**
 * 造一行。
 *
 * `declared`（项目文件里到底写了什么）**不是另读一遍 `ProjectConvention` 字段得来的**，
 * 而是把个人偏好置空、**再跑一次同一条链**：命中的是 [ConventionLayer.BUILTIN]
 * 就说明项目没声明。这样展示值与生效值走同一套归一化与类型判断 ——
 * 声明写 `./lang` 时两处都显示 `lang`，不会出现"面板显示一个样、实际匹配另一个样"。
 *
 * @param chain 取值链本身，参数是"个人偏好值"（`null` 表示没设过）
 */
private fun <T> rowOf(
    key: String,
    fallback: T,
    personal: T?,
    chain: (T?) -> ResolvedSetting<T>,
    render: (T) -> String,
): ConventionSourceRow {
    val effective = chain(personal)
    val declared = chain(null)
    return ConventionSourceRow(
        key = key,
        effective = render(effective.value),
        layer = effective.layer,
        declared = if (declared.layer == ConventionLayer.BUILTIN) null else render(declared.value),
        builtin = render(fallback),
    )
}

/**
 * 参与取值链的**设置清单**，由取值链本身产出（纯函数，不依赖 `Project` / 服务）。
 *
 * 抽成纯函数是为了能在普通 JUnit 里断言 —— 本仓库的既有约定：不依赖 IDE 的逻辑
 * 抽纯对象配单测（见 `TempShotFiles` / `TaskConfigMerge` 等）。
 *
 * ⚠️ **来源层不在这里重新算** —— 它来自各 `xxxResolved()`，也就是取值链本身。
 * 另写一套判断去复算的话迟早与实际生效值分叉，而那种分叉的表现是
 * "界面说来源是项目约定、实际生效的却是我的设置"，属最难查的一类不一致。
 *
 * 加一组新设置时在这里补一行，VS Code 侧 `conventionSources()` 的登记表也要同步补。
 *
 * 键名是 IDE 设置名，不是项目约定文件里的字段名 —— 两者**刻意允许不同名**
 * （`enablePoData` ↔ `i18n.enabled`：前者是"我这台机器要不要读它"，
 * 后者是"这个项目的 i18n 长什么样"）。面板按设置名成行，用户能直接去设置界面找。
 */
fun conventionSourceRows(
    convention: ProjectConvention,
    personal: ConventionPersonal = ConventionPersonal(),
): List<ConventionSourceRow> {
    val defaults = ConventionDefaults

    return listOf(
        rowOf(
            key = "featureAliases",
            fallback = defaults.FEATURE_ALIASES,
            personal = personal.featureAliases,
            chain = { convention.labelEnum.aliasesResolved(it.orEmpty(), defaults.FEATURE_ALIASES) },
            render = { it.joinToString(", ") },
        ),
        rowOf(
            key = "okTemplatesDirectory",
            fallback = defaults.TEMPLATES_DIRECTORY,
            personal = personal.templatesDirectory,
            chain = { convention.templates.directoryResolved(it, defaults.TEMPLATES_DIRECTORY) },
            render = { it },
        ),
        rowOf(
            key = "enablePoData",
            fallback = defaults.I18N_ENABLED,
            personal = personal.i18nEnabled,
            chain = { convention.i18n.enabledResolved(it, defaults.I18N_ENABLED) },
            render = { it.toString() },
        ),
        rowOf(
            key = "langDirectory",
            fallback = defaults.LANG_DIRECTORY,
            personal = personal.langDirectory,
            chain = { convention.i18n.langDirectoryResolved(it, defaults.LANG_DIRECTORY) },
            render = { it },
        ),
        rowOf(
            key = "poDirectory",
            fallback = defaults.PO_DIRECTORY,
            personal = personal.poDirectory,
            chain = { convention.i18n.poDirectoryResolved(it, defaults.PO_DIRECTORY) },
            render = { it },
        ),
        rowOf(
            key = "poDomains",
            fallback = defaults.PO_DOMAINS,
            personal = personal.poDomains,
            chain = { convention.i18n.poDomainsResolved(it.orEmpty(), defaults.PO_DOMAINS) },
            render = { it.joinToString(", ") },
        ),
        rowOf(
            key = "characterProjectPath",
            fallback = defaults.CHARACTER_PROJECT_PATH,
            personal = personal.characterProjectPath,
            chain = { convention.characters.projectPathResolved(it, defaults.CHARACTER_PROJECT_PATH) },
            // 兜底是**空串**（含义：与当前项目相同）。空值在面板上是一段空白，看着像坏了，
            // 所以渲染成一句人话。
            render = { it.ifEmpty { OkScriptToolkitBundle.message("conventionSources.sameAsCurrentProject") } },
        ),
        rowOf(
            key = "characterMasterFile",
            fallback = defaults.CHARACTER_MASTER_FILE,
            personal = personal.characterMasterFile,
            chain = { convention.characters.masterFileResolved(it, defaults.CHARACTER_MASTER_FILE) },
            render = { it },
        ),
        rowOf(
            key = "characterSkillsDirectory",
            fallback = defaults.CHARACTER_SKILLS_DIRECTORY,
            personal = personal.characterSkillsDirectory,
            chain = { convention.characters.skillsDirectoryResolved(it, defaults.CHARACTER_SKILLS_DIRECTORY) },
            render = { it },
        ),
        rowOf(
            key = "characterLocaleFile",
            fallback = defaults.CHARACTER_LOCALE_FILE,
            personal = personal.characterLocaleFile,
            chain = { convention.characters.localeFileResolved(it, defaults.CHARACTER_LOCALE_FILE) },
            render = { it },
        ),
        rowOf(
            key = "characterAvatarTemplateRegex",
            fallback = defaults.AVATAR_TEMPLATE_REGEX,
            personal = personal.characterAvatarTemplateRegex,
            chain = { convention.characters.avatarTemplateRegexResolved(it, defaults.AVATAR_TEMPLATE_REGEX) },
            render = { it },
        ),
        rowOf(
            key = "effectsFile",
            fallback = defaults.EFFECTS_FILE,
            personal = personal.effectsFile,
            chain = { convention.effects.fileResolved(it, defaults.EFFECTS_FILE) },
            render = { it },
        ),
    )
}
