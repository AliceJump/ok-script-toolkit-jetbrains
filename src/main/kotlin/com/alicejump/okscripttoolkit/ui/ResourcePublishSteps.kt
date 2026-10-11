package com.alicejump.okscripttoolkit.ui

/** 用户先完成全部配置；位置拒绝覆盖或发布失败后，不启动模板写入。 */
internal fun <T : Any, P : Any> publishSelectedResources(
    templateSelected: Boolean,
    positionsSelected: Boolean,
    configureTemplate: () -> T?,
    configurePositions: () -> P?,
    publishPositions: (P) -> Boolean,
    publishTemplate: (T) -> Unit,
    completePositionsOnly: () -> Unit,
    /** 两类目标都配置完成后、任何写盘之前的最后确认（发布预览）；返回 false 即取消。 */
    confirm: (T?, P?) -> Boolean = { _, _ -> true },
) {
    val template = if (templateSelected) configureTemplate() ?: return else null
    val positions = if (positionsSelected) configurePositions() ?: return else null
    if (!confirm(template, positions)) return
    if (positions != null && !publishPositions(positions)) return
    if (template != null) publishTemplate(template) else completePositionsOnly()
}
