# VS Code 扩展与 JetBrains 插件：功能取舍与实现策略差异总结报告 / VS Code Extension vs JetBrains Plugin: Feature Trade-offs and Implementation Strategy Differences Summary Report

基于 `parity-review.md`（2026-09-06）及源代码分析，总结主仓库（VS Code 扩展）与子仓库（JetBrains 插件）之间的架构设计、功能完整性、性能优化、UI/UX 体验、开发维护成本差异，并提出未来改进建议。
Based on `parity-review.md` (2026-09-06) and source code analysis, this report summarizes the differences in architecture design, feature completeness, performance optimization, UI/UX experience, and development/maintenance costs between the main repository (VS Code extension) and sub-repository (JetBrains plugin), and proposes future improvement suggestions.

---

## 1. 架构设计哲学差异 / Architecture Design Philosophy Differences

### VS Code 扩展：模块化、细粒度设计 / VS Code Extension: Modular, Fine-Grained Design

- **独立数据源**：每个数据源（Lang、Feature、Effect、Character、TemplateAsset）有独立的 TypeScript 类（`LangData`、`FeatureData`、`EffectData`、`CharacterData`、`TemplateAssetData`），各自管理生命周期、缓存和刷新。
  **Independent Data Sources**: Each data source (Lang, Feature, Effect, Character, TemplateAsset) has an independent TypeScript class (`LangData`, `FeatureData`, `EffectData`, `CharacterData`, `TemplateAssetData`), each managing its own lifecycle, caching, and refresh.
- **Worker 线程池**：图像处理（PNG 裁剪/缩略图、saveToAssets 打包）使用独立 Worker 线程池（`pngCropWorker.ts`、`assetPackWorker.ts`），主线程零阻塞。
  **Worker Thread Pool**: Image processing (PNG crop/thumbnails, saveToAssets packing) uses independent Worker thread pools (`pngCropWorker.ts`, `assetPackWorker.ts`), with zero main thread blocking.
- **Webview 隔离**：每个工具面板（模板画廊、任务启动器、角色管理、素材管理、标注编辑器）运行在独立的 Webview 中，通过消息传递与宿主通信，UI 与逻辑完全分离。
  **Webview Isolation**: Each tool panel (template gallery, task launcher, character management, asset management, annotation editor) runs in an independent Webview, communicating with the host through message passing, with UI and logic completely separated.
- **多层缓存**：缩略图文件缓存 + 内存 LRU + Worker 预热；任务 schema 磁盘缓存；语言数据内存快照。
  **Multi-Layer Caching**: Thumbnail file cache + in-memory LRU + Worker warm-up; task schema disk cache; language data in-memory snapshot.
- **文件监听**：通过 `createFileSystemWatcher` 实时监听数据文件变更，300ms 防抖后选择性刷新对应数据源。
  **File Watching**: Monitors data file changes in real-time via `createFileSystemWatcher`, with 300ms debounce before selectively refreshing the corresponding data source.

**关键文件**：`extension.ts`（353 行）、`pngCrop.ts`（896 行）、`assetPack.ts`（206 行）、`assetPackWorker.ts`（315 行）
**Key Files**: `extension.ts` (353 lines), `pngCrop.ts` (896 lines), `assetPack.ts` (206 lines), `assetPackWorker.ts` (315 lines)

### JetBrains 插件：集中式、统一快照设计 / JetBrains Plugin: Centralized, Unified Snapshot Design

- **统一数据中心**：`OkProjectDataService` 是唯一的项目级数据服务，内含 `Snapshot` 数据类，将 Lang、Feature、Effect 三大数据源合并为一个不可变快照，所有消费者从同一快照读取。
  **Unified Data Center**: `OkProjectDataService` is the sole project-level data service, containing a `Snapshot` data class that merges the three major data sources (Lang, Feature, Effect) into a single immutable snapshot, with all consumers reading from the same snapshot.
- **并发安全**：`@Volatile` + `ConcurrentHashMap` + `AtomicLong` 保证多线程安全，但无 Worker 线程池——图像处理在 `CompletableFuture` 线程池中执行。
  **Concurrency Safety**: `@Volatile` + `ConcurrentHashMap` + `AtomicLong` ensure multi-thread safety, but there is no Worker thread pool — image processing executes in a `CompletableFuture` thread pool.
