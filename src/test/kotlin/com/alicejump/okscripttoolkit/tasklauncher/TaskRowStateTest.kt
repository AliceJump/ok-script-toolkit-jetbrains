package com.alicejump.okscripttoolkit.tasklauncher

import java.awt.Component
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JTable
import javax.swing.table.DefaultTableModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 任务列表行展示规则的回归保护。
 *
 * 两条都是「看起来像 UI、其实是数据约定」的规则，改起来很容易悄悄破坏：
 * 1. 一次性任务**不能**有复选框（旧版靠 isCellEditable=false，被 JTable 默认的
 *    Boolean 渲染器画成了灰色禁用框）；
 * 2. 状态色调的判定优先级（运行中 > 触发入列态 > 一次性排队 > schema 健康度）。
 */
class TaskRowStateTest {

    private class SingleColumnModel(value: Any?) : DefaultTableModel(arrayOf("enable", "task"), 0) {
        init {
            addRow(arrayOf<Any?>(value, "demo task"))
        }

        override fun getColumnClass(columnIndex: Int): Class<*> =
            if (columnIndex == 0) java.lang.Boolean::class.javaObjectType else String::class.java
    }

    /** 走真实的 模型 → 表格 → 渲染器 链路，取第 0 列的渲染结果 */
    private fun renderCell(value: Any?): Component {
        val model = SingleColumnModel(value)
        val table = JTable(model)
        table.columnModel.getColumn(0).cellRenderer = TriggerCheckboxRenderer()
        return table.columnModel.getColumn(0).cellRenderer
            .getTableCellRendererComponent(table, model.getValueAt(0, 0), false, false, 0, 0)
    }

    // ── 勾选列的模型值 ────────────────────────────────────────────────

    @Test
    fun `trigger tasks carry a boolean checkbox value`() {
        assertEquals(true, TaskRowState.checkboxValue(TaskRowState.TRIGGER, enabled = true))
        assertEquals(false, TaskRowState.checkboxValue(TaskRowState.TRIGGER, enabled = false))
        assertTrue(TaskRowState.hasCheckbox(TaskRowState.TRIGGER))
    }

    @Test
    fun `onetime tasks carry null so no checkbox is drawn`() {
        // 关键：必须是 null 而不是 false —— false 会被画成灰色禁用复选框
        assertNull(TaskRowState.checkboxValue(TaskRowState.ONETIME, enabled = true))
        assertNull(TaskRowState.checkboxValue(TaskRowState.ONETIME, enabled = false))
        assertFalse(TaskRowState.hasCheckbox(TaskRowState.ONETIME))
    }

    // ── 勾选列的渲染结果 ─────────────────────────────────────────────

    @Test
    fun `boolean cell renders as a real checkbox`() {
        val checked = assertIs<JCheckBox>(renderCell(TaskRowState.checkboxValue(TaskRowState.TRIGGER, true)))
        assertTrue(checked.isSelected)

        val unchecked = assertIs<JCheckBox>(renderCell(TaskRowState.checkboxValue(TaskRowState.TRIGGER, false)))
        assertFalse(unchecked.isSelected)
    }

    @Test
    fun `null cell renders as a blank label, never a disabled checkbox`() {
        val component = renderCell(TaskRowState.checkboxValue(TaskRowState.ONETIME, enabled = false))
        assertFalse(
            component is JCheckBox,
            "一次性任务不能渲染成复选框（哪怕是禁用态）",
        )
        assertEquals("", (component as JLabel).text)
    }

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
