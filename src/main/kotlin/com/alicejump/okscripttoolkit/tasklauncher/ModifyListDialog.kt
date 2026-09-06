package com.alicejump.okscripttoolkit.tasklauncher

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import net.miginfocom.swing.MigLayout
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/** 与框架 ModifyListDialog.SHOW_SEARCH_OPTIONS_THRESHOLD 一致 */
private const val SEARCH_THRESHOLD = 20

/**
 * 任务启动器列表字段的修改弹窗，复刻框架 ModifyListDialog 语义（对齐 VSCode 版 fields.js）：
 * - 有 options_available：双栏模式，左侧可用选项按钮流（>20 个出现搜索框），
 *   点击添加；!allow_duplication 时已选项按钮禁用；右侧已选列表 + 上移/下移/移除。
 * - 无 options_available：自由编辑模式，已选列表 + 上移/下移/移除 + 文本添加行。
 * 确认才写回 working 快照；取消不改动。
 */
class ModifyListDialog(
    project: Project?,
    title: String,
    items: List<Any?>,
    private val available: List<*>?,
    labels: List<*>,
    private val allowDup: Boolean,
    private val labelFor: (Any?) -> String,
) : DialogWrapper(project, true) {

    companion object {
        /**
         * 打开弹窗并返回确认后的列表；取消返回 null。
         * 对齐 VSCode 语义：有 available 时丢弃不在可用集中的既有项。
         */
        fun edit(
            project: Project?,
            title: String,
            items: List<Any?>,
            available: List<*>?,
            labels: List<*>,
            allowDup: Boolean,
            labelFor: (Any?) -> String,
        ): List<Any?>? {
            val dialog = ModifyListDialog(project, title, items, available, labels, allowDup, labelFor)
            dialog.title = title.ifEmpty { OkScriptToolkitBundle.message("list.modify") }
            dialog.init()
            return if (dialog.showAndGet()) dialog.working.toList() else null
        }
    }

    private val working: MutableList<Any?> = available
        ?.let { avail -> items.filter { item -> avail.any { it?.toString().orEmpty() == item?.toString().orEmpty() } }.toMutableList() }
        ?: items.toMutableList()

    private val listModel = DefaultListModel<String>()
    private val selectedList = JBList(listModel)
    private var selectedRow = -1
    private val upButton = JButton(OkScriptToolkitBundle.message("list.moveUp"))
    private val downButton = JButton(OkScriptToolkitBundle.message("list.moveDown"))
    private val removeButton = JButton(OkScriptToolkitBundle.message("list.removeItem"))

    /** 选项按钮：value → 按钮（双栏模式） */
    private val optionButtons = LinkedHashMap<Any?, JButton>()
    private val optionsFlow: JPanel? = available?.let { buildOptionsFlow(it, labels) }
    private val searchField: JBTextField? = if ((available?.size ?: 0) > SEARCH_THRESHOLD) JBTextField() else null

    init {
        setOKButtonText(OkScriptToolkitBundle.message("list.confirm"))
        selectedList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        selectedList.addListSelectionListener {
            selectedRow = selectedList.selectedIndex
            syncActionStates()
        }
        upButton.addActionListener { move(-1) }
        downButton.addActionListener { move(1) }
        removeButton.addActionListener {
            if (selectedRow in working.indices) {
                working.removeAt(selectedRow)
                selectedRow = -1
                renderList()
            }
        }
        searchField?.document?.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = filterOptions()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = filterOptions()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = filterOptions()
        })
    }

    override fun createCenterPanel(): JComponent {
        val selectedPane = buildSelectedPane()
        return if (available != null && optionsFlow != null) {
            // 双栏模式：左侧可用选项，右侧已选列表（与框架 ModifyListDialog 布局一致）
            val optionsPane = JPanel(MigLayout("fillx, ins 0", "[grow]"))
            optionsPane.add(JBLabel(OkScriptToolkitBundle.message("list.availableOptions")), "wrap")
            val hint = JBLabel(OkScriptToolkitBundle.message("list.clickOptionToAdd"))
            hint.foreground = com.intellij.util.ui.UIUtil.getContextHelpForeground()
            optionsPane.add(hint, "wrap")
            searchField?.let {
                it.emptyText.text = OkScriptToolkitBundle.message("list.searchOptions")
                optionsPane.add(it, "growx, wrap")
            }
            optionsPane.add(JBScrollPane(optionsFlow), "grow, push, height 240::")

            val body = JPanel(MigLayout("fillx, ins 0", "[grow][grow]"))
            body.add(optionsPane, "grow, push")
            body.add(selectedPane, "grow, push, w 40%!")
            body.preferredSize = Dimension(640, 320)
            body
        } else {
            // 自由编辑模式
            val pane = JPanel(MigLayout("fillx, ins 0", "[grow]"))
            pane.add(selectedPane, "grow, push, wrap")
            val addField = JBTextField()
            addField.emptyText.text = OkScriptToolkitBundle.message("list.addValue")
            val addButton = JButton(OkScriptToolkitBundle.message("list.add"))
            addButton.isEnabled = false
            addField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
                override fun insertUpdate(e: javax.swing.event.DocumentEvent?) {
                    addButton.isEnabled = addField.text.isNotBlank()
                }
                override fun removeUpdate(e: javax.swing.event.DocumentEvent?) {
                    addButton.isEnabled = addField.text.isNotBlank()
                }
                override fun changedUpdate(e: javax.swing.event.DocumentEvent?) {
                    addButton.isEnabled = addField.text.isNotBlank()
                }
            })
            val submit = {
                val text = addField.text.trim()
                if (text.isNotEmpty()) {
                    addValue(text)
                    addField.text = ""
                    addField.requestFocusInWindow()
                }
            }
            addButton.addActionListener { submit() }
            addField.addActionListener { submit() }
            val addRow = JPanel(MigLayout("fillx, ins 0", "[grow][pref!]"))
            addRow.add(addField, "growx")
            addRow.add(addButton)
            pane.add(addRow, "growx")
            pane.preferredSize = Dimension(480, 320)
            pane
        }
    }

    private fun buildOptionsFlow(available: List<*>, labels: List<*>): JPanel {
        val flow = JPanel(MigLayout("wrap 3, ins 0, gapy 2", "[grow][grow][grow]"))
        available.forEachIndexed { index, value ->
            val button = JButton(labels.getOrNull(index)?.toString() ?: value?.toString().orEmpty())
            button.addActionListener { addValue(value) }
            optionButtons[value] = button
            flow.add(button, "growx")
        }
        return flow
    }

    private fun buildSelectedPane(): JComponent {
        val pane = JPanel(MigLayout("fillx, ins 0", "[grow][]"))
        pane.add(JBLabel(OkScriptToolkitBundle.message("list.selectedOptions")), "span, wrap")
        pane.add(JBScrollPane(selectedList), "grow, push, height 200::")
        val actions = JPanel(MigLayout("wrap, ins 0 4, gapy 2"))
        actions.add(upButton)
        actions.add(downButton)
        actions.add(removeButton)
        pane.add(actions, "growy")
        return pane
    }

    private fun filterOptions() {
        val keyword = searchField?.text?.trim()?.lowercase() ?: return
        optionButtons.forEach { (value, button) ->
            val haystack = value?.toString().orEmpty().lowercase()
            button.isVisible = keyword.isEmpty() || haystack.contains(keyword)
        }
        optionsFlow?.revalidate()
        optionsFlow?.repaint()
    }

    private fun renderList() {
        listModel.clear()
        working.forEach { listModel.addElement(labelFor(it)) }
        if (selectedRow >= listModel.size()) selectedRow = listModel.size() - 1
        if (selectedRow >= 0) selectedList.selectedIndex = selectedRow
        syncActionStates()
    }

    private fun syncActionStates() {
        upButton.isEnabled = selectedRow > 0
        downButton.isEnabled = selectedRow in 0 until working.size - 1
        removeButton.isEnabled = selectedRow in working.indices
        if (optionButtons.isNotEmpty()) {
            optionButtons.forEach { (value, button) ->
                val taken = working.any { it?.toString().orEmpty() == value?.toString().orEmpty() }
                button.isEnabled = allowDup || !taken
            }
        }
    }

    private fun move(delta: Int) {
        val target = selectedRow + delta
        if (selectedRow !in working.indices || target !in working.indices) return
        val moved = working[selectedRow]
        working[selectedRow] = working[target]
        working[target] = moved
        selectedRow = target
        renderList()
    }

    private fun addValue(value: Any?) {
        if (!allowDup && working.any { it?.toString().orEmpty() == value?.toString().orEmpty() }) return
        working.add(value)
        selectedRow = working.size - 1
        renderList()
    }
}