- **平台服务**：利用 IntelliJ Platform 的 `@Service(Service.Level.PROJECT)` 注解自动管理生命周期，无需手动 dispose。
  **Platform Services**: Leverages IntelliJ Platform's `@Service(Service.Level.PROJECT)` annotation for automatic lifecycle management, without manual dispose.
- **单线程缩略图加载**：`TemplatesToolWindowFactory` 使用 `Executors.newSingleThreadExecutor` 顺序加载缩略图，无并发解码。
  **Single-Thread Thumbnail Loading**: `TemplatesToolWindowFactory` uses `Executors.newSingleThreadExecutor` to load thumbnails sequentially, without concurrent decoding.

**关键文件**：`OkProjectDataService.kt`（405 行）、`TaskLauncherToolWindowFactory.kt`（925 行）、`TemplatesToolWindowFactory.kt`（404 行）
**Key Files**: `OkProjectDataService.kt` (405 lines), `TaskLauncherToolWindowFactory.kt` (925 lines), `TemplatesToolWindowFactory.kt` (404 lines)

| 维度 / Dimension | VS Code | JetBrains |
|------|---------|-----------|
| 数据架构 / Data Architecture | 多个独立 Data 类 + 300ms 防抖 / Multiple independent Data classes + 300ms debounce | 单一 Snapshot + 文件修改时间戳 / Single Snapshot + file modification timestamps |
| 图像处理 / Image Processing | Worker 线程池（2-4 核）/ Worker thread pool (2-4 cores) | CompletableFuture 线程池 / CompletableFuture thread pool |
| UI 隔离 / UI Isolation | Webview（HTML/CSS/JS） | Swing JPanel（原生）/ Swing JPanel (native) |
| 生命周期管理 / Lifecycle Management | 手动 disposables / Manual disposables | IntelliJ @Service 自动管理 / IntelliJ @Service automatic management |

---

## 2. 功能完整性差异 / Feature Completeness Differences

### 已对齐功能（11 项）/ Aligned Features (11 Items)

| 功能 / Feature | VS Code | JetBrains |
|------|---------|-----------|
| 语言键补全/hover/inlay / Language key completion/hover/inlay | ✅ | ✅ |
| OCR match 补全/hover / OCR match completion/hover | ✅ | ✅ |
| 效果 ID 补全/hover/inlay（Python + JSON）/ Effect ID completion/hover/inlay (Python + JSON) | ✅ | ✅ |
| 模板画廊（响应式网格、搜索、插入/复制）/ Template gallery (responsive grid, search, insert/copy) | ✅ | ✅ |
| 任务启动（AST 列任务、schema 探测、暂停/恢复）/ Task launch (AST task list, schema detection, pause/resume) | ✅ | ✅ |
| 参数控件（bool/数字/下拉/多选/级联/条件序列）/ Parameter controls (bool/dropdown/multi-select/cascade/conditional sequence) | ✅ | ✅ |
| 角色面板（只读部分）/ Character panel (read-only section) | ✅ | ✅ |
| 素材库基础操作（导入、网格浏览）/ Asset library basic operations (import, grid browsing) | ✅ | ✅ |
| 标注基础操作（查看、添加/删除标注）/ Annotation basic operations (view, add/delete annotations) | ✅ | ✅ |
| 6 语言 UI / 6-language UI | ✅ | ✅ |
| 设置项 / Settings | ✅ | ✅ |

### JetBrains 缺失/精简功能（按严重度排序）/ JetBrains Missing/Simplified Features (Ranked by Severity)

