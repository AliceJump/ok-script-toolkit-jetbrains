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
import java.util.concurrent.atomic.AtomicLong
import javax.swing.Timer

/** 数据文件变化回调：工具窗面板借此自动刷新（对应 VSCode 版的 FileSystemWatcher 派发） */
fun interface OkDataChangeListener {
    fun dataChanged()
}

/**
 * ok-script 项目数据文件监听（项目级）：语言 JSON / gettext PO / COCO 与模板图 /
 * effects.py / 角色数据任一变化时，300ms 防抖后使数据快照失效并广播 [OkDataChangeListener]。
 *
 * 对齐 VSCode 版 extension.ts 的 createFileSystemWatcher 派发：编辑器侧数据本就按
 * 时间戳懒刷新，这里主要让工具窗面板无需手动点击刷新。
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

    /** EDT 防抖计时器（VFS 事件线程触发、面板刷新统一切回 EDT） */
    private val debounceTimer = Timer(DEBOUNCE_MS) { fire() }

    private val connection = project.messageBus.connect(this)

    init {
        debounceTimer.isRepeats = false
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any { isRelevant(it.file?.path) }) schedule()
            }
        })
    }

    /** 相对项目根的路径是否属于被监听的数据源（对齐 VSCode getAffectedSources） */
    private fun isRelevant(path: String?): Boolean {
        val normalized = path?.replace('\\', '/') ?: return false
        val basePath = project.basePath?.replace('\\', '/')?.trimEnd('/') ?: return false
        val lowerBase = basePath.lowercase()
        val rel = when {
            normalized.length > lowerBase.length + 1 &&
                normalized.lowercase().startsWith("$lowerBase/") ->
                normalized.substring(lowerBase.length + 1)
            normalized.lowercase() == lowerBase -> return false
            // 非项目内路径（effectsFile 可能配置为项目外绝对路径）按原样参与比较
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
        if (rel == "assets/coco_annotations.json" || rel == "ok_tasks/assets/coco_annotations.json") return true
        if (dirMatches("assets/images", ".png") || dirMatches("ok_tasks/assets/images", ".png")) return true
        if (dirMatches(settings.okTemplatesDirectory(), ".png")) return true

        val effectsFile = exact(settings.effectsFile())
        if (rel.equals(effectsFile, ignoreCase = isWindows())) return true
        return false
    }

    /** 防抖：密集保存只触发一轮刷新；持续变化最多延迟 [DEBOUNCE_MAX_WAIT_MS] 后必发 */
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
        // 面板的 loadData 自带后台加载；统一在 EDT 上广播
        UIUtil.invokeLaterIfNeeded {
            project.messageBus.syncPublisher(TOPIC).dataChanged()
        }
    }

    override fun dispose() {
        debounceTimer.stop()
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")
}
