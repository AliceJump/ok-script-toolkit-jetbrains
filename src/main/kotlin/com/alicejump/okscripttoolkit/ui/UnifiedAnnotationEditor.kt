package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.AnnotationUiBundle
import com.alicejump.okscripttoolkit.core.TemplateImage
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import java.beans.PropertyChangeListener
import javax.swing.Icon
import javax.swing.JComponent

internal object AnnotationEditorFileType : FileType {
    override fun getName() = "ok-script Annotation Editor"
    override fun getDisplayName() = AnnotationUiBundle.message("manager.title")
    override fun getDescription() = displayName
    override fun getDefaultExtension() = "okannotations"
    override fun getIcon(): Icon = com.intellij.openapi.util.IconLoader.getIcon("/icons/assets.svg", javaClass)
    override fun isBinary() = true
    override fun isReadOnly() = true
}

class AnnotationEditorFile internal constructor(val images: List<TemplateImage>, val startIndex: Int) :
    LightVirtualFile(AnnotationUiBundle.message("manager.title"), AnnotationEditorFileType, "") {
    init { isWritable = false }
}

class AnnotationEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file is AnnotationEditorFile
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = AnnotationEditor(project, file as AnnotationEditorFile)
    override fun getEditorTypeId() = "ok-script-annotation-editor"
    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

class AnnotationEditor(project: Project, private val file: AnnotationEditorFile) : UserDataHolderBase(), FileEditor {
    private val panel = UnifiedAnnotationPanel(project, file.images, file.startIndex)
    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusedComponent(): JComponent = panel.focusedComponent
    override fun getName() = AnnotationUiBundle.message("manager.title")
    override fun setState(state: FileEditorState) = Unit
    override fun isModified() = panel.hasUnsavedChanges
    override fun isValid() = true
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun getFile(): VirtualFile = file
    override fun dispose() = panel.dispose()
    fun showImage(images: List<TemplateImage>, index: Int) = panel.showImage(images, index)
}

internal fun openUnifiedAnnotationEditor(project: Project, images: List<TemplateImage>, index: Int) {
    if (images.isEmpty() || index !in images.indices) return
    val manager = FileEditorManager.getInstance(project)
    val existing = manager.openFiles.filterIsInstance<AnnotationEditorFile>().firstOrNull()
    val editors = manager.openFile(existing ?: AnnotationEditorFile(images, index), true)
    if (existing != null) editors.filterIsInstance<AnnotationEditor>().forEach { it.showImage(images, index) }
}
