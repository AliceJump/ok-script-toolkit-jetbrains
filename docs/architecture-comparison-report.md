# VS Code 扩展与 JetBrains 插件：功能取舍与实现策略差异总结报告 / VS Code Extension vs JetBrains Plugin: Feature Trade-offs and Implementation Strategy Differences Summary Report

<div align="center">

[![简体中文](https://img.shields.io/badge/Language-%E7%AE%80%E4%BD%93%E4%B8%AD%E6%96%87%20%E2%9C%93-2EA043?style=for-the-badge)](architecture-comparison-report.md) [![English](https://img.shields.io/badge/Language-English-6E7681?style=for-the-badge)](architecture-comparison-report.en.md)

</div>

基于 `parity-review.md`（2026-09-06）及源代码分析，总结主仓库（VS Code 扩展）与子仓库（JetBrains 插件）之间的架构设计、功能完整性、性能优化、UI/UX 体验、开发维护成本差异，并提出未来改进建议。

Based on `parity-review.md` (2026-09-06) and source code analysis, this report summarizes the differences in architecture design, feature completeness, performance optimization, UI/UX experience, and development/maintenance costs between the main repository (VS Code extension) and sub-repository (JetBrains plugin), and proposes future improvement suggestions.

> ⚠️ **本文档是 2026-09-06 的快照，部分结论已被推翻 —— 读之前先看这段。**
>
> 它的基线是**已被判定失真**的那版 `parity-review.md`（2026-09-06），所以把一批当时尚未做、
> **后来都实现了**的功能写成了「❌ 缺失」。2026-09-21 逐条回查代码后确认：
> 第 2 节表里的 **#5 / #6 / #7 / #8 / #10 全部已实现**，#9 是**载体差异**（子仓走 IntelliJ
> 原生 Keymap，不需要插件自己的快捷键设置）；第 6.2 / 6.3 / 6.4 三节解释"为什么缺失"的
> 功能**都已经有了**，那三节现在只有**历史价值**（记录当时权衡过什么）。
> 各节开头已就地标注。**架构与取舍分析（第 1、3、4、5 节）仍然有效。**
>
> 权威来源是代码与测试；`parity-review.md` 是当前的功能对照表。

> 📏 **行数是 2026-09-21 重新实测的**（原文那批停在 2026-09-06，其中
> `TaskLauncherToolWindowFactory.kt` 已从 925 行涨到 **2044 行**）。

---

## 1. 架构设计哲学差异

### VS Code 扩展：模块化、细粒度设计

- **独立数据源**：每个数据源（Lang、Feature、Effect、Character、TemplateAsset）有独立的 TypeScript 类（`LangData`、`FeatureData`、`EffectData`、`CharacterData`、`TemplateAssetData`），各自管理生命周期、缓存和刷新。
- **Worker 线程池**：图像处理（PNG 裁剪/缩略图、saveToAssets 打包）使用独立 Worker 线程池（`pngCropWorker.ts`、`assetPackWorker.ts`），主线程零阻塞。
- **Webview 隔离**：每个工具面板（模板画廊、任务启动器、角色管理、素材管理、标注编辑器）运行在独立的 Webview 中，通过消息传递与宿主通信，UI 与逻辑完全分离。
- **多层缓存**：缩略图文件缓存 + 内存 LRU + Worker 预热；任务 schema 磁盘缓存；语言数据内存快照。
- **文件监听**：通过 `createFileSystemWatcher` 实时监听数据文件变更，300ms 防抖后选择性刷新对应数据源。

**关键文件**：`extension.ts`（442 行）、`pngCrop.ts`（1080 行）、`assetPack.ts`（206 行）、`assetPackWorker.ts`（315 行）

### JetBrains 插件：集中式、统一快照设计

- **统一数据中心**：`OkProjectDataService` 是唯一的项目级数据服务，内含 `Snapshot` 数据类，将 Lang、Feature、Effect 三大数据源合并为一个不可变快照，所有消费者从同一快照读取。
- **并发安全**：`@Volatile` + `ConcurrentHashMap` + `AtomicLong` 保证多线程安全，但无 Worker 线程池——图像处理在 `CompletableFuture` 线程池中执行。
- **平台服务**：利用 IntelliJ Platform 的 `@Service(Service.Level.PROJECT)` 注解自动管理生命周期，无需手动 dispose。
- **单线程缩略图加载**：`TemplatesToolWindowFactory` 使用 `Executors.newSingleThreadExecutor` 顺序加载缩略图。
  **单线程是有意的**（不是没做并发）：解一张原图 → 立刻裁完它上面的全部模板 → 释放，
  任意时刻只持有一张解码后的原图（2560×1440 ARGB ≈ 15MB）；换线程池要同时持有 N 张。
  **2026-09-21 已修掉真正的瓶颈**：原先是"**每个模板各解一次原图**"，而实测
  ok-end-field 是 276 模板 / **16** 张图（**17:1**）—— 同一张图被解 17 遍。
  现改为**按源图分组**（`core/TemplateThumbBatch.kt` + 单测），与父仓 `warmCropCache`
  的"按图分组 + 一次解码多张裁剪"对齐。
  **并补上了持久缓存**（`core/TemplateThumbCache.kt`）：键 = **原图内容 sha1 + bbox + 目标高度**，
  落在 `<IDE system>/ok-script-toolkit/template-thumbs/<项目哈希>/`（与 `TempScreenshotStore`
  同一套约定，**不写进项目目录**）。命中时**连原图都不用解码**，只读一张几 KB 的小 PNG。
  内容 hash 按 (size, mtime) 记忆化，所以不是每次渲染都读整个文件；
  用内容而不是路径做键，是因为"同路径换图后复用旧缩略图"正是父仓踩过的坑。

**关键文件**：`OkProjectDataService.kt`（479 行）、`TaskLauncherToolWindowFactory.kt`（2044 行）、`TemplatesToolWindowFactory.kt`（476 行）

| 维度| VS Code | JetBrains |
|------|---------|-----------|
| 数据架构| 多个独立 Data 类 + 300ms 防抖| 单一 Snapshot + 文件修改时间戳|
| 图像处理| Worker 线程池（2-4 核）| CompletableFuture 线程池|
| UI 隔离| Webview（HTML/CSS/JS） | Swing JPanel（原生）|
| 生命周期管理| 手动 disposables| IntelliJ @Service 自动管理|

---

## 2. 功能完整性差异

### 已对齐功能（11 项）

| 功能| VS Code | JetBrains |
|------|---------|-----------|
| 语言键补全| ✅ | ✅ |
| OCR match 补全| ✅ | ✅ |
| 效果 ID 补全| ✅ | ✅ |
| 模板画廊（响应式网格、搜索、插入/复制）| ✅ | ✅ |
| 任务启动（AST 列任务、schema 探测、暂停/恢复）| ✅ | ✅ |
| 参数控件（bool/数字/下拉/多选/级联/条件序列）| ✅ | ✅ |
| 角色面板（只读部分）| ✅ | ✅ |
| 素材库基础操作（导入、网格浏览）| ✅ | ✅ |
| 标注基础操作（查看、添加/删除标注）| ✅ | ✅ |
| 6 语言 UI| ✅ | ✅ |
| 设置项| ✅ | ✅ |

### JetBrains 缺失/精简功能（按严重度排序）

> ⚠️ **本表是 2026-09-06 的状态。2026-09-21 逐条回查代码后，原来标「❌ 缺失」的 5 项
> 全部已实现**（状态列已就地更新，说明列保留原文以便对照）。现在真正的功能缺口见
> `parity-review.md` 的「⚠️ 待办」表 —— 那里剩的都是低优先级 UI 形态差异。

| # | 功能| 严重度| 说明|
|---|------|--------|------|
| 1 | saveToAssets 打包导出| ✅ 已交付| bin-packing 多页合成 + COCO 重写|
| 2 | 游戏窗口截图采集| ✅ 已交付| probe 自动探测 + 截图并注册|
| 3 | 角色 CRUD（添加/编辑/删除技能）| ✅ 已交付| ~~强化组编辑待后续~~ 强化组编辑也已实现（`CharacterDialogs.kt` / `CharacterDataMutations.kt`）|
| 4 | 角色头像| ✅ 已交付| 表格头像列|
| 5 | 标注编辑器进阶交互| ✅ **已实现**（2026-09-21 复核）| undo/redo、copy/paste、8 向 resize、拖动移框、缩放平移、跨图导航、双击数值编辑 —— 均已在 `AnnotationDialog.kt`；**仅「改动即存」是设计取舍**（现为 OK/Cancel 语义）|
| 6 | sub_configs 子配置树| ✅ **已实现**| 折叠树 + 条件显隐（规则收敛到 `tasklauncher/SchemaTreeOverlap.kt`，有单测）|
| 7 | 调试浮层| ✅ **已实现**| 工具箱浮层开关 + 运行时参数注入（`ToolboxService` / `TaskRunnerService` / `PythonScriptRunner`）|
| 8 | 文件监听自动刷新| ✅ **已实现**| `core/OkDataChangeService.kt`：VFS `BulkFileListener` + 300ms 防抖 + 广播，与父仓 `createFileSystemWatcher` 派发对称|
| 9 | 标注快捷键配置| ◐ **载体差异**，非缺失| 子仓在 `plugin.xml` 里声明快捷键（`control alt T` / `control alt S`），用户在 IntelliJ 原生 **Settings → Keymap** 里改；父仓的 `annotationKeybindings` 设置是因为 webview 要自己处理按键|
| 10 | 条件可见性系统| ✅ **已实现**| 折叠分组吸收内联显隐（见 `SchemaTreeOverlap`）|

---

## 3. 性能优化策略差异

### VS Code 扩展

| 策略| 实现| 效果|
|------|------|------|
| Worker 线程池（缩略图）| `pngCropWorker.ts`：纯 JS PNG 解码 + 裁剪 + 缩放| 主线程零阻塞，hover 响应 < 10ms|
| Worker 线程池（saveToAssets）| `assetPackWorker.ts`：PNG 解码 + level-6 deflate + 写盘| 多页并行渲染，4K 图 ≈ 33MB/worker|
| 多层缓存| 文件缓存（磁盘）+ 内存 LRU + Worker 预热| 首次激活预热全部缩略图|
| 选择性缓存失效| `clearCropCacheForImage` 按来源目录清除| 仅失效变更文件对应的缩略图|
| 300ms 防抖| `DEBOUNCE_MS = 300` | 避免频繁刷新|

### JetBrains 插件

| 策略| 实现| 效果|
|------|------|------|
| Snapshot 不可变快照| `@Volatile` + `ConcurrentHashMap` | 读操作无锁|
| 文件修改时间戳| `fileStamps` + `lastRefreshAttempt` | 避免重复扫描|
| 单线程缩略图加载| `Executors.newSingleThreadExecutor` | 顺序加载，无并发竞争|
| 30s TTL 缓存| `okTemplateCocoCache` / `okTemplateCocoMissCache` | 减少重复 COCO 解析|
| 400ms 防抖保存| `javax.swing.Timer(400)` | 避免 EDT 高频文件写入|

**性能对比结论**：VS Code 扩展通过 Worker 线程池实现了真正的并行处理，适合图像密集型操作；JetBrains 插件依赖平台线程池和不可变快照，在轻量场景下足够，但图像处理能力受限。

---

## 4. UI/UX 体验差异

| 维度| VS Code (Webview) | JetBrains (Swing) |
|------|-------------------|-------------------|
| 渲染技术| HTML/CSS/JS | JPanel + Graphics2D |
| 自定义程度| 完全自由（Canvas/WebGL）| 受限于 Swing 组件|
| 标注编辑器| HTML5 Canvas + 完整交互| **Swing `Graphics2D` + 完整交互**（`AnnotationDialog.kt`：画框/删除/undo·redo/copy·paste/8 向 resize/拖动移框/缩放平移/跨图导航/双击改数值）|
| 任务配置面板| 结构化 JSON 编辑器| 结构化控件（`JCheckBox` / `JComboBox` / `JSpinner` / `ModifyListDialog`）+ 条件序列的 JSON 编辑|
| 响应式布局| CSS Grid/Flexbox | GridLayout + 手动计算列数|
| 主题适配| 跟随 VS Code 主题| JBColor 自动适配 Light/Dark|
| 原生集成度| 低（沙箱环境）| 高（平台 Action/Notification）|

### 原生集成 vs 灵活定制

| 能力| VS Code | JetBrains |
|------|---------|-----------|
| 工具窗口| WebviewView（侧边栏）| ToolWindow（可停靠）|
| 快捷键| keybindings JSON | KeyboardShortcut XML |
| 命令面板| registerCommand | AnAction + group |
| 通知| InformationMessage | NotificationGroup |
| 右键菜单| 编辑器上下文菜单| EditorPopupMenu |
| 设置界面| configuration JSON | ProjectConfigurable |
| 调试浮层| overlay HTML | ❌ 缺失|

---

## 5. 开发维护成本差异

### 代码量对比

| 维度| VS Code | JetBrains |
|------|---------|-----------|
| 宿主源码（TS/Kt）| 11,065 行（30 文件）| 16,337 行（55 文件）|
| Webview UI 代码| 5,877 行（28 文件，HTML/CSS/JS）| —（Swing 原生 UI，无独立资源）|
| Python 辅助脚本| 2,316 行（7 文件）| —（整包复用父仓 `python/`）|
| 测试代码| 4,430 行（18 文件）| 5,318 行（34 文件）|
| **总计**| **~23,700 行**| **~21,700 行**|

> ⚠️ **结论已反转**：2026-09-06 那版记的是 VS Code ~11,700 行 / JetBrains ~6,176 行，
> 据此隐含"子仓是更轻量的实现"。现在两端**总量相当**，而子仓的**宿主源码反而更多**
> （16.3k vs 11.1k）—— 因为 Swing 没有 HTML/CSS 可复用，UI 也得用 Kotlin 写。
> 原文那个"子仓更简单"的印象已经不成立了。

### 可维护性对比

| 维度| VS Code | JetBrains |
|------|---------|-----------|
| 模块耦合度| 低（Data 类独立，Webview 消息传递）| 中（Snapshot 耦合所有数据）|
| 类型安全| TypeScript 强类型| Kotlin 强类型 + data class|
| 错误处理| try/catch + Promise rejection | try/catch + CompletableFuture |
| 国际化| l10n 目录 + package.nls | ResourceBundle |
| 发布流程| vsce package + Marketplace | Gradle buildPlugin + Marketplace |
| 版本同步| npm run version:sync | gradle.properties |

### 测试覆盖率对比

| 维度| VS Code | JetBrains |
|------|---------|-----------|
| 单元测试文件| 18（13 个 Node 脚本 + 5 个 Python）| 34 / 34 |
| 测试行数| 4,430 行| 5,318 行|
| 测试执行入口| `npm test`（10 个套件串起来）| `./gradlew test`（250 条用例）|
| 集成测试| 无| 无|
| E2E 测试| 无| 无|

**结论**：~~两端测试覆盖率均偏低~~ **已过时** —— 现在两端都有成体系的测试：VS Code 侧
`npm test` 串了 10 个套件（含语言包对等、取值链、打包产物泄漏、执行器沙箱），
子仓 250 条用例（含纯对象单测 + 源码扫描类守卫）。**共同的做法是"不变量 + 容易静默改坏
→ 抽纯对象配单测"，并配破坏性对照**（就地改坏编译产物，证明断言真的在约束东西）。
仍然没有的是端到端测试（要真起 IDE + 真跑游戏，两端都没做）。

---

## 6. 关键取舍决策分析

> ⚠️ **6.2 / 6.3 / 6.4 三节解释的「为什么缺失」，对应功能现在都已实现**（2026-09-21 复核）。
> 那三节保留下来是作为**当时权衡过程的历史记录** —— 里面写的平台差异与实现复杂度判断
> 仍然成立，只是结论（"因此不做"）已被推翻。**别按它们判断当前状态。**
> 6.1 里除「改动即存」之外的差距同样已补齐。

### 6.1 标注编辑器功能差距的原因

**VS Code 版（完整）**：`annotationPanel.ts`（261 行）+ `media/annotationPanel/`（HTML/CSS/JS），使用 HTML5 Canvas 实现：
- undo/redo 栈、copy/paste 剪贴板、8 向 resize 手柄
- 拖动移框、鼠标滚轮缩放+平移、跨图导航（左右箭头）
- 双击数值编辑、改动即存（自动 save）
- 快捷键可配置（`annotationKeybindings` 设置项）

**JetBrains 版（仅基础）**：`TemplateAssetPanel` 中集成，使用 Swing JPanel：
- 仅支持查看标注、添加/删除标注
- 无 undo/redo、无 copy/paste、无 resize 手柄
- 无缩放平移、无跨图导航

**取舍原因**：
1. **开发成本**：Canvas 标注编辑器实现复杂度高（~260 行 TS + ~1500 行 JS），Swing 重写工作量巨大
2. **平台限制**：IntelliJ 没有内建的图像标注组件，需从零构建
3. **优先级**：标注编辑器是低频操作，优先级低于核心语言功能和任务启动

### 6.2 调试浮层功能缺失的原因

**VS Code 版**：`taskLauncher.ts` 中 `overlayActive` 状态 + Webview 消息传递 + `run_executor.py` 环境变量注入

**JetBrains 版**：完全缺失

**取舍原因**：
1. **运行时注入机制不同**：VS Code 通过环境变量 `OK_LANG_HINTS_INJECT` 传递，JetBrains 的 `TaskLauncherService.buildRunTaskCommand` 未实现此机制
2. **UI 层面**：VS Code 的 overlay 是 Webview 中的浮动 div，Swing 中需实现 JWindow/JDialog 浮动窗口
3. **使用场景有限**：调试浮层主要用于开发期实时预览，非核心功能

### 6.3 条件可见性系统缺失的原因

**VS Code 版**：任务参数面板支持 `condition` 字段，根据其他参数值动态显隐字段

**JetBrains 版**：`createFieldComponent` 中无 condition 逻辑

**取舍原因**：
1. **schema 数据已返回**：`TaskSchema.configGroups` 已传递，但 UI 层未实现 condition 监听
2. **实现复杂度**：需要为每个字段注册 ChangeListener，构建依赖图，实时更新可见性
3. **当前 configGroups 分组已满足大部分需求**

### 6.4 文件监听机制缺失的原因

**VS Code 版**：`createFileSystemWatcher` + `getAffectedSources` 精确分类 + 300ms 防抖刷新

**JetBrains 版**：`parity-review.md` 第 7 项明确记录"无文件 watcher"

**取舍原因**：
1. **平台差异**：IntelliJ 有 `VirtualFileListener` 和 `BulkFileListener`，但需要手动注册和管理
2. **快照模型限制**：`OkProjectDataService` 的 `refresh()` 方法已实现，但缺少触发入口
3. **用户习惯**：JetBrains 用户习惯手动刷新（Refresh 按钮），实时刷新可能带来性能开销

---

## 7. 未来改进方向建议

### 7.1 JetBrains 插件需要优先补齐的功能

> ⚠️ **原表列的 7 项全部已完成**（2026-09-21 复核：文件监听、标注编辑器进阶交互、
> sub_configs 子配置树、条件可见性、调试浮层、标注快捷键、强化组编辑）。
> 下面换成**现在真正剩下的**缺口 —— 与 `parity-review.md` 的「⚠️ 待办」表一致，都是低优先级。

| 优先级| 功能| 价值|
|--------|------|------|
| P2 | 注释面板独立命令（父仓有 `openAnnotationEditor`；子仓只能从素材管理器进入）| 低（多一步进入）|
| P3 | 编辑器内嵌大画廊双入口| 低|
| P3 | 任务卡片式 UI| 低（载体差异）|
| P3 | `lastPythonEditor` 跟踪（插入表达式时定位最近编辑器）| 低|
| — | 标注编辑器「改动即存」| **设计取舍，非缺陷**：子仓是 OK/Cancel 语义（`doOKAction` 时统一写回，Cancel 全弃）|

### 7.2 两端功能对齐的优先级排序

**已全部完成**（2026-09-21 复核）

| 阶段| 项目| 状态|
|---|---|---|
| 一| saveToAssets 导出| ✅ |
| 一| 截图采集| ✅ |
| 一| 角色 CRUD + 头像 + 强化组编辑| ✅ |
| 一| 标注编辑器进阶交互| ✅（除「改动即存」，属设计取舍）|
| 一| 文件监听自动刷新| ✅ `OkDataChangeService` |
| 二| sub_configs 子配置树| ✅ |
| 二| 条件可见性系统| ✅ |
| 二| 调试浮层| ✅ |
| 二| 标注快捷键配置| ✅（走 IntelliJ 原生 Keymap，属载体差异）|

**第三阶段（体验优化）—— 仍未做**
1. ~~缩略图加载优化~~ → **已完成**（2026-09-21）。诊断也被修正过：瓶颈不是"没并发"，
   而是**每个模板各解一次原图**（ok-end-field 达 17:1）。已按**源图分组** +
   **持久缓存**（内容 hash 做键）修掉，见 §1。
2. 角色面板状态栏本地化
3. 大画廊双入口
4. 任务卡片式 UI

---

## 总结

VS Code 扩展与 JetBrains 插件的设计差异本质上是**平台能力差异**的映射：

- **VS Code**：Web 平台提供了 Canvas/WebGL/WebWorker 等现代 Web 技术，适合构建富交互 UI 和并行计算，但受限于 Webview 沙箱和消息传递开销。
- **JetBrains**：IntelliJ Platform 提供了成熟的 Swing 组件和平台服务（VirtualFileListener、NotificationGroup、ToolWindow），适合构建原生集成体验，但图像处理和富交互 UI 需要更多手动实现。

两者在核心语言功能（补全/hover/inlay）上已完全对齐，~~主要差距集中在**图像密集型操作**（标注编辑器、缩略图并发）和**动态 UI 交互**（条件可见性、调试浮层）。JetBrains 插件的优先补齐方向应聚焦于**文件监听**和**标注编辑器进阶交互**，这两个功能对日常工作流影响最大。~~
**—— 这段结论已过时（2026-09-21 复核）**：上面点名的四块（标注编辑器进阶交互、文件监听、
条件可见性、调试浮层）**都已实现**，见 §7.2。现在剩下的只有低优先级的 UI 形态差异
（大画廊双入口、任务卡片式 UI）与一项**设计取舍**（标注编辑器用 OK/Cancel 而非改动即存）。
真正还没做的只有**缩略图并发加载**这类性能优化，以及两端都没有的端到端测试。

---

*报告生成日期：2026-09-06；**2026-09-21 逐条回查代码后修正**（行数、代码量、测试规模、
「缺失」清单、§6 历史说明、§7 建议与总结）/ Report generated 2026-09-06; **corrected 2026-09-21
*数据来源：parity-review.md、源代码分析
