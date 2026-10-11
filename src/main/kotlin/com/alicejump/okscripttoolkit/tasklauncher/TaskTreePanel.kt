package com.alicejump.okscripttoolkit.tasklauncher

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.alicejump.okscripttoolkit.tasklauncher.TaskLauncherService.TaskInfo
import com.intellij.icons.AllIcons
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckboxTreeListener
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.tree.TreeUtil
import java.awt.BorderLayout
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.KeyStroke
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * 任务列表的树形视图（替代自绘卡片列表，对齐 VS Code PR #41 的「运行」TreeView）：
 * 触发任务 → 勾选框；一次性任务 → 业务分组 → 任务，双击 / Enter 入队。
 * 与 [TaskCardListPanel] 相同的宿主契约 [TaskCardHost]，宿主的保存 / 执行逻辑不变。
 */
internal class TaskTreePanel(private val host: TaskCardHost) : JPanel(BorderLayout()) {

    /** 树节点载荷：纯数据，[TaskTreeModel] 构建，便于单测。 */
    sealed class Node {
        data class Kind(val kind: String, val count: Int) : Node()
        data class Group(val groupName: String?, val count: Int) : Node()
        data class Task(val task: TaskInfo) : Node()
    }

    private val root = CheckedTreeNode(null)
    private val model = DefaultTreeModel(root)
    private var tasks: List<TaskInfo> = emptyList()
    private var query = ""
    private var selectedKey: String? = null
    private var rebuilding = false

    val tree: CheckboxTree = CheckboxTree(Renderer(), root, com.intellij.ui.CheckboxTreeBase.CheckPolicy(false, false, false, false))

