package com.alicejump.okscripttoolkit.tasklauncher

import java.awt.Component
import javax.swing.JTable
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer

/**
 * 任务列表行的展示规则。
 *
 * 单独抽成不依赖 IDE / 服务的纯对象，方便在普通 JUnit 里断言 —— 这里的两条规则
 * 都是「看起来像 UI、其实是数据约定」的东西，正是最容易悄悄改坏的部分。
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
     * 勾选列的模型值：**触发任务存 Boolean，一次性任务存 null**。
     *
     * null 是「连复选框都不要画」的信号，配合 [TriggerCheckboxRenderer] 使用。
     * 不能对一次性任务存 `false` —— JTable 默认的 Boolean 渲染器会给不可编辑行画一个
     * 灰色禁用复选框，看起来就像一次性任务也有个启用开关。
     */
    fun checkboxValue(kind: String, enabled: Boolean): Any? = if (kind == TRIGGER) enabled else null

    /** 该类型的任务在列表里是否带复选框 */
    fun hasCheckbox(kind: String): Boolean = kind == TRIGGER

    /**
     * 状态色调。判定优先级与状态列文案一一对应：
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

/**
 * 勾选列渲染器：只有触发任务画复选框。
 *
 * 判定完全基于单元格的值（[TaskRowState.checkboxValue]）：是 `Boolean` 就交给表格默认的
 * Boolean 渲染器（`JTable.BooleanRenderer`，一个真正的 JCheckBox），是 `null` 就返回空白标签。
 * 不依赖 `isCellEditable` —— 那条路只会把复选框画成灰色的禁用态。
 */
internal class TriggerCheckboxRenderer : TableCellRenderer {

    private val blank = DefaultTableCellRenderer()

    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int,
    ): Component {
        if (value is Boolean) {
            val delegate = table.getDefaultRenderer(java.lang.Boolean::class.javaObjectType)
            if (delegate != null) {
                return delegate.getTableCellRendererComponent(table, value, isSelected, false, row, column)
            }
        }
        return blank.getTableCellRendererComponent(table, "", isSelected, false, row, column)
    }
}
