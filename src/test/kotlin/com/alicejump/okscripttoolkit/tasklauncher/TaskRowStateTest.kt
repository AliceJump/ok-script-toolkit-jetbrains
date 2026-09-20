package com.alicejump.okscripttoolkit.tasklauncher

import java.awt.Component
import javax.swing.JButton
import javax.swing.JCheckBox
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
 * 三条都是「看起来像 UI、其实是数据约定」的规则，改起来很容易悄悄破坏：
 * 1. 一次性任务**不能**有复选框（旧版靠 isCellEditable=false，被 JTable 默认的
 *    Boolean 渲染器画成了灰色禁用框）；它那一格是**运行按钮**；
 * 2. 操作列画复选框还是运行按钮，**由行类型决定**——一次性任务在模型里存 null，
 *    与"空单元格"无法区分，只看单元格值会把运行按钮画丢；
 * 3. 状态色调的判定优先级（运行中 > 触发入列态 > 一次性排队 > schema 健康度）。
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
    private fun renderCell(value: Any?, kind: String = TaskRowState.TRIGGER): Component {
        val model = SingleColumnModel(value)
        val table = JTable(model)
        table.columnModel.getColumn(0).cellRenderer = TaskActionRenderer { kind }
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

    // ── 操作列的渲染结果 ─────────────────────────────────────────────

    @Test
    fun `boolean cell renders as a real checkbox`() {
        val checked = assertIs<JCheckBox>(
            renderCell(TaskRowState.checkboxValue(TaskRowState.TRIGGER, true), TaskRowState.TRIGGER),
        )
        assertTrue(checked.isSelected)

        val unchecked = assertIs<JCheckBox>(
            renderCell(TaskRowState.checkboxValue(TaskRowState.TRIGGER, false), TaskRowState.TRIGGER),
        )
        assertFalse(unchecked.isSelected)
    }

    /**
     * 一次性任务那一格是**运行按钮**。
     *
     * 这条替代了原来的「渲染成空白标签」：旧设计把类型塞进任务名前的图标里，
     * 而那个图标看着能点、实际不能点；现在这一列直接给出可点的控件。
     */
    @Test
    fun `onetime cell renders as a run button, never a checkbox`() {
        val component = renderCell(TaskRowState.checkboxValue(TaskRowState.ONETIME, enabled = false), TaskRowState.ONETIME)
        assertFalse(
            component is JCheckBox,
            "一次性任务不能渲染成复选框（哪怕是禁用态）—— 它没有「启用」语义",
        )
        val button = assertIs<JButton>(component)
        assertTrue(button.icon != null, "运行按钮必须带图标，否则看不出这是干什么的")
        assertFalse(button.isFocusable, "表格单元格里的按钮不该抢焦点")
    }

    /**
     * 破坏性对照：同一份单元格值（null），**行类型**决定画出什么。
     *
     * 这正是当初必须给渲染器注入 `kindOf` 的原因 —— 一次性任务在模型里存 null，
     * 与"空单元格"无法区分；只按值判定就会把运行按钮画丢。
     */
    @Test
    fun `regression guard - the run button depends on the row kind, not the cell value`() {
        val asOnetime = renderCell(null, TaskRowState.ONETIME)
        val asTrigger = renderCell(null, TaskRowState.TRIGGER)

        assertIs<JButton>(asOnetime)
        assertFalse(
            asTrigger is JButton,
            "对照：同样是 null，触发行不该画出运行按钮 —— 说明 kindOf 确实在起作用",
        )
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
