package com.alicejump.okscripttoolkit.core

import com.alicejump.okscripttoolkit.TestTmp
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「导出到 assets」纯逻辑测试（`SaveToAssetsFlow`）。
 *
 * 背景：候选列表原先只有「保存目标」，枚举路径靠一个**追加项**兜底 —— 那条「改路径项
 * **当且仅当**跳过弹框时出现」的双向不变量曾是本文件的主要断言。现在列表改成了固定四行
 * （两个目标 + 枚举路径 + 枚举类名），不变量随之换成了另外三条，它们同属**改错也看不出来**：
 *
 * 1. **目标永远在前、顺序不变** —— 它们是这个对话框存在的理由。
 * 2. **两项枚举设置永远在，且靠下标认角色，不靠文案** —— 文案是本地化的，比较文案会在
 *    换语言时静默失效；而少了任一项，用户就再也改不了那一项。
 * 3. **首次仍然会先问一次路径** —— 「不问」会让从没配过的用户**静默拿不到枚举文件**。
 *
 * 另外两条是**路径安全**（对 VSCode 侧 `saveToAssetsPure` 的镜像）：
 * 用户输入不能是绝对路径 / 带 `..`（[labelEnumPathInputError]），而最终写入的绝对路径
 * 必须仍在项目根内（[isPathInsideRoot]）—— 后者还要挡「项目内的父目录是指向项目外的
 * 符号链接」这条绕路。
 */
class SaveToAssetsFlowTest {

    private val TARGETS = listOf("assets", "ok_tasks/assets")
    private val PATH_CHOICE = "Enum file path: "
    private val NAME_CHOICE = "Enum class name: "

    private fun options() = SaveToAssetsFlow.options(TARGETS, PATH_CHOICE, NAME_CHOICE)

    // ── 目标永远都在，且在最前面 ─────────────────────────────────────

    @Test
    fun `the export targets are always present and come first in order`() {
        val list = options().labels
        assertEquals(TARGETS, list.take(TARGETS.size), "两个目标都在，且顺序不变")
        assertEquals(
            TARGETS.size + 2,
            list.size,
            "目标之后**恒定**是「枚举路径」与「枚举类名」两项 —— 不能再有「有时有、有时没有」的项",
        )
    }

    // ── 两项枚举设置永远在，靠下标认角色 ─────────────────────────────

    @Test
    fun `both enum rows are always reachable and are identified by index`() {
        val o = options()
        assertEquals(SaveToAssetsFlow.Choice.TARGET, o.roleOf(0), "第 0 项是第一个目标")
        assertEquals(SaveToAssetsFlow.Choice.TARGET, o.roleOf(1), "第 1 项是第二个目标")
        assertEquals(SaveToAssetsFlow.Choice.ENUM_PATH, o.roleOf(2), "第 2 项（= targets.size）是「枚举路径」")
        assertEquals(SaveToAssetsFlow.Choice.ENUM_NAME, o.roleOf(3), "第 3 项是「枚举类名」")

        assertTrue(
            o.roleOf(2) != o.roleOf(0),
            "边界两边必须给出不同结论，否则下标判定形同虚设",
        )
    }

    /**
     * 角色判定必须**只认下标**。换成"比较文案"的写法，在换语言时（`Enum file path:` 变成
     * 「枚举文件路径：」）会静默失效 —— 那一项会被当成目标、点下去直接开始导出。
     */
    @Test
    fun `the role is decided by index, not by the localized label`() {
        val translated = SaveToAssetsFlow.options(TARGETS, "枚举文件路径：", "枚举类名：")
        assertEquals(SaveToAssetsFlow.Choice.ENUM_PATH, translated.roleOf(2), "换语言后角色不变")
        assertEquals(SaveToAssetsFlow.Choice.ENUM_NAME, translated.roleOf(3), "换语言后角色不变")
        assertEquals(
            translated.roleOf(2),
            options().roleOf(2),
            "文案变了、角色判定必须完全一致",
        )
    }

    // ── 一行的拼法：标签 + 值 ────────────────────────────────────────

