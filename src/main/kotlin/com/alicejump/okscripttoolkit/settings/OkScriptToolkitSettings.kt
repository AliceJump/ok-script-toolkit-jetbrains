package com.alicejump.okscripttoolkit.settings

import com.alicejump.okscripttoolkit.core.ConventionDefaults
import com.alicejump.okscripttoolkit.core.ConventionOverrideSeed
import com.alicejump.okscripttoolkit.core.ConventionPersonal
import com.alicejump.okscripttoolkit.core.ConventionSourceRow
import com.alicejump.okscripttoolkit.core.ProjectConvention
import com.alicejump.okscripttoolkit.core.ProjectConventionConfig
import com.alicejump.okscripttoolkit.core.conventionSourceRows
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(
    name = "com.alicejump.okscripttoolkit.settings.OkScriptToolkitSettings",
    storages = [Storage("ok-script-toolkit.xml")],
)
class OkScriptToolkitSettings(
    private val project: Project,
) : SimplePersistentStateComponent<OkScriptToolkitSettings.SettingsState>(SettingsState()) {
    class SettingsState : BaseState() {
        var langDirectory by string("assets/lang")
        var poDirectory by string("i18n")
        var poDomains by list<String>()
        var displayLocale by string("auto")
        var featureAliases by list<String>()

        /** 用户是否**动过** `featureAliases`（点过设置面板的「应用」）。见 [init] 的迁移说明。 */
        var featureAliasesTouched by property(false)

        /**
         * 用户**真正改过**的设置键（点过设置面板的「应用」且值确实变了）。
         *
         * 为什么需要它：本 state 里这些字段**都带非空默认值**（`ok_templates`、
         * `assets/lang`、`src/data/effects.py` …），而 `BaseState` 的取值就是"字段值"，
         * 没有"是否设置过"这一说。于是"读一下 state"永远拿得到值 → 个人偏好层永远命中
         * → **项目约定文件里声明的值永远不生效**，且症状完全静默（界面一切正常，
         * 只是项目里配的东西没反应）。
         *
         * 这与 VS Code 侧 `getConfiguration().get()` 会把 `package.json` 的 `default`
         * 一并返回是同一个坑 —— 那边用 `inspect()` 区分"用户写过"与"默认值"，
         * 这边用本集合区分。**凡是要接取值链的设置项都必须先在这里记账**，
         * 否则接了等于没接。
         *
         * 由 [OkScriptToolkitConfigurable.apply] 维护（只记录值真的变了的键）；
         * 老用户由 [init] 一次性播种。
         */
        var overriddenKeys by list<String>()

        /**
         * 是否已经跑过 [init] 里那次"老用户补记账"的迁移。
         *
         * 单独一个标志位、而不是复用 `overriddenKeys.isEmpty()`：那个条件在
         * "用户确实没覆盖任何东西"时也为真，用它当迁移开关会让迁移反复重跑；
         * 反过来，v1.7.x 的老用户已经被播过 `okTemplatesDirectory` 一个键，
         * 集合非空 —— 用集合是否为空当开关，他们就拿不到后来新增键的补记账。
         */
        var conventionSeeded by property(false)

        var effectsFile by string("src/data/effects.py")
        var enablePoData by property(true)
        var enableInlayHints by property(true)
        var enableTemplateGallery by property(true)
        // TaskLauncher settings
        var okScriptProjectPath by string("")
        var okScriptPython by string("")
        // Character settings
        var characterProjectPath by string("")
        var characterMasterFile by string("assets/data/characters.json")
        var characterSkillsDirectory by string("assets/data/character_skills")
        var characterLocaleFile by string("assets/lang/characters.json")
        var characterAvatarTemplateRegex by string("^battle[_-]?icon[_-]?")
        // Template assets settings
        var okTemplatesDirectory by string("ok_templates")
        // Screenshot settings
        var captureMethod by string("auto")
    }

    init {
        if (state.poDomains.isEmpty()) state.poDomains = mutableListOf("ocr")
        // ⚠️ 这里**不能**再给 featureAliases 填默认值 —— 见 [featureAliases] 的注释：
        // 填了默认值就等于"个人偏好层永远命中"，项目约定文件里声明的 aliases 永远不生效。
        //
        // 但旧版本填过（并已持久化进老用户的 xml），所以做一次性清理：
        // 值恰好等于内置兜底、且用户从没点过「应用」→ 视为从未设置过。
        // `featureAliasesTouched` 用来区分"init 自动写的"与"用户手填的同样值" ——
        // 没有它，用户就永远无法用设置覆盖成"恰好等于内置默认"。
        if (!state.featureAliasesTouched && state.featureAliases == DEFAULT_FEATURE_ALIASES) {
            state.featureAliases = mutableListOf()
        }
        // 老用户没有 overriddenKeys 这个集合，给他们播一次种：
        // 凡是"值不等于内置默认"的，一定是用户自己改过的（默认值不会被写成非默认值）。
        // 判据与"空/空白 = 没设过"的规则都收在 `ConventionOverrideSeed` 里 ——
        // 那段逻辑只在升级路径上跑一次，抽出去才测得到。
        if (!state.conventionSeeded) {
            state.conventionSeeded = true
            val seeded = ConventionOverrideSeed.keysToSeed(
                overriddenKeys = state.overriddenKeys.toList(),
                current = mapOf(
                    KEY_OK_TEMPLATES_DIRECTORY to state.okTemplatesDirectory,
                    KEY_LANG_DIRECTORY to state.langDirectory,
                    KEY_PO_DIRECTORY to state.poDirectory,
                    KEY_PO_DOMAINS to state.poDomains.toList(),
                    KEY_ENABLE_PO_DATA to state.enablePoData,
                    KEY_EFFECTS_FILE to state.effectsFile,
                    KEY_CHARACTER_PROJECT_PATH to state.characterProjectPath,
                    KEY_CHARACTER_MASTER_FILE to state.characterMasterFile,
                    KEY_CHARACTER_SKILLS_DIRECTORY to state.characterSkillsDirectory,
                    KEY_CHARACTER_LOCALE_FILE to state.characterLocaleFile,
                    KEY_CHARACTER_AVATAR_TEMPLATE_REGEX to state.characterAvatarTemplateRegex,
                ),
                defaults = mapOf(
                    KEY_OK_TEMPLATES_DIRECTORY to DEFAULT_TEMPLATES_DIRECTORY,
                    KEY_LANG_DIRECTORY to DEFAULT_LANG_DIRECTORY,
                    KEY_PO_DIRECTORY to DEFAULT_PO_DIRECTORY,
                    KEY_PO_DOMAINS to DEFAULT_PO_DOMAINS,
                    KEY_ENABLE_PO_DATA to DEFAULT_I18N_ENABLED,
                    KEY_EFFECTS_FILE to DEFAULT_EFFECTS_FILE,
                    KEY_CHARACTER_PROJECT_PATH to DEFAULT_CHARACTER_PROJECT_PATH,
                    KEY_CHARACTER_MASTER_FILE to DEFAULT_CHARACTER_MASTER_FILE,
                    KEY_CHARACTER_SKILLS_DIRECTORY to DEFAULT_CHARACTER_SKILLS_DIRECTORY,
                    KEY_CHARACTER_LOCALE_FILE to DEFAULT_CHARACTER_LOCALE_FILE,
                    KEY_CHARACTER_AVATAR_TEMPLATE_REGEX to DEFAULT_AVATAR_TEMPLATE_REGEX,
                ),
            )
            if (seeded.isNotEmpty()) {
                state.overriddenKeys = (state.overriddenKeys + seeded).distinct().toMutableList()
            }
        }
    }

    companion object {
        /** 截图方式可选值，与 capture_game_window.py 的 --method 保持一致 */
        val CAPTURE_METHODS = listOf("auto", "wgc", "bitblt", "foreground")

        /** 面板上的「硬前台」复选框对应的方式：激活窗口到前台再读屏幕 */
        const val CAPTURE_METHOD_FOREGROUND = "foreground"

        /**
         * 枚举引用别名的**内置兜底**。
         *
         * ⚠️ 它是取值链的最后一层，**不是** `SettingsState.featureAliases` 的默认值 ——
         * 一旦当成 state 默认值写进去，"个人偏好"这一层就永远非空，项目约定文件失效。
         *
         * 值本身定义在 [ConventionDefaults]（`core` 包），与 VS Code 侧
         * `projectConfig.DEFAULT_FEATURE_ALIASES` 同值；这里保留旧名字只是为了少改调用点。
         */
        val DEFAULT_FEATURE_ALIASES = ConventionDefaults.FEATURE_ALIASES

        /**
         * 模板目录的**内置兜底**（`ok_templates`）。
         *
         * ⚠️ 与 [DEFAULT_FEATURE_ALIASES] 同理：它是取值链的最后一层，
         * **不是**"个人偏好层"的值。定义见 [ConventionDefaults]。
         */
        const val DEFAULT_TEMPLATES_DIRECTORY = ConventionDefaults.TEMPLATES_DIRECTORY

        val DEFAULT_LANG_DIRECTORY = ConventionDefaults.LANG_DIRECTORY
        val DEFAULT_PO_DIRECTORY = ConventionDefaults.PO_DIRECTORY
        val DEFAULT_PO_DOMAINS = ConventionDefaults.PO_DOMAINS
        val DEFAULT_I18N_ENABLED = ConventionDefaults.I18N_ENABLED
        val DEFAULT_EFFECTS_FILE = ConventionDefaults.EFFECTS_FILE
        val DEFAULT_CHARACTER_PROJECT_PATH = ConventionDefaults.CHARACTER_PROJECT_PATH
        val DEFAULT_CHARACTER_MASTER_FILE = ConventionDefaults.CHARACTER_MASTER_FILE
        val DEFAULT_CHARACTER_SKILLS_DIRECTORY = ConventionDefaults.CHARACTER_SKILLS_DIRECTORY
        val DEFAULT_CHARACTER_LOCALE_FILE = ConventionDefaults.CHARACTER_LOCALE_FILE
        val DEFAULT_AVATAR_TEMPLATE_REGEX = ConventionDefaults.AVATAR_TEMPLATE_REGEX

        /**
         * `templates.directory` 对应的设置键名，用于 [SettingsState.overriddenKeys] 记账。
         * 与设置面板字段名一一对应。
         */
        const val KEY_OK_TEMPLATES_DIRECTORY = "okTemplatesDirectory"

        const val KEY_LANG_DIRECTORY = "langDirectory"
        const val KEY_PO_DIRECTORY = "poDirectory"
        const val KEY_PO_DOMAINS = "poDomains"
        const val KEY_ENABLE_PO_DATA = "enablePoData"
        const val KEY_EFFECTS_FILE = "effectsFile"
        const val KEY_CHARACTER_PROJECT_PATH = "characterProjectPath"
        const val KEY_CHARACTER_MASTER_FILE = "characterMasterFile"
        const val KEY_CHARACTER_SKILLS_DIRECTORY = "characterSkillsDirectory"
        const val KEY_CHARACTER_LOCALE_FILE = "characterLocaleFile"
        const val KEY_CHARACTER_AVATAR_TEMPLATE_REGEX = "characterAvatarTemplateRegex"

        /** 非法值（含旧配置残留）一律回退到 auto，避免把脏值传给 python 脚本 */
        fun normalizeCaptureMethod(value: String?): String =
            value?.trim()?.takeIf { it in CAPTURE_METHODS } ?: "auto"

        fun getInstance(project: Project): OkScriptToolkitSettings = project.service()
    }

    /**
     * 读"用户**真正设过**的值"；没记账就返回 `null`（= 没设过，让项目约定生效）。
     *
     * ⚠️ **凡是要接取值链的设置项都必须走这里**。`SettingsState` 里这些字段都带
     * 非空默认值，直接读 state 会让"个人偏好"层永远命中、项目约定文件里声明的值
     * 永远不生效 —— 而且症状完全静默（界面一切正常，只是项目里配的东西没反应）。
     *
     * 记账由 [OkScriptToolkitConfigurable.apply] 维护（只记录值真的变了的键），
     * 老用户由 [init] 一次性补种。见 [SettingsState.overriddenKeys]。
     */
    private fun <T> personal(key: String, read: () -> T?): T? = if (key in state.overriddenKeys) read() else null

    /** 读项目约定文件（容错在 `ProjectConvention.parseFile` 里，这里不用管）。 */
    private fun convention(): ProjectConvention = ProjectConventionConfig.getInstance(project).load()

    /**
     * 语言 JSON 目录（角色名等），相对项目根。
     *
     * 取值链：**个人偏好（IDE 设置）> 项目约定文件 `i18n.langDirectory` > `assets/lang`**。
     */
    fun langDirectory(): String =
        convention().i18n.langDirectoryOr(personal(KEY_LANG_DIRECTORY) { state.langDirectory.orEmpty() }, DEFAULT_LANG_DIRECTORY)

    /**
     * gettext .po 目录，相对项目根。
     *
     * 取值链：**个人偏好（IDE 设置）> 项目约定文件 `i18n.poDirectory` > `i18n`**。
     */
    fun poDirectory(): String =
        convention().i18n.poDirectoryOr(personal(KEY_PO_DIRECTORY) { state.poDirectory.orEmpty() }, DEFAULT_PO_DIRECTORY)

    /**
     * 参与索引的 po domain 白名单。
     *
     * 取值链：**个人偏好（IDE 设置）> 项目约定文件 `i18n.poDomains` > `["ocr"]`**。
     *
     * ⚠️ `state.poDomains` 在 [init] 里被填成 `["ocr"]`，所以它**永远非空** ——
     * 不能直接当"用户设过"。这里靠 [SettingsState.overriddenKeys] 记账区分。
     */
    fun poDomains(): List<String> =
        convention().i18n.poDomainsOr(personal(KEY_PO_DOMAINS) { state.poDomains.toList() }.orEmpty(), DEFAULT_PO_DOMAINS)

    /**
     * 是否启用 gettext po 数据源。
     *
     * 取值链：**个人偏好（IDE 设置 `enablePoData`）> 项目约定文件 `i18n.enabled` > `true`**。
     * 两处名字**刻意不同**：设置里是"我这台机器要不要读它"，项目文件里是
     * "这个项目的 i18n 长什么样"。
     */
    fun enablePoData(): Boolean =
        convention().i18n.enabledOr(personal(KEY_ENABLE_PO_DATA) { state.enablePoData }, DEFAULT_I18N_ENABLED)

    fun displayLocale(): String = state.displayLocale.orEmpty().ifBlank { "auto" }

    /**
     * 枚举引用别名。
     *
     * 取值链：**个人偏好（IDE 设置）> 项目约定文件 `labelEnum.aliases` > 内置默认**。
     * 个人偏好排最高是用户定的：项目文件是"团队开箱默认"，我改过就用我的。
     *
     * 别名是"代码里怎么写 import"这一**项目约定** —— 项目 `config.py` 从不声明它，
     * 所以此前只能靠 `fL`/`FeatureList` 硬猜；项目把枚举导入成别的名字就完全失效。
     *
     * ⚠️ 这条链成立的前提是 `state.featureAliases` **空 = 没设置过**。
     * 如果哪天有人"顺手"给它加个默认值，这一层就会永远命中、项目声明永远被压住，
     * 而且是**静默**的（界面上一切正常，只是项目约定不生效）。
     */
    fun featureAliases(): List<String> {
        val ide = state.featureAliases.filter { it.isNotBlank() }
        return ProjectConventionConfig.getInstance(project).load().labelEnum.aliasesOr(ide, DEFAULT_FEATURE_ALIASES)
    }

    /** 设置面板用：显示"我设了什么"，而不是"最终生效什么"（取值链会掺进项目约定） */
    fun rawFeatureAliases(): List<String> = state.featureAliases.toList()

    /**
     * 效果定义源文件（`EffectType` / `EFFECT_DESCRIPTIONS` 所在），相对项目根。
     *
     * 取值链：**个人偏好（IDE 设置）> 项目约定文件 `effects.file` > `src/data/effects.py`**。
     */
    fun effectsFile(): String =
        convention().effects.fileOr(personal(KEY_EFFECTS_FILE) { state.effectsFile.orEmpty() }, DEFAULT_EFFECTS_FILE)

    fun okScriptProjectPath(): String = state.okScriptProjectPath.orEmpty()
    fun okScriptPython(): String = state.okScriptPython.orEmpty()

    /**
     * 角色数据所在项目根。
     *
     * 取值链：**个人偏好（IDE 设置）> 项目约定文件 `characters.projectPath` > 空**。
     * 空 = 与当前项目相同，调用方据此退回当前项目。
     *
     * ⚠️ 这是**绝对路径**，不能做斜杠归一化（`normalizeRelPath` 会吃掉 POSIX 路径的开头斜杠）。
     */
    fun characterProjectPath(): String = convention().characters.projectPathOr(
        personal(KEY_CHARACTER_PROJECT_PATH) { state.characterProjectPath.orEmpty() },
        DEFAULT_CHARACTER_PROJECT_PATH,
    )

    /** 角色主数据文件，相对 `characterProjectPath`。 */
    fun characterMasterFile(): String = convention().characters.masterFileOr(
        personal(KEY_CHARACTER_MASTER_FILE) { state.characterMasterFile.orEmpty() },
        DEFAULT_CHARACTER_MASTER_FILE,
    )

    /** 技能 JSON 目录，相对 `characterProjectPath`。 */
    fun characterSkillsDirectory(): String = convention().characters.skillsDirectoryOr(
        personal(KEY_CHARACTER_SKILLS_DIRECTORY) { state.characterSkillsDirectory.orEmpty() },
        DEFAULT_CHARACTER_SKILLS_DIRECTORY,
    )

    /** 角色名多语言文件，相对 `characterProjectPath`。 */
    fun characterLocaleFile(): String = convention().characters.localeFileOr(
        personal(KEY_CHARACTER_LOCALE_FILE) { state.characterLocaleFile.orEmpty() },
        DEFAULT_CHARACTER_LOCALE_FILE,
    )

    /**
     * 头像模板的命名正则。
     *
     * 取值链：**个人偏好（IDE 设置）> 项目约定文件 `characters.avatarTemplateRegex` > 内置默认**。
     *
     * ⚠️ 这是**正则**，不能做斜杠归一化（会把 `\d` 的反斜杠换成 `/`）。
     * 调用方仍需自己 `runCatching { Regex(...) }` —— 手写正则写错不该让面板打挂。
     */
    fun characterAvatarTemplateRegex(): String = convention().characters.avatarTemplateRegexOr(
        personal(KEY_CHARACTER_AVATAR_TEMPLATE_REGEX) { state.characterAvatarTemplateRegex.orEmpty() },
        DEFAULT_AVATAR_TEMPLATE_REGEX,
    )

    /**
     * 模板目录名（相对项目根）。
     *
     * 取值链：**个人偏好（IDE 设置）> 项目约定文件 `templates.directory` > `ok_templates`**。
     * 个人偏好排最高是用户定的：项目文件是"团队开箱默认"，我改过就用我的。
     *
     * ⚠️ 只有用户**真正改过**（[SettingsState.overriddenKeys] 里有这个键）才把 state 的值
     * 当个人偏好。state 的默认值就是 `ok_templates`，直接读会让这一层永远命中、
     * **项目声明永远被压住** —— 与 [featureAliases] 是同一个陷阱，且同样静默。
     *
     * 历史：VS Code 侧这个设置此前是**死设置**（常量硬编码、无人读），而子仓会读（10 处），
     * 属反向不对等（`docs/project-config.md` §8.1）。现在两端都走取值链。
     */
    fun okTemplatesDirectory(): String =
        convention().templates.directoryOr(
            personal(KEY_OK_TEMPLATES_DIRECTORY) { state.okTemplatesDirectory.orEmpty() },
            DEFAULT_TEMPLATES_DIRECTORY,
        )

    /**
     * 记录用户显式改过某个设置键 —— 该键的"个人偏好"层从此生效。
     *
     * 由设置面板的 `apply()` 在**值确实变了**时调用。详见 [SettingsState.overriddenKeys]。
     */
    fun markOverridden(key: String) {
        if (key !in state.overriddenKeys) {
            state.overriddenKeys = (state.overriddenKeys + key).toMutableList()
        }
    }

    /**
     * 撤销 [markOverridden] 的记账 —— 即「恢复为项目约定」。
     *
     * 只取消记账、**不清 state 里的值**：值留着，但"个人偏好"层不再命中，
     * 于是取值链落到项目声明（没有声明则落到内置兜底）。这样做的好处是
     * **可逆** —— 用户反悔时 state 里的值还在，不需要重新输入一遍。
     *
     * 注意这与"把值改回内置默认"**不等价**：后者会让用户无法表达
     * "我就是要用恰好等于默认值的那个目录"（见 [SettingsState.featureAliasesTouched] 的同理说明）。
     *
     * @return 是否真的撤销了（键本来就不在集合里时返回 false，调用方据此决定要不要提示）
     */
    fun clearOverridden(key: String): Boolean {
        if (key !in state.overriddenKeys) return false
        state.overriddenKeys = state.overriddenKeys.filter { it != key }.toMutableList()
        return true
    }

    /** 某个键当前是否有个人覆盖（= 记账里有没有它）。 */
    fun isOverridden(key: String): Boolean = key in state.overriddenKeys

    /**
     * 溯源面板的数据源：「每一项的生效值来自哪一层」。
     *
     * 个人偏好层在这里**归一成"用户真正设过的值"**：一律走 [personal] ——
     * state 里的默认值非空，直接读会让这一层永远命中、项目声明失效
     * （见 [SettingsState.overriddenKeys] 的说明）。
     *
     * 与 VS Code 侧 `conventionSources.ts` 的 `conventionSources()` 一一对应；
     * 加一组新设置时两边的登记表都要补一行。
     */
    fun conventionSources(): List<ConventionSourceRow> = conventionSourceRows(
        convention = convention(),
        personal = ConventionPersonal(
            templatesDirectory = personal(KEY_OK_TEMPLATES_DIRECTORY) { state.okTemplatesDirectory.orEmpty() },
            featureAliases = state.featureAliases.filter { it.isNotBlank() },
            i18nEnabled = personal(KEY_ENABLE_PO_DATA) { state.enablePoData },
            langDirectory = personal(KEY_LANG_DIRECTORY) { state.langDirectory.orEmpty() },
            poDirectory = personal(KEY_PO_DIRECTORY) { state.poDirectory.orEmpty() },
            poDomains = personal(KEY_PO_DOMAINS) { state.poDomains.toList() }.orEmpty(),
            characterProjectPath = personal(KEY_CHARACTER_PROJECT_PATH) { state.characterProjectPath.orEmpty() },
            characterMasterFile = personal(KEY_CHARACTER_MASTER_FILE) { state.characterMasterFile.orEmpty() },
            characterSkillsDirectory = personal(KEY_CHARACTER_SKILLS_DIRECTORY) { state.characterSkillsDirectory.orEmpty() },
            characterLocaleFile = personal(KEY_CHARACTER_LOCALE_FILE) { state.characterLocaleFile.orEmpty() },
            characterAvatarTemplateRegex = personal(KEY_CHARACTER_AVATAR_TEMPLATE_REGEX) {
                state.characterAvatarTemplateRegex.orEmpty()
            },
            effectsFile = personal(KEY_EFFECTS_FILE) { state.effectsFile.orEmpty() },
        ),
    )

    fun captureMethod(): String = normalizeCaptureMethod(state.captureMethod)
}
