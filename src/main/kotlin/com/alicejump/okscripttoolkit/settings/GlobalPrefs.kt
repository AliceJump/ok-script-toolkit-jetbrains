package com.alicejump.okscripttoolkit.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * **Application 级**（IDE 全局）个人偏好。
 *
 * 与 [OkScriptToolkitSettings]（PROJECT 级、走「个人偏好 > 项目约定 > 兜底」取值链）
 * 刻意分开：这里的项是**跟着人走、不跟项目走**的习惯 —— 比如复制坐标时分隔符要不要
 * 带空格，同一个项目里不同人可以有不同口味，项目约定文件不该也不需要管它。
 *
 * 存在独立的 `ok-script-toolkit-global.xml`，与项目级配置互不覆盖。
 */
@Service(Service.Level.APP)
@State(
    name = "com.alicejump.okscripttoolkit.settings.GlobalPrefs",
    storages = [Storage("ok-script-toolkit-global.xml")],
)
class GlobalPrefs : SimplePersistentStateComponent<GlobalPrefs.State>(State()) {
    class State : BaseState() {
        /** 复制归一化坐标时逗号后加空格（`x, y, tox, toy`）；关闭则为 `x,y,tox,toy` */
        var copyCoordsSpace by property(true)
    }

    /** 坐标分隔符：偏好开 → `", "`；关 → `","`。消费端直接喂 [NormalizedBox.format]。 */
    fun copyCoordsSeparator(): String = if (state.copyCoordsSpace) ", " else ","

    companion object {
        fun getInstance(): GlobalPrefs = service()
    }
}
