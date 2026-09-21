# 子仓库与主仓库功能差异审查（2026-09-21 更新）

[English](parity-review.en.md) | **中文**

对照基准：主仓库 VSCode 扩展 v1.8.0、子仓库 JetBrains 插件 v1.8.0。
下次审查请覆盖本表并更新状态。

> **上一版（2026-09-07，基线 v1.4.0）已严重失真**：它把当时尚未做的标注编辑器
> 进阶交互整批列为「待办」，但其中绝大多数随后已实现，文档未同步。本次审查逐条
> 回查代码后重写。今后请勿只改日期不查代码。
>
> **2026-09-20 追加复核**：本文档的「局部结论」本身也会漂移。同一次复核里发现
> 「6 语言 UI 完整对等」的旧说法不成立（4 个语言包各缺 13 个键，详见下文 ⚠️ 标注），
> 已补齐并配了构建期检查。**读本表请按「待验证」处理，以代码与测试为准。**
>
> **2026-09-21 追加复核**：项目约定文件那一节又漂了 —— 它只列了 4 个已接入字段，
> 实际已全部接完（含新增的 `config.py` 事实层）。本次已重写该节。**再次印证：这张表
> 只记录"上次查证时的结论"，读之前先按「待验证」处理。**
> 同时修正基线版本号（v1.7.1 → v1.8.0）。

