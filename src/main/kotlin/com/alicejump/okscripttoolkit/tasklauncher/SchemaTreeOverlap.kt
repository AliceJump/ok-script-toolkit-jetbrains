package com.alicejump.okscripttoolkit.tasklauncher

/**
 * 参数树的渲染决策。
 *
 * 抽成不依赖 IDE / Swing 的纯对象，因为这里藏着一条「看起来像布局、其实是数据约定」的规则，
 * 而且它一旦错就很隐蔽 —— 字段会被渲染两遍，界面只是"多出来一块"，不像崩溃那样显眼。
 *
 * 背景：schema 里同一批 key 可能同时出现在两个来源
 * 1. 组头字段自身的 `type.sub_configs`（boolean 开关的行内子字段）；
 * 2. `configGroups` 里该组声明的 children。
 *
 * `default_config_group` 注册出来的组（如 ok-gf2 的「活动层」）两者往往**完全重合**，
 * 于是渲染器若把两个来源都画一遍，「喝水 / 吃饭」这类子字段就会在组内重复出现。
 */
internal object SchemaTreeOverlap {

    /**
     * **折叠优先、显隐其次**：该 key 是否应当忽略自身的 inline 显隐规则。
     *
     * 同一个 key 不能既是折叠分组又带显隐 —— 折叠有权「吸收」显隐。任务项目常给每个
     * 分组名也挂一份 `sub_configs`（如 ok-gf2 的 `_init_default_config_group` 循环），
     * 此时两套机制会打架：折叠展开了、子项却仍被显隐判定为 hidden。
     *
     * 被吸收后，那些「只在 sub_configs 里、不在 children 里」的子项必须**补进 children**，
     * 否则它们会因本规则失去唯一的渲染通道而彻底消失（见 [absorbedChildren]）。
     *
     * 注意：不在 configGroups 里的字段（如 ok-gf2「多账户模式」）显隐照常生效。
     */
    fun shouldIgnoreInlineRules(
        groupNames: Set<String>,
        key: String,
    ): Boolean = key in groupNames

    /**
     * 分组名的 `sub_configs` 里，哪些子项需要被吸收进该分组的 children。
     *
     * 只返回「不在 children 里」的那些 —— 已在 children 里的本来就由容器渲染，
     * 补进来反而会重复。
     */
    fun absorbedChildren(
        declared: List<String>,
        inlineChildren: List<String>,
    ): List<String> {
        val declaredSet = declared.toSet()
        return inlineChildren.distinct().filter { it !in declaredSet }
    }

    /**
     * 组头字段的行内子字段里，哪些应当跳过渲染（因为同一批 key 已由 configGroups 的
     * children 覆盖）。
     *
     * @param headerField 作为组头的字段 key
     * @param groupChildren 该组在 configGroups 里声明的 children（渲染器会在其后单独渲染）
     * @param inlineChildren 组头字段 `sub_configs` 解析出的行内子字段
     * @return 需要跳过的行内子字段；不重合时为空
     */
    fun inlineChildrenToSkip(
        headerField: String,
        groupChildren: List<String>,
        inlineChildren: List<String>,
    ): Set<String> {
        val declared = groupChildren.toSet() - headerField
        return inlineChildren.toSet() intersect declared
    }

    /**
     * 判断某个行内子字段是否应当渲染。
     *
     * 与 [inlineChildrenToSkip] 同一规则的单点判断，便于渲染器逐项调用。
     */
    fun shouldRenderInlineChild(
        headerField: String,
        groupChildren: List<String>,
        child: String,
    ): Boolean = child !in (groupChildren.toSet() - headerField)
}