| # | 功能 / Feature | 严重度 / Severity | 说明 / Description |
|---|------|--------|------|
| 1 | saveToAssets 打包导出 / saveToAssets packing export | ✅ 已交付 / Delivered | bin-packing 多页合成 + COCO 重写 / bin-packing multi-page composition + COCO rewrite |
| 2 | 游戏窗口截图采集 / Game window screenshot capture | ✅ 已交付 / Delivered | probe 自动探测 + 截图并注册 / probe auto-detection + screenshot and registration |
| 3 | 角色 CRUD（添加/编辑/删除技能）/ Character CRUD (add/edit/delete skills) | ◐ 部分交付 / Partially Delivered | 原子写入+备份，强化组编辑待后续 / Atomic write + backup, enhanced group editing pending |
| 4 | 角色头像 / Character avatar | ✅ 已交付 / Delivered | 表格头像列 / Table avatar column |
| 5 | 标注编辑器进阶交互 / Annotation editor advanced interaction | ❌ 缺失 / Missing | undo/redo、copy/paste、8 向 resize、拖动移框、缩放平移、跨图导航、双击数值编辑、改动即存 / undo/redo, copy/paste, 8-way resize, drag-move, zoom-pan, cross-image navigation, double-click value editing, auto-save on change |
| 6 | sub_configs 子配置树 / sub_configs sub-configuration tree | ❌ 缺失 / Missing | 折叠树 + boolean 条件显隐 + groupSelector / Collapsible tree + boolean conditional visibility + groupSelector |
| 7 | 调试浮层 / Debug overlay | ❌ 缺失 / Missing | overlay 控制按钮、运行时参数注入 / Overlay control buttons, runtime parameter injection |
| 8 | 文件监听自动刷新 / File watcher auto-refresh | ❌ 缺失 / Missing | 数据变化需手动刷新 / Data changes require manual refresh |
| 9 | 标注快捷键配置 / Annotation shortcut configuration | ❌ 缺失 / Missing | 依赖标注编辑器进阶交互 / Depends on annotation editor advanced interaction |
| 10 | 条件可见性系统 / Conditional visibility system | ❌ 缺失 / Missing | 参数间动态显隐逻辑 / Dynamic visibility logic between parameters |

---

## 3. 性能优化策略差异 / Performance Optimization Strategy Differences

### VS Code 扩展 / VS Code Extension

| 策略 / Strategy | 实现 / Implementation | 效果 / Effect |
|------|------|------|
| Worker 线程池（缩略图）/ Worker thread pool (thumbnails) | `pngCropWorker.ts`：纯 JS PNG 解码 + 裁剪 + 缩放 / Pure JS PNG decode + crop + resize | 主线程零阻塞，hover 响应 < 10ms / Zero main thread blocking, hover response < 10ms |
| Worker 线程池（saveToAssets）/ Worker thread pool (saveToAssets) | `assetPackWorker.ts`：PNG 解码 + level-6 deflate + 写盘 / PNG decode + level-6 deflate + disk write | 多页并行渲染，4K 图 ≈ 33MB/worker / Multi-page parallel rendering, 4K images ≈ 33MB/worker |
| 多层缓存 / Multi-layer caching | 文件缓存（磁盘）+ 内存 LRU + Worker 预热 / File cache (disk) + in-memory LRU + Worker warm-up | 首次激活预热全部缩略图 / Warm-up all thumbnails on first activation |
| 选择性缓存失效 / Selective cache invalidation | `clearCropCacheForImage` 按来源目录清除 / Clear by source directory | 仅失效变更文件对应的缩略图 / Only invalidate thumbnails corresponding to changed files |
| 300ms 防抖 / 300ms debounce | `DEBOUNCE_MS = 300` | 避免频繁刷新 / Avoid frequent refreshes |

### JetBrains 插件 / JetBrains Plugin

| 策略 / Strategy | 实现 / Implementation | 效果 / Effect |
|------|------|------|
| Snapshot 不可变快照 / Snapshot immutable snapshot | `@Volatile` + `ConcurrentHashMap` | 读操作无锁 / Lock-free read operations |
| 文件修改时间戳 / File modification timestamps | `fileStamps` + `lastRefreshAttempt` | 避免重复扫描 / Avoid redundant scanning |
| 单线程缩略图加载 / Single-thread thumbnail loading | `Executors.newSingleThreadExecutor` | 顺序加载，无并发竞争 / Sequential loading, no concurrency contention |
| 30s TTL 缓存 / 30s TTL cache | `okTemplateCocoCache` / `okTemplateCocoMissCache` | 减少重复 COCO 解析 / Reduce redundant COCO parsing |
| 400ms 防抖保存 / 400ms debounce save | `javax.swing.Timer(400)` | 避免 EDT 高频文件写入 / Avoid high-frequency file writes on EDT |

