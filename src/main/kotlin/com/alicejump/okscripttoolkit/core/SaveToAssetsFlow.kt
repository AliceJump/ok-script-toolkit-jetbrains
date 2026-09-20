package com.alicejump.okscripttoolkit.core

/**
 * 「导出到 assets」这一步的**纯逻辑**：目标列表怎么排、枚举路径要不要问。
 *
 * 与 VS Code 侧 `src/saveToAssetsPure.ts` 一一对应。
 *
 * 抽成纯对象的理由与那边一致 —— 这是**看起来像 UI、其实是数据约定**的东西，
 * 两条不变量改错都很难发现：
 *
 * 1. **什么时候可以不问用户** —— 项目约定文件已经声明了 `labelEnum.path` 时再问一遍是纯噪音；
 * 2. **不问的时候必须留一个改的口子** —— 跳过路径弹框就把它变成了"只读"。
 *
 * 而 `handleSaveToAssets` 依赖 `Project` / `ChooseDialog` / `Messages`，
 * 普通 JUnit 里构造不出来，所以决策下沉到这里。
 *
 * ⚠️ **子仓没有"上次保存的路径"这一层**（那是 VS Code 侧 `globalState` 才有的个人偏好），
 * 所以这里"有值"只可能来自项目约定文件；「修改路径」改出来的值**只对本次导出生效**。
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
     * - 项目约定文件声明了 → **不问**。那个值是团队约定好的，本来就不该反复确认。
     * - 没声明 → **必须问**：此时默认值是"`<目标目录>/LabelEnum.py`"（一个**推导**出来的值，
     *   不是谁设过的值），跳过它用户就没机会改成别的路径。
     */
    fun needsEnumPathPrompt(declaredEnumPath: String?): Boolean = declaredEnumPath == null

    /**
     * 用户选中的下标是不是「改路径」那一项。
     *
     * 用**下标区间**判断（而不是比较文案）：文案是本地化的，比较文案会在换语言时静默失效。
     */
    fun isChangePathChoice(index: Int, targetCount: Int): Boolean = index >= targetCount
}
