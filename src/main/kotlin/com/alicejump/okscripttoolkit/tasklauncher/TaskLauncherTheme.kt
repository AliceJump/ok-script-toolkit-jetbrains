package com.alicejump.okscripttoolkit.tasklauncher

import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * 任务启动器语义色 / 控件语义单点收敛（对应 VS Code 侧 media/shared/tokens.css）。
 *
 * 主仓库 #10 立下的规矩：**面板不得散落硬编码色**，语义色只有 ok/run/warn/pause/err
 * 五个 + 触发任务紫。Swing 侧等价物就是本对象 —— `tasklauncher` 包内禁止再写
 * `Color(0x…)` 字面量（静态审查：grep 只允许命中本文件）。
 *
 * 亮色值沿用迁移前的硬编码色（视觉零变化），暗色值取同一色相提亮一档（JBColor 双值）。
 */
internal object TaskLauncherTheme {

    // ── 语义色 ────────────────────────────────────────────────────────

    /** --ok：健康 / 正常结束 */
    val OK = JBColor(0x369B47, 0x5FAD65)

    /** --run：连接中 / 轮询中（未暂停） */
    val RUN = JBColor(0x2E7DD1, 0x6CA6E8)

    /** --warn：警告（schema 损坏等） */
    val WARN = JBColor(0xB87700, 0xE8A33D)

    /** --pause：执行器暂停（与 warn 区分：更黄褐、不刺眼） */
    val PAUSE = JBColor(0x8A6D00, 0xC9A227)

    /** --err：控制命令错误 / 异常退出 */
    val ERR = JBColor(0xDB3B4B, 0xF26D6D)

    /** 触发任务标识紫（沿用迁移前配色） */
    val TRIGGER = JBColor(0x7A5AF8, 0x9B8AFB)

    /** 一次性任务标识蓝（沿用迁移前 JBColor.BLUE 语义，但走双值适配暗色） */
    val ONETIME = JBColor(0x1F66C4, 0x6690D6)

    /** JSON 字段校验描边：解析合法 */
    val BORDER_OK = JBColor(0x287828, 0x4CAF50)

    /** JSON 字段校验描边：解析非法（沿用迁移前字面量，视觉零变化） */
    val BORDER_ERR = JBColor(0xB42828, 0xEF5350)

    // ── 行级可点击区（#10 两层语义之二：无描边浅底，hover 增强） ────────

    /**
     * --bg-row：行级可点击区默认底色。「默认可识别但不与按钮同权重」——
     * 取平台 Table.stripeColor（比面板底色略浅/略深一档的斑马灰），与周围内容有色差；
     * hover 不承担「让用户发现可点击」的职责。
     */
    fun rowBackground(): java.awt.Color =
        JBColor.namedColor("Table.stripeColor", JBColor(0xF7F7F7, 0x2B2D30))

    /** --bg-row-hover：hover 增强色 */
    fun rowHoverBackground(): java.awt.Color = UIUtil.getListSelectionBackground(false)

    // ── tone → 色（tone 语义的唯一权威在 TaskRowState.TONE_*，这里不重复定义） ──

    fun colorForTone(tone: Int): JBColor = when (tone) {
        TaskRowState.TONE_GOOD -> OK
        TaskRowState.TONE_WARN -> WARN
        TaskRowState.TONE_BAD -> ERR
        // 中性 tone = 前景色。注意 JBColor.foreground() 在部分平台版本里声明返回 Color
        // 而不是 JBColor，这里用双值构造保住 JBColor 类型（styleChip 需要）。
        else -> JBColor(UIUtil.getLabelForeground(), UIUtil.getLabelForeground())
    }

    // ── chip（#10 两层语义之一的小标签变体：描边 + 同色文字，无底色） ──

    /** 圆角描边 chip —— 对齐 factory 里 styleChip 范式，供非 factory 场景复用 */
    fun styleChip(label: JLabel, color: JBColor, text: String) {
        label.text = text
        label.foreground = color
        label.border = javax.swing.BorderFactory.createCompoundBorder(
            javax.swing.BorderFactory.createLineBorder(color, 1, true),
            javax.swing.BorderFactory.createEmptyBorder(0, 6, 0, 6),
        )
    }

    // ── 健康点（#9 rc-health）─────────────────────────────────────────

    /**
     * 8px 健康圆点：状态色的最小可视化单元，与状态文字同源同色。
     * 自绘而非图标 —— 平台图标没有「五态语义色圆点」这个资产。
     */
    class HealthDot : JComponent() {
        var color: java.awt.Color = UIUtil.getLabelForeground()
            set(value) {
                field = value
                repaint()
            }

        init {
            preferredSize = java.awt.Dimension(9, 9)
            minimumSize = preferredSize
            maximumSize = preferredSize
            isOpaque = false
        }

        override fun paintComponent(g: java.awt.Graphics) {
            val g2 = g.create() as java.awt.Graphics2D
            try {
                // 抗锯齿小圆；半径 3.5 让 8x9 的框里圆居中不贴边
                g2.setRenderingHint(
                    java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON,
                )
                g2.color = color
                g2.fillOval(1, 1, 7, 7)
            } finally {
                g2.dispose()
            }
        }
    }
}
