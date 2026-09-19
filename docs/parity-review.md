# Sub-repo vs Main-repo Feature Parity Review (Updated 2026-09-20)

# 子仓库与主仓库功能差异审查（2026-09-20 更新）

对照基准：主仓库 VSCode 扩展 v1.7.1、子仓库 JetBrains 插件 v1.7.1。
下次审查请覆盖本表并更新状态。

Baseline: main repo VSCode extension v1.7.1, sub-repo JetBrains plugin v1.7.1.
Next review should override this table and update the status.

> **上一版（2026-09-07，基线 v1.4.0）已严重失真**：它把当时尚未做的标注编辑器
> 进阶交互整批列为「待办」，但其中绝大多数随后已实现，文档未同步。本次审查逐条
> 回查代码后重写。今后请勿只改日期不查代码。
>
> The previous revision (2026-09-07, baseline v1.4.0) was badly stale: it listed the
> advanced annotation-editor interactions as TODO, but most were implemented afterwards
> without a doc update. This revision re-verified every row against the code.
>
> **2026-09-20 追加复核**：本文档的「局部结论」本身也会漂移。同一次复核里发现
> 「6 语言 UI 完整对等」的旧说法不成立（4 个语言包各缺 13 个键，详见下文 ⚠️ 标注），
> 已补齐并配了构建期检查。**读本表请按「待验证」处理，以代码与测试为准。**
>
> **2026-09-20 follow-up**: even this document's per-row conclusions drift. The same pass
> found the "6-language UI fully aligned" claim false (4 bundles were each missing 13 keys —
> see the ⚠️ note below). Now fixed, with a build-time check. **Treat this table as
> unverified; the code and the tests are authoritative.**

---

## 中文

### 结论概览

10 个功能域（编辑器语言功能、模板画廊、模板素材、任务启动、参数控件、角色管理、
工具箱、标注编辑器、临时截图、截图采集）**全部在子仓库有对应实现，无整块缺失**。

剩余差异集中在两类：**标注编辑器的 1 项落盘语义**、以及 **4 项低优先级的 UI 形态差异**
（均为载体不同而非功能缺失，见「差异说明」）。

### ✅ 已对齐

- 编辑器：4 类引用识别（lang/模板/效果/OCR）、5 场景补全（模板置顶、效果分类排序）、
  hover（全语言表格/缩略图预览/OCR 运行时说明）、行内提示 + tooltip、JSON 侧效果提示
- 模板画廊：响应式网格、插入/复制/红框标注原图（ok_templates 反查 30s TTL）
- 任务启动：AST 列任务（条目带 `kind`）、schema 探测与缓存、**常驻执行器**、
  **执行器显式启动**（勾选触发任务不隐式拉起；工具栏启动按钮 `startExecutorAction`）、
  触发任务勾选入列 / 一次性任务入队、启用集合持久化、停止当前任务 / 关闭执行器、
  暂停/恢复、输出区、配置持久化、**任务后台存续（工具窗关闭不杀进程）**
- **配置沙箱**：`OK_TOOLKIT_RUN_DIR` 指向 `.idea/ok-script-toolkit`，
  与父仓 `.vscode/ok-script-toolkit` 对称；两端**共用同一份** `run_executor.py`
  （子仓将父仓 `python/` 整包进 JAR），`apply_config_sandbox` 行为完全一致，
  含 `devices.json` 桥接与 `screenshots_folder` 改道
- **折叠分组吸收内联显隐**：子仓抽成可单测纯对象 `tasklauncher/SchemaTreeOverlap.kt`
  （父仓对应实现内联在 `media/taskLauncher/configPanel.js`）；子仓另有
  `SchemaTreeOverlapTest.kt` 两条破坏性对照断言，规范度高于父仓
- 参数控件：bool/数字/下拉/多选/级联下拉/条件序列 JSON/configGroups 分组、
  **列表字段 ModifyListDialog 弹窗**、**option_labels/category_labels 本地化标签**、
  **字段描述渲染**、**sub_configs 子配置树**
