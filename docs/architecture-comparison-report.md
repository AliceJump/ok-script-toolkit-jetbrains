# VS Code 扩展与 JetBrains 插件：功能取舍与实现策略差异总结报告

基于 `parity-review.md`（2026-09-06）及源代码分析，总结主仓库（VS Code 扩展）与子仓库（JetBrains 插件）之间的架构设计、功能完整性、性能优化、UI/UX 体验、开发维护成本差异，并提出未来改进建议。

---

## 1. 架构设计哲学差异

### VS Code 扩展：模块化、细粒度设计

- **独立数据源**：每个数据源（Lang、Feature、Effect、Character、TemplateAsset）有独立的 TypeScript 类（`LangData`、`FeatureData`、`EffectData`、`CharacterData`、`TemplateAssetData`），各自管理生命周期、缓存和刷新。
- **Worker 线程池**：图像处理（PNG 裁剪/缩略图、saveToAssets 打包）使用独立 Worker 线程池（`pngCropWorker.ts`、`assetPackWorker.ts`），主线程零阻塞。
- **Webview 隔离**：每个工具面板（模板画廊、任务启动器、角色管理、素材管理、标注编辑器）运行在独立的 Webview 中，通过消息传递与宿主通信，UI 与逻辑完全分离。
- **多层缓存**：缩略图文件缓存 + 内存 LRU + Worker 预热；任务 schema 磁盘缓存；语言数据内存快照。
- **文件监听**：通过 `createFileSystemWatcher` 实时监听数据文件变更，300ms 防抖后选择性刷新对应数据源。

**关键文件**：`extension.ts`（353 行）、`pngCrop.ts`（896 行）、`assetPack.ts`（206 行）、`assetPackWorker.ts`（315 行）

### JetBrains 插件：集中式、统一快照设计

- **统一数据中心**：`OkProjectDataService` 是唯一的项目级数据服务，内含 `Snapshot` 数据类，将 Lang、Feature、Effect 三大数据源合并为一个不可变快照，所有消费者从同一快照读取。
- **并发安全**：`@Volatile` + `ConcurrentHashMap` + `AtomicLong` 保证多线程安全，但无 Worker 线程池——图像处理在 `CompletableFuture` 线程池中执行。
- **平台服务**：利用 IntelliJ Platform 的 `@Service(Service.Level.PROJECT)` 注解自动管理生命周期，无需手动 dispose。
- **单线程缩略图加载**：`TemplatesToolWindowFactory` 使用 `Executors.newSingleThreadExecutor` 顺序加载缩略图，无并发解码。

**关键文件**：`OkProjectDataService.kt`（405 行）、`TaskLauncherToolWindowFactory.kt`（925 行）、`TemplatesToolWindowFactory.kt`（404 行）

| 维度 | VS Code | JetBrains |
|------|---------|-----------|
| 数据架构 | 多个独立 Data 类 + 300ms 防抖 | 单一 Snapshot + 文件修改时间戳 |
| 图像处理 | Worker 线程池（2-4 核） | CompletableFuture 线程池 |
| UI 隔离 | Webview（HTML/CSS/JS） | Swing JPanel（原生） |
| 生命周期管理 | 手动 disposables | IntelliJ @Service 自动管理 |

---

## 2. 功能完整性差异

### 已对齐功能（11 项）

| 功能 | VS Code | JetBrains |
|------|---------|-----------|
| 语言键补全/hover/inlay | ✅ | ✅ |
| OCR match 补全/hover | ✅ | ✅ |
| 效果 ID 补全/hover/inlay（Python + JSON） | ✅ | ✅ |
| 模板画廊（响应式网格、搜索、插入/复制） | ✅ | ✅ |
| 任务启动（AST 列任务、schema 探测、暂停/恢复） | ✅ | ✅ |
| 参数控件（bool/数字/下拉/多选/级联/条件序列） | ✅ | ✅ |
| 角色面板（只读部分） | ✅ | ✅ |
| 素材库基础操作（导入、网格浏览） | ✅ | ✅ |
| 标注基础操作（查看、添加/删除标注） | ✅ | ✅ |
| 6 语言 UI | ✅ | ✅ |
| 设置项 | ✅ | ✅ |

### JetBrains 缺失/精简功能（按严重度排序）

