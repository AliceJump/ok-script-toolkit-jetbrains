package com.alicejump.okscripttoolkit.core

import java.nio.file.Paths

/**
 * 「导出到 assets」这一步的**纯逻辑**：目标列表怎么排、枚举的路径与类名怎么给入口。
 *
 * 与 VS Code 侧 `src/saveToAssetsPure.ts` 一一对应。
 *
 * 抽成纯对象的理由与那边一致 —— 这是**看起来像 UI、其实是数据约定**的东西，
 * 几条不变量改错都很难发现：
 *
 * 1. **保存目标永远在，且顺序不变** —— 它们是这个对话框存在的理由。
 * 2. **「枚举路径」「枚举类名」两项永远在** —— 用户要能在**点保存的这一步**就把这两项
 *    设好，不必先去设置界面找（设置界面里确实有，但要先知道有它、还要找到它）。
 *    点的是哪一类用**下标区间**判定，不比较文案：文案是本地化的，比较文案会在换语言时
 *    静默失效。
 * 3. **首次仍然会先问一次路径**（见 [needsEnumPathPrompt]）—— "不问"会让从没配过的
 *    用户**静默拿不到枚举文件**。这两件事（问一次 + 也有两项）并存是**刻意的**：
 *    问一次保证不会漏，两项保证之后随时能改。
 *
 * 而 `handleSaveToAssets` 依赖 `Project` / `ChooseDialog` / `Messages`，
 * 普通 JUnit 里构造不出来，所以决策下沉到这里。
 *
 * ⚠️ 这里"有值"的**来源**后来变了：早先只可能来自项目约定文件，现在多了一层
 * **个人偏好**（设置界面里的 `labelEnumPath` / `labelEnumName`，对话框里的两项也会写它）
 * —— 于是"填过一次就记住"在两端都成立。
 */
object SaveToAssetsFlow {

    /** 候选列表里每一项的角色。 */
    enum class Choice { TARGET, ENUM_PATH, ENUM_NAME }

    /**
     * 候选列表 + 每项的角色。
     *
     * 角色用**下标区间**判定：前 [targetCount] 个是保存目标，紧接一个路径项、一个类名项。
     * 不比较文案 —— 文案是本地化的，比较文案会在换语言时静默失效。
     */
    data class Options(val labels: List<String>, val targetCount: Int) {
        fun roleOf(index: Int): Choice = when {
            index < targetCount -> Choice.TARGET
            index == targetCount -> Choice.ENUM_PATH
            else -> Choice.ENUM_NAME
        }
    }

    /**
     * 构造导出目标的候选列表：**保存目标 → 枚举路径 → 枚举类名**。
     *
     * 目标在前是因为"保存"才是这一步的主意图；两项枚举设置放末尾，扫一眼就知道有、
     * 又不会挡在目标前面。
     *
     * @param targets 保存目标（`assets` / `ok_tasks/assets`），顺序即展示顺序
     * @param enumPathChoice 「枚举文件路径」那一行的完整文案（含当前值，见 [choiceLine]）
     * @param enumNameChoice 「枚举类名」那一行的完整文案
     */
    fun options(targets: List<String>, enumPathChoice: String, enumNameChoice: String): Options =
        Options(targets + enumPathChoice + enumNameChoice, targets.size)

    /**
     * 把「标签 + 当前值」拼成候选列表里那一行。
     *
     * `value` 为空时用 `empty` 兜底 —— **必须让用户看到"这里能点"**，否则他以为没有这个入口
     * （那正是"设置藏在设置界面里、找不到"的同一个问题）。
     * 冒号写在 [label] 里（随语言包走），所以这里不做任何标点拼接 ——
     * 中文用「：」、西文用「: 」，硬编码任何一个都会在另一种语言下显得别扭。
     */
    fun choiceLine(label: String, value: String?, empty: String): String =
        label + (value?.takeIf { it.isNotBlank() } ?: empty)

    /**
     * 首次导出时给输入框预填的**推导值**：`<目标目录>/LabelEnum.py`（相对项目根的写法）。
     *
     * 为什么不预填绝对路径：那个输入框的提示语写的是"相对于项目根目录"，预填绝对路径
     * 与提示语自相矛盾。而且存进设置前也要转回相对，来回转换没有意义。
     * 与 VS Code 侧 `derivedEnumPath()` 同值。
     */
    fun derivedEnumPath(targetLabel: String): String {
        val dir = targetLabel.replace('\\', '/').trim('/')
        return if (dir.isEmpty()) "LabelEnum.py" else "$dir/LabelEnum.py"
    }

    /**
     * 是否需要**先问一次**枚举路径。
     *
     * - 已经有生效路径（个人偏好 / 项目约定）→ **不问**。每次导出都要确认一遍是纯噪音，
     *   而且那个值是用户自己定的、或团队约定好的，本来就不该反复确认。
     * - 用户在「修改路径」里**显式清空**了它（[decided]）→ **不问**。那表达的是
     *   "回到项目约定"；再问一遍会变成"清空了还被追着问"。
     * - 其余（从没定过）→ **必须问**：此时默认值是"`<目标目录>/LabelEnum.py`"
     *   （一个**推导**出来的值，不是谁设过的值），跳过它用户就没机会改成别的路径。
     */
    fun needsEnumPathPrompt(enumPath: String?, decided: Boolean = false): Boolean = !decided && enumPath == null

    /**
     * 把用户在对话框里填的路径转成**相对项目根**的写法，用于存进设置。
     *
     * 为什么必须转：设置里的值要跟"项目在哪"无关 —— 存绝对路径的话，换个检出目录
     * （或同事用同一个配置文件）就指向了不存在的地方。填的本来就是相对路径时原样返回；
     * 填的是项目**外**的绝对路径时相对化不了，只能原样存（消费端按"绝对路径优先"处理）。
     *
     * 用 `java.nio.file.Paths` 而不是 `File`：这两步是纯路径运算，没有 IO，
     * 放在这里就能在普通 JUnit 里断言。
     */
    fun toProjectRelative(projectDir: String, value: String): String {
        val abs = Paths.get(value)
        if (!abs.isAbsolute) return value
        val root = Paths.get(projectDir).toAbsolutePath().normalize()
        val target = abs.toAbsolutePath().normalize()
        return if (target.startsWith(root)) {
            root.relativize(target).toString().replace('\\', '/')
        } else {
            value
        }
    }

    /**
     * 把设置里的值解析成**绝对路径**。相对与绝对都容忍。
     *
     * 相对路径按 [projectDir] 解析；已经是绝对路径的原样返回 —— 否则用户从别处
     * 复制来一个绝对路径会被拼成 `<项目根>/D:/other/x.py`，报一个看不懂的错。
     *
     * ⚠️ 消费端（[TemplateAssetDataService.saveToAssets]、`confirmLabelEnumRename`）
     * 要的都是**绝对路径**，所以对话框拿到用户输入后必须先归一化（补 `.py`）再走这里，
     * 不能把裸输入直接传下去 —— 裸输入既可能没有扩展名、又可能是相对写法。
     */
    fun toAbsolute(projectDir: String, value: String): String {
        val p = Paths.get(value)
        return if (p.isAbsolute) p.toString() else Paths.get(projectDir, value).toString()
    }
}
