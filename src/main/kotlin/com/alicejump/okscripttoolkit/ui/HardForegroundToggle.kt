package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.alicejump.okscripttoolkit.settings.OkScriptToolkitSettings
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import javax.swing.JCheckBox

/**
 * 截图入口共用的「硬前台」复选框（对齐 VSCode 端两个 webview 里的同名复选框）。
 *
 * 勾选时本次截图强制走 --method foreground：激活游戏窗口并临时置顶再读屏幕，
 * 用于后台截图（PrintWindow / WGC）出来是空白的启动器／登录界面；会抢焦点，
 * 所以只做单次覆盖，不改设置项里的 captureMethod。
 *
 * 勾选状态按项目持久化（PropertiesComponent），对应 VSCode 的 webview state。
 */
object HardForegroundToggle {

    private const val KEY = "okScriptToolkit.hardForeground"

    fun create(project: Project): JCheckBox =
        JCheckBox(OkScriptToolkitBundle.message("capture.hardForeground")).apply {
            toolTipText = OkScriptToolkitBundle.message("capture.hardForegroundTooltip")
            isFocusable = false
            isSelected = PropertiesComponent.getInstance(project).getBoolean(KEY, false)
            addActionListener {
                PropertiesComponent.getInstance(project).setValue(KEY, isSelected, false)
            }
        }

    /** 复选框 → capture_game_window.py 的 --method 覆盖值；未勾选返回 null（回退设置项） */
    fun methodOverride(check: JCheckBox): String? =
        if (check.isSelected) OkScriptToolkitSettings.CAPTURE_METHOD_FOREGROUND else null
}
