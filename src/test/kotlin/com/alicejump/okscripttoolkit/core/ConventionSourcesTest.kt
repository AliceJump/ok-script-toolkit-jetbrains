package com.alicejump.okscripttoolkit.core

import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「项目约定 vs 我的设置」溯源行的测试（`conventionSourceRows`）。
 *
 * 背景（`docs/project-config.md` §3）：取值链把**个人偏好**排最高，好处是
 * "项目文件给团队开箱默认、我改过就用我的"；代价是**一旦我手动改过，项目声明的
 * 那一项就对我永久失效** —— 界面上毫无提示。这个面板就是那个缓冲。
 *
 * 这里测的是**纯函数**（不依赖 `Project` / 服务），符合本仓库 `src/test` 的既有约定：
 * 要 `Project` 的服务类测不了，所以逻辑先下沉成纯对象。
 *
 * 面板是用户唯一能看见来源的地方，所以断言都按**用户看到的字符串**写，
 * 而不只是断言纯对象（纯对象那层在 `ProjectConventionTest` 里已经钉过）。
 */
class ConventionSourcesTest {

    private val JSON = ObjectMapper()
    private val ALIASES_FALLBACK = listOf("fL", "FeatureList")
    private val TPL_FALLBACK = "ok_templates"

    private fun rows(
        json: String? = null,
        personal: ConventionPersonal = ConventionPersonal(),
    ): List<ConventionSourceRow> = conventionSourceRows(
        convention = json?.let { ProjectConvention.parse(JSON.readTree(it)) } ?: ProjectConvention.EMPTY,
        personal = personal,
    )

    private fun row(rows: List<ConventionSourceRow>, key: String): ConventionSourceRow =
        rows.firstOrNull { it.key == key } ?: error("溯源行里没有 $key，实际：${rows.map { it.key }}")

    // ── 登记表 ───────────────────────────────────────────────────────

    @Test
    fun `the registry lists every setting that takes part in the chain`() {
        val keys = rows().map { it.key }
        assertEquals(
            listOf(
                "featureAliases",
                // 枚举路径 / 类名此前**没有**个人偏好层（VS Code 侧藏在 `globalState` 里，
                // 界面上看不见、还跨项目串味；类名则完全没有）。升级成正式设置之后，
                // 它们必须和其它设置一样可溯源、可恢复 —— 否则用户改过类名之后
                // **看不到团队声明、也回不去**，而这一项改错会让整个项目 import 失败。
                "labelEnumPath",
                "labelEnumName",
                "okTemplatesDirectory",
                "enablePoData",
                "langDirectory",
                "poDirectory",
                "poDomains",
                "characterProjectPath",
                "characterMasterFile",
                "characterSkillsDirectory",
                "characterLocaleFile",
                "characterAvatarTemplateRegex",
                "effectsFile",
            ),
            keys,
            "登记表内容与顺序",
        )
        assertEquals(keys.size, keys.toSet().size, "登记表里不能有重复键")
    }

    // ── 三层的来源标注 ───────────────────────────────────────────────

    @Test
    fun `the enum path and class name are traceable too`() {
        val json = """{"labelEnum": {"path": "src/data/feature_list", "name": "FeatureList"}}"""
        val pathRow = row(rows(json), "labelEnumPath")
        val nameRow = row(rows(json), "labelEnumName")

        assertEquals(
            "src/data/feature_list.py",
            pathRow.effective,
            "路径行展示的是**文件路径** —— 项目声明写的是模块路径，链上已经补过 .py",
        )
        assertEquals("src/data/feature_list.py", pathRow.declared, "declared 走同一条链，所以展示值与生效值一致")
        assertEquals(ConventionLayer.PROJECT, pathRow.layer)
        assertEquals("FeatureList", nameRow.effective, "类名行按项目声明取值")
        assertEquals(ConventionLayer.PROJECT, nameRow.layer)

        // 个人覆盖
        val overridden = rows(
            json,
            ConventionPersonal(labelEnumPath = "mine/x.py", labelEnumName = "MyEnum"),
        )
        val path2 = row(overridden, "labelEnumPath")
        val name2 = row(overridden, "labelEnumName")
        assertEquals("mine/x.py", path2.effective, "路径的个人偏好压过项目声明")
        assertEquals(ConventionLayer.PERSONAL, path2.layer)
        assertEquals("MyEnum", name2.effective, "类名的个人偏好压过项目声明")
        assertEquals(ConventionLayer.PERSONAL, name2.layer)
        assertEquals("src/data/feature_list.py", path2.declared, "被覆盖时仍然展示项目声明")
        assertEquals(
            "FeatureList",
            name2.declared,
            "类名同理 —— 这一项被覆盖后尤其危险（会让整个项目 ImportError），必须能看见原值",
        )
        assertTrue(path2.overridden && name2.overridden, "两项都提供「恢复为项目约定」")

        // 空值 = 没设置，不是「钉死为空」
        val blank = rows(json, ConventionPersonal(labelEnumPath = "   ", labelEnumName = ""))
        assertEquals(ConventionLayer.PROJECT, row(blank, "labelEnumPath").layer, "空白 = 回到项目约定")
        assertEquals(ConventionLayer.PROJECT, row(blank, "labelEnumName").layer)
    }