/**
 * 任务启动器列表字段的折叠编辑器（对齐 VSCode 版 buildList）：
 * 显示当前项摘要 + 「修改」按钮，弹窗确认后经 [onChanged] 触发自动保存。
 * 值保留原始元素（可含非字符串选项），比较/展示统一按字符串。
 */
class ListEditorComponent(
    project: Project?,
    dialogTitle: String,
    typeMeta: Map<String, Any>?,
    initialValue: List<Any?>,
    private val onChanged: (List<Any?>) -> Unit,
) : JPanel(BorderLayout(8, 0)) {

    companion object {
        /** typeMeta → (options_available, options_available_labels, allow_duplication) */
        fun availableOptions(typeMeta: Map<String, Any>?): Triple<List<*>?, List<*>, Boolean> {
            val available = typeMeta?.get("options_available") as? List<*>
            val labels = typeMeta?.get("options_available_labels") as? List<*> ?: emptyList<Any>()
            val allowDup = typeMeta?.get("allow_duplication") == true
            return Triple(available, labels, allowDup)
        }

        fun labelFor(value: Any?, available: List<*>?, labels: List<*>): String {
            if (available == null) return value?.toString().orEmpty()
            val index = available.indexOfFirst { it?.toString().orEmpty() == value?.toString().orEmpty() }
            if (index < 0) return value?.toString().orEmpty()
            return labels.getOrNull(index)?.toString() ?: value?.toString().orEmpty()
        }
    }

    var value: List<Any?> = initialValue
        private set

    /** 供重复行值同步：更新取值并刷新摘要（不触发回调，避免回环） */
    fun replaceValue(next: List<Any?>) {
        value = next
        refreshSummary()
    }

    private val typeMeta = typeMeta
    private val summaryLabel = JBLabel()

    init {
        val modifyButton = JButton(OkScriptToolkitBundle.message("list.modify"))
        modifyButton.addActionListener {
            val (available, labels, allowDup) = availableOptions(typeMeta)
            val next = ModifyListDialog.edit(
                project = project,
                title = dialogTitle,
                items = value,
                available = available,
                labels = labels,
                allowDup = allowDup,
                labelFor = { labelFor(it, available, labels) },
            )
            if (next != null) {
                value = next
                refreshSummary()
                onChanged(next)
            }
        }
        add(summaryLabel, BorderLayout.CENTER)
        add(modifyButton, BorderLayout.EAST)
        refreshSummary()
    }

    /** 对齐 VSCode 摘要规则：>30 字符或 >3 项按行展示，否则逗号连接，空为 — */
    private fun refreshSummary() {
        val (available, labels, _) = availableOptions(typeMeta)
        val texts = value.map { labelFor(it, available, labels) }
        val joined = texts.joinToString("")
        summaryLabel.text = if (joined.length > 30 || value.size > 3) {
            "<html>${texts.joinToString("<br>") { escapeHtml(it) }}</html>"
        } else {
            texts.joinToString(", ").ifEmpty { "—" }
        }
    }

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