- 角色面板（只读+技能 CRUD+强化组）、状态栏/表头去硬编码英文
  - ⚠️ 2026-09-20 复核并修复：**同步技能的保护粒度两端不一致**（P3-6）。
    父仓允许对同步技能改数值/效果，只锁 `skill_id`/`name`/`skill_type`/`element`/`description`；
    子仓原先是「更新同步技能直接抛 `is synced and locked`」，**且编辑按钮只对自定义技能显示** ——
    同一个技能文件在父仓能调参、在子仓连入口都没有。现已对齐父仓语义：
    编辑入口无条件开放（删除仍限自定义），标识字段在对话框内只读并显示
    `syncedSkillLocked` 提示，数值与效果照常可改。规则收敛到纯对象
    `core/SyncedSkillPolicy.kt`，由 `SyncedSkillPolicyTest.kt` 钉住（含破坏性对照）。
- 素材库：**批量导入+数字序号自动命名（nextImageName）**、**saveToAssets 导出可取消**、
  截图采集（**截图方式 auto/wgc/bitblt/foreground + 面板「硬前台」单次覆盖**）、
  标注基础操作、数据源、**数据文件 watcher 自动刷新面板**（VFS 监听+300ms 防抖）
- **工具箱**：游戏连接/断开（connect_game.py，未运行自动启动）、调试浮层开关、
  状态持久化 `.idea/ok-script-toolkit-toolbox.json`
- **标注编辑器**（`AnnotationDialog`）：画框/删除/坐标模式、**undo/redo**（`MAX_UNDO`
  上限栈，`AnnotationDialog.kt:94-95,148-153,418-433`）、**copy/paste**
 （Ctrl+C/V，`:486-487,751,757`）、**8 向 resize**（`:343 resizeHandle`）、
  **拖动移框**（`:305 CoordDrag`、`:659 applyCoordDrag`）、**缩放平移**（`:1031 isZoomed()`）、
  **跨图导航 ←/→**（`:490-491`、`:239 navigate()`）、**双击数值编辑**（`:645 clickCount`）
- 临时截图：10 张上限（`TempShotFiles.MAX_SHOTS`）、粘贴/截屏入列、0.1s 轮播、
  框选复制归一化坐标、缩略图拖到素材面板导入（`TempShotTransferable` 自定义 DataFlavor）
- 6 语言 UI、设置项、PythonScriptLocator 只从插件 JAR 提取脚本
  - ⚠️ 2026-09-20 复核：**语言包并非完整对等**。`zh_TW`/`ja`/`ko`/`es` 各缺 13 个
    `characterManager.*` 键（`enhancements` / `addEnhancement` / `deleteEnhancementConfirm` …），
    `ResourceBundle` 会静默回落成英文 —— 界面中英夹杂而无人报错。已补齐译文，
    并新增 `src/test/.../core/BundleParityTest.kt` 把「6 个 properties 键集必须逐一对等」钉死，
    以后漂移会在构建期失败而不是等用户发现。

### ⚠️ 待办

| # | 问题 | 严重度 | 状态 |
|---|---|---|---|
| 1 | 标注编辑器**改动即存**：当前为 OK/Cancel 语义（`doOKAction` 时统一写回，Cancel 全弃），父仓为改动即落盘 | 低 | 设计取舍，非缺陷 |
| 2 | 编辑器大画廊双入口（编辑器内嵌大画廊视图） | 低 | 待办 |
| 3 | 任务卡片式 UI（VSCode 任务列表为卡片布局） | 低 | 待办 |
| 4 | lastPythonEditor 跟踪（插入表达式定位最近编辑器） | 低 | 待办 |
| 5 | 注释面板命令（VSCode 有独立 `openAnnotationEditor` 命令；子仓无对应 Action，只能从素材管理器进入） | 低 | 待办 |

> 上一版把 `undo/redo、copy/paste、8 向 resize、拖动移框、缩放平移、跨图导航、
> 双击数值编辑` 一并列为待办 —— **这些均已实现**，本次移入「已对齐」。
> 该行仅剩「改动即存」。

