package com.alicejump.okscripttoolkit.core

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    // ── 取值链 2：类名（个人偏好 > 项目声明 > 文件名）─────────────────

    @Test
    fun `class name is decoupled from the file name once declared`() {
        assertEquals(
            "feature_labels",
            LabelEnumConvention().classNameOr(null, fileNameWithoutPy("D:/proj/src/data/feature_labels.py")),
            "没声明时退回文件名（去 .py）—— 即旧行为",
        )
        assertEquals(
            "FeatureList",
            LabelEnumConvention(name = "FeatureList")
                .classNameOr(null, fileNameWithoutPy("D:/proj/src/data/feature_labels.py")),
            "**声明后文件与类名解耦** —— 文件叫 feature_labels.py、类叫 FeatureList",
        )
    }

    @Test
    fun `class name handles both separators and a missing extension`() {
        assertEquals("LabelEnum", fileNameWithoutPy("C:\\proj\\assets\\LabelEnum.py"), "Windows 反斜杠")
        assertEquals("LabelEnum", fileNameWithoutPy("assets/LabelEnum.py"), "相对路径")
        assertEquals("LabelEnum", fileNameWithoutPy("assets/LabelEnum"), "没有扩展名时也要能给出名字")
    }

    @Test
    fun `personal preference beats the declared class name`() {
        val declared = LabelEnumConvention(name = "FeatureList")
        assertEquals(
            "MyEnum",
            declared.classNameOr("MyEnum", "feature_labels"),
            "**个人偏好压过项目声明** —— 与全局取值链一致（个人偏好最高）",
        )
        assertEquals(
            "FeatureList",
            declared.classNameOr("   ", "feature_labels"),
            "个人偏好写成空白 = 没设置，退回项目声明（与 aliases 的空列表同一条规则）",
        )
        assertEquals(
            "feature_labels",
            LabelEnumConvention().classNameOr(null, "feature_labels"),
            "都没设置时落到兜底层（由调用方算出来的文件名）",
        )
    }

    @Test
    fun `class name reports which layer it came from`() {
        assertEquals(
            ConventionLayer.PERSONAL,
            LabelEnumConvention(name = "FeatureList").classNameResolved("MyEnum", "x").layer,
            "有个人偏好时报「我的设置」",
        )
        assertEquals(
            ConventionLayer.PROJECT,
            LabelEnumConvention(name = "FeatureList").classNameResolved(null, "x").layer,
            "只有项目声明时报「项目约定」",
        )
        assertEquals(
            ConventionLayer.BUILTIN,
            LabelEnumConvention().classNameResolved(null, "from_file").layer,
            "都没有时报「内置默认」—— 面板据此显示「由文件名推导」",
        )
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
    fun `personal preference beats the declared path`() {
        val declared = LabelEnumConvention(path = "src/data/FeatureList")
        assertEquals(
            "mine/Label.py",
            declared.filePathOr("mine/Label.py"),
            "**个人偏好压过项目声明**",
        )
        assertEquals(
            "mine/Label.py",
            declared.filePathOr("mine/Label"),
            "**个人偏好写的是模块路径也要补 .py** —— 旧实现把这一层原样返回，" +
                "于是从输入框里填模块路径会生成一个没有扩展名的文件，Python import 不到",
        )
        assertEquals(
            "src/data/FeatureList.py",
            declared.filePathOr("   "),
            "个人偏好写成空白 = 没设置，退回项目声明",
        )
        assertEquals("", LabelEnumConvention().filePathOr(null), "都没有时返回空串（= 这次不生成）")
        assertEquals("mine/Label.py", LabelEnumConvention().filePathOr("mine/Label.py"), "只有个人偏好时用它")
    }

    @Test
    fun `path reports which layer it came from`() {
        assertEquals(
            ConventionLayer.PERSONAL,
            LabelEnumConvention(path = "src/data/FeatureList").pathResolved("mine/x.py").layer,
            "有个人偏好时报「我的设置」",
        )
        assertEquals(
            ConventionLayer.BUILTIN,
            LabelEnumConvention().pathResolved(null).layer,
            "都没有时报「内置默认」（面板据此显示「未设置 —— 导出时询问」）",
        )
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

    // ── 取值链 3：来源层（溯源面板用） ────────────────────────────────
    //
    // 溯源面板要把"生效值来自哪一层"显示给用户。这一层**必须由取值链本身产出**：
    // 另写一套判断去复算的话迟早与实际生效值分叉，而分叉的表现是
    // "界面说来源是项目约定、实际生效的却是我的设置" —— 最难查的一类不一致。

    @Test
    fun `the resolved layer names the winning source for templates`() {
        val declared = TemplatesConvention(directory = "my_tpl")
        assertEquals(
            ResolvedSetting("mine", ConventionLayer.PERSONAL),
            declared.directoryResolved("mine", DEFAULT_TPL),
            "**个人偏好命中时层是 PERSONAL**，且值就是它",
        )
        assertEquals(
            ResolvedSetting("my_tpl", ConventionLayer.PROJECT),
            declared.directoryResolved(null, DEFAULT_TPL),
            "没设过个人偏好时落到项目声明",
        )
        assertEquals(
            ResolvedSetting(DEFAULT_TPL, ConventionLayer.BUILTIN),
            TemplatesConvention().directoryResolved(null, DEFAULT_TPL),
            "都没声明时落到内置兜底",
        )
        assertEquals(
            ResolvedSetting("my_tpl", ConventionLayer.PROJECT),
            declared.directoryResolved("   ", DEFAULT_TPL),
            "全空白的个人偏好按「没设置」处理，层不能变成 PERSONAL",
        )
    }

    @Test
    fun `the resolved layer names the winning source for aliases`() {
        val declared = LabelEnumConvention(aliases = listOf("FL"))
        assertEquals(
            ResolvedSetting(listOf("mine"), ConventionLayer.PERSONAL),
            declared.aliasesResolved(listOf("mine"), FALLBACK),
            "个人偏好命中时层是 PERSONAL",
        )
        assertEquals(
            ResolvedSetting(listOf("FL"), ConventionLayer.PROJECT),
            declared.aliasesResolved(emptyList(), FALLBACK),
            "空列表 = 没设置 → 落到项目声明",
        )
        assertEquals(
            ResolvedSetting(FALLBACK, ConventionLayer.BUILTIN),
            LabelEnumConvention().aliasesResolved(emptyList(), FALLBACK),
            "都没声明时落到内置兜底",
        )
    }

    /**
     * 空白项在**两侧**都按"没写"处理。
     *
     * 声明侧由 `stringOrNull()` 保证；个人偏好侧原来没有过滤 —— 于是设置面板里
     * 留一个空行（`[" "]`）会变成一条永远匹配不到的正则，且**静默**
     * （代码补全就是不出结果，看不出哪儿错了）。VS Code 侧 `nonEmptyStrings()`
     * 一直有这层过滤，两端必须一致。
     */
    @Test
    fun `blank aliases count as not set on both sides of the chain`() {
        val declared = LabelEnumConvention(aliases = listOf("FL"))
        assertEquals(
            ResolvedSetting(listOf("FL"), ConventionLayer.PROJECT),
            declared.aliasesResolved(listOf("  "), FALLBACK),
            "个人偏好只有空白项时等同于没设置 → 项目声明生效",
        )
        assertEquals(
            ResolvedSetting(FALLBACK, ConventionLayer.BUILTIN),
            LabelEnumConvention(aliases = listOf("  ")).aliasesResolved(emptyList(), FALLBACK),
            "声明里只有空白项时等同于没声明 → 内置兜底",
        )
    }

    /**
     * `aliasesOr` / `directoryOr` 必须与带层版本给出**同一个值**。
     *
     * 它们是薄封装（`.value`）。这条断言存在的意义：如果哪天有人把其中一个改回
     * 独立实现，两者就会开始漂移，而漂移的表现是"界面上显示的生效值与实际用的值不同"。
     */
    @Test
    fun `the thin accessors agree with the layer carrying versions`() {
        val templates = TemplatesConvention(directory = "my_tpl")
        assertEquals(
            templates.directoryResolved("mine", DEFAULT_TPL).value,
            templates.directoryOr("mine", DEFAULT_TPL),
            "directoryOr 必须就是 directoryResolved(...).value",
        )
        val aliases = LabelEnumConvention(aliases = listOf("FL"))
        assertEquals(
            aliases.aliasesResolved(listOf("mine"), FALLBACK).value,
            aliases.aliasesOr(listOf("mine"), FALLBACK),
            "aliasesOr 必须就是 aliasesResolved(...).value",
        )
    }

    /**
     * 对照：来源层被写死后，项目声明的值也会被报成「我的设置」——
     * 这正是"界面与实际生效值分叉"的样子。用纯对象构造出那个错误结果，
     * 证明上面那组断言确实在区分两种来源。
     */
    @Test
    fun `regression guard - a hardcoded layer would misreport the project value`() {
        val declared = TemplatesConvention(directory = "my_tpl")
        val truth = declared.directoryResolved(null, DEFAULT_TPL)
        assertEquals(ConventionLayer.PROJECT, truth.layer, "真实现：没设过个人偏好时来源是项目约定")
        val hardcoded = ResolvedSetting(truth.value, ConventionLayer.PERSONAL)
        assertEquals(
            "my_tpl",
            hardcoded.value,
            "对照：来源写死后**值不变、层变错** —— 所以必须断言层本身，只断言值抓不到这个 bug",
        )
        assertTrue(
            hardcoded.layer != truth.layer,
            "对照：两者的层确实不同，证明断言层是有意义的",
        )
    }

    // ── i18n / characters / effects 三组 ─────────────────────────────
    //
    // 这三组此前是**硬编码常量**（设置访问器直接 `state.xxx.ifBlank { 默认 }`），
    // 接进取值链后每个字段都多了一条"项目声明"的来源。
    //
    // 这里按字段**类型**分组断言，因为类型决定了走哪条归一化：
    //   · 相对路径 → normalizeRelPath（斜杠归一化，会被拼进路径 / 做目录段匹配）
    //   · 绝对路径 / 正则 → textOrNull（**绝不能归一化**，见下面那条最关键的断言）
    //   · 布尔 / 字符串列表 → 类型守卫 + "空 = 没声明"

    @Test
    fun `i18n - the project file beats the fallback and the personal value beats both`() {
        val convention = convention("""{"i18n": {"enabled": false, "langDirectory": "assets\\lang"}}""")
        assertEquals(
            false,
            convention.i18n.enabledOr(null, ConventionDefaults.I18N_ENABLED),
            "**项目声明 false 生效** —— 此前这一项根本没有项目层，只能靠个人设置关",
        )
        assertEquals(
            true,
            convention.i18n.enabledOr(true, ConventionDefaults.I18N_ENABLED),
            "**个人偏好压过项目声明** —— 与全局取值链一致（个人偏好最高）",
        )
        assertEquals(
            "assets/lang",
            convention.i18n.langDirectoryOr(null, ConventionDefaults.LANG_DIRECTORY),
            "语言目录归一化 —— 它会被拿去做目录段匹配，反斜杠会静默失配",
        )
        assertEquals(
            ConventionLayer.PROJECT,
            convention.i18n.enabledResolved(null, ConventionDefaults.I18N_ENABLED).layer,
            "来源层标注为「项目约定」（溯源面板用）",
        )
    }

    @Test
    fun `i18n - a string written where a boolean belongs counts as not declared`() {
        // 手写文件里 `"enabled": "false"` 是最容易犯的错。若照真值算，
        // 字符串 "false" 是 truthy → 项目声明关掉 i18n 反而被锁在开，且完全静默。
        val convention = convention("""{"i18n": {"enabled": "false"}}""")
        assertEquals(
            ConventionDefaults.I18N_ENABLED,
            convention.i18n.enabledOr(null, ConventionDefaults.I18N_ENABLED),
            "非布尔一律当没写（与 VS Code 侧 boolResolved 的 typeof === 'boolean' 一致）",
        )
        assertEquals(
            ConventionLayer.BUILTIN,
            convention.i18n.enabledResolved(null, ConventionDefaults.I18N_ENABLED).layer,
            "层也不能被污染成「项目约定」",
        )
    }

    @Test
    fun `i18n - an empty po domain list means not declared, not cleared`() {
        assertEquals(
            ConventionDefaults.PO_DOMAINS,
            convention("""{"i18n": {"poDomains": []}}""")
                .i18n.poDomainsOr(emptyList(), ConventionDefaults.PO_DOMAINS),
            "空数组 = 没声明 —— 否则用户没法用空值表达「回到项目约定」",
        )
        assertEquals(
            listOf("ocr", "ui"),
            convention("""{"i18n": {"poDomains": ["ocr", "ui"]}}""")
                .i18n.poDomainsOr(emptyList(), ConventionDefaults.PO_DOMAINS),
            "列表按项目声明取值",
        )
        assertEquals(
            ConventionDefaults.PO_DOMAINS,
            convention("""{"i18n": {"poDomains": [" ", 7]}}""")
                .i18n.poDomainsOr(emptyList(), ConventionDefaults.PO_DOMAINS),
            "列表里全是无效项时退回兜底",
        )
    }

    @Test
    fun `characters - an absolute project path keeps its leading slash`() {
        // 走路径归一化会把 `/home/me/other_proj` 变成相对路径 `home/me/other_proj`，
        // 指向一个不存在的地方 —— 而且不报错，只是"角色数据加载不出来"。
        val convention = convention("""{"characters": {"projectPath": "/home/me/other_proj"}}""")
        assertEquals(
            "/home/me/other_proj",
            convention.characters.projectPathOr(null, ConventionDefaults.CHARACTER_PROJECT_PATH),
            "**POSIX 绝对路径的开头斜杠必须保住**",
        )
        assertEquals(
            "D:\\items\\other_proj",
            convention("""{"characters": {"projectPath": "D:\\items\\other_proj"}}""")
                .characters.projectPathOr(null, ConventionDefaults.CHARACTER_PROJECT_PATH),
            "**Windows 绝对路径的反斜杠必须保住** —— 归一化会把分隔符换成正斜杠，展示与 `~` 展开会失真",
        )
    }

    @Test
    fun `characters - an empty project path is a legal declaration meaning the current project`() {
        val convention = convention("""{"characters": {"projectPath": ""}}""")
        assertEquals(
            "",
            convention.characters.projectPathOr(null, ConventionDefaults.CHARACTER_PROJECT_PATH),
            "空串 = 与当前项目相同（等价于没声明）",
        )
        assertEquals(
            "/mine",
            convention.characters.projectPathOr("/mine", ConventionDefaults.CHARACTER_PROJECT_PATH),
            "个人偏好压过项目声明",
        )
    }

    @Test
    fun `characters - the avatar regex is passed through verbatim`() {
        // 这是正则不是路径。归一化会把 `\d` 的反斜杠换成 `/`、把尾部 `/` 吃掉
        // —— 正则当场失效，头像永远匹配不上，且没有任何报错。
        val convention = convention("""{"characters": {"avatarTemplateRegex": "^icon\\d+/"}}""")
        assertEquals(
            "^icon\\d+/",
            convention.characters.avatarTemplateRegexOr(null, ConventionDefaults.AVATAR_TEMPLATE_REGEX),
            "**正则必须原样保留**",
        )
    }

    @Test
    fun `characters - relative fields are normalized and a non-object group degrades to empty`() {
        val convention = convention(
            """{"characters": {"masterFile": "data/chars.json", "skillsDirectory": "data\\skills\\", "localeFile": "/lang/chars.json/"}}""",
        )
        assertEquals("data/chars.json", convention.characters.masterFileOr(null, ConventionDefaults.CHARACTER_MASTER_FILE))
        assertEquals(
            "data/skills",
            convention.characters.skillsDirectoryOr(null, ConventionDefaults.CHARACTER_SKILLS_DIRECTORY),
            "技能目录是相对路径，要归一化",
        )
        assertEquals(
            "lang/chars.json",
            convention.characters.localeFileOr(null, ConventionDefaults.CHARACTER_LOCALE_FILE),
            "角色名多语言文件同样归一化",
        )
        assertEquals(
            CharactersConvention(),
            convention("""{"characters": "不是对象"}""").characters,
            "整组类型写错时按没写处理（不能抛异常）",
        )
    }

    @Test
    fun `effects - the declared file wins and is normalized`() {
        assertEquals(
            "src/data/effect_defs.py",
            convention("""{"effects": {"file": "src/data/effect_defs.py"}}""")
                .effects.fileOr(null, ConventionDefaults.EFFECTS_FILE),
            "项目声明生效",
        )
        assertEquals(
            "src/data/effects.py",
            convention("""{"effects": {"file": "./src/data/effects.py"}}""")
                .effects.fileOr(null, ConventionDefaults.EFFECTS_FILE),
            "声明里写 `./` 前缀也会被归一化掉 —— 否则文件变更比较会静默失配",
        )
        assertEquals(
            "mine.py",
            convention("""{"effects": {"file": "proj.py"}}""").effects.fileOr("mine.py", ConventionDefaults.EFFECTS_FILE),
            "个人偏好压过项目声明",
        )
        assertEquals(
            ConventionLayer.PROJECT,
            convention("""{"effects": {"file": "a.py"}}""")
                .effects.fileResolved(null, ConventionDefaults.EFFECTS_FILE).layer,
            "来源层标注为「项目约定」",
        )
    }

    @Test
    fun `regression guard - routing an absolute path through path normalization eats its leading slash`() {
        // 对照：把 `projectPath` 当成相对路径处理（= 接线时最容易犯的错）。
        val convention = convention("""{"characters": {"projectPath": "/home/me/other_proj"}}""")
        val broken = normalizeRelPath(convention.characters.projectPath)
        assertEquals("home/me/other_proj", broken, "对照：归一化后开头斜杠没了，变成一个相对路径")
        assertTrue(
            broken != convention.characters.projectPathOr(null, ConventionDefaults.CHARACTER_PROJECT_PATH),
            "对照：与真实现不同 —— 证明上面那条断言确实在约束「不做归一化」这件事",
        )
    }

    @Test
    fun `regression guard - routing a regex through path normalization breaks it silently`() {
        // 对照：正则被当成相对路径。`\d` 的反斜杠会被换成 `/`、尾部 `/` 被吃掉，
        // 正则**语法仍然合法**，只是再也匹配不到东西 —— 静默失效。
        val convention = convention("""{"characters": {"avatarTemplateRegex": "^icon\\d+/"}}""")
        val broken = normalizeRelPath(convention.characters.avatarTemplateRegex)
        assertEquals("^icon/d+", broken, "对照：`\\d` 变成 `/d`、尾部 `/` 被吃掉")
        assertTrue(
            broken != convention.characters.avatarTemplateRegexOr(null, ConventionDefaults.AVATAR_TEMPLATE_REGEX),
            "对照：与真实现不同 —— 证明上面那条断言确实在约束「不做归一化」这件事",
        )
    }

    @Test
    fun `regression guard - without the type guard a string boolean would lock the switch on`() {
        // 手写文件里 `"enabled": "false"` 是最容易犯的错。若不守卫、照真值算，
        // 字符串 "false" 是 truthy → 项目声明"关掉 i18n"反而被锁在开，且完全静默。
        //
        // 对照：把"收下字符串"的错误形态摆出来。Kotlin 的类型系统让 `I18nConvention.enabled`
        // 只能是 `Boolean?`，所以真正的守卫点在 `parse` —— 用 String 版的 ResolvedSetting 演示。
        val loose = ResolvedSetting("false", ConventionLayer.PROJECT)
        assertTrue(
            (loose.value as Any) != false,
            "对照：字符串 \"false\" 不等于布尔 false —— 只断言值的测试抓不到这个类型错误",
        )
        assertEquals(
            ConventionLayer.PROJECT,
            loose.layer,
            "对照：层还会被污染成「项目约定」—— 面板会显示「来源是项目约定、值是 false」，而实际生效的是 true",
        )

        // 真实现：`parse` 把非布尔当没写，于是链退回兜底、层是干净的 BUILTIN。
        val resolved = convention("""{"i18n": {"enabled": "false"}}""")
            .i18n.enabledResolved(null, ConventionDefaults.I18N_ENABLED)
        assertEquals(ConventionDefaults.I18N_ENABLED, resolved.value, "真实现：类型守卫把它当没写，退回兜底")
        assertEquals(
            ConventionLayer.BUILTIN,
            resolved.layer,
            "真实现：层是 BUILTIN —— 面板不会误报「来源是项目约定」",
        )
    }
}
