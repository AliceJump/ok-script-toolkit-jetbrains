package com.alicejump.okscripttoolkit.core

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle

/**
 * 生成枚举文件前的**类名变更校验**：把"个人覆盖把项目 import 弄坏"挡在写入之前。
 *
 * 与 VS Code 侧 `src/labelEnumGuard.ts` 一一对应（判据必须一致，否则两端会对同一份
 * 项目给出不同的结论）。
 *
 * 背景（`docs/project-config.md` §3）：`labelEnum.name` 与 `labelEnum.path` 现在都有
 * "个人偏好"层，而这一项比其它设置危险 —— 它**决定写进源码的类名**，项目的代码是按
 * 名字 import 的：
 *
 * ```python
 * from src.data.feature_list import FeatureList      # 项目里有 10 处这么写
 * ```
 *
 * 于是"我在设置里把类名改成 `MyEnum`"的后果不是"我这边看着不一样"，而是
 * **整个项目 `ImportError`**。而这个动作在界面上没有任何反馈 —— 导出成功的提示
 * 照样弹出来，坏掉的是下次运行脚本的时候。
 *
 * 这里不试图阻止用户改（那是他的自由），只做一件事：**覆盖一个已存在的枚举文件、
 * 且类名会变**时，先算清楚影响面、问一句。
 *
 * **为什么只校验类名、不校验路径**：换路径时旧文件原样留着，按旧模块路径 import 的
 * 代码仍然 import 得到（只是拿不到新标签），不会报错；而改类名是**同一个文件里名字变了**，
 * 引用方当场全废。两者的后果不对称，所以只给前者加闸。
 *
 * 抽成纯对象是为了能在普通 JUnit 里断言（本仓库的既有约定：不依赖 IDE 的逻辑抽纯对象
 * 配单测，见 `TaskConfigMerge` / `TempShotFiles` 等）；扫项目文件那步是 IO，
 * 留在 `ui/TemplateAssetToolWindowFactory`。
 */
object LabelEnumGuard {

    /** 类名非法时退回的默认名（与 VS Code 侧 `FALLBACK_ENUM_CLASS_NAME` 同值）。 */
    const val FALLBACK_ENUM_CLASS_NAME = "LabelEnum"

    private val PYTHON_IDENTIFIER = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
    private val CLASS_DECLARATION = Regex("^[ \t]*class[ \t]+([A-Za-z_][A-Za-z0-9_]*)", RegexOption.MULTILINE)

    /**
     * **真正会写进源码的类名**：非法标识符退回 [FALLBACK_ENUM_CLASS_NAME]。
     *
     * 与 `TemplateAssetDataService.generateLabelEnum` **共用**这一个函数。各写一遍的后果是
     * 校验拿"用户填的名字"去比、而文件里写的是"兜底名字"，于是警告内容与实际不符 ——
     * 比如用户把类名填成 `2Bad`，面板会报"要从 `LabelEnum` 改名为 `2Bad`"，
     * 而实际写进去的还是 `LabelEnum`，什么都没变。**一句不成立的警告比没有警告更糟**。
     */
    fun writableClassName(raw: String): String =
        if (PYTHON_IDENTIFIER.matches(raw)) raw else FALLBACK_ENUM_CLASS_NAME

    /**
     * 从 Python 源码里取第一个 `class X(...)` 的类名。
     *
     * 只看行首（允许缩进）的 `class`，避免匹配到字符串字面量或注释里的 `class ` ——
     * 生成出来的枚举文件是 `class FeatureList(str, Enum):` 顶格一行，够用。
     */
    fun extractClassName(source: String): String? =
        CLASS_DECLARATION.find(source)?.groupValues?.get(1)

