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
