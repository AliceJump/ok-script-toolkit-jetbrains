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

internal object TemplateGalleryFileType : FileType {
    private val icon: Icon =
        com.intellij.openapi.util.IconLoader.getIcon("/icons/templates.svg", TemplateGalleryFileType::class.java)

    override fun getName(): String = "ok-script Template Gallery"
    override fun getDisplayName(): String = OkScriptToolkitBundle.message("taskLauncher.toolTemplatesTitle")
    override fun getDescription(): String = OkScriptToolkitBundle.message("action.openTemplatesEditor.description")
    override fun getDefaultExtension(): String = "oktemplates"
    override fun getIcon(): Icon = icon
    override fun isBinary(): Boolean = true
    override fun isReadOnly(): Boolean = true
}

class TemplateGalleryFile : LightVirtualFile(
    OkScriptToolkitBundle.message("taskLauncher.toolTemplatesTitle"),
    TemplateGalleryFileType,
    "",
) {
    init { isWritable = false }
}

class TemplateGalleryEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean = file is TemplateGalleryFile

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        TemplateGalleryEditor(project, file as TemplateGalleryFile)

    override fun getEditorTypeId(): String = "ok-script-template-gallery"
    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

class TemplateGalleryEditor(project: Project, private val file: TemplateGalleryFile) : FileEditor {
    private val panel = TemplateGalleryPanel(project)

    override fun getComponent(): JComponent = panel.component
    override fun getPreferredFocusedComponent(): JComponent = panel.component
    override fun getName(): String = OkScriptToolkitBundle.message("taskLauncher.toolTemplatesTitle")
    override fun setState(state: FileEditorState) = Unit
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = true
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun <T : Any> getUserData(key: com.intellij.openapi.util.Key<T>): T? = null
    override fun <T : Any> putUserData(key: com.intellij.openapi.util.Key<T>, value: T?) = Unit
    override fun getFile(): VirtualFile = file
    override fun dispose() = panel.dispose()

    fun refresh() = panel.refresh()
}

/** Open a wide gallery tab; an already open tab is focused and refreshed. */
fun openTemplateGalleryEditor(project: Project) {
    val manager = FileEditorManager.getInstance(project)
    val existing = manager.openFiles.filterIsInstance<TemplateGalleryFile>().firstOrNull()
    val editors = manager.openFile(existing ?: TemplateGalleryFile(), true)
    if (existing != null) editors.filterIsInstance<TemplateGalleryEditor>().forEach { it.refresh() }
}

class OpenTemplateGalleryEditorAction : AnAction() {
    init {
        templatePresentation.text = OkScriptToolkitBundle.message("action.openTemplatesEditor.text")
        templatePresentation.description = OkScriptToolkitBundle.message("action.openTemplatesEditor.description")
    }

    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let(::openTemplateGalleryEditor)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