---


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
- **项目约定文件 `ok-script-toolkit.json`**：放在**被调试项目**根目录，两端共用同一份
  （**只读**，插件绝不写入）。取值链 **个人设置 > 项目约定文件 > 项目 `config.py` 已声明的事实
  > 内置默认**。
  **已接入全部字段**（2026-09-21 收尾）：`labelEnum.path` / `labelEnum.name` / `labelEnum.aliases` /
  `templates.directory` / `templates.cocoAnnotations` / `i18n.enabled` / `i18n.langDirectory` /
  `i18n.poDirectory` / `i18n.poDomains` / `characters.projectPath` / `characters.masterFile` /
  `characters.skillsDirectory` / `characters.localeFile` / `characters.avatarTemplateRegex` /
  `effects.file`。
  - **`config.py` 事实层**目前只有 `templates.cocoAnnotations` 用上了：它是 ok 框架加载的
    **运行时模板库**（`ok/__init__.py` 读 `template_matching.coco_feature_json`）的路径，
    链为 `templates.cocoAnnotations` → `config.py` → 依次探测 `assets/coco_annotations.json`、
    `ok_tasks/assets/coco_annotations.json`（即引入约定文件之前的行为）。
    实测 6 个 ok 系项目**全都**声明了它，其中一个的文件名是 `coco_detection.json` ——
    没有这条链时插件在那类项目上**一个候选都探不到，模板库是空的**。
  - ⚠️ **素材面板自己的 `<模板目录>/coco_annotations.json` 是另一个文件**，路径由
    `templates.directory` 决定，不受 `templates.cocoAnnotations` 影响。
  - **`labelEnum.path` / `labelEnum.name` 有个人偏好层**（设置界面「模板素材」组里的
    `labelEnumPath` / `labelEnumName`）。这两项比其它设置危险：它们**决定往哪写文件、类叫什么**，
    而项目的代码是按名字 import 的（`from src.data.feature_list import FeatureList`）——
    个人覆盖改错就是全项目 `ImportError`。所以两端都在**覆盖已有文件前**做一次类名变更校验
    （`core/LabelEnumGuard.kt` ↔ `src/labelEnumGuard.ts`）：文件不存在不问、同名不问、
    但**类名会变时**先扫一遍项目里按旧类名 import 的文件、把"会炸多少处"报给用户确认。
    **只校验类名不校验路径** —— 换路径时旧文件原样留着，按旧模块路径 import 的代码仍然能跑。
  - ⚠️ 「修改路径…」填的值会写进设置（相对项目根的写法），所以"填过一次就记住"两端一致；
    留空 = **撤销覆盖、回到项目约定**（与 `labelEnum.aliases` 的空值同一条规则）。
    VS Code 侧此前把这层藏在 `globalState` 里（全局、界面看不见、跨项目串味），已废弃。
  - ⚠️ **按字段类型选归一化**：相对路径走 `normalizeRelPath`；绝对路径（`characters.projectPath`）
    与正则（`characters.avatarTemplateRegex`）**绝不归一化** —— 前者会被吃掉开头斜杠、
    后者会把 `\d` 换成 `/d`，都只是"匹配不到"，不报错。
  两端各有一个**溯源面板**（父仓命令 `showConventionSources`；子仓 Tools 菜单
  `ShowConventionSources`）：列出每一项的生效值来自哪一层，并给被个人设置覆盖过的项
  一个「恢复」按钮。两端实现对称 —— 纯对象（父仓 `projectConfigPure.ts` /
  子仓 `core/ProjectConvention.kt`）都产出 `{ value, layer }`，**来源层由取值链本身
  产出、不在 UI 里复算**（复算会与实际生效值分叉，且分叉是静默的）。
  面板里的"项目文件里写了什么"也**由同一条链再跑一遍**得出（把个人偏好置空），
  这样展示值与生效值走同一套归一化。
  ⚠️ 两端设置项都带**非空默认值**，接新设置前必须先拿到"用户是否真的改过"这个信号
  （父仓 `inspect()`，子仓 `overriddenKeys`），否则项目声明会被**永久静默屏蔽**。
  两处都有守卫测试防漏：子仓 `OverrideKeyParityTest`（`personal` / `recordIfChanged` /
  播种三处键集必须一致）、父仓 `test_convention_sources.js`（登记表必须覆盖每个
  `ideSetting` 键）。
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
  - ⚠️ 2026-09-20 复核并修复：**读取层把显式 JSON `null` 读成了字符串 `"null"`**。
    Jackson 的 `get()` 在字段值为 `null` 时返回 `NullNode`（不是 Kotlin `null`），
    而 `NullNode.asText()` 返回**字面量 `"null"`** —— 于是 `node.get(k)?.asText() ?: 兜底`
    既不触发兜底、又把 `"null"` 当成真实值。技能 `element` 上实测到：父仓
    `sanitizeSkill` 写的是 `optionalString(...) || null`，空元素**就是** `"element": null`，
    子仓会把它显示成元素名 "null"。父仓 `characterData.ts:577` 的 `stringValue` 会兜底到
    角色元素。现已新增 `JsonNodeExt.textOrNull()` / `textOr()` 统一该语义，并应用到
    角色/技能/效果引用/强化组的全部字符串读取点（`JsonNodeExtTest` + `CharacterDataServiceTest` 覆盖）。
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
    （2026-09-21 复核：键集逐一对等，**译文也没有遗漏** —— 各语言与英文取值相同的只剩
    `plugin.name`、`templateAsset.exportEnumTitle` 这类**刻意不译**的产品名/文件名，
    以及西班牙语里本来就写作 `Error` 的那个词。）

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
| 项目约定文件 | `<project>/ok-script-toolkit.json`（**被调试项目**根目录） | 同左 —— 两端读同一份，只读 |
| 配置沙箱 | `<workspace>/.vscode/ok-script-toolkit` | `<project>/.idea/ok-script-toolkit` |
| 任务配置/启用集合 | `.vscode/ok-script-toolkit-tasks.json` | `.idea/ok-script-toolkit-tasks.json` |
| Schema 缓存 | `.vscode/ok-script-toolkit-schema.json` | `.idea/ok-script-toolkit-schema.json` |
| 工具箱状态 | 工作区 `.vscode` | `.idea/ok-script-toolkit-toolbox.json` |
| 临时截图 | 扩展 `globalStorage`（工作区哈希隔离） | `<IDE system>/ok-script-toolkit/temp-screenshots/<项目哈希>` |

---