**性能对比结论**：VS Code 扩展通过 Worker 线程池实现了真正的并行处理，适合图像密集型操作；JetBrains 插件依赖平台线程池和不可变快照，在轻量场景下足够，但图像处理能力受限。
**Performance Comparison Conclusion**: The VS Code extension achieves true parallel processing through Worker thread pools, suitable for image-intensive operations; the JetBrains plugin relies on platform thread pools and immutable snapshots, which is sufficient for lightweight scenarios but has limited image processing capability.

---

## 4. UI/UX 体验差异 / UI/UX Experience Differences

### Webview vs Swing

| 维度 / Dimension | VS Code (Webview) | JetBrains (Swing) |
|------|-------------------|-------------------|
| 渲染技术 / Rendering Technology | HTML/CSS/JS | JPanel + Graphics2D |
| 自定义程度 / Customization Level | 完全自由（Canvas/WebGL）/ Completely free (Canvas/WebGL) | 受限于 Swing 组件 / Limited to Swing components |
| 标注编辑器 / Annotation Editor | HTML5 Canvas + 完整交互 / HTML5 Canvas + full interaction | 无（仅基础查看）/ None (basic view only) |
| 任务配置面板 / Task Configuration Panel | 结构化 JSON 编辑器 / Structured JSON editor | JTextArea + JSON 验证边框 / JTextArea + JSON validation border |
| 响应式布局 / Responsive Layout | CSS Grid/Flexbox | GridLayout + 手动计算列数 / GridLayout + manual column calculation |
| 主题适配 / Theme Adaptation | 跟随 VS Code 主题 / Follows VS Code theme | JBColor 自动适配 Light/Dark / JBColor auto-adapts to Light/Dark |
| 原生集成度 / Native Integration Level | 低（沙箱环境）/ Low (sandbox environment) | 高（平台 Action/Notification）/ High (platform Action/Notification) |

### 原生集成 vs 灵活定制 / Native Integration vs Flexible Customization

| 能力 / Capability | VS Code | JetBrains |
|------|---------|-----------|
| 工具窗口 / Tool Window | WebviewView（侧边栏）/ WebviewView (sidebar) | ToolWindow（可停靠）/ ToolWindow (dockable) |
| 快捷键 / Shortcuts | keybindings JSON | KeyboardShortcut XML |
| 命令面板 / Command Palette | registerCommand | AnAction + group |
| 通知 / Notification | InformationMessage | NotificationGroup |
| 右键菜单 / Context Menu | 编辑器上下文菜单 / Editor context menu | EditorPopupMenu |
| 设置界面 / Settings UI | configuration JSON | ProjectConfigurable |
| 调试浮层 / Debug overlay | overlay HTML | ❌ 缺失 / Missing |

---

## 5. 开发维护成本差异 / Development and Maintenance Cost Differences

### 代码量对比 / Code Volume Comparison

| 维度 / Dimension | VS Code | JetBrains |
|------|---------|-----------|
| 宿主源码（TS/Kt）/ Host source (TS/Kt) | 7,747 行（19 文件）/ 7,747 lines (19 files) | 5,967 行（22 文件）/ 5,967 lines (22 files) |
| Webview UI 代码 / Webview UI code | 3,949 行（HTML/CSS/JS）/ 3,949 lines (HTML/CSS/JS) | — |
| Python 辅助脚本 / Python helper scripts | 7 文件 / 7 files | — |
| 测试代码 / Test code | 3 文件 / 3 files | 3 文件（209 行）/ 3 files (209 lines) |
| **总计** / **Total** | **~11,700+ 行** / **~11,700+ lines** | **~6,176 行** / **~6,176 lines** |

### 可维护性对比 / Maintainability Comparison

