package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.core.TestRepoLayout
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** 平台组件测试：发布预览对话框、编辑器横幅与右键菜单注册。 */
class PublishPreviewDialogUiTest : BasePlatformTestCase() {
    fun testDialogRendersEveryRow() {
        val preview = PublishPreview.build(PublishPreview.TemplateInput("t", null, listOf("a"), listOf("b")), emptyList())
        val dialog = PublishPreviewDialog(project, preview)
        try {
            assertEquals(listOf("FILE:t/coco_annotations.json", "FILE:t/images/", "ADD:a", "REMOVE:b"), dialog.rowTexts())
            assertNotNull(dialog.createCenterPanel())
        } finally { Disposer.dispose(dialog.disposable) }
    }

    fun testEditorAndProjectViewActionsAreRegistered() {
        val manager = com.intellij.openapi.actionSystem.ActionManager.getInstance()
        assertNotNull(manager.getAction("com.alicejump.okscripttoolkit.FindTemplateReferences"))
        assertNotNull(manager.getAction("com.alicejump.okscripttoolkit.OpenWithAnnotationEditor"))
        assertNotNull(manager.getAction("com.alicejump.okscripttoolkit.CaptureToInbox"))
        assertEquals(setOf("png", "jpg", "jpeg", "webp"), ANNOTATABLE_IMAGE_EXTENSIONS)
    }

    fun testCaptureShortcutHasNoConflictInDefaultKeymaps() {
        val keymaps = com.intellij.openapi.keymap.KeymapManager.getInstance() as com.intellij.openapi.keymap.ex.KeymapManagerEx
        val shortcut = com.intellij.openapi.actionSystem.KeyboardShortcut.fromString("control alt shift O")
        val macShortcut = com.intellij.openapi.actionSystem.KeyboardShortcut.fromString("meta alt shift O")
        val checked = mutableListOf<String>()
        for (keymap in keymaps.allKeymaps) {
            val ids = keymap.getActionIds(if (keymap.name.startsWith("Mac")) macShortcut else shortcut).toList()
            checked += keymap.name
            assertTrue("${keymap.name}: $ids", ids.all { it == "com.alicejump.okscripttoolkit.CaptureToInbox" })
        }
        assertTrue(checked.toString(), checked.isNotEmpty())
        val settings = com.intellij.openapi.actionSystem.KeyboardShortcut.fromString("control alt S")
        val defaultKeymap = keymaps.getKeymap("\$default")!!
        assertFalse(defaultKeymap.getActionIds(settings).any { it.startsWith("com.alicejump") })
        assertTrue(TestRepoLayout.locate("docs/keymap.md").exists())
    }
}
