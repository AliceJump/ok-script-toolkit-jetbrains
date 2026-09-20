package com.alicejump.okscripttoolkit.core

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 项目约定文件 `ok-script-toolkit.json` 的解析与取值链测试。
 *
 * 这一组是**看起来像配置、其实是数据约定**的东西：取值链的优先级一旦写反，
 * 界面上一切正常，只是"项目里声明的东西不生效" —— 静默、极难发现。
 * 所以每条链都配断言，并另配破坏性对照证明断言真的在约束东西。
 *
 * 约定（用户定的）：**个人偏好（IDE 设置）> 项目约定文件 > 内置默认**。
 * 项目文件是"团队开箱默认"，我改过就用我的。
 */
class ProjectConventionTest {

    private val JSON = ObjectMapper()
    private val FALLBACK = listOf("fL", "FeatureList")
    private val DEFAULT_TPL = "ok_templates"

    private fun convention(json: String): ProjectConvention = ProjectConvention.parse(JSON.readTree(json))

    // ── 解析容错 ─────────────────────────────────────────────────────

    @Test
    fun `a missing or malformed document means no convention at all`() {
        assertEquals(ProjectConvention.EMPTY, ProjectConvention.parse(null), "null 节点应退回空约定")
        assertEquals(
            ProjectConvention.EMPTY,
            ProjectConvention.parse(JSON.readTree("[]")),
            "顶层是数组同样当没写（约定文件的顶层必须是对象）",
        )
        assertEquals(
            ProjectConvention.EMPTY,
            ProjectConvention.parse(JSON.readTree("\"x\"")),
            "顶层是字符串同理",
        )
    }

    @Test
    fun `wrong field types fall back to the field default instead of throwing`() {
        val c = convention("""{"labelEnum": {"path": 42, "name": ["x"], "aliases": "fL"}}""")
        assertEquals(null, c.labelEnum.path, "path 是数字 → 当没写")
        assertEquals(null, c.labelEnum.name, "name 是数组 → 当没写")
        assertEquals(emptyList(), c.labelEnum.aliases, "aliases 是字符串（不是数组）→ 当没写")
    }

    @Test
    fun `blank strings count as not declared`() {
        val c = convention("""{"labelEnum": {"path": "   ", "name": "", "aliases": ["", "  ", "fL"]}}""")
        assertEquals(null, c.labelEnum.path, "全空白等同于没写（否则会拼出一个叫空白的路径）")
        assertEquals(null, c.labelEnum.name, "空串等同于没写")
        assertEquals(listOf("fL"), c.labelEnum.aliases, "数组里的空项要被过滤掉")
    }

    /**
     * 类型不符必须当"没写"，而不是被 `asText()` 顺手转成字符串。
     *
     * 这是两端一致性的一条：VSCode 的 `nonEmpty()` 用 `typeof value === 'string'` 判断，
     * 数字会被拒；Kotlin 侧若用 `textOrNull()`（内部 `asText()`），`42` 会变成 `"42"`
     * 被当成路径。同一份文件两端得出不同结论 = 最难查的那类缺陷。
     */
    @Test
    fun `aliases array keeps only real strings`() {
        val c = convention("""{"labelEnum": {"aliases": ["fL", 7, true, null, "  ", "FeatureList"]}}""")
        assertEquals(
            listOf("fL", "FeatureList"),
            c.labelEnum.aliases,
            "数组里的非字符串与空项都要过滤 —— 别名会被拼进正则，混进一个数字等于匹配规则被污染",
        )
    }

