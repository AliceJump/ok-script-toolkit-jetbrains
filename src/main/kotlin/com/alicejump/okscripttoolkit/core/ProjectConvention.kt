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
 * 相对路径归一化：统一成 `/` 分隔、去掉首尾斜杠；空（或只有斜杠）→ `null`。
 *
 * 为什么需要它：这个值会被 `File(root, dir)` 拼绝对路径，也会被拿去做目录段匹配。
 * 声明文件里写 `ok_templates\` 或 `/ok_templates` 时，匹配会**静默**失配 ——
 * 界面一切正常，只是"改了设置不生效"。入口处统一归一化一次。
 *
 * 与 VS Code 侧 `projectConfigPure.normalizeRelPath()` 语义一一对应，改一边记得改另一边。
 */
internal fun normalizeRelPath(value: String?): String? {
    val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val cleaned = trimmed.replace('\\', '/').trimStart('/').trimEnd('/')
    return cleaned.takeIf { it.isNotEmpty() }
}

/** 取值链命中的那一层。与 VS Code 侧 `SettingLayer` 一一对应。 */
enum class ConventionLayer {
    /** 个人偏好（IDE 设置 / 上次保存）—— 链的最高层 */
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

/** `labelEnum` 一组：模板标签枚举的路径、类名与引用别名。 */
data class LabelEnumConvention(
    /** 枚举文件路径，相对项目根，不带 .py */
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
     * 枚举类名。没声明 [name] 时用 `filePath` 反推 —— 即旧行为。
     *
     * 解耦的意义：文件可以叫 `feature_labels.py`，而类叫 `FeatureList`。
     * 旧写法只有 basename 一条路，想叫 `FeatureList` 就必须把文件命名成 `FeatureList.py`。
     *
     * 这里自己按分隔符切文件名（不引 `java.io.File`），纯字符串处理 → 与平台无关、好断言。
     */
    fun classNameOr(filePath: String): String {
        name?.let { return it }
        val fileName = filePath.substringAfterLast('/').substringAfterLast('\\')
        return fileName.removeSuffix(".py")
    }

    /**
     * 枚举文件的**文件路径**（相对项目根，带 `.py`）。
     *
     * ⚠️ 必须做一次「模块路径 → 文件路径」的转换，别直接返回声明值。
     * 本字段与项目 `config.py` 的 `label_enum_relative_path` 一样是**模块路径**
     * （`src/data/FeatureList`，**不带 .py**）—— 已核实 ok 框架的
     * `_normalize_label_enum_relative_path()`（`ok/ui/qt/tasks/TemplateTab.py`）
     * 会把用户输入的 `.py` 主动剥掉，以点分模块路径存盘。而消费端（生成枚举文件、
     * 拼绝对路径）需要的是**文件路径**：拿模块路径直接去写，会产出一个叫
     * `FeatureList`、**没有扩展名**的文件 —— Python 根本 import 不到。
     *
     * 两者都没有时返回 null，表示交给调用方用内置默认（`<目标目录>/LabelEnum.py`）。
     *
     * @param lastSaved 个人偏好（上次保存的路径）。**已经是文件路径**，原样返回、不补后缀
     */
    fun filePathOr(lastSaved: String?): String? {
        val saved = lastSaved?.takeIf { it.isNotBlank() }
        if (saved != null) return saved
        val declared = path ?: return null
        return if (declared.endsWith(".py", ignoreCase = true)) declared else "$declared.py"
    }

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