    init {
        tree.model = model
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.emptyText.text = OkScriptToolkitBundle.message("taskLauncher.noTasks")
        tree.accessibleContext.accessibleName = OkScriptToolkitBundle.message("taskLauncher.tabTasks")
        tree.addCheckboxTreeListener(object : CheckboxTreeListener {
            override fun nodeStateChanged(node: CheckedTreeNode) {
                if (rebuilding) return
                val task = (node.userObject as? Node.Task)?.task ?: return
                host.onToggleTrigger(task, node.isChecked)
            }
        })
        tree.addTreeSelectionListener { event ->
            if (rebuilding) return@addTreeSelectionListener
            val task = taskAt(event.newLeadSelectionPath) ?: return@addTreeSelectionListener
            selectedKey = TaskSchemaMerge.keyOf(task)
            host.onTaskActivated(task)
        }
        tree.addTreeExpansionListener(object : TreeExpansionListener {
            override fun treeExpanded(event: TreeExpansionEvent) = fold(event.path, collapsed = false)
            override fun treeCollapsed(event: TreeExpansionEvent) = fold(event.path, collapsed = true)
        })
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount != 2 || event.button != MouseEvent.BUTTON1) return
                val path = tree.getPathForLocation(event.x, event.y) ?: return
                runIfOnetime(path)
            }
        })
        tree.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "ok.runTask")
        tree.actionMap.put("ok.runTask", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) { tree.selectionPath?.let(::runIfOnetime) }
        })
        add(JBScrollPane(tree).apply { border = com.intellij.util.ui.JBUI.Borders.empty() }, BorderLayout.CENTER)
    }

    fun setTasks(tasks: List<TaskInfo>) { this.tasks = tasks; rebuild() }
    fun setSearch(query: String) { this.query = query; rebuild() }

    /** 执行器状态变化：只重画（勾选态 / 状态文字从宿主实时读取）。 */
    fun refreshStatuses() {
        rebuilding = true
        try {
            TreeUtil.treeNodeTraverser(root).forEach { node ->
                val checked = node as? CheckedTreeNode ?: return@forEach
                val task = (checked.userObject as? Node.Task)?.task ?: return@forEach
                if (host.kindOf(task) == TaskRowState.TRIGGER) checked.isChecked = host.isTriggerEnabled(TaskSchemaMerge.keyOf(task))
            }
        } finally { rebuilding = false }
        tree.repaint()
    }

    fun selectTask(taskKey: String) {
        selectedKey = taskKey
        val node = findTaskNode(taskKey) ?: return
        val path = TreePath(node.path)
        rebuilding = true
        try { tree.selectionPath = path } finally { rebuilding = false }
        tree.scrollPathToVisible(path)
    }

    fun clearSelection() {
        selectedKey = null
        rebuilding = true
        try { tree.clearSelection() } finally { rebuilding = false }
    }

    private fun rebuild() {
        rebuilding = true
        try {
            root.removeAllChildren()
            val searching = query.isNotBlank()
            val structure = TaskTreeModel.build(TaskListGrouping.rows(tasks, host::schemaOf, host::kindOf, query))
            for (kindEntry in structure) {
                val kindNode = DefaultMutableTreeNode(kindEntry.node)
                root.add(kindNode)
                for (child in kindEntry.children) {
                    if (child.node is Node.Group) {
                        val groupNode = DefaultMutableTreeNode(child.node)
                        kindNode.add(groupNode)
                        child.children.forEach { groupNode.add(taskNode(it.node as Node.Task)) }
                    } else {
                        kindNode.add(taskNode(child.node as Node.Task))
                    }
                }
            }
            model.reload()
            TreeUtil.treeNodeTraverser(root).forEach { node ->
                val treeNode = node as? DefaultMutableTreeNode ?: return@forEach
                val key = foldKeyOf(treeNode.userObject) ?: return@forEach
                if (searching || !host.isCollapsed(key)) tree.expandPath(TreePath(treeNode.path))
            }
            selectedKey?.let { key -> findTaskNode(key)?.let { tree.selectionPath = TreePath(it.path) } }
        } finally { rebuilding = false }
    }

    private fun taskNode(node: Node.Task): DefaultMutableTreeNode =
        if (host.kindOf(node.task) == TaskRowState.TRIGGER) {
            CheckedTreeNode(node).apply { isChecked = host.isTriggerEnabled(TaskSchemaMerge.keyOf(node.task)) }
        } else DefaultMutableTreeNode(node)

    private fun foldKeyOf(payload: Any?): String? = when (payload) {
        is Node.Kind -> TaskListGrouping.kindFoldKey(payload.kind)
        is Node.Group -> TaskListGrouping.groupFoldKey(payload.groupName)
        else -> null
    }

    private fun fold(path: TreePath, collapsed: Boolean) {
        if (rebuilding || query.isNotBlank()) return
        val key = foldKeyOf((path.lastPathComponent as? DefaultMutableTreeNode)?.userObject) ?: return
        if (host.isCollapsed(key) != collapsed) host.onCollapseChanged(key, collapsed)
    }

    private fun taskAt(path: TreePath?): TaskInfo? =
        ((path?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? Node.Task)?.task

    private fun runIfOnetime(path: TreePath) {
        val task = taskAt(path) ?: return
        if (host.kindOf(task) == TaskRowState.TRIGGER) return
        if (host.cardStatusOf(task).launchEnabled) host.onRunTask(task)
    }

    private fun findTaskNode(taskKey: String): DefaultMutableTreeNode? =
        TreeUtil.treeNodeTraverser(root).filter(DefaultMutableTreeNode::class.java).firstOrNull { node ->
            (node.userObject as? Node.Task)?.let { TaskSchemaMerge.keyOf(it.task) == taskKey } == true
        }

    /** 当前可见树结构的文本快照（测试用）。 */
    fun snapshot(): List<String> = TreeUtil.treeNodeTraverser(root).filter(DefaultMutableTreeNode::class.java)
        .filter { it !== root }.map { node ->
            val depth = node.level - 1
            val label = when (val payload = node.userObject) {
                is Node.Kind -> "kind:${payload.kind}(${payload.count})"
                is Node.Group -> "group:${payload.groupName ?: "-"}(${payload.count})"
                is Node.Task -> "task:${payload.task.className}" + if (node is CheckedTreeNode) "[${if (node.isChecked) "x" else " "}]" else ""
                else -> "?"
            }
            "  ".repeat(depth) + label
        }.toList()

    private inner class Renderer : CheckboxTree.CheckboxTreeCellRenderer(true, false) {
        override fun customizeRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            val node = value as? DefaultMutableTreeNode ?: return
            val text = textRenderer
            when (val payload = node.userObject) {
                is Node.Kind -> {
                    val trigger = payload.kind == TaskRowState.TRIGGER
                    text.icon = if (trigger) AllIcons.Actions.Lightning else AllIcons.Actions.ListFiles
                    text.append(OkScriptToolkitBundle.message(if (trigger) "taskLauncher.triggerTask" else "taskLauncher.oneTimeTask"), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    text.append("  " + OkScriptToolkitBundle.message("taskLauncher.itemsCount", payload.count), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is Node.Group -> {
                    text.icon = AllIcons.Nodes.Folder
                    text.append(payload.groupName ?: OkScriptToolkitBundle.message("taskLauncher.ungrouped"))
                    text.append("  ${payload.count}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is Node.Task -> {
                    val status = host.cardStatusOf(payload.task)
                    val trigger = host.kindOf(payload.task) == TaskRowState.TRIGGER
                    text.icon = when (status.tone) {
                        TaskRowState.TONE_GOOD -> AllIcons.Actions.Execute
                        TaskRowState.TONE_ENQUEUED -> if (trigger) null else AllIcons.Vcs.History
                        TaskRowState.TONE_BAD -> AllIcons.General.Warning
                        else -> if (trigger) null else AllIcons.Nodes.Method
                    }
                    text.append(host.schemaOf(payload.task)?.displayName ?: payload.task.displayName)
                    if (status.text.isNotBlank()) {
                        val attributes = SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, TaskLauncherTheme.colorForTone(status.tone))
                        text.append("  " + status.text, attributes)
                    }
                    text.toolTipText = payload.task.module + "." + payload.task.className
                }
            }
        }
    }
}

/** 由分组行构建树结构（纯函数，单测覆盖）。 */
internal object TaskTreeModel {
    data class Entry(val node: TaskTreePanel.Node, val children: MutableList<Entry> = mutableListOf())

    fun build(rows: List<TaskListGrouping.Row>): List<Entry> {
        val out = mutableListOf<Entry>()
        var kind: Entry? = null
        var group: Entry? = null
        for (row in rows) when (row) {
            is TaskListGrouping.Row.KindHead -> { kind = Entry(TaskTreePanel.Node.Kind(row.kind, row.count)).also(out::add); group = null }
            is TaskListGrouping.Row.GroupHead -> { group = Entry(TaskTreePanel.Node.Group(row.groupName, row.count)).also { kind?.children?.add(it) } }
            is TaskListGrouping.Row.Card -> (group ?: kind)?.children?.add(Entry(TaskTreePanel.Node.Task(row.task)))
        }
        return out
    }
}