| 维度 / Dimension | VS Code | JetBrains |
|------|---------|-----------|
| 模块耦合度 / Module Coupling | 低（Data 类独立，Webview 消息传递）/ Low (independent Data classes, Webview message passing) | 中（Snapshot 耦合所有数据）/ Medium (Snapshot couples all data) |
| 类型安全 / Type Safety | TypeScript 强类型 / TypeScript strong typing | Kotlin 强类型 + data class / Kotlin strong typing + data class |
| 错误处理 / Error Handling | try/catch + Promise rejection | try/catch + CompletableFuture |
| 国际化 / Internationalization | l10n 目录 + package.nls | ResourceBundle |
| 发布流程 / Release Process | vsce package + Marketplace | Gradle buildPlugin + Marketplace |
| 版本同步 / Version Sync | npm run version:sync | gradle.properties |

### 测试覆盖率对比 / Test Coverage Comparison

| 维度 / Dimension | VS Code | JetBrains |
|------|---------|-----------|
| 单元测试文件 / Unit test files | 3 | 3 |
| 测试行数 / Test lines | ~300 行（scripts/test_task_launcher_subconfigs.js）/ ~300 lines (scripts/test_task_launcher_subconfigs.js) | 209 行 / 209 lines |
| 集成测试 / Integration tests | 无 / None | 无 / None |
| E2E 测试 / E2E tests | 无 / None | 无 / None |

**结论**：两端测试覆盖率均偏低，但 VS Code 扩展有脚本级测试工具（`test_task_launcher_subconfigs.js`），JetBrains 插件仅有 Kotlin 单元测试。
**Conclusion**: Both sides have low test coverage, but the VS Code extension has script-level testing tools (`test_task_launcher_subconfigs.js`), while the JetBrains plugin only has Kotlin unit tests.

---

## 6. 关键取舍决策分析 / Key Trade-off Decision Analysis

### 6.1 标注编辑器功能差距的原因 / Reasons for Annotation Editor Feature Gap

**VS Code 版（完整）**：`annotationPanel.ts`（261 行）+ `media/annotationPanel/`（HTML/CSS/JS），使用 HTML5 Canvas 实现：
**VS Code version (complete)**: `annotationPanel.ts` (261 lines) + `media/annotationPanel/` (HTML/CSS/JS), implemented using HTML5 Canvas:
- undo/redo 栈、copy/paste 剪贴板、8 向 resize 手柄 / undo/redo stack, copy/paste clipboard, 8-way resize handles
- 拖动移框、鼠标滚轮缩放+平移、跨图导航（左右箭头）/ Drag-move, mouse wheel zoom+pan, cross-image navigation (left/right arrows)
- 双击数值编辑、改动即存（自动 save）/ Double-click value editing, auto-save on change
- 快捷键可配置（`annotationKeybindings` 设置项）/ Configurable shortcuts (`annotationKeybindings` setting)

**JetBrains 版（仅基础）**：`TemplateAssetPanel` 中集成，使用 Swing JPanel：
**JetBrains version (basic only)**: Integrated in `TemplateAssetPanel`, using Swing JPanel:
- 仅支持查看标注、添加/删除标注 / Only supports viewing annotations, adding/deleting annotations
- 无 undo/redo、无 copy/paste、无 resize 手柄 / No undo/redo, no copy/paste, no resize handles
- 无缩放平移、无跨图导航 / No zoom-pan, no cross-image navigation

**取舍原因**：
**Trade-off Reasons**:
1. **开发成本**：Canvas 标注编辑器实现复杂度高（~260 行 TS + ~1500 行 JS），Swing 重写工作量巨大 / **Development Cost**: Canvas annotation editor implementation complexity is high (~260 lines TS + ~1500 lines JS), Swing rewrite workload is enormous
2. **平台限制**：IntelliJ 没有内建的图像标注组件，需从零构建 / **Platform Limitations**: IntelliJ has no built-in image annotation components, must be built from scratch
3. **优先级**：标注编辑器是低频操作，优先级低于核心语言功能和任务启动 / **Priority**: Annotation editor is a low-frequency operation, priority is lower than core language features and task launching

### 6.2 调试浮层功能缺失的原因 / Reasons for Missing Debug Overlay Feature

**VS Code 版**：`taskLauncher.ts` 中 `overlayActive` 状态 + Webview 消息传递 + `run_executor.py` 环境变量注入
**VS Code version**: `overlayActive` state in `taskLauncher.ts` + Webview message passing + `run_executor.py` environment variable injection

