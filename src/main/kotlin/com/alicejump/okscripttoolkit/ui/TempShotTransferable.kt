package com.alicejump.okscripttoolkit.ui

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File

/**
 * 「临时截图 → 标注管理」拖拽载荷。
 *
 * 与 VSCode 端不同：JetBrains 的两个工具窗口在同一个 JVM、同一个 Swing 窗口内，
 * 因此可以用自定义 DataFlavor 直接把文件路径传给放置目标，**不需要宿主中继**
 * （VSCode 各 webview 是不同 origin 的 iframe，dataTransfer 数据会被浏览器屏蔽）。
 */
class TempShotTransferable(private val file: File) : Transferable {

    companion object {
        /** JVM 内部自定义 flavor：MIME 未注册时仅在本 JVM 内可用，正好满足需求 */
        val FLAVOR: DataFlavor = DataFlavor("application/x-ok-temp-screenshot;class=java.lang.String")

        /** 从 Transferable 里取出临时截图文件，不支持或失败返回 null */
        fun fileOf(transferable: Transferable): File? {
            if (!transferable.isDataFlavorSupported(FLAVOR)) return null
            return try {
                (transferable.getTransferData(FLAVOR) as? String)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { File(it) }
                    ?.takeIf { it.isFile }
            } catch (_: Exception) {
                null
            }
        }
    }

    override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(FLAVOR)

    override fun isDataFlavorSupported(flavor: DataFlavor?): Boolean = FLAVOR == flavor

    override fun getTransferData(flavor: DataFlavor?): Any = file.absolutePath
}
