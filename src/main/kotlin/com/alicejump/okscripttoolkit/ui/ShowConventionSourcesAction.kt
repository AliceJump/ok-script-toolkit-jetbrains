package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.alicejump.okscripttoolkit.core.ConventionLayer
import com.alicejump.okscripttoolkit.core.ConventionSourceRow
import com.alicejump.okscripttoolkit.settings.OkScriptToolkitSettings
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants

/**
 * 「项目约定 vs 我的设置」：列出参与取值链的设置项，显示每一项的**生效值来自哪一层**，
 * 并允许一键「恢复为项目约定」（清掉个人覆盖）。
 *
 * 与 VS Code 侧的 `okScriptToolkit.showConventionSources` 命令一一对应。
 *
 * 为什么要有它（`docs/project-config.md` §3）：取值链把**个人偏好**排最高
 * （项目文件是团队开箱默认，我改过就用我的），代价是一旦手动改过，
 * 项目声明的那一项就对我**永久失效** —— 界面上毫无提示。这个面板就是那个缓冲。
 *
 * ⚠️ 「恢复」的实现是 [OkScriptToolkitSettings.clearOverridden]（**撤销记账**），
 * 不是"把值改回内置默认"：值留着但个人偏好层不再命中。这样可逆 ——
 * 用户反悔时不需要重新输入一遍。
 */
class ShowConventionSourcesAction : AnAction() {
    init {
        // 文案走 bundle（i18n 铁律：不硬编码在业务代码里）；plugin.xml 只声明 id / class
        templatePresentation.text = OkScriptToolkitBundle.message("action.conventionSources.text")
        templatePresentation.description = OkScriptToolkitBundle.message("action.conventionSources.description")
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ConventionSourcesDialog(project).show()
    }

    /** 本 Action 不读写 Swing 组件，选 `BGT` 与仓库其它 Action 保持一致（见 `ShowTasksAction`）。 */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

private class ConventionSourcesDialog(project: Project) : DialogWrapper(project, true) {

    private val settings = OkScriptToolkitSettings.getInstance(project)
    private val rowsPanel = JBPanel<JBPanel<*>>()

    init {
        title = OkScriptToolkitBundle.message("conventionSources.title")
        // 只读面板：主按钮就是「关闭」，没有"保存/取消"语义。
        // 复用既有的 `annotation.ok`（与 `CharacterDialogs` 的信息类弹窗一致），不新增键。
        setOKButtonText(OkScriptToolkitBundle.message("annotation.ok"))
        init()
    }

    override fun createCenterPanel(): JComponent {
        rowsPanel.layout = BoxLayout(rowsPanel, BoxLayout.Y_AXIS)
        rowsPanel.isOpaque = false

        // 生效值 / 声明值可能很长（别名列表），给个宽度上限并允许纵向滚动
        val scroll = JScrollPane(rowsPanel).apply {
            border = null
            viewport.isOpaque = false
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            preferredSize = Dimension(JBUI.scale(680), JBUI.scale(220))
        }

        rebuildRows()

        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(
                JBLabel(OkScriptToolkitBundle.message("conventionSources.hint")).apply {
                    border = JBUI.Borders.empty(0, 0, JBUI.scale(8), 0)
                },
                BorderLayout.NORTH,
            )
            add(scroll, BorderLayout.CENTER)
        }
    }

    /**
     * 重建行。
     *
     * 「恢复」之后必须**重新读一遍**（[OkScriptToolkitSettings.conventionSources] 会重新走
     * 取值链），而不是就地改一下行的文字 —— 否则界面上会出现第二份"真相"，
     * 与 `docs/project-config.md` §3 里"来源层必须由取值链本身产出"是同一个理由。
     */
    private fun rebuildRows() {
        rowsPanel.removeAll()
        val rows = settings.conventionSources()
        if (rows.isEmpty()) {
            rowsPanel.add(JBLabel(OkScriptToolkitBundle.message("conventionSources.empty")))
        } else {
            rows.forEach { rowsPanel.add(rowPanel(it)) }
        }
        rowsPanel.revalidate()
        rowsPanel.repaint()
    }

    private fun rowPanel(row: ConventionSourceRow): JBPanel<JBPanel<*>> {
        val panel = JBPanel<JBPanel<*>>()
        panel.layout = BoxLayout(panel, BoxLayout.X_AXIS)
        panel.alignmentX = 0f
        panel.isOpaque = false
        panel.border = JBUI.Borders.empty(4, 4)

        val text = buildString {
            append(row.key)
            append(" = ")
            append(row.effective)
            append("    ·    ")
            append(OkScriptToolkitBundle.message("conventionSources.source", layerLabel(row.layer)))
            append("    ·    ")
            append(
                row.declared
                    ?.let { OkScriptToolkitBundle.message("conventionSources.projectFile", it) }
                    ?: OkScriptToolkitBundle.message("conventionSources.projectFileNotDeclared"),
            )
            append("    ·    ")
            append(OkScriptToolkitBundle.message("conventionSources.builtin", row.builtin))
        }
        panel.add(JBLabel(text))
        panel.add(Box.createHorizontalGlue())

        // 只有真有个人覆盖的行才给按钮 —— 与 VS Code 侧一致：避免用户以为能"恢复"到不存在的东西
        if (row.overridden) {
            panel.add(
                JButton(OkScriptToolkitBundle.message("conventionSources.revert")).apply {
                    addActionListener {
                        if (settings.clearOverridden(row.key)) {
                            rebuildRows()
                        }
                    }
                },
            )
        }
        return panel
    }

    private fun layerLabel(layer: ConventionLayer): String = when (layer) {
        ConventionLayer.PERSONAL -> OkScriptToolkitBundle.message("conventionSources.layer.personal")
        ConventionLayer.PROJECT -> OkScriptToolkitBundle.message("conventionSources.layer.project")
        ConventionLayer.BUILTIN -> OkScriptToolkitBundle.message("conventionSources.layer.builtin")
    }
}
