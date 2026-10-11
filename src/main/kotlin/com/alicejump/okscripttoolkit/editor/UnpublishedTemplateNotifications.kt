package com.alicejump.okscripttoolkit.editor

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.alicejump.okscripttoolkit.core.OkDataChangeListener
import com.alicejump.okscripttoolkit.core.OkDataChangeService
import com.alicejump.okscripttoolkit.core.OkProjectDetection
import com.alicejump.okscripttoolkit.core.TemplateReferences
import com.alicejump.okscripttoolkit.settings.OkScriptToolkitSettings
import com.alicejump.okscripttoolkit.ui.OkScriptToolWindows
import com.alicejump.okscripttoolkit.ui.ResourceSection
import com.alicejump.okscripttoolkit.ui.unpublishedTemplateNames
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import java.util.function.Function
import javax.swing.JComponent

/** 未发布模板名的缓存：数据变化时后台重算，再刷新编辑器横幅。 */
@Service(Service.Level.PROJECT)
class UnpublishedTemplatesCache(private val project: Project) : Disposable {
    @Volatile private var names: Set<String>? = null

    init {
        project.service<OkDataChangeService>()
        project.messageBus.connect(this).subscribe(OkDataChangeService.TOPIC, OkDataChangeListener { refresh() })
    }

    /** 已算好就返回；首次调用触发后台计算并先返回空。 */
    fun current(): Set<String> = names ?: emptySet<String>().also { if (names == null) refresh() }

    fun refresh() {
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            val fresh = runCatching { unpublishedTemplateNames(project).toSet() }.getOrDefault(emptySet())
            val changed = fresh != names
            names = fresh
            if (changed && !project.isDisposed) EditorNotifications.getInstance(project).updateAllNotifications()
        }
    }

    override fun dispose() = Unit
}

/** 当前 .py 引用了尚未发布的模板时，在编辑器顶部给出横幅（预览并发布 / 在资源中查看）。 */
class UnpublishedTemplateNotificationProvider : EditorNotificationProvider {
    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        if (file.extension != "py" || !OkProjectDetection.isOkScriptProject(project)) return null
        val unpublished = project.service<UnpublishedTemplatesCache>().current()
        if (unpublished.isEmpty()) return null
        val source = FileDocumentManager.getInstance().getDocument(file)?.text ?: return null
        val missing = referencedUnpublished(source, unpublished, OkScriptToolkitSettings.getInstance(project).featureAliases())
        if (missing.isEmpty()) return null
        return Function { editor ->
            EditorNotificationPanel(editor, EditorNotificationPanel.Status.Warning).apply {
                text = OkScriptToolkitBundle.message("unpublishedBanner.text", missing.size, missing.take(3).joinToString(", "))
                createActionLabel(OkScriptToolkitBundle.message("okscript.resources.previewPublish")) {
                    OkScriptToolWindows.withResources(project, ResourceSection.DRAFT) { it.publish() }
                }
                createActionLabel(OkScriptToolkitBundle.message("unpublishedBanner.show")) {
                    OkScriptToolWindows.show(project, com.alicejump.okscripttoolkit.ui.OkTab.RESOURCES, ResourceSection.DRAFT)
                }
            }
        }
    }

    companion object {
        /** 文件里以特征别名引用、且只在草稿里的模板名（纯函数，单测覆盖）。 */
        fun referencedUnpublished(text: String, unpublished: Set<String>, aliases: List<String>): List<String> =
            unpublished.filter { name -> TemplateReferences.find(text, name, aliases).any { it.kind == TemplateReferences.Kind.FEATURE } }.sorted()
    }
}
