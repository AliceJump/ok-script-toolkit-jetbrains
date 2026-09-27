package com.alicejump.okscripttoolkit.core

import com.alicejump.okscripttoolkit.TestTmp
import java.io.File
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [RunDir] 的契约测试。
 *
 * 这里钉的是**跨语言契约**，不是路径拼接的美观问题：
 *  1. 环境变量名 `OK_TOOLKIT_RUN_DIR` 由 Kotlin 写、由 Python 读（`os.environ.get(...)`）。
 *     改一侧不改另一侧 **不会有任何报错** —— Python 拿不到变量就静默退回自己的历史默认值，
 *     而那个默认值是**另一个宿主的目录**（`.vscode/...`）。表现为：JetBrains 用户看到
 *     「打开数据位置」指向一个既不存在、也永不被读写的路径。
 *  2. 沙箱目录名两端**刻意不同**（VS Code `.vscode/`、JetBrains `.idea/`），
 *     所以"把 Kotlin 的值抄成 Python 的默认值"同样是 bug，不是简化。
 *
 * 断言对象取自 **classpath 上的打包脚本**（`build.gradle.kts` 的 `copyPythonScripts`
 * 从父仓 `../python` 同步进来），因此校验的是**真正会随插件发布的那份**。
 *
 * ⚠️ `jetbrains/` 是独立公开仓库，其 CI 只检出自己，`../python` 不可见。只有这种没有
 * 脚本源的情况才用 `assumeTrue` **跳过**（而不是 `return`，也不是让断言恒真）。父仓 CI
 * 带 submodules 检出后，classpath 上缺脚本会直接失败，确保 `copyPythonScripts` 的打包回归
 * 不会掩盖跨语言断言。判据与生产代码同源（`PythonScriptLocator.BUNDLED_SCRIPTS.first()`），
 * 见 [PythonScriptLocatorTest]。
 */
class RunDirTest {

    private val parentPythonSourcePresent: Boolean
        get() = File(System.getProperty("user.dir"), "../python").toPath().normalize().toFile().isDirectory

    private val bundledScriptsPresent: Boolean
        get() = PythonScriptLocator::class.java.classLoader
            .getResource("python/${PythonScriptLocator.BUNDLED_SCRIPTS.first()}") != null

    private fun requireBundledScripts() {
        assumeTrue(
            parentPythonSourcePresent,
            "父仓的 python/ 源目录不存在 —— 只检出了 jetbrains 子仓库，跳过跨语言断言。",
        )
        assertTrue(
            bundledScriptsPresent,
            "父仓的 python/ 源目录存在，但 classpath 上没有 python/*.py —— " +
                "copyPythonScripts 未将脚本打包进测试资源。",
        )
    }

