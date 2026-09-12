package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import java.beans.PropertyChangeListener
import javax.swing.Icon
import javax.swing.JComponent

/**
 * 角色管理打开成编辑器标签页（对齐 VSCode 版 `ViewColumn.One` 的大窗口），
 * 而不是挤在侧边栏工具窗口里。
 *
 * 实现要点：IntelliJ 的编辑器区只认 `VirtualFile`，所以用一个非物理的
 * `LightVirtualFile`（`shouldSkipEventSystem() = true`，不触发 VFS 事件）作载体，
 * 配 `FileEditorProvider` + `FileEditorPolicy.HIDE_DEFAULT_EDITOR`，
 * 让平台只创建我们自己的 `FileEditor`，不再叠一个文本编辑器。
 */
internal object CharacterManagerFileType : FileType {
    // IconLoader 会自动挑 _dark 变体（icons/characters_dark.svg）
    private val ICON: Icon =
        com.intellij.openapi.util.IconLoader.getIcon("/icons/characters.svg", CharacterManagerFileType::class.java)

    override fun getName(): String = "ok-script Character Manager"
    override fun getDisplayName(): String = OkScriptToolkitBundle.message("characterManager.title")
    override fun getDescription(): String = OkScriptToolkitBundle.message("characterManager.fileTypeDescription")
    override fun getDefaultExtension(): String = "okcharacters"
    override fun getIcon(): Icon = ICON
    override fun isBinary(): Boolean = true
    override fun isReadOnly(): Boolean = true
}

/** 角色管理标签页的载体文件（非物理，不落盘）。 */
class CharacterManagerFile : LightVirtualFile(
    OkScriptToolkitBundle.message("characterManager.title"),
    CharacterManagerFileType,
    "",
) {
    init {
        isWritable = false
    }
}

class CharacterManagerEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean = file is CharacterManagerFile

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        CharacterManagerEditor(project, file as CharacterManagerFile)

    override fun getEditorTypeId(): String = "ok-script-character-manager"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

class CharacterManagerEditor(
    private val project: Project,
    private val file: CharacterManagerFile,
) : FileEditor {

    private val panel = CharacterManagerPanel(project)

    override fun getComponent(): JComponent = panel.mainPanel
    override fun getPreferredFocusedComponent(): JComponent? = panel.preferredFocusedComponent
    override fun getName(): String = OkScriptToolkitBundle.message("characterManager.title")
    override fun setState(state: FileEditorState) = Unit
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = true
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun <T : Any> getUserData(key: com.intellij.openapi.util.Key<T>): T? = null
    override fun <T : Any> putUserData(key: com.intellij.openapi.util.Key<T>, value: T?) = Unit
    override fun getFile(): VirtualFile = file
    override fun dispose() = panel.dispose()
}

/** 打开（或复用已打开的）角色管理标签页。 */
fun openCharacterManager(project: Project) {
    val manager = FileEditorManager.getInstance(project)
    val existing = manager.openFiles.filterIsInstance<CharacterManagerFile>().firstOrNull()
    manager.openFile(existing ?: CharacterManagerFile(), true)
}

class ShowCharacterManagerAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        openCharacterManager(project)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