**JetBrains 版**：完全缺失
**JetBrains version**: Completely missing

**取舍原因**：
**Trade-off Reasons**:
1. **运行时注入机制不同**：VS Code 通过环境变量 `OK_LANG_HINTS_INJECT` 传递，JetBrains 的 `TaskLauncherService.buildRunTaskCommand` 未实现此机制 / **Different Runtime Injection Mechanisms**: VS Code passes via environment variable `OK_LANG_HINTS_INJECT`, while JetBrains' `TaskLauncherService.buildRunTaskCommand` does not implement this mechanism
2. **UI 层面**：VS Code 的 overlay 是 Webview 中的浮动 div，Swing 中需实现 JWindow/JDialog 浮动窗口 / **UI Level**: VS Code's overlay is a floating div in Webview, while Swing requires implementing JWindow/JDialog floating windows
3. **使用场景有限**：调试浮层主要用于开发期实时预览，非核心功能 / **Limited Usage Scenarios**: Debug overlay is mainly for development-time real-time preview, not a core feature

### 6.3 条件可见性系统缺失的原因 / Reasons for Missing Conditional Visibility System

**VS Code 版**：任务参数面板支持 `condition` 字段，根据其他参数值动态显隐字段
**VS Code version**: Task parameter panel supports the `condition` field, dynamically showing/hiding fields based on other parameter values

**JetBrains 版**：`createFieldComponent` 中无 condition 逻辑
**JetBrains version**: No condition logic in `createFieldComponent`

**取舍原因**：
**Trade-off Reasons**:
1. **schema 数据已返回**：`TaskSchema.configGroups` 已传递，但 UI 层未实现 condition 监听 / **Schema data already returned**: `TaskSchema.configGroups` is already passed, but the UI layer has not implemented condition listening
2. **实现复杂度**：需要为每个字段注册 ChangeListener，构建依赖图，实时更新可见性 / **Implementation Complexity**: Requires registering ChangeListener for each field, building dependency graphs, and updating visibility in real-time
3. **当前 configGroups 分组已满足大部分需求** / **Current configGroups grouping already meets most needs**

### 6.4 文件监听机制缺失的原因 / Reasons for Missing File Watching Mechanism

**VS Code 版**：`createFileSystemWatcher` + `getAffectedSources` 精确分类 + 300ms 防抖刷新
**VS Code version**: `createFileSystemWatcher` + `getAffectedSources` precise classification + 300ms debounce refresh

**JetBrains 版**：`parity-review.md` 第 7 项明确记录"无文件 watcher"
**JetBrains version**: Item 7 in `parity-review.md` explicitly records "no file watcher"

**取舍原因**：
**Trade-off Reasons**:
1. **平台差异**：IntelliJ 有 `VirtualFileListener` 和 `BulkFileListener`，但需要手动注册和管理 / **Platform Differences**: IntelliJ has `VirtualFileListener` and `BulkFileListener`, but they require manual registration and management
2. **快照模型限制**：`OkProjectDataService` 的 `refresh()` 方法已实现，但缺少触发入口 / **Snapshot Model Limitations**: `OkProjectDataService`'s `refresh()` method is implemented, but lacks a trigger entry point
3. **用户习惯**：JetBrains 用户习惯手动刷新（Refresh 按钮），实时刷新可能带来性能开销 / **User Habits**: JetBrains users are accustomed to manual refresh (Refresh button), real-time refresh may bring performance overhead

---

## 7. 未来改进方向建议 / Future Improvement Suggestions

### 7.1 JetBrains 插件需要优先补齐的功能 / Features JetBrains Plugin Needs to Prioritize

