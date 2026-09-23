package com.alicejump.okscripttoolkit.core

import com.alicejump.okscripttoolkit.TestTmp
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
 * ⚠️ `jetbrains/` 是独立公开仓库，其 CI 只检出自己，`../python` 不可见 ⇒ classpath 上
 * 没有脚本。此时用 `assumeTrue` **跳过**（而不是 `return`，也不是让断言恒真）。
 * 父仓 CI 带 submodules 检出，这些断言在那里完整跑。判据与生产代码同源
 * （`PythonScriptLocator.BUNDLED_SCRIPTS.first()`），见 [PythonScriptLocatorTest]。
 */
class RunDirTest {

    private val bundledScriptsPresent: Boolean
        get() = PythonScriptLocator::class.java.classLoader
            .getResource("python/${PythonScriptLocator.BUNDLED_SCRIPTS.first()}") != null

    private fun requireBundledScripts() {
        assumeTrue(
            bundledScriptsPresent,
            "classpath 上没有 python/*.py —— 只检出了 jetbrains 子仓库，本断言无可断言对象，跳过。",
        )
    }

    private fun bundledScript(name: String): String? =
        PythonScriptLocator::class.java.classLoader
            .getResourceAsStream("python/$name")
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }

    /**
     * 真正**读环境变量**的 Python 脚本。
     *
     * ⚠️ `account_store.py` **不在这里** —— 它走的是 `--run-dir` 命令行参数（见
     * [account store takes the run dir as a CLI flag]）。把它错列进来会让本测试恒红，
     * 而"恒红的断言"和"恒真的断言"一样没有价值。两者的共同约定只有一条：
     * **沙箱根目录由宿主给出**，不给时各自退回自己的默认值。
     */
    private val envReadingScripts = listOf(
        "probe_task_schemas.py",
        "run_executor.py",
    )

    // ── 1. 环境变量名：Kotlin 写的必须等于 Python 读的 ────────────────────

    @Test
    fun `every env reading script uses exactly the name Kotlin writes`() {
        requireBundledScripts()

        val sources = envReadingScripts.map { name ->
            val text = bundledScript(name)
            assertNotNull(text, "$name 必须能从 classpath 读到（否则这条断言没有对象）")
            assertTrue(text.isNotBlank(), "$name 读出来是空的 —— 断言会退化成恒真")
            name to text
        }
        // 空集包含于任何集合：先保证真的扫到了东西，再看内容。
        assertTrue(
            sources.size == envReadingScripts.size,
            "必须扫到 ${envReadingScripts.size} 个脚本，实际 ${sources.size}",
        )

        val missing = sources.filter { (_, text) -> RunDir.ENV !in text }.map { it.first }
        assertTrue(
            missing.isEmpty(),
            "这些脚本里没有出现 ${RunDir.ENV}：$missing —— " +
                "说明 Kotlin 侧的变量名和 Python 侧读的对不上，Python 会静默退回自己的默认沙箱目录。",
        )
    }

    /**
     * 破坏性对照：证明上面那条"名字必须出现"的断言**真的能捕获改名**，
     * 而不是恒真（"扫描类断言恒真"是本仓库踩过的坑）。
     *
     * 手法：用**与生产断言完全相同的表达式**，只把被扫的名字换成 `envName` 参数。
     * 真名字 → 缺失集为空；假名字 → 缺失集必须非空。
     */
    @Test
    fun `regression guard - a renamed env var really is detected`() {
        requireBundledScripts()

        fun missingFor(envName: String): List<String> =
            envReadingScripts.filter { name ->
                val text = bundledScript(name) ?: return@filter true
                envName !in text
            }

        val bogus = "OK_TOOLKIT_RUN_DIR_RENAMED_FOR_TEST"
        val missingReal = missingFor(RunDir.ENV)
        val missingBogus = missingFor(bogus)

        assertTrue(
            missingBogus.isNotEmpty(),
            "换成假名字后缺失集必须非空 —— 若这里为空，说明上面的扫描表达式恒真、捕获不到任何改名",
        )
        assertTrue(
            missingBogus.size == envReadingScripts.size,
            "假名字应在**全部** ${envReadingScripts.size} 个脚本里都缺失，实际缺 ${missingBogus.size} 个：$missingBogus",
        )
        assertTrue(
            missingReal.isEmpty(),
            "真名字 ${RunDir.ENV} 应当一个都不缺，实际缺：$missingReal",
        )
    }

    /**
     * `account_store.py` 的约定**不同**：它收 `--run-dir` 命令行参数，不读环境变量。
     *
     * 钉住它是为了让"两套约定"这件事显式化 —— 日后给账号编辑接宿主时，要传的是
     * `--run-dir`，而不是设环境变量；设错了不会报错，只是**悄悄读写项目 `configs/`**
     * （脚本里写了"不传 --run-dir 则读写项目 configs，仅诊断用途"）。
     */
    @Test
    fun `account store takes the run dir as a CLI flag`() {
        requireBundledScripts()
        val text = bundledScript("account_store.py")
        assertNotNull(text, "account_store.py 必须能从 classpath 读到")
        assertTrue(text.isNotBlank(), "读出来是空的 —— 断言会退化成恒真")

        assertTrue(
            "--run-dir" in text,
            "account_store.py 必须接受 --run-dir（宿主经命令行传沙箱根目录）",
        )
        assertFalse(
            RunDir.ENV in text,
            "account_store.py 不该读 ${RunDir.ENV} —— 它走 --run-dir。" +
                "若这里红了，说明约定变了，需要同步改宿主侧传参方式。",
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
        val text = bundledScript("probe_task_schemas.py")
        assertNotNull(text)

        val match = Regex("""LEGACY_RUN_DIR_PARTS\s*=\s*\(([^)]*)\)""").find(text)
        assertNotNull(
            match,
            "probe_task_schemas.py 里找不到 LEGACY_RUN_DIR_PARTS —— 探针的兜底机制被改动了，" +
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
