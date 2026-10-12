package com.alicejump.okscripttoolkit.ui

import com.intellij.ui.JBColor
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Color
import javax.swing.Icon
import javax.swing.JButton

/**
 * 插件 UI 颜色的唯一来源（任务启动器的语义色在 `tasklauncher/TaskLauncherTheme`）。
 * `ui/` 与 `tasklauncher/` 包内禁止再写 `JBColor(0x…)` / `Color(r,g,b)` 字面量，
 * 由 `ThemeLiteralAuditTest` 静态扫描保证。全部为亮/暗双值。
 */
internal object OkTheme {
    // 标注画布：与 VS Code 标注编辑器同一套语义
    val SHAPE_NORMAL = JBColor(0xE53935, 0xFF5252)
    val SHAPE_SELECTED = JBColor(0x0078D4, 0x4A9EFF)
    val SHAPE_HOVER = JBColor(0xE08700, 0xFFA02E)
    val SHAPE_COORD = JBColor(0x7A5AF8, 0xA48BFF)
    val SHAPE_DRAFT = JBColor(0x2E9E44, 0x5FD07A)

    // 冲突预览：本地 = 选中蓝，外部 = 悬停橙
    val CONFLICT_LOCAL get() = SHAPE_SELECTED
    val CONFLICT_EXTERNAL get() = SHAPE_HOVER
    val CONFLICT_BORDER: Color get() = JBColor.border()

    // 临时截图的坐标框
    val SHOT_COORD = JBColor(0xE8A33D, 0xFFB454)

    /** 标签底色上的文字：底色都是饱和色，亮暗主题都用白字。 */
    val LABEL_ON_SHAPE: Color = JBColor(0xFFFFFF, 0xFFFFFF)

    // 资源发布状态点（与 VS Code resources 视图一致）
    val STATUS_PUBLISHED = JBColor(0x369B47, 0x5FAD65)
    val STATUS_DRAFT_ONLY = JBColor(0xB87700, 0xE8A33D)
    val STATUS_ORPHAN = JBColor(0x8C8C8C, 0x868A91)

    /** 随 IDE 缩放的线宽：`-Dide.ui.scale` 等非 JRE 缩放下也保持视觉粗细。 */
    fun stroke(width: Float): BasicStroke = BasicStroke(JBUIScale.scale(width))

    fun dashed(width: Float, dash: Float, gap: Float): BasicStroke =
        BasicStroke(JBUIScale.scale(width), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
            floatArrayOf(JBUIScale.scale(dash), JBUIScale.scale(gap)), 0f)
}

/** 纯图标按钮：tooltip 与读屏名都用本地化文本，不显示 Unicode 字符。 */
internal fun iconButton(icon: Icon, label: String): JButton = JButton(icon).apply {
    toolTipText = label
    accessibleContext.accessibleName = label
    margin = JBUI.insets(2)
    isFocusable = true
}
