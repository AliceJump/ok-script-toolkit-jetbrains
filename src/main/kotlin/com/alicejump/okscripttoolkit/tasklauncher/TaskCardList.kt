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

/**
 * 任务卡列表 —— VS Code 任务页编排的 Swing 等价物。
 *
 * 对齐 VS Code 侧 media/console/taskCard.js 的编排（规则见 [TaskListGrouping]）：
 *
 * - 顶层「触发任务」「一次性任务」两个可折叠组头（带计数，状态落 tasks.json 的 uiState）；
 * - 一次性任务按 schema.groupName 二级分组（组头 = 组名 + 计数，缺省归「未分组」）；
 * - 每个任务是一张**精简**卡：状态点 + 名称 + 类名·模块 + 类型 chip + 状态徽标，
 *   以及类型动作（触发 = 启用勾选 / 一次性 = ▶启动）。参数与快照操作不在卡上 ——
 *   点击卡片即选中，右栏显示详情与参数（信息架构重设计：卡片即入口）；
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

    /** 当前选中（右栏详情对应）的任务 key */
    private var selectedKey: String? = null

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

    /**
     * 选中并定位任务（运行器页「队列与轮询」行点击跳回任务列表用）：
     * 高亮卡片并滚动到可见；任务不在当前过滤结果里时只清空高亮。
     */
    fun selectTask(taskKey: String) {
        selectedKey = taskKey
        for ((key, card) in cardsByKey) {
            card.setSelected(key == taskKey)
        }
        cardsByKey[taskKey]?.scrollRectToVisible(cardRectOf(cardsByKey.getValue(taskKey)))
    }

    private fun cardRectOf(card: TaskCard) = java.awt.Rectangle(0, card.y, width, card.height)

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
        val row = JPanel(WrapLayout(FlowLayout.LEFT, 4, 0))
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
     * 一张精简任务卡（article.task-card 的 Swing 等价物，IA 重设计后瘦身）。
     *
     * **卡片上只有两处文字：任务名 + 任务介绍**。类型、状态、类名一律不写：
     * - **外框色 = 任务性质**：触发 = 紫（[TaskLauncherTheme.TRIGGER]），
     *   一次性 = 青（[TaskLauncherTheme.ONETIME]）；
     * - **内框色 = 当前运行情况**：绿 = 正在运行、蓝 = 已入列、灰 = 未运行、红 = schema 异常；
     * - 类型动作 = **一个不带文字的按钮**（触发任务翻图标表示启用 / 停用轮询，一次性 = ▶启动）；
     * - 类型 / 状态 / 类名的文字语义进 tooltip。
     *
     * 两层框之间留 2px 缝，两个颜色互不干扰。
     *
     * 布局是**两行**（窄工具窗是常态）：第一行 = 任务名（放不下按像素省略）+ 动作按钮，
     * 第二行 = 任务介绍（可换行）。两端拿 preferred 宽度**永不裁切**，中间的自由文本拿
     * 剩余宽度。之前是单个 `FlowLayout` 一行塞五件东西 —— FlowLayout 的 preferredLayoutSize
     * 只算**一行**，一旦换行，多出来的那行会被父容器按「一行高」给的高度裁掉，
     * 窄窗里正好把右侧的 chip 与徽标切没了（用户反馈「露一半」）。
     *
     * 参数表单与 ⇄/⟲ 快照操作全部移到详情页；点击卡片主体 = 选中 + 加载详情。
     */
    private inner class TaskCard(val task: TaskInfo) : JPanel(BorderLayout(0, 2)) {

        val cardKey: String = TaskSchemaMerge.keyOf(task)
        private val kind: String = host.kindOf(task)
        private val isTrigger = kind == TaskRowState.TRIGGER

        /** 外框色：任务性质（不随运行状态变） */
        private val kindColor: JBColor =
            if (isTrigger) TaskLauncherTheme.TRIGGER else TaskLauncherTheme.ONETIME

        /** 内框色：当前运行情况（[refresh] 更新） */
        private var statusColor: Color = UIUtil.getLabelDisabledForeground()
        private var statusText: String = ""

        private val nameLabel = EllipsizingLabel(displayName()).apply {
            font = font.deriveFont(Font.BOLD)
        }
        /** 任务介绍：可换行（卡片上只留「名称 + 介绍」两处文字） */
        private val descriptionArea = SchemaFieldUi.WrappingDescription(descriptionOf())

        /**
         * 类型动作：**一个不带文字的按钮**。
         * 触发任务 = 启用/停用轮询（图标随状态翻），一次性任务 = ▶启动。
         */
        private val actionButton: JButton = if (isTrigger) {
            JButton().apply {
                isFocusable = false
                addActionListener { host.onToggleTrigger(task, !host.isTriggerEnabled(cardKey)) }
            }
        } else {
            JButton(AllIcons.RunConfigurations.TestState.Run).apply {
                isFocusable = false
                toolTipText = OkScriptToolkitBundle.message("taskLauncher.run")
                addActionListener { host.onRunTask(task) }
            }
        }

        init {
            isOpaque = false

            val titleRow = JPanel(BorderLayout(6, 0))
            titleRow.isOpaque = false
            titleRow.add(nameLabel, BorderLayout.CENTER)
            titleRow.add(actionButton, BorderLayout.EAST)

            add(titleRow, BorderLayout.NORTH)
            add(descriptionArea, BorderLayout.CENTER)
            // 没写介绍的任务不留空行
            descriptionArea.isVisible = descriptionArea.text.isNotBlank()

            // 悬停高亮 + 悬停弹层 + 主体点击（按钮消费自己的事件，不会重复触发）
            val cardMouse = object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    if (cardKey != selectedKey) {
                        background = TaskLauncherTheme.rowBackground()
                        isOpaque = true
                        repaint()
                    }
                    host.onTaskHover(task, this@TaskCard)
                }

                override fun mouseExited(e: MouseEvent) {
                    if (cardKey != selectedKey) {
                        background = null
                        isOpaque = false
                        repaint()
                    }
                    host.onTaskHoverEnd()
                }

                override fun mouseClicked(e: MouseEvent) {
                    host.onTaskActivated(task)
                }
            }
            addMouseListener(cardMouse)
            // Swing 事件不冒泡：标签会把点击「吃掉」，而卡片大半面积都是文字 ——
            // 不转发的话「点击卡片即选中」只在卡片留白处生效。按钮 / 勾选框自己消费，跳过。
            forwardMouse(titleRow, cardMouse)

            refresh(host.cardStatusOf(task))
        }

        /** 把卡片级鼠标监听挂到纯展示子组件上（递归下钻容器） */
        private fun forwardMouse(container: java.awt.Container, listener: MouseAdapter) {
            for (child in container.components) {
                if (child is JButton || child is JCheckBox) continue
                child.addMouseListener(listener)
                if (child is java.awt.Container) forwardMouse(child, listener)
            }
        }

        /**
         * 双层色框：外框 = 任务性质，内框 = 当前运行情况（两层之间 2px 缝）。
         * 选中态把外框加粗到 2px 并铺底 —— **外框色仍归「性质」所有**，不被选中态顶掉。
         */
        private fun cardBorder(): javax.swing.border.Border {
            val selected = cardKey == selectedKey
            return BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(2, 2, 2, 2),
                BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(kindColor, if (selected) 2 else 1, true),
                    BorderFactory.createCompoundBorder(
                        BorderFactory.createEmptyBorder(2, 2, 2, 2),
                        BorderFactory.createCompoundBorder(
                            BorderFactory.createLineBorder(statusColor, 1, true),
                            BorderFactory.createEmptyBorder(4, 6, 4, 6),
                        ),
                    ),
                ),
            )
        }

        /** 选中态：外框加粗 + 常亮底色（运行器页跳转定位也走这里） */
        fun setSelected(selected: Boolean) {
            border = cardBorder()
            if (selected) {
                background = TaskLauncherTheme.rowHoverBackground()
                isOpaque = true
            } else {
                background = null
                isOpaque = false
            }
            repaint()
        }

        private fun displayName(): String =
            host.schemaOf(task)?.displayName ?: task.displayName

        private fun descriptionOf(): String =
            host.schemaOf(task)?.description.orEmpty()

        /** 原地刷新执行状态：换内框色 + 翻动作按钮图标 + 更新 tooltip（卡片上没有状态文字） */
        fun refresh(status: TaskCardStatus) {
            statusColor = statusColorForTone(status.tone)
            statusText = status.text
            border = cardBorder()
            // 颜色撤掉文字后，语义只能靠 tooltip 讲清楚（类型 chip / 状态文字 / 类名都在这）
            toolTipText = "${kindLabel()} \u00b7 $statusText \u00b7 $cardKey"
            if (isTrigger) {
                val enabled = host.isTriggerEnabled(cardKey)
                actionButton.icon = if (enabled) AllIcons.Actions.Suspend else AllIcons.Actions.Execute
                actionButton.toolTipText = OkScriptToolkitBundle.message(
                    if (enabled) "taskLauncher.disableTrigger" else "taskLauncher.enableTrigger",
                )
            } else {
                actionButton.isEnabled = status.launchEnabled
            }
            repaint()
        }

        /** 颜色撤掉文字后，语义只能靠 tooltip 讲清楚 */
        private fun kindLabel(): String = OkScriptToolkitBundle.message(
            if (isTrigger) "taskLauncher.triggerTask" else "taskLauncher.oneTimeTask",
        )

        private fun statusColorForTone(tone: Int): Color =
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
