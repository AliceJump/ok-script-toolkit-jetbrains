package com.alicejump.okscripttoolkit.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.ButtonGroup
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 主题适配的单选弹窗（替代已弃用的 Messages.showChooseDialog(Project, ...)）：
 * 无图标纯按钮组，确认返回选中索引，取消返回 null（调用方按 <0 处理）。
 */
object ChooseDialog {

    fun show(
        project: Project?,
        messageText: String,
        title: String,
        options: List<String>,
        initialIndex: Int = 0,
    ): Int? {
        val dialog = ChooseDialogImpl(project, messageText, title, options, initialIndex)
        return if (dialog.showAndGet()) dialog.selectedIndex else null
    }
}

private class ChooseDialogImpl(
    project: Project?,
    private val messageText: String,
    title: String,
    options: List<String>,
    private val initialIndex: Int,
) : DialogWrapper(project, true) {

    private val buttons = options.mapIndexed { index, text ->
        JBRadioButton(text, index == initialIndex.coerceIn(options.indices))
    }

    var selectedIndex: Int = -1
        private set

    init {
        this.title = title
        val group = ButtonGroup()
        buttons.forEach { group.add(it) }
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        panel.add(
            JBLabel(messageText),
            GridBagConstraints().apply {
                gridx = 0; gridy = 0
                anchor = GridBagConstraints.WEST
                fill = GridBagConstraints.HORIZONTAL
                weightx = 1.0
                insets = Insets(4, 8, 8, 8)
            },
        )
        buttons.forEachIndexed { index, button ->
            panel.add(button, GridBagConstraints().apply {
                gridx = 0; gridy = index + 1
                anchor = GridBagConstraints.WEST
                insets = Insets(2, 8, 2, 8)
            })
        }
        return panel
    }

    override fun doOKAction() {
        selectedIndex = buttons.indexOfFirst { it.isSelected }.takeIf { it >= 0 } ?: initialIndex
        super.doOKAction()
    }
}
