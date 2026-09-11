package com.alicejump.okscripttoolkit.core

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 项目级临时截图存储（对齐 VSCode 版 tempScreenshotStore.ts）。
 *
 * 落盘位置：`<IDE system>/ok-script-toolkit/temp-screenshots/<项目哈希>/`，
 * 按项目隔离，避免多项目共享同一份临时截图。
 *
 * 「临时」体现在它是取材用的暂存区（最多 10 张、超出淘汰最早），而不是项目资产：
 * 需要长期保留的图要显式导入到 ok_templates（拖拽 / 右键菜单）。
 */
@Service(Service.Level.PROJECT)
class TempScreenshotStore(private val project: Project) : Disposable {

    companion object {
        private val LOG = Logger.getInstance(TempScreenshotStore::class.java)

        fun getInstance(project: Project): TempScreenshotStore = project.service()

        /** 按项目路径哈希隔离的存储目录 */
        fun directoryFor(project: Project): File {
            val base = project.basePath ?: "default"
            val hash = MessageDigest.getInstance("SHA-1")
                .digest(base.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(12)
            return Paths.get(PathManager.getSystemPath(), "ok-script-toolkit", "temp-screenshots", hash).toFile()
        }
    }

    private val files = TempShotFiles(directoryFor(project))
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    val directory: File get() = files.directory

    init {
        try {
            if (!directory.isDirectory && !directory.mkdirs()) {
                LOG.warn("Temp screenshot directory not writable: $directory")
            }
        } catch (e: Exception) {
            LOG.warn("Failed to prepare temp screenshot directory: $directory", e)
        }
    }

    fun list(): List<TempShot> = files.list()

    fun get(id: String): TempShot? = files.get(id)

    fun newFilePath(): File = files.newFilePath()

    fun register(file: File): TempShot? = files.register(file)?.also { notifyChanged() }

    fun writeImage(image: BufferedImage): TempShot? = files.writeImage(image)?.also { notifyChanged() }

    fun remove(id: String): Boolean =
        if (files.remove(id)) {
            notifyChanged()
            true
        } else {
            false
        }

    fun clear(): Int {
        val removed = files.clear()
        if (removed > 0) notifyChanged()
        return removed
    }

    /** 订阅增/删/清空。回调在调用线程同步触发。 */
    fun onChange(listener: () -> Unit): Disposable {
        listeners.add(listener)
        return Disposable { listeners.remove(listener) }
    }

    private fun notifyChanged() {
        for (listener in listeners) {
            runCatching { listener() }
        }
    }

    override fun dispose() {
        listeners.clear()
    }
}
