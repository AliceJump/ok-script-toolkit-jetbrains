package com.alicejump.okscripttoolkit.tasklauncher

import com.alicejump.okscripttoolkit.tasklauncher.TaskLauncherService.TaskInfo
import com.alicejump.okscripttoolkit.tasklauncher.TaskLauncherService.TaskSchema
import javax.swing.JComponent
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals

class TaskTreePanelTest {
    private fun task(name: String, kind: String) = TaskInfo(module = "src.tasks.$name", className = name, kind = kind)


    private val tasks = listOf(task("AutoLogin", TaskRowState.TRIGGER), task("Daily", TaskRowState.ONETIME), task("Farm", TaskRowState.ONETIME))

    @Test
    fun `model groups trigger and onetime tasks like the VS Code run tree`() {
        val rows = TaskListGrouping.rows(tasks, { TaskSchema(groupName = if (it.className == "Farm") "Combat" else null) }, { it.kind!! }, "")
        val tree = TaskTreeModel.build(rows)
        assertEquals(2, tree.size)
        assertEquals(1, tree[0].children.size)
        assertEquals(listOf("-", "Combat"), tree[1].children.map { (it.node as TaskTreePanel.Node.Group).groupName ?: "-" })
    }
}
