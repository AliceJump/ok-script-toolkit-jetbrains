package com.alicejump.okscripttoolkit.core

import com.alicejump.okscripttoolkit.settings.OkScriptToolkitSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.ui.UIUtil
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import javax.swing.Timer

internal fun samePath(a: Path, b: Path): Boolean {
    val left = a.toAbsolutePath().normalize()
    val right = b.toAbsolutePath().normalize()
    if (Files.exists(left) && Files.exists(right)) {
        runCatching { if (Files.isSameFile(left, right)) return true }
    }
    val windows = System.getProperty("os.name", "").contains("win", ignoreCase = true)
    return if (windows) left.toString().equals(right.toString(), ignoreCase = true) else left == right
}

internal fun isTemplateImageChange(path: Path, directory: Path): Boolean =
    path.fileName.toString().substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "bmp") &&
        path.parent?.let { samePath(it, directory) } == true

/** 数据文件变化回调：工具窗面板借此自动刷新（对应 VSCode 版的 FileSystemWatcher 派发） */
fun interface OkDataChangeListener {
    fun dataChanged()
}

/**
 * ok-script 项目数据文件监听（项目级）：语言 JSON / gettext PO / COCO 与模板图 /
 * effects.py / 角色数据任一变化时，300ms 防抖后使数据快照失效并广播 [OkDataChangeListener]。
 */
@Service(Service.Level.PROJECT)
class OkDataChangeService(private val project: Project) : Disposable {

    companion object {
        val TOPIC = Topic.create("ok-script data changed", OkDataChangeListener::class.java)
        private const val DEBOUNCE_MS = 300
        private const val DEBOUNCE_MAX_WAIT_MS = 1500
    }

    private val settings: OkScriptToolkitSettings = OkScriptToolkitSettings.getInstance(project)
    private val dataService: OkProjectDataService = project.service()
    private val firstPendingAt = AtomicLong(0)
    private val debounceTimer = Timer(DEBOUNCE_MS) { fire() }
    private val connection = project.messageBus.connect(this)
    private val annotationChanges = AnnotationDataChanges.subscribe { path ->
        if (isRelevant(path.toString())) UIUtil.invokeLaterIfNeeded { schedule() }
    }

    init {
        debounceTimer.isRepeats = false
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                for (event in events) {
                    if (isRelevant(event.path)) {
                        AnnotationDataChanges.notify(java.nio.file.Paths.get(event.path))
                        UIUtil.invokeLaterIfNeeded { schedule() }
                    }
                }
            }
        })
    }

    /** 相对项目根的路径是否属于被监听的数据源（对齐 VSCode getAffectedSources） */
    private fun isRelevant(path: String?): Boolean {
        val normalized = path?.replace('\\', '/') ?: return false
        val basePath = project.basePath?.replace('\\', '/')?.trimEnd('/') ?: return false
        val lowerBase = basePath.lowercase()
        val rel = when {
            normalized.length > lowerBase.length + 1 && normalized.lowercase().startsWith("$lowerBase/") ->
                normalized.substring(lowerBase.length + 1)
            normalized.lowercase() == lowerBase -> return false
            else -> normalized
        }.trimStart('/')

        fun dirMatches(dir: String, suffix: String): Boolean {
            val clean = dir.replace('\\', '/').trim('/', ' ')
            return rel.startsWith("$clean/") && rel.endsWith(suffix)
        }

        fun exact(value: String): String = value.replace('\\', '/').trim('/')

        if (dirMatches(settings.langDirectory(), ".json")) return true
        if (dirMatches(settings.poDirectory(), ".po")) return true
        if (dirMatches(settings.characterSkillsDirectory(), ".json")) return true
        if (rel == exact(settings.characterMasterFile())) return true
        if (rel == exact(settings.characterLocaleFile())) return true
        if (rel in dataService.cocoFeatureRelPaths()) return true

        val templateDir = exact(settings.okTemplatesDirectory())
        if (rel in setOf(
                "$templateDir/boxes.json",
                "$templateDir/points.json",
                "$templateDir/coco_annotations.json",
            )) return true

        val root = dataService.rootPath()
        if (root != null && listOf("boxes.json", "points.json", "coco_annotations.json").any { file ->
                samePath(java.nio.file.Paths.get(normalized), root.resolve(settings.okTemplatesDirectory()).resolve(file))
            }) return true

        if (rel == "config.py" || rel == "src/config.py") {
            dataService.ensureCocoFeatureProbed(force = true)
            return false
        }
        if (dirMatches("assets/images", ".png") || dirMatches("ok_tasks/assets/images", ".png")) return true
        val templatesPath = CocoAnnotationData.templateDir(root?.toString() ?: basePath, settings.okTemplatesDirectory())
        if (isTemplateImageChange(java.nio.file.Paths.get(normalized), templatesPath)) return true

        val effectsFile = exact(settings.effectsFile())
        if (rel.equals(effectsFile, ignoreCase = isWindows())) return true
        return false
    }

    private fun schedule() {
        val now = System.currentTimeMillis()
        firstPendingAt.compareAndSet(0, now)
        val elapsed = now - firstPendingAt.get()
        val delay = if (elapsed >= DEBOUNCE_MAX_WAIT_MS) 50 else DEBOUNCE_MS
        debounceTimer.delay = delay
        debounceTimer.restart()
    }

    private fun fire() {
        firstPendingAt.set(0)
        if (project.isDisposed) return
        dataService.invalidate()
        UIUtil.invokeLaterIfNeeded {
            project.messageBus.syncPublisher(TOPIC).dataChanged()
        }
    }

    override fun dispose() {
        debounceTimer.stop()
        annotationChanges.close()
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")
}