    @Test
    fun `choice line appends the current value, or the empty placeholder`() {
        assertEquals("Enum file path: assets/LabelEnum.py", SaveToAssetsFlow.choiceLine(PATH_CHOICE, "assets/LabelEnum.py", "x"))
        assertEquals(
            "Enum file path: Not set — click to set",
            SaveToAssetsFlow.choiceLine(PATH_CHOICE, "", "Not set — click to set"),
            "值为空 → 用兜底文案，**必须让用户看到「这里能点」**",
        )
        assertEquals(
            "Enum file path: Not set — click to set",
            SaveToAssetsFlow.choiceLine(PATH_CHOICE, null, "Not set — click to set"),
            "null 与空串同义",
        )
        assertEquals(
            "Enum class name: ",
            SaveToAssetsFlow.choiceLine(NAME_CHOICE, "   ", "", ),
            "全空白也当作「没设置」（否则会拼出一行只有空格的值，看不见也点不准）",
        )
    }

    // ── 首次导出预填的推导值 ─────────────────────────────────────────

    @Test
    fun `the derived path is the target directory plus LabelEnum dot py`() {
        assertEquals("assets/LabelEnum.py", SaveToAssetsFlow.derivedEnumPath("assets"))
        assertEquals("ok_tasks/assets/LabelEnum.py", SaveToAssetsFlow.derivedEnumPath("ok_tasks/assets"))
        assertEquals(
            "assets/LabelEnum.py",
            SaveToAssetsFlow.derivedEnumPath("assets/"),
            "尾斜杠不会拼出双斜杠",
        )
        assertEquals(
            "LabelEnum.py",
            SaveToAssetsFlow.derivedEnumPath(""),
            "空目标 → 退回裸文件名，而不是以斜杠开头（那会变成绝对路径）",
        )
    }

    // ── 什么时候还要先问一次路径 ─────────────────────────────────────

    @Test
    fun `the prompt only happens when nothing has been decided yet`() {
        assertTrue(
            SaveToAssetsFlow.needsEnumPathPrompt(null),
            "从没定过 → 必须问（默认值是推导出来的，跳过它用户就没机会改成别的路径）",
        )
        assertTrue(
            !SaveToAssetsFlow.needsEnumPathPrompt(null, decided = true),
            "**用户显式清空过 → 不问**：那表达的是「回到项目约定」；再问一遍会变成「清空了还被追着问」",
        )
        assertTrue(
            !SaveToAssetsFlow.needsEnumPathPrompt("src/data/LabelEnum.py"),
            "已经有生效路径 → 不问（自己定的 / 团队约定好的值，每次导出都确认一遍是纯噪音）",
        )
        assertTrue(
            !SaveToAssetsFlow.needsEnumPathPrompt("src/data/LabelEnum.py", decided = true),
            "有值 + 定过 → 同样不问（decided 只用来抑制追问，不会让有值的情况变成要问）",
        )
    }

    /**
     * [SaveToAssetsFlow.needsEnumPathPrompt] 收到的是 `String?`，调用方传的是
     * `rel.takeIf { it.isNotEmpty() }`。这条钉住"空串 = 没定过"这一步转换 ——
     * 少了它，清空过的路径会被当成"有值"，追问照旧弹出来。
     */
    @Test
    fun `an empty path counts as undecided when it reaches the prompt check`() {
        assertTrue(SaveToAssetsFlow.needsEnumPathPrompt("".takeIf { it.isNotEmpty() }), "空串 → null → 仍然要问")
        assertTrue(
            !SaveToAssetsFlow.needsEnumPathPrompt("assets/LabelEnum.py".takeIf { it.isNotEmpty() }),
            "非空 → 不问",
        )
    }

    // ── 路径存成相对、解析回绝对 ─────────────────────────────────────

    /**
     * 设置里存的值必须**相对项目根**（换个检出目录、或同事用同一份配置都还有效），
     * 而导出时需要一个绝对路径。这两步是纯路径运算，所以放在纯对象里钉住。
     */
    private val root: String = Paths.get("").toAbsolutePath().normalize().toString()

    private fun underRoot(vararg parts: String): String = Paths.get(root, *parts).toString()

    @Test
    fun `a relative input is stored unchanged`() {
        assertEquals(
            "src/data/LabelEnum.py",
            SaveToAssetsFlow.toProjectRelative(root, "src/data/LabelEnum.py"),
            "本来就是相对路径 → 原样返回，不要自作聪明",
        )
    }

    @Test
    fun `an absolute path inside the project is stored relative`() {
        assertEquals(
            "src/data/LabelEnum.py",
            SaveToAssetsFlow.toProjectRelative(root, underRoot("src", "data", "LabelEnum.py")),
            "**存绝对路径的话，换个检出目录就指向了不存在的地方**",
        )
    }