    @Test
    fun `the empty fallbacks of the enum rows are rendered as readable text`() {
        val bare = rows()
        val pathRow = row(bare, "labelEnumPath")
        val nameRow = row(bare, "labelEnumName")

        assertEquals(ConventionLayer.BUILTIN, pathRow.layer, "都没声明时报「内置默认」")
        assertNull(pathRow.declared)
        assertTrue(
            pathRow.effective.isNotEmpty(),
            "**空兜底也要渲染成可读文案** —— 直接展示空串在列表里是一段空白，看着像坏了",
        )
        assertEquals(pathRow.effective, pathRow.builtin, "builtin 与 effective 都是同一句兜底文案")
        assertTrue(
            nameRow.effective.isNotEmpty(),
            "类名的兜底是「用文件名推导」（**不是常量**，面板拿不到文件路径）—— 文案要说清这一层会做什么",
        )
    }

    @Test
    fun `a project declaration is reported as the project layer`() {
        val json = """{"templates": {"directory": "proj_tpl"}, "labelEnum": {"aliases": ["PL"]}}"""
        val tpl = row(rows(json), "okTemplatesDirectory")
        assertEquals("proj_tpl", tpl.effective, "生效值来自项目声明")
        assertEquals(ConventionLayer.PROJECT, tpl.layer, "来源标注为项目约定")
        assertEquals("proj_tpl", tpl.declared, "declared 展示文件里写的值")
        assertEquals(TPL_FALLBACK, tpl.builtin, "builtin 展示「恢复之后会回到什么」")
        assertTrue(!tpl.overridden, "没有个人覆盖时不提供「恢复」")

        val aliases = row(rows(json), "featureAliases")
        assertEquals("PL", aliases.effective, "别名同样按项目声明取值")
        assertEquals(ConventionLayer.PROJECT, aliases.layer)
    }

    @Test
    fun `a personal value wins and is reported as the personal layer`() {
        val json = """{"templates": {"directory": "proj_tpl"}}"""
        val tpl = row(rows(json, ConventionPersonal(templatesDirectory = "mine_tpl")), "okTemplatesDirectory")
        assertEquals("mine_tpl", tpl.effective, "个人偏好压过项目声明")
        assertEquals(ConventionLayer.PERSONAL, tpl.layer, "来源标注为我的设置")
        assertEquals(
            "proj_tpl",
            tpl.declared,
            "**被覆盖时仍然展示项目文件里的值** —— 否则用户永远看不到团队改了什么",
        )
        assertTrue(tpl.overridden, "有个人覆盖时才提供「恢复」")
    }

    @Test
    fun `with no convention at all everything falls back to the builtin layer`() {
        val tpl = row(rows(), "okTemplatesDirectory")
        assertEquals(TPL_FALLBACK, tpl.effective, "项目文件缺席时回到内置兜底")
        assertEquals(ConventionLayer.BUILTIN, tpl.layer)
        assertNull(tpl.declared, "没声明时 declared 为空（界面显示「未声明」）")
        assertEquals(TPL_FALLBACK, tpl.builtin)
        assertTrue(!tpl.overridden, "内置层不算个人覆盖")

        val aliases = row(rows(), "featureAliases")
        assertEquals("fL, FeatureList", aliases.effective, "别名兜底同样可读")
        assertEquals(ConventionLayer.BUILTIN, aliases.layer)
        assertNull(aliases.declared)

        assertEquals("src/data/effects.py", row(rows(), "effectsFile").effective, "后接入的 effects 组同样有兜底")
        assertEquals("assets/lang", row(rows(), "langDirectory").effective)
        assertEquals("ocr", row(rows(), "poDomains").effective, "列表兜底也要能渲染成可读文案")
    }

