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
) {

    companion object {
        /** 没有约定文件、或文件不可用时的取值。所有链都会退到各自的兜底。 */
        val EMPTY = ProjectConvention()

        private val JSON = ObjectMapper()

        /** 解析约定文件内容。任何字段缺失/类型不符都退回该字段的默认值，不抛异常。 */
        fun parse(root: JsonNode?): ProjectConvention {
            if (root == null || !root.isObject) return EMPTY
            return ProjectConvention(labelEnum = LabelEnumConvention.parse(root.get("labelEnum")))
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
     * 枚举引用别名。
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
     * @param ideValue 调用方读到的个人偏好（IDE 设置）；空表示没设置
     * @param fallback 内置兜底
     */
    fun aliasesOr(ideValue: List<String>, fallback: List<String>): List<String> {
        if (ideValue.isNotEmpty()) return ideValue
        if (aliases.isNotEmpty()) return aliases
        return fallback
    }

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
