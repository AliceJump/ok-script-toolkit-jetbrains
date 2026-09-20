package com.alicejump.okscripttoolkit.tasklauncher

import com.intellij.icons.AllIcons
import java.awt.Component
import java.awt.Insets
import javax.swing.JButton
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
     * null 是「连复选框都不要画」的信号，配合 [TaskActionRenderer] 使用 ——
     * 一次性任务的那一格改画运行按钮，所以 null 同时兼作「按行类型另行处理」的标记。
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
 * 左列（操作列）渲染器 —— 这一列按**行类型**给出各自真正可点的控件：
 *
 * - **触发任务** → 真正的 `JCheckBox`。可交互由表格自身的 Boolean 编辑器保证
 *   （模型里存 Boolean，见 [TaskRowState.checkboxValue]）。
 * - **一次性任务** → **运行按钮**。点击由
 *   `TaskLauncherToolWindowFactory` 装在表格上的鼠标监听处理 ——
 *   渲染器只负责画，点击语义在宿主那边。
 *
 * 判定分两段，缺一不可：
 * 1. **单元格值**决定"是不是复选框"：是 `Boolean` 就交给表格默认的 Boolean 渲染器
 *    （一个真正的 JCheckBox），否则不画复选框。
 * 2. **行类型**决定"要不要画运行按钮"：一次性任务在模型里存 `null`（见
 *    [TaskRowState.checkboxValue] 关于"不能用 false"的说明），与"空单元格"无法区分，
 *    所以必须额外知道这一行的类型，故构造时注入 [kindOf]。
 *
 * 为什么不依赖 `isCellEditable`：那条路只会把复选框画成灰色的禁用态。
 *
 * 历史：这里原本叫 `TriggerCheckboxRenderer`，一次性任务那格是**空白**；
 * 类型只靠任务名前面的图标表达，而那个图标看着能点、实际不能点，
 * 于是把类型图标去掉、改成这一列直接给可点的控件。
 */
internal class TaskActionRenderer(
    private val kindOf: (Int) -> String,
) : TableCellRenderer {

    private val blank = DefaultTableCellRenderer()

    /**
     * 一次性任务的运行按钮。用 `JButton` 而不是画一个图标 ——
     * 要的就是"看起来能点"和"真的能点"一致。
     */
    private val runButton = JButton(AllIcons.RunConfigurations.TestState.Run).apply {
        isFocusable = false
        isOpaque = false
        margin = Insets(0, 0, 0, 0)
        // 单元格很窄，去掉默认内边距免得图标被裁
        border = javax.swing.BorderFactory.createEmptyBorder(1, 1, 1, 1)
    }

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
        if (kindOf(row) == TaskRowState.ONETIME) {
            return runButton
        }
        return blank.getTableCellRendererComponent(table, "", isSelected, false, row, column)
    }
}