    @Test
    fun `the declared directory shown to the user is normalized the same way as the effective one`() {
        // 文件里写 ok_templates\ —— 生效值会被归一化。展示值必须同源，
        // 否则用户看到"文件里写的是 ok_templates\、生效的却是 ok_templates"会以为哪儿出错了
        val tpl = row(rows("""{"templates": {"directory": "ok_templates\\"}}"""), "okTemplatesDirectory")
        assertEquals("ok_templates", tpl.declared, "declared 也要归一化")
        assertEquals(tpl.effective, tpl.declared, "没有个人覆盖时两者应当一致")
    }

    // ── 后接入的三组（i18n / characters / effects）────────────────────

    @Test
    fun `the newly chained groups are traceable in the panel`() {
        val json = """
            {
              "i18n": {"enabled": false, "langDirectory": "./lang", "poDirectory": "i18n", "poDomains": ["ocr", "ui"]},
              "characters": {
                "projectPath": "/home/me/other_proj",
                "avatarTemplateRegex": "^icon\\d+/",
                "masterFile": "data/chars.json"
              },
              "effects": {"file": "src/data/effect_defs.py"}
            }
        """.trimIndent()
        val all = rows(json)

        assertEquals("false", row(all, "enablePoData").effective, "布尔项展示的是项目声明里的 false")
        assertEquals(ConventionLayer.PROJECT, row(all, "enablePoData").layer)
        assertEquals("ocr, ui", row(all, "poDomains").effective, "列表项用逗号连接展示")
        assertEquals("src/data/effect_defs.py", row(all, "effectsFile").effective, "effects 组也接了链")
        assertEquals(ConventionLayer.BUILTIN, row(all, "characterSkillsDirectory").layer, "没声明的那几项仍标注为内置兜底")

        val lang = row(all, "langDirectory")
        assertEquals("lang", lang.effective, "声明写 `./lang` 时生效值是归一化后的 `lang`")
        assertEquals(
            "lang",
            lang.declared,
            "**归一化对生效值与声明值一致生效** —— 否则会出现「面板显示一个样、实际匹配另一个样」",
        )

        assertEquals(
            "/home/me/other_proj",
            row(all, "characterProjectPath").effective,
            "**绝对路径在面板上原样展示** —— 被归一化会显示成 home/me/other_proj，用户会以为声明写错了",
        )
        assertEquals(
            "^icon\\d+/",
            row(all, "characterAvatarTemplateRegex").effective,
            "**正则在面板上原样展示** —— 归一化会显示成 ^icon/d+，用户照抄回去就把自己的正则改坏了",
        )
    }

    @Test
    fun `an empty project path is rendered as readable text instead of a blank cell`() {
        // `characters.projectPath` 的兜底是**空串**（含义：与当前项目相同）。
        // 直接展示空串在面板上是一段空白，看着像坏了。
        val row = row(rows(), "characterProjectPath")
        assertTrue(row.effective.isNotEmpty(), "空兜底也要渲染成可读文案")
        assertEquals(ConventionLayer.BUILTIN, row.layer)
        assertTrue(row.builtin.isNotEmpty(), "builtin 列同样不能是空白")
    }

    @Test
    fun `a personal value in a newly chained group is reported as personal`() {
        val json =
            """{"i18n": {"poDirectory": "i18n", "langDirectory": "lang"}, "characters": {"masterFile": "data/chars.json"}}"""
        val personal = ConventionPersonal(poDirectory = "my_po", characterMasterFile = "data/mine.json")
        val all = rows(json, personal)

        val po = row(all, "poDirectory")
        assertEquals("my_po", po.effective, "新分组同样受个人偏好优先")
        assertEquals(ConventionLayer.PERSONAL, po.layer)
        assertEquals("i18n", po.declared, "被覆盖时仍然展示项目声明 —— 否则用户看不到团队改了什么")
        assertTrue(po.overridden)

        assertEquals("data/mine.json", row(all, "characterMasterFile").effective, "同组的另一项也能被个人覆盖")
        assertEquals(
            ConventionLayer.PROJECT,
            row(all, "langDirectory").layer,
            "同组里没被个人覆盖的那一项仍按项目声明取值（只有记账过的键才走个人偏好）",
        )
    }

