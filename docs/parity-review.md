# Sub-repo vs Main-repo Feature Parity Review (Updated 2026-09-07)

# 子仓库与主仓库功能差异审查（2026-09-07 更新）

对照基准：主仓库 VSCode 扩展 v1.4.0、子仓库 JetBrains 插件 v1.4.0（含本轮对齐提交）。
下次审查请覆盖本表并更新状态。

Baseline: main repo VSCode extension v1.4.0, sub-repo JetBrains plugin v1.4.0 (including this round of alignment commits).
Next review should override this table and update the status.

---

## 中文

### 结论概览

编辑器语言功能（补全/hover/inlay）完全对齐；工具箱（游戏连接+调试浮层）、
列表弹窗、sub_configs 子配置树、任务后台存续、批量导入、文件 watcher、
导出取消均已对齐；剩余大块为标注编辑器进阶交互与次要 UI 项。

### ✅ 已对齐

- 编辑器：4 类引用识别（lang/模板/效果/OCR）、5 场景补全（模板置顶、效果分类排序）、
  hover（全语言表格/缩略图预览/OCR 运行时说明）、行内提示 + tooltip、JSON 侧效果提示
- 模板画廊：响应式网格、插入/复制/红框标注原图（ok_templates 反查 30s TTL）
- 任务启动：AST 列任务（条目带 `kind`，不必等 schema 采集即可区分触发 / 一次性）、
  schema 探测与缓存、**常驻执行器**（`run_executor.py` 一次连接 + 框架 `TaskExecutor`
  循环轮询全部已启用的触发任务；两端一致）、触发任务勾选入列 / 一次性任务入队、
  启用集合持久化、停止当前任务 / 关闭执行器、暂停/恢复、输出区、配置持久化、
  **任务后台存续（工具窗关闭不杀进程）**
  - 变更：**extraArgs / env 不再按任务应用** —— 常驻执行器把全部任务跑在同一进程里，
    进程级参数无法再按任务区分；历史数据保留，启动时输出一次提示。参数覆盖改为运行期
    经 stdin `params` 全量推送即时生效。
- 参数控件：bool/数字/下拉/多选/级联下拉/条件序列 JSON/configGroups 分组、
  **列表字段 ModifyListDialog 弹窗**（options_available 双栏+搜索+上移下移移除+
  allow_duplication）、**option_labels/category_labels 本地化标签**、
  **字段描述渲染**、**sub_configs 子配置树**（boolean 条件显隐+折叠组+groupSelector 隐藏）
- 角色面板（只读+技能 CRUD+强化组）、**状态栏/表头去硬编码英文**
- 素材库：**批量导入+数字序号自动命名（nextImageName）**、
  **saveToAssets 导出可取消**、截图采集（**截图方式 auto/wgc/bitblt/foreground
  + 面板「硬前台」单次覆盖**）、标注基础操作、数据源、
  **数据文件 watcher 自动刷新面板**（VFS 监听+300ms 防抖+相关性过滤）
- **工具箱：游戏连接/断开（connect_game.py，未运行自动启动）、调试浮层开关
  （任务启动沿用 OK_TOOLKIT_USE_OVERLAY、运行中 stdin overlay_on/off 即时下发、
  overlay_host.py 常驻宿主）**，状态持久化 .idea/ok-script-toolkit-toolbox.json
- 6 语言 UI、设置项、PythonScriptLocator 只从插件 JAR 提取脚本（7 个脚本全量打包）

### ⚠️ 待办

| # | 问题 | 严重度 | 状态 |
|---|---|---|---|
| 1 | 标注编辑器进阶交互（undo/redo、copy/paste、8 向 resize、拖动移框、缩放平移、跨图导航、双击数值编辑、改动即存） | 中 | 待办（2026-09-07 用户指示跳过） |
| 2 | 编辑器大画廊双入口（编辑器内嵌大画廊视图） | 低 | 待办 |
| 3 | 任务卡片式 UI（VSCode 任务列表为卡片布局） | 低 | 待办 |
| 4 | lastPythonEditor 跟踪（插入表达式定位最近编辑器） | 低 | 待办 |
| 5 | 注释面板命令（VSCode annotationPanel 独立面板命令） | 低 | 待办 |

### 已修复（本轮提交）

- 脚本定位只从插件 JAR 提取（78885dd，与父仓 c7ac05d 配对）
- 工具箱游戏连接+调试浮层（091065d）
- 列表弹窗 ModifyListDialog 语义（3e676b3，含对象数组 toString 破坏数据回归修复）
- extraArgs/env 应用 + option_labels/描述 + 角色面板去硬编码（c805b90）
- TaskRunnerService 任务后台存续（0634706）
- 素材批量导入+数字序号命名（982b785）
- 数据文件 watcher（eaca68a）
- sub_configs 子配置树（91c5dd5）
- 素材导出取消（b78b76f）
- 父仓 6 语言过期键 okLangHints→okScriptToolkit（父仓 60ded3e）
- 截图能力对齐父仓 9ab367f：设置项 captureMethod、ScreenshotCapture 传 --method、
  素材与临时截图面板「硬前台」复选框（d53a948 / f007aa5 / cdca803）；
  父仓 cecadb7 的 windows.args 走共享 python/，无需 Kotlin 改动

