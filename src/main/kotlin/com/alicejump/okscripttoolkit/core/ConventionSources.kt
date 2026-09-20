package com.alicejump.okscripttoolkit.core

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
 * 参与取值链的**设置清单**，由取值链本身产出（纯函数，不依赖 `Project` / 服务）。
 *
 * 抽成纯函数是为了能在普通 JUnit 里断言 —— 本仓库的既有约定：不依赖 IDE 的逻辑
 * 抽纯对象配单测（见 `TempShotFiles` / `TaskConfigMerge` 等）。
 *
 * ⚠️ **来源层不在这里重新算** —— 它来自 [TemplatesConvention.directoryResolved] /
 * [LabelEnumConvention.aliasesResolved]，也就是取值链本身。
 * 另写一套判断去复算的话迟早与实际生效值分叉，而那种分叉的表现是
 * "界面说来源是项目约定、实际生效的却是我的设置"，属最难查的一类不一致。
 *
 * 加一组新设置时在这里补一行，VS Code 侧 `conventionSources()` 的登记表也要同步补。
 *
 * @param personalTemplatesDirectory 用户**真正改过**的模板目录（没改过给 `null`）
 * @param personalFeatureAliases 用户**真正改过**的别名（没改过给空列表）
 */
fun conventionSourceRows(
    convention: ProjectConvention,
    personalTemplatesDirectory: String?,
    personalFeatureAliases: List<String>,
    templatesFallback: String,
    aliasesFallback: List<String>,
): List<ConventionSourceRow> {
    val templates = convention.templates.directoryResolved(personalTemplatesDirectory, templatesFallback)
    val aliases = convention.labelEnum.aliasesResolved(personalFeatureAliases, aliasesFallback)

    val declaredAliases = convention.labelEnum.aliases.filter { it.isNotBlank() }

    return listOf(
        ConventionSourceRow(
            key = "featureAliases",
            effective = aliases.value.joinToString(", "),
            layer = aliases.layer,
            declared = declaredAliases.takeIf { it.isNotEmpty() }?.joinToString(", "),
            builtin = aliasesFallback.joinToString(", "),
        ),
        ConventionSourceRow(
            key = "okTemplatesDirectory",
            effective = templates.value,
            layer = templates.layer,
            // 归一化后再展示 —— 展示值必须与生效值同源，否则用户会看到"文件里写的是
            // ok_templates\、生效的是 ok_templates"而以为哪儿出错了
            declared = normalizeRelPath(convention.templates.directory),
            builtin = templatesFallback,
        ),
    )
}
