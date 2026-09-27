package com.alicejump.okscripttoolkit.tasklauncher

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 任务状态色调判定的回归保护。
 *
 * 这条规则是「看起来像 UI、其实是数据约定」的东西，改起来很容易悄悄破坏：
 * 状态色调的判定优先级（正在跑 > 触发任务的入列态 > 一次性任务排队 > schema 健康度）。
 *
 * 历史：本文件原有一套「勾选列模型值 / 操作列渲染器」的测试，服务于旧版三列平铺
 * JTable（触发任务画复选框、一次性任务画运行按钮）。任务列表改版为卡片列表
 * （[TaskCardListPanel]）后，那套规则随 TaskRowState 里的表格时代代码一起移除。
 */
class TaskRowStateTest {

    // ── 状态色调 ─────────────────────────────────────────────────────

    @Test
    fun `running wins over every other state`() {
        assertEquals(
            TaskRowState.TONE_GOOD,
            TaskRowState.statusTone(
                kind = TaskRowState.ONETIME,
                running = true,
                enabled = false,
                queued = false,
                schemaBroken = true,
                schemaError = false,
            ),
        )
    }

    @Test
    fun `trigger tone follows the enqueue state and ignores schema health`() {
        assertEquals(
            TaskRowState.TONE_WARN,
            TaskRowState.statusTone(TaskRowState.TRIGGER, false, true, false, false, false),
        )
        assertEquals(
            TaskRowState.TONE_NEUTRAL,
            TaskRowState.statusTone(TaskRowState.TRIGGER, false, false, false, false, false),
        )
        // 触发任务的状态只讲入列/轮询，schema 问题不参与着色
        assertEquals(
            TaskRowState.TONE_NEUTRAL,
            TaskRowState.statusTone(TaskRowState.TRIGGER, false, false, false, true, true),
        )
    }

    @Test
    fun `onetime tone covers queue and schema health`() {
        assertEquals(
            TaskRowState.TONE_WARN,
            TaskRowState.statusTone(TaskRowState.ONETIME, false, false, true, false, false),
        )
        assertEquals(
            TaskRowState.TONE_BAD,
            TaskRowState.statusTone(TaskRowState.ONETIME, false, false, false, true, false),
        )
        assertEquals(
            TaskRowState.TONE_BAD,
            TaskRowState.statusTone(TaskRowState.ONETIME, false, false, false, false, true),
        )
        assertEquals(
            TaskRowState.TONE_NEUTRAL,
            TaskRowState.statusTone(TaskRowState.ONETIME, false, false, false, false, false),
        )
    }
}
