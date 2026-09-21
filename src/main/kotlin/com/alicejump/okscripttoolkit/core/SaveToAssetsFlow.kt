package com.alicejump.okscripttoolkit.core

import java.nio.file.Paths

/**
 * 「导出到 assets」这一步的**纯逻辑**：目标列表怎么排、枚举路径要不要问。
 *
 * 与 VS Code 侧 `src/saveToAssetsPure.ts` 一一对应。
 *
 * 抽成纯对象的理由与那边一致 —— 这是**看起来像 UI、其实是数据约定**的东西，
 * 两条不变量改错都很难发现：
 *
 * 1. **什么时候可以不问用户** —— 已经有生效的枚举路径时再问一遍是纯噪音；
 * 2. **不问的时候必须留一个改的口子** —— 跳过路径弹框之后，目标列表里那一项就是
 *    用户**顺手**改路径的唯一入口（设置界面里也有 `labelEnumPath`，但要自己知道有它、
 *    还要找到它）。少了这一项，"不问"就从"省一步"变成"绕远路"。
 *
 * 而 `handleSaveToAssets` 依赖 `Project` / `ChooseDialog` / `Messages`，
 * 普通 JUnit 里构造不出来，所以决策下沉到这里。
 *
 * ⚠️ 这里"有值"的**来源**后来变了：早先只可能来自项目约定文件，现在多了一层
 * **个人偏好**（设置界面里的 `labelEnumPath`，「修改路径…」也会写它）——
 * 于是"填过一次就记住"在两端都成立。判定逻辑不变（非空即不问）。
 */
object SaveToAssetsFlow {

    /**
     * 构造导出目标的候选列表。
     *
     * **不变量**：末尾的「改路径」项**当且仅当**会跳过路径弹框时出现。
     * 少了它 → 用户再也改不了枚举路径；多了它 → 变成"既问了又给入口"，
     * 用户看到两个都能改路径的地方。
     *
     * @param targets 保存目标（`assets` / `ok_tasks/assets`），顺序即展示顺序
     * @param declaredEnumPath 项目约定文件里声明的枚举**文件路径**；`null` = 没声明
     * @param changePathLabel 「修改 LabelEnum.py 路径…」的文案（本对象不依赖语言包，由调用方传入）
     * @return 候选列表；**前 [targets].size 个是目标**，之后（如果有）是「改路径」项
     */
    fun options(targets: List<String>, declaredEnumPath: String?, changePathLabel: String): List<String> =
        if (needsEnumPathPrompt(declaredEnumPath)) targets else targets + changePathLabel

    /**
     * 是否需要**先问一次**枚举路径。
     *
     * - 已经有生效路径（个人偏好 / 项目约定）→ **不问**。每次导出都要确认一遍是纯噪音，
     *   而且那个值是用户自己定的、或团队约定好的，本来就不该反复确认。
     * - 用户在「修改路径」里**显式清空**了它（[decided]）→ **不问**。那表达的是
     *   "回到项目约定"；再问一遍会变成"清空了还被追着问"。
     * - 其余（从没定过）→ **必须问**：此时默认值是"`<目标目录>/LabelEnum.py"`
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
     */
    fun toAbsolute(projectDir: String, value: String): String {
        val p = Paths.get(value)
        return if (p.isAbsolute) p.toString() else Paths.get(projectDir, value).toString()
    }

    /**
     * 用户选中的下标是不是「改路径」那一项。
     *
     * 用**下标区间**判断（而不是比较文案）：文案是本地化的，比较文案会在换语言时静默失效。
     */
    fun isChangePathChoice(index: Int, targetCount: Int): Boolean = index >= targetCount
}
