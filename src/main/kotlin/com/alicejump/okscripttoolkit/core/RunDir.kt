package com.alicejump.okscripttoolkit.core

import java.io.File

/**
 * 宿主沙箱根目录的**单一事实来源**。
 *
 * 三处必须一致，任何一处写死都会静默错位：
 *  1. 插件侧：启动执行器 / 探测探针时经环境变量传入（[ENV]）；
 *  2. 脚本侧：`run_executor.py` / `account_store.py` / `probe_task_schemas.py`
 *     都读同一个变量名，不设时各自退回自己的历史默认值；
 *  3. 落点：本插件的任务配置、schema 缓存、账号覆盖都在这个目录下。
 *
 * ⚠️ 两端沙箱目录**不同名**：VS Code 是 `.vscode/ok-script-toolkit`，JetBrains 是
 * `.idea/ok-script-toolkit`。探针曾把 VS Code 那个写死，于是 JetBrains 侧拿到的
 * `multiAccount.storePath` 指向一个既不存在、也永远不会被读写的路径。
 *
 * 这里刻意**不 import `com.intellij.*`** —— 保持可独立单测（同 [PythonScriptUtils]）。
 */
object RunDir {

    /** 宿主告知脚本沙箱根目录的环境变量名（三端共用同一字面量）。 */
    const val ENV = "OK_TOOLKIT_RUN_DIR"

    /** 本插件（JetBrains）沙箱根目录相对项目根的路径。 */
    const val RELATIVE = ".idea/ok-script-toolkit"

    /**
     * 本插件在 [projectDir] 下的沙箱根目录绝对路径。
     *
     * 用 `File(...).path` 而非字符串拼接：Windows 上得到反斜杠形式，与既有行为一致
     * （[File] 也是执行器/探针侧统一用的路径构造方式）。
     */
    fun forProject(projectDir: String): String = File(projectDir, RELATIVE).path
}