| # | 功能 | 严重度 | 说明 |
|---|------|--------|------|
| 1 | saveToAssets 打包导出 | ✅ 已交付 | bin-packing 多页合成 + COCO 重写 |
| 2 | 游戏窗口截图采集 | ✅ 已交付 | probe 自动探测 + 截图并注册 |
| 3 | 角色 CRUD（添加/编辑/删除技能） | ◐ 部分交付 | 原子写入+备份，强化组编辑待后续 |
| 4 | 角色头像 | ✅ 已交付 | 表格头像列 |
| 5 | 标注编辑器进阶交互 | ❌ 缺失 | undo/redo、copy/paste、8 向 resize、拖动移框、缩放平移、跨图导航、双击数值编辑、改动即存 |
| 6 | sub_configs 子配置树 | ❌ 缺失 | 折叠树 + boolean 条件显隐 + groupSelector |
| 7 | 调试浮层 | ❌ 缺失 | overlay 控制按钮、运行时参数注入 |
| 8 | 文件监听自动刷新 | ❌ 缺失 | 数据变化需手动刷新 |
| 9 | 标注快捷键配置 | ❌ 缺失 | 依赖标注编辑器进阶交互 |
| 10 | 条件可见性系统 | ❌ 缺失 | 参数间动态显隐逻辑 |

---

## 3. 性能优化策略差异

### VS Code 扩展

| 策略 | 实现 | 效果 |
|------|------|------|
| Worker 线程池（缩略图） | `pngCropWorker.ts`：纯 JS PNG 解码 + 裁剪 + 缩放 | 主线程零阻塞，hover 响应 < 10ms |
| Worker 线程池（saveToAssets） | `assetPackWorker.ts`：PNG 解码 + level-6 deflate + 写盘 | 多页并行渲染，4K 图 ≈ 33MB/worker |
| 多层缓存 | 文件缓存（磁盘）+ 内存 LRU + Worker 预热 | 首次激活预热全部缩略图 |
| 选择性缓存失效 | `clearCropCacheForImage` 按来源目录清除 | 仅失效变更文件对应的缩略图 |
| 300ms 防抖 | `DEBOUNCE_MS = 300` | 避免频繁刷新 |

### JetBrains 插件

| 策略 | 实现 | 效果 |
|------|------|------|
| Snapshot 不可变快照 | `@Volatile` + `ConcurrentHashMap` | 读操作无锁 |
| 文件修改时间戳 | `fileStamps` + `lastRefreshAttempt` | 避免重复扫描 |
| 单线程缩略图加载 | `Executors.newSingleThreadExecutor` | 顺序加载，无并发竞争 |
| 30s TTL 缓存 | `okTemplateCocoCache` / `okTemplateCocoMissCache` | 减少重复 COCO 解析 |
| 400ms 防抖保存 | `javax.swing.Timer(400)` | 避免 EDT 高频文件写入 |

**性能对比结论**：VS Code 扩展通过 Worker 线程池实现了真正的并行处理，适合图像密集型操作；JetBrains 插件依赖平台线程池和不可变快照，在轻量场景下足够，但图像处理能力受限。

---

## 4. UI/UX 体验差异

### Webview vs Swing

| 维度 | VS Code (Webview) | JetBrains (Swing) |
|------|-------------------|-------------------|
| 渲染技术 | HTML/CSS/JS | JPanel + Graphics2D |
| 自定义程度 | 完全自由（Canvas/WebGL） | 受限于 Swing 组件 |
| 标注编辑器 | HTML5 Canvas + 完整交互 | 无（仅基础查看） |
| 任务配置面板 | 结构化 JSON 编辑器 | JTextArea + JSON 验证边框 |
| 响应式布局 | CSS Grid/Flexbox | GridLayout + 手动计算列数 |
| 主题适配 | 跟随 VS Code 主题 | JBColor 自动适配 Light/Dark |
| 原生集成度 | 低（沙箱环境） | 高（平台 Action/Notification） |

### 原生集成 vs 灵活定制

| 能力 | VS Code | JetBrains |
|------|---------|-----------|
| 工具窗口 | WebviewView（侧边栏） | ToolWindow（可停靠） |
| 快捷键 | keybindings JSON | KeyboardShortcut XML |
| 命令面板 | registerCommand | AnAction + group |
| 通知 | InformationMessage | NotificationGroup |
| 右键菜单 | 编辑器上下文菜单 | EditorPopupMenu |
| 设置界面 | configuration JSON | ProjectConfigurable |
| 调试浮层 | overlay HTML | ❌ 缺失 |