    @Test
    fun `a broken file on disk never throws - it just means no convention`() {
        val dir = Files.createTempDirectory("ok-toolkit-convention")
        try {
            val file = dir.resolve(ProjectConventionConfig.PROJECT_CONFIG_FILE).toFile()
            assertEquals(ProjectConvention.EMPTY, ProjectConvention.parseFile(file), "文件不存在时当没写")

            file.writeText("{ this is not json", Charsets.UTF_8)
            assertEquals(
                ProjectConvention.EMPTY,
                ProjectConvention.parseFile(file),
                "**JSON 语法错绝不能抛异常** —— 这是可选的增量配置，坏文件不能拦住插件",
            )

            file.writeText("""{"labelEnum": {"aliases": ["fL"]}}""", Charsets.UTF_8)
            assertEquals(
                listOf("fL"),
                ProjectConvention.parseFile(file).labelEnum.aliases,
                "正常文件要真的读出来 —— 否则上面两条\"容错\"只是因为它压根没读",
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ── 取值链 1：引用别名 ────────────────────────────────────────────

    @Test
    fun `aliases - personal preference beats the project file, which beats the fallback`() {
        val project = convention("""{"labelEnum": {"aliases": ["FL"]}}""").labelEnum
        assertEquals(FALLBACK, LabelEnumConvention().aliasesOr(emptyList(), FALLBACK), "都没声明时用内置兜底")
        assertEquals(listOf("FL"), project.aliasesOr(emptyList(), FALLBACK), "项目声明生效")
        assertEquals(
            listOf("mine"),
            project.aliasesOr(listOf("mine"), FALLBACK),
            "**个人偏好压过项目声明** —— 用户定的优先级（项目文件是团队默认，我改过就用我的）",
        )
    }

    /**
     * 这条是整条链的**前提**，单独钉住。
     *
     * `SettingsState.featureAliases` 原先在 `init` 里被填了 `["fL", "FeatureList"]` 作默认值，
     * 于是"个人偏好"这一层永远非空 → 项目声明的 aliases **永远被压住**（接了等于没接）。
     * 如果哪天有人又给它加个默认值，这条断言不会失败（它测的是纯对象），
     * 但 `OkScriptToolkitSettings.featureAliases()` 的注释里写明了同一件事 —— 改前先读那段。
     */
    @Test
    fun `an empty personal preference means not set, not cleared`() {
        val project = LabelEnumConvention(aliases = listOf("FL"))
        assertEquals(
            listOf("FL"),
            project.aliasesOr(emptyList(), FALLBACK),
            "**空列表 = 没设置，不是\"清空\"** —— 否则用户没法用空值表达\"回到项目约定\"",
        )
    }

    // ── 取值链 2：类名 ───────────────────────────────────────────────

    @Test
    fun `class name is decoupled from the file name once declared`() {
        assertEquals(
            "feature_labels",
            LabelEnumConvention().classNameOr("D:/proj/src/data/feature_labels.py"),
            "没声明时退回文件名（去 .py）—— 即旧行为",
        )
        assertEquals(
            "FeatureList",
            LabelEnumConvention(name = "FeatureList").classNameOr("D:/proj/src/data/feature_labels.py"),
            "**声明后文件与类名解耦** —— 文件叫 feature_labels.py、类叫 FeatureList",
        )
    }

    @Test
    fun `class name handles both separators and a missing extension`() {
        assertEquals("LabelEnum", LabelEnumConvention().classNameOr("C:\\proj\\assets\\LabelEnum.py"), "Windows 反斜杠")
        assertEquals("LabelEnum", LabelEnumConvention().classNameOr("assets/LabelEnum.py"), "相对路径")
        assertEquals("LabelEnum", LabelEnumConvention().classNameOr("assets/LabelEnum"), "没有扩展名时也要能给出名字")
    }

    // ── 取值链 3：文件路径（含「模块路径 → 文件路径」转换）─────────────

    @Test
    fun `declared path is a module path and must gain the py suffix`() {
        assertEquals(
            "src/data/FeatureList.py",
            LabelEnumConvention(path = "src/data/FeatureList").filePathOr(null),
            "**项目声明是模块路径，必须补 .py** —— 否则会生成一个没有扩展名的文件，Python import 不到" +
                "（config.py 的 label_enum_relative_path 就是这个形态，ok 框架的 " +
                "_normalize_label_enum_relative_path 会把用户输入的 .py 主动剥掉）",
        )
        assertEquals(
            "src/data/FeatureList.py",
            LabelEnumConvention(path = "src/data/FeatureList.py").filePathOr(null),
            "声明里已经带了 .py 就不重复补（对手写文件宽容一点）",
        )
    }

    @Test
    fun `last saved path beats the declared one and is used verbatim`() {
        val declared = LabelEnumConvention(path = "src/data/FeatureList")
        assertEquals(
            "mine/Label.py",
            declared.filePathOr("mine/Label.py"),
            "**个人偏好压过项目声明** —— 且它已经是文件路径，不能再去补一次 .py",
        )
        assertEquals(null, LabelEnumConvention().filePathOr(null), "都没有时返回 null，交给调用方用内置默认")
        assertEquals("mine/Label.py", LabelEnumConvention().filePathOr("mine/Label.py"), "只有个人偏好时用它")
    }

    // ── 取值链 4：模板目录 ───────────────────────────────────────────

    /**
     * 这一项此前在 **VS Code 侧完全没被读过**（`ok_templates` 写成常量、设置项是死的），
     * 而子仓会读（10 处）—— 属反向不对等（`docs/project-config.md` §8.1）。
     * 现在两端都走这条链，所以这里把三段都钉住。
     */
    @Test
    fun `templates directory - personal preference beats the project file, which beats the fallback`() {
        val project = convention("""{"templates": {"directory": "my_tpl"}}""").templates
        assertEquals(
            DEFAULT_TPL,
            TemplatesConvention().directoryOr(null, DEFAULT_TPL),
            "都没声明时用内置兜底",
        )
        assertEquals(
            "my_tpl",
            project.directoryOr(null, DEFAULT_TPL),
            "**项目声明生效** —— 修复前这一项在 VS Code 侧被完全忽略",
        )
        assertEquals(
            "mine",
            project.directoryOr("mine", DEFAULT_TPL),
            "**个人偏好压过项目声明** —— 用户定的优先级（项目文件是团队默认，我改过就用我的）",
        )
    }

    /**
     * 与别名同理：`null` / 空白表示"没设置"，不是"清空"。
     *
     * 这条尤其重要，因为 `SettingsState.okTemplatesDirectory` 的默认值就是
     * `ok_templates`（非空）—— 直接把它当个人偏好传进来，项目声明就永远不生效。
     */
    @Test
    fun `a blank templates preference means not set, not cleared`() {
        val project = TemplatesConvention(directory = "my_tpl")
        assertEquals("my_tpl", project.directoryOr(null, DEFAULT_TPL), "null = 没设置 → 项目声明生效")
        assertEquals("my_tpl", project.directoryOr("   ", DEFAULT_TPL), "全空白同样是没设置")
    }

    @Test
    fun `templates directory is normalized before it reaches path building`() {
        assertEquals("ok_templates", normalizeRelPath("ok_templates\\"), "反斜杠转正斜杠并去掉尾斜杠")
        assertEquals("ok_templates", normalizeRelPath("/ok_templates/"), "去掉首尾斜杠")
        assertEquals("a/b", normalizeRelPath("  a/b  "), "去掉首尾空白，多级目录保留")
        assertEquals(null, normalizeRelPath("   "), "全空白 → 没写")
        assertEquals(null, normalizeRelPath("/"), "只有斜杠 → 没写（不能变成空目录名）")
        assertEquals(null, normalizeRelPath(null), "null → 没写")
    }

    @Test
    fun `wrong field types in templates fall back instead of becoming a directory name`() {
        val c = convention("""{"templates": {"directory": 42, "cocoAnnotations": ["x"]}}""")
        assertEquals(null, c.templates.directory, """directory 是数字 → 当没写（不能变成目录名 "42"）""")
        assertEquals(null, c.templates.cocoAnnotations, "cocoAnnotations 是数组 → 当没写")
        assertEquals(
            DEFAULT_TPL,
            c.templates.directoryOr(null, DEFAULT_TPL),
            "类型写错时整条链退回兜底",
        )
    }

    @Test
    fun `a declared directory with a trailing backslash still wins over the fallback`() {
        // 原始字符串里 `\\` 就是两个反斜杠 → JSON 解析出来是 `my_tpl\`（尾随一个反斜杠）
        val c = convention("""{"templates": {"directory": "my_tpl\\"}}""")
        assertEquals(
            "my_tpl",
            c.templates.directoryOr(null, DEFAULT_TPL),
            "**声明值也要归一化** —— 否则拼进路径/做目录段匹配时会静默失配",
        )
    }

    // ── 破坏性对照 ───────────────────────────────────────────────────

    /**
     * 对照：不补 `.py`（= 修复前的行为）时拿到的正是那个会弄坏项目的值。
     *
     * 纯对象没法"改回旧写法"再跑，所以直接把修复前的那行实现复刻出来对比 ——
     * 若两者相等，说明上面那条断言其实没在约束任何东西。
     */
    @Test
    fun `regression guard - without the py suffix the generated file is unusable`() {
        val declared = LabelEnumConvention(path = "src/data/FeatureList")
        val beforeFix = declared.path
        assertEquals(
            "src/data/FeatureList",
            beforeFix,
            "对照：修复前返回的是模块路径 —— 落到磁盘上就是一个没有扩展名的文件",
        )
        assertEquals(
            "src/data/FeatureList.py",
            declared.filePathOr(null),
            "真实现必须补上 .py，否则等于把项目弄坏",
        )
    }

    /** 对照：把取值链写反（项目声明优先）会得到相反的结果，证明那条优先级断言不是空过。 */
    @Test
    fun `regression guard - reversing the precedence yields the other value`() {
        val declared = LabelEnumConvention(path = "src/data/FeatureList")
        val reversed = "${declared.path}.py"
        assertEquals("src/data/FeatureList.py", reversed, "对照：链写反时拿到的是项目声明")
        assertEquals(
            "mine/Label.py",
            declared.filePathOr("mine/Label.py"),
            "真实现必须让个人偏好胜出 —— 与对照相反，证明该断言确实在约束优先级",
        )
    }

    /** 对照：不归一化时尾反斜杠会原样漏出去 —— 拼进 `File(root, dir)` 与目录段匹配就是静默失配。 */
    @Test
    fun `regression guard - without normalization a trailing backslash leaks through`() {
        val declared = TemplatesConvention(directory = "ok_templates\\")
        assertEquals(
            "ok_templates\\",
            declared.directory,
            "对照：修复前直接把声明值当路径用 —— 尾反斜杠会一路带进路径拼接与匹配",
        )
        assertEquals(
            "ok_templates",
            declared.directoryOr(null, DEFAULT_TPL),
            "真实现必须归一化，否则目录名匹配静默失配（界面正常，只是配置不生效）",
        )
    }

    /** 对照：把模板目录的取值链写反（项目声明优先）会得到相反的结果。 */
    @Test
    fun `regression guard - reversing the templates precedence yields the other value`() {
        val declared = TemplatesConvention(directory = "my_tpl")
        assertEquals("my_tpl", declared.directory, "对照：链写反时拿到的是项目声明")
        assertEquals(
            "mine",
            declared.directoryOr("mine", DEFAULT_TPL),
            "真实现必须让个人偏好胜出 —— 与对照相反，证明该断言确实在约束优先级",
        )
    }
}
