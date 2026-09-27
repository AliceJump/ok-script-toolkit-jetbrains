package com.alicejump.okscripttoolkit.tasklauncher

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.alicejump.okscripttoolkit.tasklauncher.TaskLauncherService.TaskInfo
import com.alicejump.okscripttoolkit.tasklauncher.TaskLauncherService.TaskSchema
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
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
 * 单张任务卡的执行状态（对齐 VS Code 侧 taskCard.js 的 cardState → 徽标语义）。
 *
 * [text] / [tone] 与状态列同源（工厂的 statusCellFor）；[showBadge] 对齐 webview
 * 「没有可展示事件就不画徽标」（一次性任务既不在队也不在跑时只留状态点）；
 * [launchEnabled] 对齐 webview 的 launch.disabled（排队中 / 执行中 / 执行器跨项目时禁用）。
 */
internal data class TaskCardStatus(
    val text: String,
    val tone: Int,
    val showBadge: Boolean,
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
    fun hasParamOverrides(taskKey: String): Boolean
    fun isCollapsed(foldKey: String): Boolean
    fun onCollapseChanged(foldKey: String, collapsed: Boolean)
    /** 卡片主体点击（表格时代的「选中 → 右栏加载参数」的等价物） */
    fun onTaskActivated(task: TaskInfo)
    fun onOpenParams(task: TaskInfo)
    fun onToggleTrigger(task: TaskInfo, enabled: Boolean)
    fun onRunTask(task: TaskInfo)
    fun onSyncDefault(task: TaskInfo)
    fun onResetDefault(task: TaskInfo)
    /** 悬停 800ms 后弹只读摘要（宿主持有弹层单例与抑制窗口） */
    fun onTaskHover(task: TaskInfo, anchor: JComponent)
    fun onTaskHoverEnd()
}

/**
 * 任务卡列表 —— VS Code 任务页编排的 Swing 等价物。
 *
 * 之前这里是「操作 / 任务 / 状态」三列的平铺 JTable：probe 什么顺序就什么顺序
 * （一次性任务整段压在触发任务后面），无分组、无搜索、无折叠。现在对齐
 * VS Code 侧 media/console/taskCard.js 的编排（规则见 [TaskListGrouping]）：
 *
 * - 顶层「触发任务」「一次性任务」两个可折叠组头（带计数，状态落 tasks.json 的 uiState）；
 * - 一次性任务按 schema.groupName 二级分组（组头 = 组名 + 计数，缺省归「未分组」）；
 * - 每个任务是一张卡：状态点 + 名称 + 类型 chip + 状态徽标 + 类名·模块 + 描述，
 *   右侧动作 = ⚙参数 / ⇄同步 default / ⟲恢复默认 /（触发）启用勾选 /（一次性）▶启动；
 * - 搜索框过滤（命中显示名/类名/模块/描述/分组名），搜索激活时无视折叠；
 * - 收起的组不建卡片 DOM（对齐 webview 的伸缩性处理）；
 * - 执行器状态推送走 [refreshStatuses] 原地更新，不整列重建。
 */