---

## 5. 开发维护成本差异

### 代码量对比

| 维度 | VS Code | JetBrains |
|------|---------|-----------|
| 宿主源码（TS/Kt） | 7,747 行（19 文件） | 5,967 行（22 文件） |
| Webview UI 代码 | 3,949 行（HTML/CSS/JS） | — |
| Python 辅助脚本 | 7 文件 | — |
| 测试代码 | 3 文件 | 3 文件（209 行） |
| **总计** | **~11,700+ 行** | **~6,176 行** |

### 可维护性对比

| 维度 | VS Code | JetBrains |
|------|---------|-----------|
| 模块耦合度 | 低（Data 类独立，Webview 消息传递） | 中（Snapshot 耦合所有数据） |
| 类型安全 | TypeScript 强类型 | Kotlin 强类型 + data class |
| 错误处理 | try/catch + Promise rejection | try/catch + CompletableFuture |
| 国际化 | l10n 目录 + package.nls | ResourceBundle |
| 发布流程 | vsce package + Marketplace | Gradle buildPlugin + Marketplace |
| 版本同步 | npm run version:sync | gradle.properties |

### 测试覆盖率对比

| 维度 | VS Code | JetBrains |
|------|---------|-----------|
| 单元测试文件 | 3 | 3 |
| 测试行数 | ~300 行（scripts/test_task_launcher_subconfigs.js） | 209 行 |
| 集成测试 | 无 | 无 |
| E2E 测试 | 无 | 无 |

**结论**：两端测试覆盖率均偏低，但 VS Code 扩展有脚本级测试工具（`test_task_launcher_subconfigs.js`），JetBrains 插件仅有 Kotlin 单元测试。

---

## 6. 关键取舍决策分析

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

**VS Code 版**：`taskLauncher.ts` 中 `overlayActive` 状态 + Webview 消息传递 + `run_task.py` 环境变量注入

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

| 优先级 | 功能 | 预估工作量 | 价值 |
|--------|------|------------|------|
| P0 | 文件监听自动刷新 | 2-3 天 | 高（消除手动刷新痛点） |
| P0 | 标注编辑器进阶交互 | 5-8 天 | 高（标注工作流核心） |
| P1 | sub_configs 子配置树 | 3-5 天 | 中（复杂任务配置需求） |
| P1 | 条件可见性系统 | 2-3 天 | 中（参数联动体验） |
| P1 | 调试浮层 | 3-5 天 | 中（开发期调试） |
| P2 | 标注快捷键配置 | 1-2 天 | 低（依赖标注编辑器） |
| P2 | 角色强化组编辑完善 | 2-3 天 | 低（CRUD 已交付） |

### 7.2 两端功能对齐的优先级排序

**第一阶段（核心体验对齐）**：
1. ✅ saveToAssets 导出（已完成）
2. ✅ 截图采集（已完成）
3. ✅ 角色 CRUD + 头像（已完成）
4. 🔄 标注编辑器进阶交互（进行中）
5. 🔄 文件监听自动刷新（待启动）

**第二阶段（高级功能对齐）**：
1. sub_configs 子配置树
2. 条件可见性系统
3. 调试浮层
4. 标注快捷键配置

**第三阶段（体验优化）**：
1. 缩略图并发加载优化
2. 角色面板状态栏本地化
3. 大画廊双入口
4. 任务卡片式 UI

---

## 总结

VS Code 扩展与 JetBrains 插件的设计差异本质上是**平台能力差异**的映射：

- **VS Code**：Web 平台提供了 Canvas/WebGL/WebWorker 等现代 Web 技术，适合构建富交互 UI 和并行计算，但受限于 Webview 沙箱和消息传递开销。
- **JetBrains**：IntelliJ Platform 提供了成熟的 Swing 组件和平台服务（VirtualFileListener、NotificationGroup、ToolWindow），适合构建原生集成体验，但图像处理和富交互 UI 需要更多手动实现。

两者在核心语言功能（补全/hover/inlay）上已完全对齐，主要差距集中在**图像密集型操作**（标注编辑器、缩略图并发）和**动态 UI 交互**（条件可见性、调试浮层）。JetBrains 插件的优先补齐方向应聚焦于**文件监听**和**标注编辑器进阶交互**，这两个功能对日常工作流影响最大。

---

*报告生成日期：2026-09-06*
*数据来源：parity-review.md、源代码分析*