    @Test
    fun `an absolute path outside the project cannot be relativised and is kept`() {
        val outside = Paths.get(root, "..", "other_proj", "LabelEnum.py").toAbsolutePath().normalize().toString()
        assertEquals(
            outside,
            SaveToAssetsFlow.toProjectRelative(root, outside),
            "项目外的绝对路径相对化不了 —— 原样存，由 toAbsolute 按「绝对优先」处理",
        )
    }

    @Test
    fun `toAbsolute resolves relative values and passes absolute ones through`() {
        val abs = underRoot("src", "data", "LabelEnum.py")
        assertEquals(abs, SaveToAssetsFlow.toAbsolute(root, "src/data/LabelEnum.py"), "相对值按项目根解析")
        assertEquals(
            abs,
            SaveToAssetsFlow.toAbsolute("D:/somewhere/else", abs),
            "**已经是绝对路径的原样返回** —— 否则会被拼成 `<项目根>/D:/other/x.py`，报一个看不懂的错",
        )
    }

    @Test
    fun `store then resolve round-trips to the same absolute path`() {
        val abs = underRoot("src", "data", "LabelEnum.py")
        assertEquals(
            abs,
            SaveToAssetsFlow.toAbsolute(root, SaveToAssetsFlow.toProjectRelative(root, abs)),
            "**存进去再取出来必须是同一个文件** —— 这两步是配对的，单独改任一个都会静默指到别处",
        )
    }

    // ── 写入前的边界复核（含符号链接） ───────────────────────────────

    /** 建一棵假项目树：`proj/assets` 存在、`evil` 在项目外，`proj/link` 指向 `evil`。 */
    private fun withFakeProject(block: (proj: java.io.File, outside: java.io.File) -> Unit) {
        val base = TestTmp.create("okTplFlow")
        try {
            val proj = java.io.File(base, "proj").apply { mkdirs() }
            java.io.File(proj, "assets").mkdirs()
            val outside = java.io.File(base, "evil").apply { mkdirs() }
            block(proj, outside)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `paths inside the project are accepted`() {
        withFakeProject { proj, _ ->
            assertTrue(isPathInsideRoot(proj.path, "assets/LabelEnum.py"), "相对路径按项目根解析")
            assertTrue(
                isPathInsideRoot(proj.path, java.io.File(proj, "assets/LabelEnum.py").path),
                "项目内的绝对路径",
            )
            assertTrue(
                isPathInsideRoot(
                    proj.path,
                    java.io.File(java.io.File(proj, "a/b"), "c.py").path,
                ),
                "**目标还不存在**（多级新目录）也要能判 —— 枚举文件通常就是还没生成",
            )
            assertTrue(isPathInsideRoot(proj.path, proj.path), "项目根本身算在内")
        }
    }

    @Test
    fun `traversal and sibling-prefix paths are rejected`() {
        withFakeProject { proj, outside ->
            assertFalse(
                isPathInsideRoot(proj.path, java.io.File(outside, "x.py").path),
                "项目外的绝对路径",
            )
            assertFalse(
                isPathInsideRoot(proj.path, "../evil/x.py"),
                "相对写法上跳",
            )
            assertFalse(
                isPathInsideRoot(proj.path, java.io.File(proj.parentFile, "projEvil/x.py").path),
                "**前缀相同但不是子目录**（`projEvil` 不是 `proj` 的下级）—— 字符串比较会误放",
            )
        }
    }

    /**
     * 符号链接绕路：`proj/link` 指向项目外，`proj/link/x.py` 的**词法**路径明明在项目内。
     * 只看字符串（或 `File.canonicalPath`：对不存在的路径不解析链接）会放行，
     * 于是 `writeFileSync` 把文件写到了项目外面。
     *
     * CI 是 ubuntu-24.04，一定跑得到；Windows 上需要开发者模式/管理员权限才能建链接，
     * 建不出来时**显式跳过并打印原因**，而不是让断言静默恒真。
     */
    @Test
    fun `a symlink escaping the project is rejected`() {
        withFakeProject { proj, outside ->
            val link = java.io.File(proj, "link")
            try {
                Files.createSymbolicLink(link.toPath(), outside.toPath())
            } catch (e: Exception) {
                println("跳过符号链接断言：当前环境不支持创建符号链接（${e.javaClass.simpleName}）")
                return@withFakeProject
            }
            assertTrue(Files.isSymbolicLink(link.toPath()), "前提：链接真的建出来了")
            assertFalse(
                isPathInsideRoot(proj.path, java.io.File(link, "x.py").path),
                "**链接指向项目外 → 必须拒绝**（这一步只靠词法路径是抓不到的）",
            )
        }
    }
}