    /**
     * 这段源码是否**按名字 import** 了 [name]。
     *
     * 覆盖 `from a.b import X`、`from a.b import (A, X)`、`from a.b import X as fL`、
     * `import a.b.X` 这几种写法 —— 判据是"同一行里既有 `import` 又有这个名字"。
     *
     * 宁可多报不可漏报：这里产出的是**给用户看的提示**，不是自动决策。误报的代价是
     * 多问一句（用户点"继续"即可），漏报的代价是项目静默 import 失败。
     * 唯一的例外是 `from a.b import *`（连名字都没写），那种确实查不出来 —— 也没法查。
     */
    fun importsName(source: String, name: String): Boolean {
        if (name.isEmpty()) return false
        // `[^\n]*` 只吃同一行，所以跨行的 `class X:` 之类不会误命中
        return Regex("(^|[^\\w.])import[ \\t][^\\n]*\\b${Regex.escape(name)}\\b")
            .containsMatchIn(source)
    }

    /** 覆盖已有枚举文件时的"改名影响面"。 */
    data class RenameImpact(
        /** 目标文件里**现有**的类名（旧名字）；空串 = 文件在、但认不出类名 */
        val existingClassName: String,
        /** 这次要写入的类名（新名字） */
        val newClassName: String,
    )

    /**
     * 要不要就"类名变了"问一句。返回 `null` = **不必问**。
     *
     * 两种**不必问**的情形（常规操作，打扰用户就是噪音）：
     *
     * 1. **目标文件不存在**（[existingSource] 为 `null`）—— 全新生成，没有"旧名字"可废；
     * 2. **新旧类名相同** —— 常规的"重新生成一遍"，每次导出都会发生。
     *
     * 一种**必须问**的情形：目标文件存在、内容也会被覆盖，但新旧类名不同 —— 此时按旧类名
     * import 的代码会全部失效。这包括"文件在、却读不出类名"（`existingClassName` 为空串）：
     * 用户很可能把路径填到了一个**普通模块**上，覆盖它会直接删掉那个文件里的东西。
     */
    fun renameImpact(existingSource: String?, newClassName: String): RenameImpact? {
        if (existingSource == null) return null
        val existingClassName = extractClassName(existingSource).orEmpty()
        if (existingClassName == newClassName) return null
        return RenameImpact(existingClassName, newClassName)
    }

    /**
     * 从「文件相对路径 + 源码」里挑出按 [name] import 的那些路径（**全部**，已排序）。
     *
     * 返回全部而不是截断后的前几个：调用方要分别拿到"会炸多少处"（总数）和
     * "前几个是谁"（展示用），截断放在调用方做。
     *
     * @param files 已经读进来的源码（IO 由调用方做 —— 本对象不碰文件系统）
     * @param name 旧类名；空串（读不出旧名字）时**返回空数组**：
     *   那种情况下"谁引用了它"没有意义，提示文案会换成"文件内容会被覆盖"。
     */
    fun referencingFiles(files: List<Pair<String, String>>, name: String): List<String> =
        if (name.isEmpty()) emptyList()
        else files.filter { importsName(it.second, name) }.map { it.first }.sorted()

    /**
     * 拼给用户看的那句话。
     *
     * [referencingFiles] 是命中的**全部**文件；文案里只列前 [maxListed] 个，
     * 但**总数照实报** —— 用户要的是"会炸多少处"，不是"前 5 个是谁"。
     */
    fun renameMessage(
        impact: RenameImpact,
        referencingFiles: List<String>,
        maxListed: Int = 5,
    ): String {
        val target = if (impact.existingClassName.isNotEmpty()) {
            OkScriptToolkitBundle.message("labelEnum.rename.currentClass", impact.existingClassName)
        } else {
            OkScriptToolkitBundle.message("labelEnum.rename.unrecognized")
        }
        val rename = if (impact.existingClassName.isNotEmpty()) {
            OkScriptToolkitBundle.message("labelEnum.rename.willRename", impact.newClassName)
        } else {
            OkScriptToolkitBundle.message("labelEnum.rename.willWrite", impact.newClassName)
        }
        val refs = if (referencingFiles.isNotEmpty()) {
            OkScriptToolkitBundle.message(
                "labelEnum.rename.references",
                referencingFiles.size,
                impact.existingClassName,
                referencingFiles.take(maxListed).joinToString(", "),
            )
        } else {
            ""
        }
        return listOf(target, rename, refs).filter { it.isNotEmpty() }.joinToString(" ")
    }
}