    private fun bundledScript(name: String): String? =
        PythonScriptLocator::class.java.classLoader
            .getResourceAsStream("python/$name")
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }

    // ── 1. 环境变量名：Kotlin 写的必须等于 Python 读的 ────────────────────

    @Test
    fun `shared Python runtime uses the environment name Kotlin writes`() {
        requireBundledScripts()
        val core = assertNotNull(bundledScript("project_runtime.py"))
        val declared = Regex("""RUN_DIR_ENV\s*=\s*["']([^"']+)["']""").find(core)?.groupValues?.get(1)
        assertEquals(RunDir.ENV, declared, "两端必须读取同一个环境变量")
        assertTrue("os.environ.get(RUN_DIR_ENV" in core)
    }

    @Test
    fun `probe executor and account gateway use the shared sandbox contract`() {
        requireBundledScripts()
        val probe = assertNotNull(bundledScript("probe_task_schemas.py"))
        val executor = assertNotNull(bundledScript("run_executor.py"))
        val account = assertNotNull(bundledScript("account_store.py"))
        assertTrue("from project_runtime import" in probe && "resolve_run_dir(project_dir)" in probe)
        assertTrue("from project_runtime import RUN_DIR_ENV" in executor && "os.environ.get(RUN_DIR_ENV" in executor)
        assertTrue("from project_runtime import RUN_DIR_ENV" in account && "os.environ.get(RUN_DIR_ENV" in account)
    }

    @Test
    fun `account store keeps the old CLI flag for compatibility`() {
        requireBundledScripts()
        val text = bundledScript("account_store.py")
        assertNotNull(text, "account_store.py 必须能从 classpath 读到")
        assertTrue(text.isNotBlank(), "读出来是空的 —— 断言会退化成恒真")

        assertTrue(
            "--run-dir" in text,
            "已有的脚本调用方仍可传 --run-dir",
        )
    }

    // ── 2. 沙箱目录名：两端刻意不同 ──────────────────────────────────────

    @Test
    fun `relative sandbox dir is the JetBrains one and never the VS Code one`() {
        assertEquals(
            ".idea/ok-script-toolkit", RunDir.RELATIVE,
            "JetBrains 侧沙箱相对路径是 .idea/ok-script-toolkit（与 .idea 下其他数据文件并列）",
        )
        assertTrue(".idea" in RunDir.RELATIVE, "必须是 .idea 下的目录")
        assertFalse(
            ".vscode" in RunDir.RELATIVE,
            "不能出现 .vscode —— 那是 VS Code 宿主的目录，正是探针写死它才出的错",
        )
    }

    /**
     * Python 侧的历史默认值必须**恰好是另一个宿主的目录**。
     *
     * 这条不是吹毛求疵：正因为默认值指向 `.vscode`，Kotlin 侧一旦不传变量，
     * 错误就会以"路径看着挺正常"的形式静默发生。若哪天 Python 把默认值改成了
     * `.idea`，JetBrains 就再也发现不了自己没传变量 —— 那才是真正的隐患。
     */
    @Test
    fun `probe legacy default is the other host's dir so a missing env var stays visible`() {
        requireBundledScripts()
        val text = bundledScript("project_runtime.py")
        assertNotNull(text)

        val match = Regex("""LEGACY_RUN_DIR_PARTS\s*=\s*\(([^)]*)\)""").find(text)
        assertNotNull(
            match,
            "project_runtime.py 里找不到 LEGACY_RUN_DIR_PARTS —— 探针的兜底机制被改动了，" +
                "需要重新确认「不传环境变量时会退回哪个目录」",
        )
        val parts = match.groupValues[1]
        assertTrue(
            ".vscode" in parts,
            "兜底默认值应当仍是 VS Code 的 .vscode（实际：$parts）—— " +
                "若它变成了 .idea，JetBrains 侧漏传变量就再也看不出来了",
        )
        assertFalse(
            ".idea" in parts,
            "兜底默认值不能是 JetBrains 自己的目录（实际：$parts），否则漏传变量会被无声掩盖",
        )
    }

    // ── 3. 路径构造 ─────────────────────────────────────────────────────

    @Test
    fun `forProject appends the sandbox dir to the project root`() {
        val root = TestTmp.create("run-dir").absolutePath
        val resolved = RunDir.forProject(root)

        assertTrue(
            resolved.endsWith(".idea${java.io.File.separator}ok-script-toolkit"),
            "应以 .idea${java.io.File.separator}ok-script-toolkit 结尾，实际：$resolved",
        )
        assertTrue(resolved.startsWith(root), "必须挂在项目根之下，实际：$resolved")
        assertFalse(".vscode" in resolved, "不得出现 .vscode，实际：$resolved")
    }

    @Test
    fun `forProject keeps nested project roots intact`() {
        // 项目根本身可能已含一层 .idea（少见但合法），拼接不能把它吃掉
        val root = TestTmp.create("run-dir-nested").absolutePath
        val nested = java.io.File(root, "sub/proj").apply { mkdirs() }.absolutePath
        val resolved = RunDir.forProject(nested)

        assertTrue(resolved.startsWith(nested), "嵌套项目根必须原样保留，实际：$resolved")
        assertTrue(
            resolved.endsWith(".idea${java.io.File.separator}ok-script-toolkit"),
            "尾部仍是沙箱目录，实际：$resolved",
        )
    }

    // ── 4. 宿主侧确实把它传下去了（源码文本断言，同 OverrideKeyParityTest 手法）──

    @Test
    fun `probe invocation passes the run dir env var`() {
        val service = TestRepoLayout
            .mainSource("com/alicejump/okscripttoolkit/tasklauncher/TaskLauncherService.kt")
            .readText()
        assertTrue(service.isNotBlank(), "源码读出来是空的 —— 断言会退化成恒真")
        assertTrue(
            "RunDir.ENV" in service && "RunDir.forProject" in service,
            "TaskLauncherService 必须把 RunDir 传给探针（env = mapOf(RunDir.ENV to RunDir.forProject(...))）。" +
                "否则探针拿不到沙箱路径，multiAccount.storePath 会退回 .vscode。",
        )
    }

    @Test
    fun `account gateway invocation passes the same run dir env var`() {
        val service = TestRepoLayout
            .mainSource("com/alicejump/okscripttoolkit/core/AccountStoreService.kt")
            .readText()
        assertTrue(
            "builder.environment()[RunDir.ENV] = RunDir.forProject(projectDir)" in service,
            "账号网关必须和探针、执行器使用同一个宿主沙箱",
        )
    }

    @Test
    fun `tool window no longer hardcodes the sandbox literal`() {
        val factory = TestRepoLayout
            .mainSource("com/alicejump/okscripttoolkit/tasklauncher/TaskLauncherToolWindowFactory.kt")
            .readText()
        assertTrue(factory.isNotBlank(), "源码读出来是空的 —— 断言会退化成恒真")
        assertFalse(
            "\".idea/ok-script-toolkit\"" in factory,
            "工具窗不应再硬编码沙箱字面量 —— 它和探针会各写一份，静默错位。应统一走 RunDir。",
        )
        assertTrue(
            "RunDir.forProject" in factory,
            "工具窗启动执行器时必须经 RunDir 取沙箱路径",
        )
    }
}
