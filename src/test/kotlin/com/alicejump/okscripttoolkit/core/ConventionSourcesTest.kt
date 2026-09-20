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
 */
class ConventionSourcesTest {

    private val JSON = ObjectMapper()
    private val ALIASES_FALLBACK = listOf("fL", "FeatureList")
    private val TPL_FALLBACK = "ok_templates"

    private fun rows(
        json: String? = null,
        personalTemplates: String? = null,
        personalAliases: List<String> = emptyList(),
    ): List<ConventionSourceRow> = conventionSourceRows(
        convention = json?.let { ProjectConvention.parse(JSON.readTree(it)) } ?: ProjectConvention.EMPTY,
        personalTemplatesDirectory = personalTemplates,
        personalFeatureAliases = personalAliases,
        templatesFallback = TPL_FALLBACK,
        aliasesFallback = ALIASES_FALLBACK,
    )

    private fun row(rows: List<ConventionSourceRow>, key: String): ConventionSourceRow =
        rows.firstOrNull { it.key == key } ?: error("溯源行里没有 $key，实际：${rows.map { it.key }}")

    // ── 登记表 ───────────────────────────────────────────────────────

    @Test
    fun `the registry lists every setting that takes part in the chain`() {
        val keys = rows().map { it.key }
        assertEquals(listOf("featureAliases", "okTemplatesDirectory"), keys, "登记表内容与顺序")
        assertEquals(keys.size, keys.toSet().size, "登记表里不能有重复键")
    }

    // ── 三层的来源标注 ───────────────────────────────────────────────

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
        val tpl = row(rows(json, personalTemplates = "mine_tpl"), "okTemplatesDirectory")
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
    }

    @Test
    fun `the declared directory shown to the user is normalized the same way as the effective one`() {
        // 文件里写 ok_templates\ —— 生效值会被归一化。展示值必须同源，
        // 否则用户看到"文件里写的是 ok_templates\、生效的却是 ok_templates"会以为哪儿出错了
        val tpl = row(rows("""{"templates": {"directory": "ok_templates\\"}}"""), "okTemplatesDirectory")
        assertEquals("ok_templates", tpl.declared, "declared 也要归一化")
        assertEquals(tpl.effective, tpl.declared, "没有个人覆盖时两者应当一致")
    }

    // ── overridden 由 layer 推出 ─────────────────────────────────────

    /**
     * 若改成"再比一次值"来推断，就会出现「来源写着我的设置、却没有恢复按钮」
     * （或反之）这类自相矛盾的界面。这条不变量是刻意钉住的。
     */
    @Test
    fun `overridden always agrees with the layer`() {
        val cases = listOf(
            Triple(null, emptyList<String>(), "都没设"),
            Triple("mine", emptyList<String>(), "只设了模板目录"),
            Triple(null, listOf("mine"), "只设了别名"),
            Triple("mine", listOf("mine"), "两个都设了"),
        )
        for ((tpl, aliases, name) in cases) {
            val all = rows("""{"templates": {"directory": "proj_tpl"}}""", tpl, aliases)
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
}