    // ── overridden 由 layer 推出 ─────────────────────────────────────

    /**
     * 若改成"再比一次值"来推断，就会出现「来源写着我的设置、却没有恢复按钮」
     * （或反之）这类自相矛盾的界面。这条不变量是刻意钉住的。
     */
    @Test
    fun `overridden always agrees with the layer`() {
        val cases = listOf(
            ConventionPersonal() to "都没设",
            ConventionPersonal(templatesDirectory = "mine") to "只设了模板目录",
            ConventionPersonal(featureAliases = listOf("mine")) to "只设了别名",
            ConventionPersonal(poDirectory = "mine", effectsFile = "mine.py") to "只设了后接入的两项",
            ConventionPersonal(i18nEnabled = false, characterProjectPath = "/x") to "只设了布尔与绝对路径两项",
        )
        for ((personal, name) in cases) {
            val all = rows("""{"templates": {"directory": "proj_tpl"}}""", personal)
            assertTrue(
                all.all { it.overridden == (it.layer == ConventionLayer.PERSONAL) },
                "$name：每一行的 overridden 都等于「layer 是我的设置」",
            )
        }
    }

    /**
     * 对照：来源层被写死后，项目声明的值也会被报成「我的设置」，
     * 于是面板会给出一个**不该有**的「恢复」按钮 —— 而点下去什么都不会变。
     * 用纯对象构造出那个错误结果，证明上面那组断言确实在区分两种来源。
     */
    @Test
    fun `regression guard - a hardcoded layer would offer a revert button that does nothing`() {
        val truth = row(rows("""{"templates": {"directory": "proj_tpl"}}"""), "okTemplatesDirectory")
        assertEquals(ConventionLayer.PROJECT, truth.layer, "真实现：来源是项目约定")
        assertTrue(!truth.overridden, "真实现：不给「恢复」按钮")

        val hardcoded = truth.copy(layer = ConventionLayer.PERSONAL)
        assertEquals(truth.effective, hardcoded.effective, "对照：值一样")
        assertTrue(
            hardcoded.overridden,
            "对照：层写错就会多出一个「恢复」按钮，而它点下去什么都不会变 —— 所以必须断言层本身",
        )
    }

    /**
     * 对照：展示值若不归一化，用户会看到与生效值不一致的"项目文件里写的值"。
     */
    @Test
    fun `regression guard - an unnormalized declared value disagrees with the effective one`() {
        val convention = ProjectConvention.parse(JSON.readTree("""{"templates": {"directory": "ok_templates\\"}}"""))
        val raw = convention.templates.directory
        assertEquals("ok_templates\\", raw, "对照：声明值原样带尾反斜杠")
        val effective = convention.templates.directoryOr(null, TPL_FALLBACK)
        assertTrue(raw != effective, "对照：不归一化时展示值与生效值不同 —— 会让人以为配置没生效")
    }

    /**
     * 对照：`declared` 若不再由"同一条链再跑一遍"产出，新接入分组的声明值会整片消失
     * —— 与上面 `the newly chained groups are traceable in the panel` 的期望相反。
     *
     * 这里用纯对象直接演示那个错误形态：绕过链、直接读字段拿到的是**未归一化**的值，
     * 于是面板显示 `./lang`、实际按 `lang` 匹配。
     */
    @Test
    fun `regression guard - reading the raw field instead of re-running the chain disagrees with the effective value`() {
        val convention = ProjectConvention.parse(JSON.readTree("""{"i18n": {"langDirectory": "./lang"}}"""))
        val raw = convention.i18n.langDirectory
        assertEquals("./lang", raw, "对照：直接读字段拿到的是未归一化的声明值")
        val effective = convention.i18n.langDirectoryOr(null, ConventionDefaults.LANG_DIRECTORY)
        assertEquals("lang", effective, "真实现：生效值已归一化")
        assertTrue(raw != effective, "对照：直接读字段展示会和生效值不一致 —— 用户照面板改项目文件反而改坏")
    }
}
