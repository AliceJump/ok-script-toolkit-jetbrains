package com.alicejump.okscripttoolkit.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ok-script 项目根解析规则的回归测试。
 *
 * 抽出这个纯对象是为了修一个真实缺陷：插件里原本有**三份**项目根解析实现 ——
 * `ScreenshotCapture.detectProjectDir`、`TaskLauncherToolWindowFactory.detectProjectPath`
 * 是「设置优先 + 校验 config.py」，而 `TaskLauncherService.getProjectRoot()` **只取
 * `project.basePath`**。任务启动器真正跑 `parse_config_tasks.py` 用的是后者，
 * 于是设置了 `okScriptProjectPath` 时会出现「界面按设置判定、脚本却去工作区根找
 * config.py」——两边看的不是同一个项目。
 *
 * 这些用例把规则钉死，避免以后又有人复制出第四份。
 */
class ProjectDirResolutionTest {

    private val home = "C:/Users/tester"

    /** 只把给定的几个路径当作真实目录。 */
    private fun dirs(vararg paths: String): (String) -> Boolean {
        val set = paths.toSet()
        return { it in set }
    }

    /** 只把给定的几个目录当作含 config.py。 */
    private fun configs(vararg paths: String): (String) -> Boolean {
        val set = paths.toSet()
        return { it in set }
    }

    private fun resolve(
        configured: String = "",
        basePath: String = "",
        isDirectory: (String) -> Boolean = dirs(),
        hasConfigFile: (String) -> Boolean = configs(),
    ) = ProjectDirResolution.resolve(configured, basePath, home, isDirectory, hasConfigFile)

    @Test
    fun `configured path wins over the workspace root`() {
        assertEquals(
            "D:/proj/ok-end-field",
            resolve(
                configured = "D:/proj/ok-end-field",
                basePath = "D:/proj",
                isDirectory = dirs("D:/proj/ok-end-field", "D:/proj"),
                hasConfigFile = configs("D:/proj/ok-end-field"),
            ),
            "设置项存在且是目录时必须优先 —— 这正是原来被忽略的那条路径",
        )
    }

    @Test
    fun `tilde is expanded against the home directory`() {
        assertEquals(
            "$home/proj/ok-end-field",
            resolve(
                configured = "~/proj/ok-end-field",
                isDirectory = dirs("$home/proj/ok-end-field"),
            ),
            "`~` 必须展开，否则用户按文档写法填的路径会被判为不存在",
        )
    }

    @Test
    fun `trailing separators are trimmed`() {
        assertEquals(
            "D:/proj/ok",
            resolve(configured = "D:/proj/ok/", isDirectory = dirs("D:/proj/ok")),
            "结尾的斜杠要去掉：它会被当作路径的一部分传进脚本，也影响缓存键比较",
        )
        assertEquals(
            "D:/proj/ok",
            resolve(configured = "D:/proj/ok\\", isDirectory = dirs("D:/proj/ok")),
            "反斜杠同样要处理（Windows 用户常这么写）",
        )
    }

    @Test
    fun `falls back to the workspace root when the setting is unusable`() {
        assertEquals(
            "D:/proj/ok-end-field",
            resolve(
                configured = "D:/nonexistent",
                basePath = "D:/proj/ok-end-field",
                isDirectory = dirs(),
                hasConfigFile = configs("D:/proj/ok-end-field"),
            ),
            "设置项指向不存在的目录时不应卡死，要退回工作区根",
        )
        assertEquals(
            "D:/proj/ok-end-field",
            resolve(
                configured = "",
                basePath = "D:/proj/ok-end-field",
                hasConfigFile = configs("D:/proj/ok-end-field"),
            ),
            "未设置时用工作区根",
        )
    }

    /**
     * 工作区根**必须含 `config.py`** 才算项目根。
     *
     * 这条是"打开父目录"场景的守门人：如果只是盲目返回 `basePath`，
     * 就会把一个普通文件夹当成 ok-script 项目，脚本随后报「找不到 config.py」。
     * 返回空串能让上层给出「未找到项目」的明确提示。
     */
    @Test
    fun `workspace root without config file is not a project root`() {
        assertEquals(
            "",
            resolve(basePath = "D:/some/random/folder", hasConfigFile = configs()),
            "没有 config.py 的工作区根不能当成项目根，应返回空串让上层提示",
        )
    }

    @Test
    fun `returns empty when nothing resolves`() {
        assertEquals("", resolve(), "全空输入必须安全返回空串，而不是抛异常")
        assertEquals("", resolve(basePath = ""), "basePath 为空同理")
    }

    /**
     * 破坏性对照：证明「设置优先」这条断言不是空过。
     *
     * 把实现换成"只用 basePath"（= 修复前服务层的行为），同一个输入会得到
     * 工作区根而不是配置目录 —— 两者不同，说明这条优先级确实有意义。
     */
    @Test
    fun `regression guard - ignoring the setting would resolve to the workspace root`() {
        val configured = "D:/proj/ok-end-field"
        val basePath = "D:/proj"
        val isDirectory = dirs(configured, basePath)
        val hasConfigFile = configs(configured, basePath)

        val onlyBasePath = if (basePath.isNotBlank() && hasConfigFile(basePath)) basePath else ""
        val real = resolve(
            configured = configured,
            basePath = basePath,
            isDirectory = isDirectory,
            hasConfigFile = hasConfigFile,
        )

        assertEquals("D:/proj", onlyBasePath, "对照实现（忽略设置）确实会落到工作区根")
        assertEquals(configured, real, "真实现必须落到配置目录 —— 否则等于没修")
    }
}
