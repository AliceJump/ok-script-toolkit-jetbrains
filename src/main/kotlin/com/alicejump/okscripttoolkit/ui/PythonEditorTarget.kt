package com.alicejump.okscripttoolkit.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project

/** Shared target for template and box insertion, even after focus moves to a tool window. */
internal class PythonEditorTarget(private val project: Project, parent: Disposable) {
    private var recent: Editor? = current()

    init {
        project.messageBus.connect(parent).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    current()?.let { recent = it }
                }
            },
        )
    }

    fun editor(): Editor? = recent?.takeIf { !it.isDisposed } ?: current()

    private fun current(): Editor? = FileEditorManager.getInstance(project).selectedTextEditor
        ?.takeIf { !it.isDisposed && it.virtualFile?.extension?.equals("py", true) == true }
}
