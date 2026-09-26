package com.alicejump.okscripttoolkit.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File

/**
 * 项目约定文件 `ok-script-toolkit.json` 的**取值链**。
 *
 * 单独抽成不依赖 IDE / 服务的纯对象，方便在普通 JUnit 里断言 —— 这里的优先级
 * 是「看起来像 UI、其实是数据约定」的东西，改错很难被发现（VS Code 侧对应实现见
 * `src/projectConfigPure.ts`，逻辑一一对应）。
 *
 * 取值链（高 → 低）：
 * **个人偏好（IDE 设置 / 上次保存）> 项目约定文件 > 调用方给的兜底**
 *
 * 个人偏好排最高是**刻意的**（用户明确纠正过）：项目文件是"团队开箱默认"，
 * 我手动指定过就以我的为准。代价是项目之后改声明我看不到 —— 由 UI 的
 * 「当前值来自哪一层」+「恢复为项目约定」来抵消（见 `docs/project-config.md` §3）。
 *
 * 设计说明见仓库根的 `docs/project-config.md`。
 */
data class ProjectConvention(
    val labelEnum: LabelEnumConvention = LabelEnumConvention(),
    val templates: TemplatesConvention = TemplatesConvention(),
    val i18n: I18nConvention = I18nConvention(),
    val characters: CharactersConvention = CharactersConvention(),
    val effects: EffectsConvention = EffectsConvention(),
) {

    companion object {
        /** 没有约定文件、或文件不可用时的取值。所有链都会退到各自的兜底。 */
        val EMPTY = ProjectConvention()

        private val JSON = ObjectMapper()

        /** 解析约定文件内容。任何字段缺失/类型不符都退回该字段的默认值，不抛异常。 */
        fun parse(root: JsonNode?): ProjectConvention {
            if (root == null || !root.isObject) return EMPTY
            return ProjectConvention(
                labelEnum = LabelEnumConvention.parse(root.get("labelEnum")),
                templates = TemplatesConvention.parse(root.get("templates")),
                i18n = I18nConvention.parse(root.get("i18n")),
                characters = CharactersConvention.parse(root.get("characters")),
                effects = EffectsConvention.parse(root.get("effects")),
            )
        }

        /**
         * 读盘并解析。
         *
         * **容错是刻意的**：文件不存在、JSON 语法错、顶层不是对象一律当成"没有约定" ——
         * 这是可选的纯增量配置，任何异常都不能影响调用方。
         *
         * 读盘逻辑放在纯对象里（而不是读盘侧服务），是为了让"坏文件不炸"这条不变量
         * 能在普通 JUnit 里用临时目录断言 —— 服务类的构造函数要 `Project`，测不了。
         */
        fun parseFile(file: File): ProjectConvention = try {
            parse(JSON.readTree(file))
        } catch (_: Exception) {
            EMPTY
        }
    }
}

/**
 * 相对路径归一化：统一成 `/` 分隔、去掉首尾斜杠与开头的 `./`；空（或只有斜杠）→ `null`。
 *
 * 为什么需要它：这个值会被 `File(root, dir)` 拼绝对路径，也会被拿去做目录段匹配、
 * 甚至拼进 glob。声明文件里写 `ok_templates\` 或 `./ok_templates` 时，后两种都会
 * **静默**失配 —— 界面一切正常，只是"改了设置不生效"。入口处统一归一化一次。
 *
 * 只剥开头的 `./`，**不碰 `../`** —— 后者是有意义的上跳，剥了就指到别处去了。
 *
 * 与 VS Code 侧 `projectConfigPure.normalizeRelPath()` 语义一一对应，改一边记得改另一边。
 */
internal fun normalizeRelPath(value: String?): String? {
    val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val cleaned = trimmed
        .replace('\\', '/')
        // `^(?:\.?/)+` 同时覆盖 `/x`、`./x`、`/./x`、`.//x`，且不会碰 `../x`
        .replace(LEADING_SLASH_OR_DOT_SLASH, "")
        .trimEnd('/')
    return cleaned.takeIf { it.isNotEmpty() }
}

