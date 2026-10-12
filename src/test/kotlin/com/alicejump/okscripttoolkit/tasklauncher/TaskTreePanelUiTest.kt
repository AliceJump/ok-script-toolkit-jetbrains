package com.alicejump.okscripttoolkit.tasklauncher

import com.alicejump.okscripttoolkit.tasklauncher.TaskLauncherService.TaskInfo
import com.alicejump.okscripttoolkit.tasklauncher.TaskLauncherService.TaskSchema
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import javax.swing.JComponent

/** 平台组件测试：CheckboxTree 需要 Application（TreeUIHelper 等服务）。 */
class TaskTreePanelUiTest : BasePlatformTestCase() {
    private fun task(name: String, kind: String) = TaskInfo(module = "src.tasks.$name", className = name, kind = kind)
    private val tasks get() = listOf(task("AutoLogin", TaskRowState.TRIGGER), task("Daily", TaskRowState.ONETIME), task("Farm", TaskRowState.ONETIME))

    private class FakeHost(val tasks: List<TaskInfo>) : TaskCardHost {
        val calls = mutableListOf<String>()
        val enabled = mutableSetOf<String>()
        val collapsed = mutableSetOf<String>()
        override fun taskList() = tasks
        override fun schemaOf(task: TaskInfo) = TaskSchema()
        override fun kindOf(task: TaskInfo) = task.kind ?: TaskRowState.ONETIME
        override fun isTriggerEnabled(taskKey: String) = taskKey in enabled
        override fun cardStatusOf(task: TaskInfo) = TaskCardStatus("", TaskRowState.TONE_NEUTRAL, true)
        override fun isCollapsed(foldKey: String) = foldKey in collapsed
        override fun onCollapseChanged(foldKey: String, collapsed: Boolean) { calls += "fold:$foldKey=$collapsed"; if (collapsed) this.collapsed += foldKey else this.collapsed -= foldKey }
        override fun onTaskActivated(task: TaskInfo) { calls += "select:${task.className}" }
        override fun onToggleTrigger(task: TaskInfo, enabled: Boolean) { calls += "trigger:${task.className}=$enabled" }
        override fun onRunTask(task: TaskInfo) { calls += "run:${task.className}" }
        override fun onTaskHover(task: TaskInfo, anchor: JComponent) = Unit
        override fun onTaskHoverEnd() = Unit
    }

    fun testTreeShowsTriggerCheckboxesSelectsAndRunsOnetimeTasks() {
        run {
            val host = FakeHost(tasks)
            host.enabled += TaskSchemaMerge.keyOf(tasks[0])
            val panel = TaskTreePanel(host)
            panel.setTasks(tasks)
            kotlin.test.assertEquals(listOf(
                "kind:trigger(1)", "  task:AutoLogin[x]",
                "kind:onetime(2)", "  group:-(2)", "    task:Daily", "    task:Farm",
            ), panel.snapshot())
            panel.selectTask(TaskSchemaMerge.keyOf(tasks[2]))
            kotlin.test.assertEquals(emptyList(), host.calls, "programmatic selection must not echo back to the host")
            val path = panel.tree.getPathForRow(4)
            panel.tree.selectionPath = path
            kotlin.test.assertEquals(listOf("select:Daily"), host.calls)
            panel.tree.actionMap.get("ok.runTask").actionPerformed(null)
            kotlin.test.assertEquals(listOf("select:Daily", "run:Daily"), host.calls)
        }
    }

    fun testSearchFiltersAndCollapsingPersistsThroughTheHost() {
        run {
            val host = FakeHost(tasks)
            val panel = TaskTreePanel(host)
            panel.setTasks(tasks)
            panel.tree.collapseRow(0)
            kotlin.test.assertEquals(listOf("fold:${TaskListGrouping.kindFoldKey(TaskRowState.TRIGGER)}=true"), host.calls)
            panel.setSearch("farm")
            kotlin.test.assertEquals(listOf("kind:trigger(0)", "kind:onetime(1)", "  group:-(1)", "    task:Farm"), panel.snapshot())
        }
    }

}