### 差异说明（载体不同，非功能缺失）

- 子仓库为**纯 Kotlin + Swing 原生 UI**，无 JCEF/webview；父仓为 webview HTML。
- 标注编辑器：父仓是独立面板（`src/annotationPanel.ts`），子仓是 `AnnotationDialog`
  且只从模板素材管理器打开。
- 角色管理器：父仓是 webview，子仓是 fileType `okcharacters` 编辑器标签页。
- 资产打包：父仓用 worker 池 + 自实现 PNG/JPEG/BMP 编解码，子仓同线程 AWT + `ImageIO`
  （无并行度，仅 `onProgress` 回调）；分包算法与命名一致。

### 存储路径对照

| 项 | VSCode 主仓 | JetBrains 子仓 |
|---|---|---|
| 配置沙箱 | `<workspace>/.vscode/ok-script-toolkit` | `<project>/.idea/ok-script-toolkit` |
| 任务配置/启用集合 | `.vscode/ok-script-toolkit-tasks.json` | `.idea/ok-script-toolkit-tasks.json` |
| Schema 缓存 | `.vscode/ok-script-toolkit-schema.json` | `.idea/ok-script-toolkit-schema.json` |
| 工具箱状态 | 工作区 `.vscode` | `.idea/ok-script-toolkit-toolbox.json` |
| 临时截图 | 扩展 `globalStorage`（工作区哈希隔离） | `<IDE system>/ok-script-toolkit/temp-screenshots/<项目哈希>` |

---

## English

### Summary

All 10 feature domains (editor language features, template gallery, template assets, task launcher,
parameter controls, character manager, toolbox, annotation editor, temp screenshots, screenshot capture)
**have a counterpart implementation in the sub-repo — nothing is wholly missing.**

Remaining gaps are two kinds: **one annotation-editor save-semantics difference**, and
**four low-priority UI-shape differences** (carrier differences, not missing functionality — see below).

### ✅ Aligned

- Editor: 4 reference recognition types (lang/template/effect/OCR), 5 completion scenarios, hover
  (full locale table / thumbnail preview / OCR runtime description), inline hints + tooltip, JSON-side effect hints
- Template gallery: responsive grid, insert/copy/red-box annotated source image (ok_templates reverse lookup 30s TTL)
- Task launcher: AST-based task listing (entries with `kind`), schema probing & caching, **persistent executor**,
  **explicit executor start** (toggling a trigger task does not implicitly launch it; toolbar `startExecutorAction`),
  trigger toggle / one-time enqueue, enabled set persistence, stop current / close executor,
  pause/resume, output area, config persistence, **background persistence**
- **Config sandbox**: `OK_TOOLKIT_RUN_DIR` = `.idea/ok-script-toolkit`, symmetric with the parent's
  `.vscode/ok-script-toolkit`; both sides **share the same** `run_executor.py` (the sub-repo bundles the
  parent `python/` into its JAR), so `apply_config_sandbox` behaves identically — including `devices.json`
  bridging and `screenshots_folder` redirection
- **Collapsible group absorbing inline show/hide**: the sub-repo extracts it into a unit-testable pure object
  `tasklauncher/SchemaTreeOverlap.kt` (the parent inlines it in `media/taskLauncher/configPanel.js`), plus
  `SchemaTreeOverlapTest.kt` with two destructive-control assertions — stricter than the parent side
- Parameter controls: bool/number/dropdown/multi-select/cascading/condition-sequence JSON/configGroups,
  **ModifyListDialog**, **option_labels/category_labels**, **field descriptions**, **sub_configs tree**