private val LEADING_SLASH_OR_DOT_SLASH = Regex("^(?:\\.?/)+")

/**
 * 枚举路径的归一化：**模块路径与文件路径都容忍**。
 *
 * 项目约定文件里写的是模块路径（`src/data/FeatureList`，不带 `.py` —— 与 `config.py` 的
 * `label_enum_relative_path` 同形），而 IDE 设置那个输入框要的是文件路径（带 `.py`）。
 * 两种写法指同一个文件，没必要让用户记住"哪个框该写哪种" —— 有 `.py` 就用，没有就补。
 *
 * 旧实现只给"项目声明"补后缀、把"上次保存"原样返回，于是从输入框里填模块路径会生成一个
 * **没有扩展名**的文件。统一在这里补，消费点不用各自判断。
 *
 * 与 VS Code 侧 `projectConfigPure.normalizeLabelEnumFile` 一一对应。
 */
internal fun normalizeLabelEnumFile(value: String?): String? {
    val rel = normalizeRelPath(value) ?: return null
    return if (rel.endsWith(".py", ignoreCase = true)) rel else "$rel.py"
}

/** 用户输入的枚举路径为什么不能按「项目根相对路径」使用。 */
enum class LabelEnumPathInputError { ABSOLUTE, TRAVERSAL }

/**
 * 校验**输入框里的原始值**，必须在 [normalizeRelPath] 剥掉开头斜杠**之前**调用。
 *
 * 空值合法（表示这次不生成枚举）；非空值必须是项目根相对路径。Windows 盘符、UNC/POSIX
 * 绝对路径，以及任意 `..` 段都拒绝 —— 后者即使当前组合恰好没越界也不保留：
 * 路径在日后被移动或前缀变化时可能越过项目根，而且枚举文件没有使用上跳段的合理需求。
 *
 * 为什么单独立一个函数、而不是让 [normalizeLabelEnumFile] 顺手拒绝：归一化是**共用的**
 * （项目约定文件里的 `labelEnum.path` 也走它），在那里拒 `..` 会连"项目自己声明的路径"
 * 一起改语义；而且 [normalizeRelPath] 会把开头的 `/` 剥掉 —— 剥完就分不清
 * `/etc/x.py` 与合法的 `etc/x.py` 了。所以校验只对**用户输入**做，且在归一化之前。
 *
 * 与 VS Code 侧 `projectConfigPure.labelEnumPathInputError()` 一一对应，改一边记得改另一边。
 */
fun labelEnumPathInputError(value: String?): LabelEnumPathInputError? {
    val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    // 开头是斜杠（POSIX 绝对路径 / UNC）或盘符（`C:`）→ 绝对路径
    if (ABSOLUTE_INPUT_PREFIX.containsMatchIn(raw)) return LabelEnumPathInputError.ABSOLUTE
    if (raw.replace('\\', '/').split('/').contains("..")) return LabelEnumPathInputError.TRAVERSAL
    return null
}

/** `[\\/]` 是"反斜杠或斜杠"：`\\` 在正则里就是字面反斜杠。与 VS Code 侧同名正则同义。 */
private val ABSOLUTE_INPUT_PREFIX = Regex("""^(?:[\\/]|[A-Za-z]:)""")

/**
 * 从文件路径取"去掉 `.py` 的文件名"，用作类名的兜底。
 *
 * 自己按分隔符切（不引 `java.io.File`），纯字符串处理 → 与平台无关、好断言。
 * 与 VS Code 侧 `path.basename(filePath, '.py')` 对应。
 */
fun fileNameWithoutPy(filePath: String): String =
    filePath.substringAfterLast('/').substringAfterLast('\\').removeSuffix(".py")