---

## English

### Summary

Editor language features (completion/hover/inlay) are fully aligned; toolbox (game connection + debug overlay),
list dialog, sub_configs sub-config tree, task background persistence, batch import, file watcher,
and export cancellation are all aligned; remaining gaps are advanced annotation editor interactions and minor UI items.

### ✅ Aligned

- Editor: 4 reference recognition types (lang/template/effect/OCR), 5 completion scenarios (template pinned, effect category sorting),
  hover (full locale table / thumbnail preview / OCR runtime description), inline hints + tooltip, JSON-side effect hints
- Template gallery: responsive grid, insert/copy/red-box annotated source image (ok_templates reverse lookup 30s TTL)
- Task launcher: AST-based task listing (entries with `kind`, distinguishes trigger / one-time without waiting for schema collection),
  schema probing & caching, **persistent executor** (`run_executor.py` one-time connection + framework `TaskExecutor`
  polls all enabled trigger tasks; aligned on both sides), trigger task toggle / one-time task enqueue,
  enabled set persistence, stop current task / close executor, pause/resume, output area, config persistence,
  **task background persistence (tool window close does not kill process)**
  - Change: **extraArgs / env no longer applied per-task** — the persistent executor runs all tasks in one process,
    so process-level parameters can no longer be distinguished per-task; historical data is retained with a startup hint.
    Parameter overrides now take effect in real-time via stdin `params` full push.
- Parameter controls: bool/number/dropdown/multi-select/cascading dropdown/condition sequence JSON/configGroups grouping,
  **list field ModifyListDialog dialog** (options_available dual-column + search + move up/down/remove +
  allow_duplication), **option_labels/category_labels localized labels**,
  **field description rendering**, **sub_configs sub-config tree** (boolean condition show/hide + collapsible group + groupSelector hidden)
- Character panel (read-only + skill CRUD + enhancement groups), **status bar / table header de-hardcoded English**
- Asset library: **batch import + numbered auto-naming (nextImageName)**,
  **saveToAssets export cancelable**, screenshot capture (**capture method auto/wgc/bitblt/foreground
  + panel "hard foreground" one-shot override**), annotation basic operations, data sources,
  **data file watcher auto-refresh panel** (VFS listener + 300ms debounce + relevance filtering)
- **Toolbox: game connect/disconnect (connect_game.py, auto-starts if not running), debug overlay toggle
  (task launcher reuses OK_TOOLKIT_USE_OVERLAY, live stdin overlay_on/off dispatch during run,
  overlay_host.py persistent host)**, state persistence in .idea/ok-script-toolkit-toolbox.json
- 6-language UI, settings, PythonScriptLocator extracts scripts only from plugin JAR (all 7 scripts bundled)

### ⚠️ TODO

| # | Issue | Severity | Status |
|---|---|---|---|
| 1 | Advanced annotation editor interactions (undo/redo, copy/paste, 8-direction resize, drag-to-move, zoom/pan, cross-image navigation, double-click value edit, save-on-change) | Medium | TODO (skipped per user instruction 2026-09-07) |
| 2 | Editor large gallery dual entry (embedded large gallery view in editor) | Low | TODO |
| 3 | Task card-style UI (VSCode task list uses card layout) | Low | TODO |
| 4 | lastPythonEditor tracking (insert expression locates nearest editor) | Low | TODO |
| 5 | Annotation panel command (VSCode annotationPanel standalone panel command) | Low | TODO |

### Fixed (this round)

- Script extraction only from plugin JAR (78885dd, paired with parent c7ac05d)
- Toolbox game connection + debug overlay (091065d)
- List dialog ModifyListDialog semantics (3e676b3, including object array toString data corruption regression fix)
- extraArgs/env application + option_labels/description + character panel de-hardcoding (c805b90)
- TaskRunnerService task background persistence (0634706)
- Asset batch import + numbered naming (982b785)
- Data file watcher (eaca68a)
- sub_configs sub-config tree (91c5dd5)
- Asset export cancellation (b78b76f)
- Parent repo 6-language stale keys okLangHints→okScriptToolkit (parent 60ded3e)
- Screenshot capability alignment with parent 9ab367f: captureMethod setting, ScreenshotCapture --method pass,
  asset & temp shots panel "hard foreground" checkbox (d53a948 / f007aa5 / cdca803);
  parent cecadb7's windows.args goes through shared python/, no Kotlin changes needed