- Character panel (read-only + skill CRUD + enhancement groups), status bar / table header de-hardcoded
  - ⚠️ 2026-09-20 reviewed and fixed: **synced-skill protection granularity differed across repos** (P3-6).
    The parent lets you edit a synced skill's numeric/effect fields and locks only
    `skill_id`/`name`/`skill_type`/`element`/`description`; the sub-repo used to throw
    `is synced and locked` on any update, **and only showed the edit button for custom skills** —
    so the same skill file was tunable in the parent but had no entry point at all in the sub-repo.
    Now aligned with the parent: the edit button is unconditional (delete stays custom-only), identity
    fields are read-only in the dialog with a `syncedSkillLocked` notice, and numeric/effect fields
    remain editable. The rule lives in the pure object `core/SyncedSkillPolicy.kt` and is pinned by
    `SyncedSkillPolicyTest.kt` (with destructive controls).
- Asset library: **batch import + numbered naming**, **saveToAssets cancelable**, screenshot capture
  (auto/wgc/bitblt/foreground + "hard foreground" override), annotation basics, data sources,
  **data file watcher auto-refresh** (VFS + 300ms debounce)
- **Toolbox**: game connect/disconnect, debug overlay toggle, state in `.idea/ok-script-toolkit-toolbox.json`
- **Annotation editor** (`AnnotationDialog`): draw/delete/coords modes, **undo/redo** (`MAX_UNDO` stack,
  `AnnotationDialog.kt:94-95,148-153,418-433`), **copy/paste** (Ctrl+C/V, `:486-487,751,757`),
  **8-way resize** (`:343`), **drag-to-move** (`:305`, `:659`), **zoom/pan** (`:1031`),
  **cross-image ←/→** (`:490-491`, `:239`), **double-click value edit** (`:645`)
- Temp screenshots: 10-shot limit, paste/capture enqueue, 0.1s carousel, box-select normalized coords,
  thumbnail drag to asset panel (`TempShotTransferable` custom DataFlavor)
- 6-language UI, settings, PythonScriptLocator extracts scripts only from the plugin JAR

### ⚠️ TODO

| # | Issue | Severity | Status |
|---|---|---|---|
| 1 | Annotation editor **save-on-change**: currently OK/Cancel semantics (`doOKAction` writes back all changed images, Cancel discards), vs the parent's save-on-change | Low | Design trade-off, not a defect |
| 2 | Editor large gallery dual entry | Low | TODO |
| 3 | Task card-style UI | Low | TODO |
| 4 | lastPythonEditor tracking | Low | TODO |
| 5 | Annotation panel command (parent has standalone `openAnnotationEditor`; sub-repo has no matching Action) | Low | TODO |

> The previous revision listed `undo/redo, copy/paste, 8-way resize, drag-to-move, zoom/pan,
> cross-image navigation, double-click value edit` as TODO — **all are implemented** and moved to
> "Aligned" here. Only "save-on-change" remains from that row.

### Differences (carrier, not missing functionality)

- The sub-repo is **pure Kotlin + Swing**, no JCEF/webview; the parent uses webview HTML.
- Annotation editor: parent is a standalone panel (`src/annotationPanel.ts`); sub-repo is
  `AnnotationDialog`, opened only from the template asset manager.
- Character manager: parent is a webview; sub-repo is a fileType `okcharacters` editor tab.
- Asset packing: parent uses a worker pool with hand-written PNG/JPEG/BMP codecs; sub-repo renders
  on the same thread with AWT + `ImageIO` (no parallelism, only an `onProgress` callback);
  packing algorithm and naming match.

### Storage path comparison

| Item | VSCode (parent) | JetBrains (sub-repo) |
|---|---|---|
| Config sandbox | `<workspace>/.vscode/ok-script-toolkit` | `<project>/.idea/ok-script-toolkit` |
| Task config / enabled set | `.vscode/ok-script-toolkit-tasks.json` | `.idea/ok-script-toolkit-tasks.json` |
| Schema cache | `.vscode/ok-script-toolkit-schema.json` | `.idea/ok-script-toolkit-schema.json` |
| Toolbox state | workspace `.vscode` | `.idea/ok-script-toolkit-toolbox.json` |
| Temp screenshots | extension `globalStorage` (workspace-hash isolated) | `<IDE system>/ok-script-toolkit/temp-screenshots/<project hash>` |