/**
 * 纯文本类字段（正则、绝对路径…）：只做"非空"判断，**不做斜杠归一化**。
 *
 * ⚠️ 与 [normalizeRelPath] 分开是刻意的，混用会**静默**弄坏值：
 * - `characters.projectPath` 是绝对路径，归一化会把 POSIX 路径的开头斜杠吃掉
 *   （`/home/me/proj` → `home/me/proj`）；
 * - `characters.avatarTemplateRegex` 是正则，归一化会把 `\d` 的反斜杠换成 `/`、
 *   把尾部 `/` 吃掉，正则当场失效。
 *
 * 与 VS Code 侧 `projectConfigPure` 的 `textResolved` 一一对应。
 */
internal fun textOrNull(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

/**
 * 各条取值链的**内置兜底**。
 *
 * ⚠️ 这些是链的最后一层，**不是** `SettingsState` 里对应字段的默认值 ——
 * 后者一旦非空（本 state 里几乎都非空），"个人偏好"层就永远命中、项目声明永远不生效。
 * 所以读个人偏好必须靠 [com.alicejump.okscripttoolkit.settings.OkScriptToolkitSettings.SettingsState.overriddenKeys]
 * 记账，不能直接读 state 字段。
 *
 * 与 VS Code 侧 `src/projectConfig.ts` 的 `DEFAULT_*` 常量一一对应（值必须相同）。
 */
object ConventionDefaults {
    const val TEMPLATES_DIRECTORY = "ok_templates"
    val FEATURE_ALIASES = listOf("fL", "FeatureList", "Labels")

    const val I18N_ENABLED = true
    const val LANG_DIRECTORY = "assets/lang"
    const val PO_DIRECTORY = "i18n"
    val PO_DOMAINS = listOf("ocr")

    /** `characters.projectPath` 的兜底是**空串**：空 = 与当前项目相同。 */
    const val CHARACTER_PROJECT_PATH = ""
    const val CHARACTER_MASTER_FILE = "assets/data/characters.json"
    const val CHARACTER_SKILLS_DIRECTORY = "assets/data/character_skills"
    const val CHARACTER_LOCALE_FILE = "assets/lang/characters.json"
    const val AVATAR_TEMPLATE_REGEX = "^battle[_-]?icon[_-]?"

    const val EFFECTS_FILE = "src/data/effects.py"

    /**
     * 枚举文件路径的兜底：**空串 = 没指定**（这次不生成枚举）。
     *
     * 与 [CHARACTER_PROJECT_PATH] 一样，空串是有含义的值、不是"缺省忘了填" ——
     * 所以溯源面板必须把它渲染成一句人话（直接展示空串在列表里是一段空白，看着像坏了）。
     */
    const val LABEL_ENUM_PATH = ""

    /**
     * 枚举类名的兜底：**空串 = 没有可用的名字**，调用方退回"用文件名推导"。
     *
     * 兜底层不是一个常量而是**从文件路径算出来的**，所以这里只能放占位空串；
     * 真正求值在 `LabelEnumConvention.classNameOr(ideValue, basename)` 里。
     */
    const val LABEL_ENUM_NAME = ""
}

/** 取值链命中的那一层。与 VS Code 侧 `SettingLayer` 一一对应。 */
enum class ConventionLayer {
    /** 个人偏好（IDE 设置）—— 链的最高层 */
    PERSONAL,

    /** 项目约定文件 `ok-script-toolkit.json` */
    PROJECT,

    /** 调用方给的内置兜底 */
    BUILTIN,
}

/** 取值链的结果：**生效值**加上它**来自哪一层**。 */
data class ResolvedSetting<T>(
    val value: T,
    val layer: ConventionLayer,
)

/**
 * 通用取值链：**个人偏好 > 项目约定文件 > 内置默认**，并**同时给出命中的层**。
 *
 * ⚠️ **"来源层"必须由这条链自己产出，不要在别处另写一套判断去复算。**
 * 复算出来的层与实际生效值迟早会分叉 —— 而分叉的表现是"界面说来源是项目约定、
 * 实际生效的却是我的设置"，属于最难查的那类不一致。
 * 溯源面板（[com.alicejump.okscripttoolkit.core.conventionSourceRows]）直接消费这里的
 * `layer`，所以它永远和生效值一致。与 VS Code 侧 `projectConfigPure.resolveSetting()` 对应。
 *
 * 调用方负责把"没设置过"归一成 `null`：
 * - 标量设置看 `SettingsState.overriddenKeys`（state 默认值非空，不能直接当"用户设过"）；
 * - 列表设置看"空列表"（`featureAliases` 的 state 默认值是空列表）。
 */
internal fun <T> resolveSetting(ideValue: T?, declared: T?, fallback: T): ResolvedSetting<T> = when {
    ideValue != null -> ResolvedSetting(ideValue, ConventionLayer.PERSONAL)
    declared != null -> ResolvedSetting(declared, ConventionLayer.PROJECT)
    else -> ResolvedSetting(fallback, ConventionLayer.BUILTIN)
}

/** `templates` 一组：模板与标注资源的位置。 */
data class TemplatesConvention(
    /** 模板目录（png 切图 + coco_annotations.json），相对项目根 */
    val directory: String? = null,
    /** COCO 标注文件路径，相对项目根 */
    val cocoAnnotations: String? = null,
) {

    /**
     * 模板目录名（相对项目根），已归一化，**带来源层**。
     *
     * 取值链与全局一致：**个人偏好（IDE 设置）> 项目约定文件 > 兜底**。
     *
     * ⚠️ `ideValue` 必须是**用户真正设置过的值**，`null` / 空白表示"没设过"。
     * `SettingsState.okTemplatesDirectory` 的默认值就是 `"ok_templates"` ——
     * 若直接把 state 里的值当"个人偏好"传进来，这一层永远非空 →
     * **项目声明的目录名永远不生效**。与 [LabelEnumConvention.aliasesResolved] 是同一个陷阱。
     *
     * 历史：VS Code 侧这个设置此前是**死设置**（常量硬编码、无人读），
     * 而子仓会读（10 处）—— 属反向不对等，见 `docs/project-config.md` §8.1。
     */
    fun directoryResolved(ideValue: String?, fallback: String): ResolvedSetting<String> =
        resolveSetting(normalizeRelPath(ideValue), normalizeRelPath(directory), fallback)

    /** 只要值时的薄封装（绝大多数消费点用这个）。 */
    fun directoryOr(ideValue: String?, fallback: String): String =
        directoryResolved(ideValue, fallback).value

    /**
     * **运行时模板库**路径的声明（`templates.cocoAnnotations`），已归一化；没声明返回 `null`。
     *
     * ⚠️ 它指向的是 ok 框架加载的那份 COCO（`config.py` 的
     * `template_matching.coco_feature_json`），**不是**素材面板自己的
     * `<模板目录>/coco_annotations.json`。见 [CocoFeaturePath] 的对照表。
     *
     * 这一项**没有对应的 IDE 设置**（所以没有"个人偏好"层）—— 链是
     * `项目约定 > config.py > 依次探测两个候选`，见 [CocoFeaturePath.plan]。
     */
    fun cocoAnnotationsOrNull(): String? = normalizeRelPath(cocoAnnotations)

    companion object {
        /** 解析 `templates` 节点。非对象、字段类型不符一律当没写。 */
        fun parse(node: JsonNode?): TemplatesConvention {
            if (node == null || !node.isObject) return TemplatesConvention()
            return TemplatesConvention(
                // 与 LabelEnumConvention.parse 同理：用严格版 `stringOrNull()`，
                // `"directory": 42` 属于写错类型，应当作没写，而不是变成目录名 "42"。
                directory = node.get("directory").stringOrNull(),
                cocoAnnotations = node.get("cocoAnnotations").stringOrNull(),
            )
        }
    }
}

/** `i18n` 一组：gettext / 语言 JSON 的位置与开关。 */
data class I18nConvention(
    /** 是否读 po 数据 */
    val enabled: Boolean? = null,
    /** 语言 JSON 目录（角色名等），相对项目根 */
    val langDirectory: String? = null,
    /** gettext .po 目录，相对项目根 */
    val poDirectory: String? = null,
    /** 参与索引的 po domain */
    val poDomains: List<String> = emptyList(),
) {

    /**
     * 是否启用 gettext po 数据源。
     *
     * 项目约定文件里叫 `i18n.enabled`，IDE 设置里叫 `enablePoData` —— **名字不同**，
     * 因为前者是"这个项目的 i18n 长什么样"（团队约定），后者是"我这台机器要不要读它"。
     *
     * ⚠️ 不做真值判断：手写文件里 `"enabled": "false"` 是**字符串**，照真值算会得到
     * truthy，把开关反向锁死在开。类型不符一律当"没写"（与 VS Code 侧
     * `boolResolved` 的 `typeof === 'boolean'` 一致）。
     */
    fun enabledResolved(ideValue: Boolean?, fallback: Boolean): ResolvedSetting<Boolean> =
        resolveSetting(ideValue, enabled, fallback)

    /** 只要值时的薄封装。 */
    fun enabledOr(ideValue: Boolean?, fallback: Boolean): Boolean = enabledResolved(ideValue, fallback).value

    /** 语言 JSON 目录（角色名等），相对项目根，已归一化。 */
    fun langDirectoryResolved(ideValue: String?, fallback: String): ResolvedSetting<String> =
        resolveSetting(normalizeRelPath(ideValue), normalizeRelPath(langDirectory), fallback)

    /** 只要值时的薄封装。 */
    fun langDirectoryOr(ideValue: String?, fallback: String): String =
        langDirectoryResolved(ideValue, fallback).value

    /** gettext .po 目录，相对项目根，已归一化。 */
    fun poDirectoryResolved(ideValue: String?, fallback: String): ResolvedSetting<String> =
        resolveSetting(normalizeRelPath(ideValue), normalizeRelPath(poDirectory), fallback)

    /** 只要值时的薄封装。 */
    fun poDirectoryOr(ideValue: String?, fallback: String): String =
        poDirectoryResolved(ideValue, fallback).value

    /**
     * 参与索引的 po domain。
     *
     * ⚠️ 空列表 = "没声明"，不是"清空" —— 否则用户没法用空值表达"回到项目约定"
     * （与 [LabelEnumConvention.aliasesResolved] 同一条规则）。
     *
     * @param ideValue 调用方读到的个人偏好；空表示没设置
     */
    fun poDomainsResolved(ideValue: List<String>, fallback: List<String>): ResolvedSetting<List<String>> {
        val ide = ideValue.filter { it.isNotBlank() }
        val declared = poDomains.filter { it.isNotBlank() }
        return resolveSetting(
            ide.takeIf { it.isNotEmpty() },
            declared.takeIf { it.isNotEmpty() },
            fallback,
        )
    }

    /** 只要值时的薄封装。 */
    fun poDomainsOr(ideValue: List<String>, fallback: List<String>): List<String> =
        poDomainsResolved(ideValue, fallback).value

    companion object {
        /** 解析 `i18n` 节点。非对象、字段类型不符一律当没写。 */
        fun parse(node: JsonNode?): I18nConvention {
            if (node == null || !node.isObject) return I18nConvention()
            return I18nConvention(
                // 只认真正的 JSON 布尔：`"enabled": "false"` 属于写错类型，应当作没写。
                // 用 `asBoolean()` 会把字符串 "false" 解析成 false、字符串 "yes" 解析成 true，
                // 于是"写错了"会静默变成一个用户没打算要的取值。
                enabled = node.get("enabled")?.takeIf { it.isBoolean }?.asBoolean(),
                langDirectory = node.get("langDirectory").stringOrNull(),
                poDirectory = node.get("poDirectory").stringOrNull(),
                poDomains = node.get("poDomains")
                    ?.takeIf { it.isArray }
                    ?.mapNotNull { it.stringOrNull() }
                    .orEmpty(),
            )
        }
    }
}

/** `characters` 一组：角色数据的位置。 */
data class CharactersConvention(
    /** 角色数据所在项目根。**空 = 与当前项目相同**（角色数据放在另一个仓库时才需要填） */
    val projectPath: String? = null,
    /** 角色主数据文件，相对 [projectPath] */
    val masterFile: String? = null,
    /** 技能 JSON 目录，相对 [projectPath] */
    val skillsDirectory: String? = null,
    /** 角色名多语言文件，相对 [projectPath] */
    val localeFile: String? = null,
    /** 头像模板的命名正则（把模板名关联到角色） */
    val avatarTemplateRegex: String? = null,
) {

    /**
     * 角色数据所在项目根。
     *
     * **空字符串是合法的声明值**，含义是"与当前项目相同"（角色数据放在另一个仓库时才需要填）
     * —— 所以这里用 [textOrNull] 而**不是** [normalizeRelPath]：后者会把空串当"没写"，
     * 还会吃掉 POSIX 绝对路径的开头斜杠。消费端照旧处理 `~` 展开与 `File()`。
     */
    fun projectPathResolved(ideValue: String?, fallback: String): ResolvedSetting<String> =
        resolveSetting(textOrNull(ideValue), textOrNull(projectPath), fallback)

    /** 只要值时的薄封装。 */
    fun projectPathOr(ideValue: String?, fallback: String): String =
        projectPathResolved(ideValue, fallback).value

    /** 角色主数据文件，相对 `characters.projectPath`。 */
    fun masterFileResolved(ideValue: String?, fallback: String): ResolvedSetting<String> =
        resolveSetting(normalizeRelPath(ideValue), normalizeRelPath(masterFile), fallback)

    /** 只要值时的薄封装。 */
    fun masterFileOr(ideValue: String?, fallback: String): String = masterFileResolved(ideValue, fallback).value

    /** 技能 JSON 目录，相对 `characters.projectPath`。 */
    fun skillsDirectoryResolved(ideValue: String?, fallback: String): ResolvedSetting<String> =
        resolveSetting(normalizeRelPath(ideValue), normalizeRelPath(skillsDirectory), fallback)

    /** 只要值时的薄封装。 */
    fun skillsDirectoryOr(ideValue: String?, fallback: String): String =
        skillsDirectoryResolved(ideValue, fallback).value

    /** 角色名多语言文件，相对 `characters.projectPath`。 */
    fun localeFileResolved(ideValue: String?, fallback: String): ResolvedSetting<String> =
        resolveSetting(normalizeRelPath(ideValue), normalizeRelPath(localeFile), fallback)

    /** 只要值时的薄封装。 */
    fun localeFileOr(ideValue: String?, fallback: String): String = localeFileResolved(ideValue, fallback).value

    /**
     * 头像模板的命名正则。
     *
     * ⚠️ 走 [textOrNull] 而**不是** [normalizeRelPath] —— 这是正则不是路径，
     * 归一化会把 `\d` 里的反斜杠换掉、把首尾斜杠吃掉，正则就废了。
     */
    fun avatarTemplateRegexResolved(ideValue: String?, fallback: String): ResolvedSetting<String> =
        resolveSetting(textOrNull(ideValue), textOrNull(avatarTemplateRegex), fallback)

    /** 只要值时的薄封装。 */
    fun avatarTemplateRegexOr(ideValue: String?, fallback: String): String =
        avatarTemplateRegexResolved(ideValue, fallback).value

    companion object {
        /** 解析 `characters` 节点。非对象、字段类型不符一律当没写。 */
        fun parse(node: JsonNode?): CharactersConvention {
            if (node == null || !node.isObject) return CharactersConvention()
            return CharactersConvention(
                projectPath = node.get("projectPath").stringOrNull(),
                masterFile = node.get("masterFile").stringOrNull(),
                skillsDirectory = node.get("skillsDirectory").stringOrNull(),
                localeFile = node.get("localeFile").stringOrNull(),
                avatarTemplateRegex = node.get("avatarTemplateRegex").stringOrNull(),
            )
        }
    }
}

/** `effects` 一组：效果定义源文件。 */
data class EffectsConvention(
    /** 效果定义源文件（`EffectType` / `EFFECT_DESCRIPTIONS` 所在），相对项目根 */
    val file: String? = null,
) {

    /** 效果定义源文件，相对项目根，已归一化。 */
    fun fileResolved(ideValue: String?, fallback: String): ResolvedSetting<String> =
        resolveSetting(normalizeRelPath(ideValue), normalizeRelPath(file), fallback)

    /** 只要值时的薄封装。 */
    fun fileOr(ideValue: String?, fallback: String): String = fileResolved(ideValue, fallback).value

    companion object {
        /** 解析 `effects` 节点。非对象、字段类型不符一律当没写。 */
        fun parse(node: JsonNode?): EffectsConvention {
            if (node == null || !node.isObject) return EffectsConvention()
            return EffectsConvention(file = node.get("file").stringOrNull())
        }
    }
}

/** `labelEnum` 一组：模板标签枚举的路径、类名与引用别名。 */
data class LabelEnumConvention(
    /** 枚举文件路径，相对项目根。模块路径（不带 .py）与文件路径都容忍，见 [normalizeLabelEnumFile] */
    val path: String? = null,
    /** 枚举类名。缺席时调用方退回文件名 */
    val name: String? = null,
    /** 代码里引用该枚举的别名，如 fL */
    val aliases: List<String> = emptyList(),
) {

    /**
     * 枚举引用别名，**带来源层**。
     *
     * 别名是"代码里怎么写 import"这一**项目约定** —— 项目 `config.py` 从不声明它，
     * 所以此前只能靠内置的 `fL`/`FeatureList` 硬猜；项目把枚举导入成别的名字就完全失效。
     *
     * ⚠️ `ideValue` 必须是**用户真正设置过的值**，空列表表示"没设过"。
     * 这条不是形式主义：`SettingsState.featureAliases` 原先在 `init` 里被填了
     * `["fL", "FeatureList"]` 作默认值，于是这一层永远非空、**项目声明永远被压住**
     * （"接了等于没接"）。VS Code 侧有同一个陷阱（`package.json` 里 `featureAliases`
     * 的 `default`），那边靠 `inspect()` 区分"用户写过"与"默认值"，这边靠"默认值留空"区分。
     *
     * 两侧的空白项都按"没写"处理（声明侧是 [stringOrNull]，个人偏好侧在这里过滤）——
     * 否则一个 `" "` 别名会变成一条永远匹配不到的正则，且**静默**。
     *
     * @param ideValue 调用方读到的个人偏好（IDE 设置）；空表示没设置
     * @param fallback 内置兜底
     */
    fun aliasesResolved(ideValue: List<String>, fallback: List<String>): ResolvedSetting<List<String>> {
        val ide = ideValue.filter { it.isNotBlank() }
        val declared = aliases.filter { it.isNotBlank() }
        return resolveSetting(
            ide.takeIf { it.isNotEmpty() },
            declared.takeIf { it.isNotEmpty() },
            fallback,
        )
    }

    /**
     * 只要值时的薄封装。
     *
     * @param ideValue 调用方读到的个人偏好（IDE 设置）；空表示没设置
     * @param fallback 内置兜底
     */
    fun aliasesOr(ideValue: List<String>, fallback: List<String>): List<String> =
        aliasesResolved(ideValue, fallback).value

    /**
     * 枚举类名，**带来源层**。
     *
     * 取值链：**个人偏好（IDE 设置）> 项目约定 `labelEnum.name` > 文件名推导**。
     *
     * 兜底层是"用文件名推导"（旧行为）—— **不是常量**，所以 [fallback] 由调用方传入
     * （通常是 `filePath` 的 basename 去掉 `.py`）。溯源面板拿不到文件路径，传空串，
     * 由 `render` 渲染成一句人话（与 `characters.projectPath` 的空兜底同样处理）。
     *
     * 解耦的意义：文件可以叫 `feature_labels.py`，而类叫 `FeatureList`。
     * 旧写法只有 basename 一条路，想叫 `FeatureList` 就必须把文件命名成 `FeatureList.py`。
     *
     * ⚠️ 这个字段比其它设置危险：它**决定写进源码的类名**，而项目的代码是按名字 import 的
     * （`from src.data.feature_list import FeatureList`）。个人覆盖改错就是全项目 `ImportError`。
     * 所以消费端在**覆盖已有文件**前会先做一次类名变更校验（[LabelEnumGuard]）。
     *
     * @param ideValue 调用方读到的个人偏好（IDE 设置）；空白表示没设置
     */
    fun classNameResolved(ideValue: String?, fallback: String): ResolvedSetting<String> =
        resolveSetting(textOrNull(ideValue), textOrNull(name), fallback)

    /** 只要值时的薄封装。返回空串表示"没有名字可用"（调用方应退回文件名）。 */
    fun classNameOr(ideValue: String?, fallback: String): String = classNameResolved(ideValue, fallback).value

    /**
     * 枚举文件的**文件路径**（相对项目根，带 `.py`）。**带来源层**。
     *
     * ⚠️ 必须做一次「模块路径 → 文件路径」的转换，别直接返回声明值。
     * 本字段与项目 `config.py` 的 `label_enum_relative_path` 一样是**模块路径**
     * （`src/data/FeatureList`，**不带 .py**）—— 已核实 ok 框架的
     * `_normalize_label_enum_relative_path()`（`ok/ui/qt/tasks/TemplateTab.py`）
     * 会把用户输入的 `.py` 主动剥掉，以点分模块路径存盘。而消费端（生成枚举文件、
     * 拼绝对路径）需要的是**文件路径**：拿模块路径直接去写，会产出一个叫
     * `FeatureList`、**没有扩展名**的文件 —— Python 根本 import 不到。
     *
     * 取值链：**个人偏好（IDE 设置）> 项目约定 `labelEnum.path` > 空串（= 这次不生成）**。
     *
     * 空串 = "没有指定"。注意空串同时也是"没设置过"的归一化结果，所以用户在设置里
     * 清空它就等于"回到项目约定" —— 与 `labelEnum.aliases` 同一条规则
     * （空值表达"回到项目约定"，而不是"钉死为空"）。
     *
     * @param ideValue 调用方读到的个人偏好（IDE 设置）；空白表示没设置
     */
    fun pathResolved(ideValue: String?): ResolvedSetting<String> =
        resolveSetting(normalizeLabelEnumFile(ideValue), normalizeLabelEnumFile(path), "")

    /** 只要值时的薄封装。空串 = 没指定（调用方应跳过生成）。 */
    fun filePathOr(ideValue: String?): String = pathResolved(ideValue).value

    companion object {
        /** 解析 `labelEnum` 节点。非对象、字段类型不符一律当没写。 */
        fun parse(node: JsonNode?): LabelEnumConvention {
            if (node == null || !node.isObject) return LabelEnumConvention()
            return LabelEnumConvention(
                // 用 `stringOrNull()` 而不是 `textOrNull()`：这是**手写文件**，`"path": 42`
                // 属于写错类型，应当作没写，而不是变成一个叫 "42" 的路径。
                // 对端 VSCode 的 `nonEmpty()` 用 `typeof === 'string'` 判断，语义一致。
                path = node.get("path").stringOrNull(),
                name = node.get("name").stringOrNull(),
                aliases = node.get("aliases")
                    ?.takeIf { it.isArray }
                    ?.mapNotNull { it.stringOrNull() }
                    .orEmpty(),
            )
        }
    }
}
