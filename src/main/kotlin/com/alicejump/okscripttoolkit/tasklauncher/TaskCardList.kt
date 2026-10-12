package com.alicejump.okscripttoolkit.tasklauncher

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.alicejump.okscripttoolkit.tasklauncher.TaskLauncherService.TaskInfo
import com.alicejump.okscripttoolkit.tasklauncher.TaskLauncherService.TaskSchema
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.WrapLayout
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Scrollable

/**
 * 单张任务卡的执行状态。
 *
 * [text] / [tone] 与状态列同源（工厂的 statusCellFor）。卡片上**不写状态文字** ——
 * 颜色就是唯一的状态载体（见 [TaskRowState.statusTone] 的色语义），[text] 只进 tooltip，
 * 让「颜色什么意思」可查。`launchEnabled` 对齐 webview 的 launch.disabled
 * （排队中 / 执行中 / 执行器跨项目时禁用）。
 */
internal data class TaskCardStatus(
    val text: String,
    val tone: Int,
    val launchEnabled: Boolean,
)

/**
 * 任务卡列表与宿主（TaskLauncherPanel）的契约。
 * 宿主持有 schemas / enabledTriggers / 执行器状态与全部动作实现；列表只管编排与渲染。
 */
internal interface TaskCardHost {
    fun taskList(): List<TaskInfo>
    fun schemaOf(task: TaskInfo): TaskSchema?
    fun kindOf(task: TaskInfo): String
    fun isTriggerEnabled(taskKey: String): Boolean
    fun cardStatusOf(task: TaskInfo): TaskCardStatus
    fun isCollapsed(foldKey: String): Boolean
    fun onCollapseChanged(foldKey: String, collapsed: Boolean)
    /** 卡片点击 = 选中该任务（右栏显示详情与参数 —— 卡片即入口，没有独立的「参数」按钮） */
    fun onTaskActivated(task: TaskInfo)
    fun onToggleTrigger(task: TaskInfo, enabled: Boolean)
    fun onRunTask(task: TaskInfo)
    /** 悬停 800ms 后弹只读摘要（宿主持有弹层单例与抑制窗口） */
    fun onTaskHover(task: TaskInfo, anchor: JComponent)
    fun onTaskHoverEnd()
}

/**
 * 单行省略标签：宽度不够时按像素截断并补「…」。
 *
 * `JLabel` 默认是**硬裁剪** —— 窄工具窗里表现为「字被切掉一半」，看不出后面还有内容，
 * 也不知道自己少了什么。这里改成绘制时截断（完整文本留在 tooltip 里）。
 *
 * 只在 `BorderLayout.CENTER` 里用：WEST/EAST 拿 preferred 宽度、CENTER 拿剩余宽度，
 * 于是「两端的信息永不裁切、只有中间的文本会省略」。
 */
internal class EllipsizingLabel(text: String = "") : JBLabel(text) {

    override fun paintComponent(g: Graphics) {
        if (isOpaque) {
            g.color = background
            g.fillRect(0, 0, width, height)
        }
        val metrics = g.getFontMetrics(font)
        val available = width - insets.left - insets.right
        val shown = if (available <= 0) "" else ellipsize(text.orEmpty(), metrics, available)
        if (shown.isEmpty()) return
        val previous = g.color
        g.color = if (isEnabled) foreground else UIUtil.getLabelDisabledForeground()
        val baseline = insets.top + (height - insets.top - insets.bottom - metrics.height) / 2 + metrics.ascent
        g.drawString(shown, insets.left, baseline)
        g.color = previous
    }

    /** 逐字符收缩到「截断 + …」放得下为止（二分容易差一个字符，字符数本来就不多） */
    private fun ellipsize(full: String, metrics: FontMetrics, available: Int): String {
        if (metrics.stringWidth(full) <= available) return full
        val dots = "\u2026"
        if (metrics.stringWidth(dots) > available) return ""
        var end = full.length
        while (end > 0 && metrics.stringWidth(full.substring(0, end) + dots) > available) end--
        return full.substring(0, end) + dots
    }
}