| 优先级 / Priority | 功能 / Feature | 预估工作量 / Estimated Effort | 价值 / Value |
|--------|------|------------|------|
| P0 | 文件监听自动刷新 / File watcher auto-refresh | 2-3 天 / 2-3 days | 高（消除手动刷新痛点）/ High (eliminates manual refresh pain point) |
| P0 | 标注编辑器进阶交互 / Annotation editor advanced interaction | 5-8 天 / 5-8 days | 高（标注工作流核心）/ High (core of annotation workflow) |
| P1 | sub_configs 子配置树 / sub_configs sub-configuration tree | 3-5 天 / 3-5 days | 中（复杂任务配置需求）/ Medium (complex task configuration needs) |
| P1 | 条件可见性系统 / Conditional visibility system | 2-3 天 / 2-3 days | 中（参数联动体验）/ Medium (parameter linkage experience) |
| P1 | 调试浮层 / Debug overlay | 3-5 天 / 3-5 days | 中（开发期调试）/ Medium (development-time debugging) |
| P2 | 标注快捷键配置 / Annotation shortcut configuration | 1-2 天 / 1-2 days | 低（依赖标注编辑器）/ Low (depends on annotation editor) |
| P2 | 角色强化组编辑完善 / Character enhanced group editing refinement | 2-3 天 / 2-3 days | 低（CRUD 已交付）/ Low (CRUD already delivered) |

### 7.2 两端功能对齐的优先级排序 / Feature Alignment Priority Between Both Sides

**第一阶段（核心体验对齐）**：
**Phase 1 (Core Experience Alignment)**:
1. ✅ saveToAssets 导出（已完成）/ saveToAssets export (completed)
2. ✅ 截图采集（已完成）/ Screenshot capture (completed)
3. ✅ 角色 CRUD + 头像（已完成）/ Character CRUD + avatar (completed)
4. 🔄 标注编辑器进阶交互（进行中）/ Annotation editor advanced interaction (in progress)
5. 🔄 文件监听自动刷新（待启动）/ File watcher auto-refresh (pending)

**第二阶段（高级功能对齐）**：
**Phase 2 (Advanced Feature Alignment)**:
1. sub_configs 子配置树 / sub_configs sub-configuration tree
2. 条件可见性系统 / Conditional visibility system
3. 调试浮层 / Debug overlay
4. 标注快捷键配置 / Annotation shortcut configuration

**第三阶段（体验优化）**：
**Phase 3 (Experience Optimization)**:
1. 缩略图并发加载优化 / Thumbnail concurrent loading optimization
2. 角色面板状态栏本地化 / Character panel status bar localization
3. 大画廊双入口 / Large gallery dual entry points
4. 任务卡片式 UI / Task card-style UI

---

## 总结 / Summary

VS Code 扩展与 JetBrains 插件的设计差异本质上是**平台能力差异**的映射：
The design differences between the VS Code extension and JetBrains plugin are essentially a reflection of **platform capability differences**:

- **VS Code**：Web 平台提供了 Canvas/WebGL/WebWorker 等现代 Web 技术，适合构建富交互 UI 和并行计算，但受限于 Webview 沙箱和消息传递开销。
  **VS Code**: The Web platform provides modern web technologies such as Canvas/WebGL/WebWorker, suitable for building rich interactive UIs and parallel computing, but limited by Webview sandbox and message passing overhead.
- **JetBrains**：IntelliJ Platform 提供了成熟的 Swing 组件和平台服务（VirtualFileListener、NotificationGroup、ToolWindow），适合构建原生集成体验，但图像处理和富交互 UI 需要更多手动实现。
  **JetBrains**: IntelliJ Platform provides mature Swing components and platform services (VirtualFileListener, NotificationGroup, ToolWindow), suitable for building native integration experiences, but image processing and rich interactive UIs require more manual implementation.

两者在核心语言功能（补全/hover/inlay）上已完全对齐，主要差距集中在**图像密集型操作**（标注编辑器、缩略图并发）和**动态 UI 交互**（条件可见性、调试浮层）。JetBrains 插件的优先补齐方向应聚焦于**文件监听**和**标注编辑器进阶交互**，这两个功能对日常工作流影响最大。
Both sides are fully aligned on core language features (completion/hover/inlay), with the main gaps concentrated in **image-intensive operations** (annotation editor, thumbnail concurrency) and **dynamic UI interactions** (conditional visibility, debug overlay). The JetBrains plugin's prioritized gap-filling should focus on **file watching** and **annotation editor advanced interaction**, as these two features have the greatest impact on daily workflows.

---

*报告生成日期：2026-09-06 / Report generation date: 2026-09-06*
*数据来源：parity-review.md、源代码分析 / Data sources: parity-review.md, source code analysis*