internal class TaskCardListPanel(private val host: TaskCardHost) :
    JPanel(GridBagLayout()), Scrollable {

    companion object {
        private val KIND_HEAD_INSETS = Insets(8, 2, 2, 2)
        private val GROUP_HEAD_INSETS = Insets(6, 10, 2, 2)
        private val CARD_INSETS = Insets(2, 4, 2, 4)
        private val EMPTY_INSETS = Insets(12, 10, 10, 10)
    }

    private var tasks: List<TaskInfo> = emptyList()
    private var searchQuery = ""

    /** 任务 key → 卡片；refreshStatuses 据此原地更新，不重建列表 */
    private val cardsByKey = LinkedHashMap<String, TaskCard>()

    /** 程序化改写勾选框时抑制回调（表格时代 updatingTableModel 的等价物） */
    private var programmaticToggle = false

    init {
        isOpaque = false
    }

    /** 全量重建（任务/schema 变化、搜索、折叠切换时调用 —— 对齐 webview 的 renderTasks） */
    fun setTasks(tasks: List<TaskInfo>) {
        this.tasks = tasks
        rebuild()
    }

    fun setSearch(query: String) {
        searchQuery = query
        rebuild()
    }

    /** 执行器状态推送：逐卡原地刷新（对齐 webview 的 updateRunningState） */
    fun refreshStatuses() {
        for (card in cardsByKey.values) {
            card.refresh(host.cardStatusOf(card.task))
        }
    }

    private fun rebuild() {
        removeAll()
        cardsByKey.clear()
        val searching = searchQuery.isNotBlank()
        val rows = TaskListGrouping.rows(tasks, host::schemaOf, host::kindOf, searchQuery)
        var gridY = 0
        // 当前 kind 段整体隐藏（该 kind 组被折叠）；业务组卡片单独隐藏（组被折叠）
        var kindSectionHidden = false
        var groupHidden = false
        for (row in rows) {
            when (row) {
                is TaskListGrouping.Row.KindHead -> {
                    val collapsed = !searching && host.isCollapsed(TaskListGrouping.kindFoldKey(row.kind))
                    kindSectionHidden = collapsed
                    groupHidden = false
                    addRow(buildKindHead(row.kind, row.count, collapsed), gridY++, KIND_HEAD_INSETS)
                }
                is TaskListGrouping.Row.GroupHead -> {
                    val collapsed = !searching && host.isCollapsed(TaskListGrouping.groupFoldKey(row.groupName))
                    groupHidden = collapsed
                    if (!kindSectionHidden) {
                        addRow(buildGroupHead(row.groupName, row.count, collapsed), gridY++, GROUP_HEAD_INSETS)
                    }
                }
                is TaskListGrouping.Row.Card -> {
                    // 收起的组不建卡片（大列表伸缩性，对齐 webview renderGroupedOnetime 的 continue）
                    if (kindSectionHidden || groupHidden) continue
                    val card = TaskCard(row.task)
                    cardsByKey[TaskSchemaMerge.keyOf(row.task)] = card
                    addRow(card, gridY++, CARD_INSETS)
                }
            }
        }
        if (tasks.isEmpty()) {
            addRow(mutedLabel(OkScriptToolkitBundle.message("taskLauncher.noTasks")), gridY++, EMPTY_INSETS)
        }
        // 底部弹簧：内容顶对齐
        add(
            JPanel().apply { isOpaque = false },
            GridBagConstraints().apply {
                gridx = 0
                gridy = gridY
                weightx = 1.0
                weighty = 1.0
                fill = GridBagConstraints.BOTH
            },
        )
        revalidate()
        repaint()
    }

    private fun addRow(component: JComponent, gridY: Int, insets: Insets) {
        add(
            component,
            GridBagConstraints().apply {
                gridx = 0
                gridy = gridY
                gridwidth = GridBagConstraints.REMAINDER
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.NORTH
                this.insets = insets
            },
        )
    }

    /** 折叠切换：先落盘（宿主持久化），再整列重建（对齐 webview：折叠跳过卡片 DOM） */
    private fun toggleCollapse(foldKey: String) {
        host.onCollapseChanged(foldKey, !host.isCollapsed(foldKey))
        rebuild()
    }

    /** 组头行：chevron 标签（非按钮，避免事件被消费后行点击失效）+ 粗体标题 + 计数 */
    private fun buildGroupHeadRow(
        label: String,
        count: Int,
        collapsed: Boolean,
        foldKey: String,
        small: Boolean,
    ): JComponent {
        val row = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        row.isOpaque = false
        val chevron = JBLabel(if (collapsed) "▶" else "▼")
        if (small) chevron.font = chevron.font.deriveFont(chevron.font.size2D - 2f)
        val title = JBLabel(label)
        title.font = title.font.deriveFont(Font.BOLD)
        if (small) title.font = title.font.deriveFont(title.font.size2D - 1f)
        val countLabel = mutedLabel(OkScriptToolkitBundle.message("taskLauncher.itemsCount", count))
        row.add(chevron)
        row.add(title)
        row.add(countLabel)
        row.toolTipText = if (collapsed) {
            OkScriptToolkitBundle.message("taskLauncher.expandSection")
        } else {
            OkScriptToolkitBundle.message("taskLauncher.collapseSection")
        }
        val clickAdapter = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                hoverGuard()
                toggleCollapse(foldKey)
            }
        }
        row.addMouseListener(clickAdapter)
        // 组头的子组件（标签）不消费鼠标事件会自然冒泡到行；chevron 标签同样由行接管。
        row.alignmentX = java.awt.Component.LEFT_ALIGNMENT
        return row
    }

    private fun buildKindHead(kind: String, count: Int, collapsed: Boolean): JComponent =
        buildGroupHeadRow(
            label = OkScriptToolkitBundle.message(
                if (kind == TaskRowState.TRIGGER) "taskLauncher.triggerTask" else "taskLauncher.oneTimeTask",
            ),
            count = count,
            collapsed = collapsed,
            foldKey = TaskListGrouping.kindFoldKey(kind),
            small = false,
        )

    private fun buildGroupHead(groupName: String?, count: Int, collapsed: Boolean): JComponent =
        buildGroupHeadRow(
            label = groupName ?: OkScriptToolkitBundle.message("taskLauncher.ungrouped"),
            count = count,
            collapsed = collapsed,
            foldKey = TaskListGrouping.groupFoldKey(groupName),
            small = true,
        )

    private fun mutedLabel(text: String): JBLabel {
        val label = JBLabel(text)
        label.foreground = UIUtil.getContextHelpForeground()
        return label
    }

    /** 行内点击后短暂抑制悬停弹层（对齐 VS Code：点按后 1.2s 不弹，规则在宿主） */
    private fun hoverGuard() {
        host.onTaskHoverEnd()
    }

    // ── 任务卡 ────────────────────────────────────────────────────────

    /**
     * 一张任务卡（article.task-card 的 Swing 等价物）。
     *
     * 布局：左身份区（状态点 + 名称 + 类型 chip + 状态徽标，下一行类名·模块与描述），
     * 右动作区（⚙参数 / ⇄⟲ 快照 / 启用勾选或 ▶启动）。卡片主体可点 —— 点一下
     * 右栏加载该任务参数（表格时代「选中行 → 参数」的等价交互）。
     */
    private inner class TaskCard(val task: TaskInfo) : JPanel(BorderLayout(8, 0)) {

        val cardKey: String = TaskSchemaMerge.keyOf(task)
        private val kind: String = host.kindOf(task)
        private val isTrigger = kind == TaskRowState.TRIGGER

        private val dot = TaskLauncherTheme.HealthDot()
        private val nameLabel = JBLabel(displayName()).apply {
            font = font.deriveFont(Font.BOLD)
            toolTipText = cardKey
        }
        private val kindChip = JBLabel()
        private val statusBadge = JBLabel().apply { isVisible = false }

        private val paramsButton = JButton(
            OkScriptToolkitBundle.message("taskLauncher.parameters"),
            AllIcons.General.Settings,
        )
        private val syncButton = JButton("⇄")
        private val resetButton = JButton("⟲")
        private val triggerCheckbox = if (isTrigger) JCheckBox(OkScriptToolkitBundle.message("taskLauncher.enableTrigger")) else null
        private val runButton = if (!isTrigger) {
            JButton(OkScriptToolkitBundle.message("taskLauncher.run"), AllIcons.RunConfigurations.TestState.Run)
        } else {
            null
        }

        init {
            isOpaque = false
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(3, 3, 3, 3),
                BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(JBColor.border(), 1, true),
                    BorderFactory.createEmptyBorder(6, 8, 6, 8),
                ),
            )

            val schema = host.schemaOf(task)
            TaskLauncherTheme.styleChip(
                kindChip,
                if (isTrigger) TaskLauncherTheme.TRIGGER else TaskLauncherTheme.ONETIME,
                OkScriptToolkitBundle.message(
                    if (isTrigger) "taskLauncher.triggerTask" else "taskLauncher.oneTimeTask",
                ),
            )

            val titleRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
            titleRow.isOpaque = false
            titleRow.add(dot)
            titleRow.add(nameLabel)
            titleRow.add(kindChip)
            titleRow.add(statusBadge)

            val identity = JPanel()
            identity.layout = javax.swing.BoxLayout(identity, javax.swing.BoxLayout.Y_AXIS)
            identity.isOpaque = false
            identity.add(titleRow)
            val classLine = mutedLabel("${task.className} · ${task.module}")
            classLine.alignmentX = java.awt.Component.LEFT_ALIGNMENT
            identity.add(classLine)
            schema?.description?.takeIf { it.isNotBlank() }?.let { description ->
                val line = mutedLabel(description)
                line.alignmentX = java.awt.Component.LEFT_ALIGNMENT
                line.toolTipText = description
                identity.add(line)
            }

            val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
            actions.isOpaque = false
            paramsButton.isFocusable = false
            paramsButton.addActionListener {
                host.onOpenParams(task)
            }
            actions.add(paramsButton)

            // ⇄ / ⟲ 只在 schema 就绪时出现（对齐 webview buildSnapshotButtons 的守卫）
            if (schema != null && !schema.broken && schema.fields.isNotEmpty()) {
                syncButton.isFocusable = false
                syncButton.margin = Insets(0, 2, 0, 2)
                syncButton.toolTipText = OkScriptToolkitBundle.message("taskLauncher.syncDefaultBtn")
                syncButton.addActionListener { host.onSyncDefault(task) }
                resetButton.isFocusable = false
                resetButton.margin = Insets(0, 2, 0, 2)
                resetButton.toolTipText = OkScriptToolkitBundle.message("taskLauncher.resetDefaultBtn")
                resetButton.addActionListener { host.onResetDefault(task) }
                actions.add(syncButton)
                actions.add(resetButton)
            }

            triggerCheckbox?.let { checkbox ->
                checkbox.isFocusable = false
                checkbox.addActionListener {
                    if (!programmaticToggle) host.onToggleTrigger(task, checkbox.isSelected)
                }
                checkbox.isSelected = host.isTriggerEnabled(cardKey)
                actions.add(checkbox)
            }
            runButton?.let { button ->
                button.isFocusable = false
                button.addActionListener { host.onRunTask(task) }
                actions.add(button)
            }

            add(identity, BorderLayout.CENTER)
            add(actions, BorderLayout.EAST)

            // 悬停高亮 + 悬停弹层 + 主体点击（按钮 / 勾选框消费自己的事件，不会重复触发）
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    background = TaskLauncherTheme.rowBackground()
                    isOpaque = true
                    repaint()
                    host.onTaskHover(task, this@TaskCard)
                }

                override fun mouseExited(e: MouseEvent) {
                    background = null
                    isOpaque = false
                    repaint()
                    host.onTaskHoverEnd()
                }

                override fun mouseClicked(e: MouseEvent) {
                    host.onTaskActivated(task)
                }
            })

            refresh(host.cardStatusOf(task))
        }

        private fun displayName(): String =
            host.schemaOf(task)?.displayName ?: task.displayName

        /** 原地刷新执行状态（对齐 webview updateRunningState 对单卡做的事） */
        fun refresh(status: TaskCardStatus) {
            dot.color = dotColor(status.tone)
            statusBadge.isVisible = status.showBadge
            if (status.showBadge) {
                TaskLauncherTheme.styleChip(statusBadge, TaskLauncherTheme.colorForTone(status.tone), status.text)
            }
            programmaticToggle = true
            try {
                triggerCheckbox?.let { checkbox ->
                    val want = host.isTriggerEnabled(cardKey)
                    if (checkbox.isSelected != want) checkbox.isSelected = want
                }
                runButton?.isEnabled = status.launchEnabled
            } finally {
                programmaticToggle = false
            }
            // 参数覆盖徽标：快照值 ≠ 出厂值（或存在孤儿键）时高亮 ⚙ 参数（对齐 has-overrides）
            val overrides = host.hasParamOverrides(cardKey)
            paramsButton.foreground = if (overrides) TaskLauncherTheme.WARN else UIUtil.getLabelForeground()
            paramsButton.toolTipText = if (overrides) {
                OkScriptToolkitBundle.message("taskLauncher.hasOverrides")
            } else {
                OkScriptToolkitBundle.message("taskLauncher.parameters")
            }
        }

        private fun dotColor(tone: Int): Color =
            if (tone == TaskRowState.TONE_NEUTRAL) UIUtil.getLabelDisabledForeground()
            else TaskLauncherTheme.colorForTone(tone)
    }

    // ── Scrollable：宽度贴视口、高度自由（与右侧 paramPanel 同款） ────

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

    override fun getScrollableUnitIncrement(visibleRect: java.awt.Rectangle, orientation: Int, direction: Int): Int = 16

    override fun getScrollableBlockIncrement(visibleRect: java.awt.Rectangle, orientation: Int, direction: Int): Int =
        (visibleRect.height - 16).coerceAtLeast(16)

    override fun getScrollableTracksViewportWidth(): Boolean = true

    override fun getScrollableTracksViewportHeight(): Boolean = false
}
