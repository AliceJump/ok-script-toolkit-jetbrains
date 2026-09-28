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

    /**
     * 状态语义色调（实际颜色见 TaskLauncherTheme.colorForTone，那里才需要 JBColor）。
     *
     * 任务卡不再写状态文字，**颜色就是唯一的状态载体**：
     * 绿 = 正在运行、蓝 = 已入列、灰 = 未运行、红 = schema 异常。
     */
    const val TONE_NEUTRAL = 0
    const val TONE_GOOD = 1
    /** 保留：警告色仍在调色板里（如 schema 相关的旁路提示），但当前不由 [statusTone] 产出 */
    const val TONE_WARN = 2
    const val TONE_BAD = 3

    /**
     * 「已入列」独立于 [TONE_WARN]：入列是「已经排上队了」而不是「有问题」，
     * 所以走蓝色（[TaskLauncherTheme.RUN]）而不是警告琥珀色。
     */
    const val TONE_ENQUEUED = 4

    /**
     * 状态色调。判定优先级与状态文案一一对应：
     * 正在跑 > 触发任务的入列态 > 一次性任务排队 > schema 健康度 > 未运行。
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
        kind == TRIGGER -> if (enabled) TONE_ENQUEUED else TONE_NEUTRAL
        queued -> TONE_ENQUEUED
        schemaBroken || schemaError -> TONE_BAD
        else -> TONE_NEUTRAL
    }

    /** Running and connecting executors can accept another one-time task. */
    fun canLaunchOnetime(
        projectMatches: Boolean,
        taskKey: String,
        currentTask: String,
        queuedTasks: Collection<String>,
    ): Boolean = projectMatches && currentTask != taskKey && taskKey !in queuedTasks
}
