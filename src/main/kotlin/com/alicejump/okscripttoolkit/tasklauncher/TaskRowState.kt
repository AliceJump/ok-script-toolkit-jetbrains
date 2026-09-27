package com.alicejump.okscripttoolkit.tasklauncher

/**
 * 任务状态的展示规则（色调判定）。
 *
 * 单独抽成不依赖 IDE / 服务的纯对象，方便在普通 JUnit 里断言 —— 这条规则
 * 是「看起来像 UI、其实是数据约定」的东西，正是最容易悄悄改坏的部分。
 *
 * 历史：这里原有一套「勾选列模型值 + 操作列渲染器」的规则（checkboxValue /
 * hasCheckbox / TaskActionRenderer），服务于旧版三列平铺 JTable —— 操作列
 * 触发任务画复选框、一次性任务画运行按钮。任务列表改版为卡片列表
 * （[TaskCardListPanel]，对齐 VS Code 任务页编排）后，勾选框和启动按钮
 * 直接长在卡片上，这套表格时代的规则随之移除。
 */
internal object TaskRowState {

    const val TRIGGER = "trigger"
    const val ONETIME = "onetime"

    /** 状态语义色调（实际颜色见 TaskLauncherPanel 的 COLOR_*，那里才需要 JBColor） */
    const val TONE_NEUTRAL = 0
    const val TONE_GOOD = 1
    const val TONE_WARN = 2
    const val TONE_BAD = 3

    /**
     * 状态色调。判定优先级与状态文案一一对应：
     * 正在跑 > 触发任务的入列态 > 一次性任务排队 > schema 健康度 > 就绪。
     */
    fun statusTone(
        kind: String,
        running: Boolean,
        enabled: Boolean,
        queued: Boolean,
        schemaBroken: Boolean,
        schemaError: Boolean,
    ): Int = when {
        running -> TONE_GOOD
        kind == TRIGGER -> if (enabled) TONE_WARN else TONE_NEUTRAL
        queued -> TONE_WARN
        schemaBroken || schemaError -> TONE_BAD
        else -> TONE_NEUTRAL
    }
}
