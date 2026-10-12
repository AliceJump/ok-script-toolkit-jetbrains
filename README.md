<div align="center">

[![简体中文](https://img.shields.io/badge/Language-%E7%AE%80%E4%BD%93%E4%B8%AD%E6%96%87%20%E2%9C%93-2EA043?style=for-the-badge)](README.md) [![English](https://img.shields.io/badge/Language-English-6E7681?style=for-the-badge)](README.en.md)

<img src="icon.png" alt="ok-script Toolkit for JetBrains" width="128" height="128">

# ok-script Toolkit for JetBrains

**ok-script Toolkit VS Code 扩展的 JetBrains Platform / PyCharm 移植版。**

将 ok-script 语言键、OCR 修正、模板、效果和任务带入你的 IDE。

[![JetBrains Marketplace](https://img.shields.io/badge/JetBrains%20Marketplace-OK%20Script%20Toolkit-000000?logo=jetbrains&logoColor=white)](https://plugins.jetbrains.com/plugin/34091-ok-script-toolkit)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/34091)](https://plugins.jetbrains.com/plugin/34091-ok-script-toolkit)
[![Version](https://img.shields.io/badge/version-1.25.0-blue)](gradle.properties)
[![Platform](https://img.shields.io/badge/Platform-2025.1%2B-000000?logo=jetbrains&logoColor=white)](gradle.properties)
[![Kotlin](https://img.shields.io/badge/Kotlin-JDK%2021-7F52FF?logo=kotlin&logoColor=white)](build.gradle.kts)
[![License](https://img.shields.io/badge/license-MIT-green)](LICENSE)
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/AliceJump/ok-script-toolkit-jetbrains)

[安装](#安装) · [功能](#功能) · [构建](#构建) · [发布](#发布) · [开发](#agent-技能)

</div>
---

本仓库是仓库根目录中 VS Code 扩展的 JetBrains Platform/PyCharm 移植版。两个版本共享相同的数据源和配置模型，因此 ok-script 项目在任一 IDE 中的行为一致。

## 安装

从 JetBrains Marketplace 安装 **ok-script Toolkit**：

- **在 IDE 中** — <kbd>Settings</kbd> → <kbd>Plugins</kbd> → <kbd>Marketplace</kbd>，搜索 `ok-script Toolkit`。
- **在网页上** — 打开 [插件页面](https://plugins.jetbrains.com/plugin/34091-ok-script-toolkit)，点击 **Install to IDE**。

需要平台版本 **2025.1** 或更高，以及支持 Python 的 IDE — PyCharm，或安装了 Python 插件的 IntelliJ IDEA Ultimate。

如需从源码构建插件，见 [构建](#构建)。

## 功能
### 编辑辅助
<!-- Demo GIF: code hints. Show self.lang inline hints + hover locale table. Width 900.
     Record from the JetBrains sandbox IDE — the parent repo's GIFs are VS Code. -->
<!-- Suggested file: screenshots/code-hints.gif -->
<!--
<p align="center">
  <img src="screenshots/code-hints.gif" alt="self.lang completion and locale hover" width="900">
</p>
-->

| 功能 | 说明 |
|---|---|
| `self.lang` 补全 | `self.lang.<module>.<key>` 的补全和快速文档 — 悬停时显示完整的 locale 表格，加行内值提示和 tooltip |
| OCR `match` | 来自 `ocr.po` 的补全和文档，加调用后的行内翻译提示 |
| 模板补全 | `fL` / `FeatureList` 补全，置顶优先级，快速文档显示裁剪预览 |
| 效果 ID | `EffectType.XXX` 和 JSON/Python 效果 ID 补全（按分类分组）、文档和行内描述提示 |

### 工具窗口

所有功能都在右侧**一个**「ok-script」工具窗口里（与 VS Code PR #41 的单一容器对齐）：

- **运行** tab — 上半区是任务树（触发任务 = 勾选框；一次性任务按业务分组，双击 / Enter 入队，支持速搜），下半区是所选任务的参数表单；执行器控制在 ActionToolbar 上，状态栏另有执行器 widget（点击弹出暂停 / 继续 / 停止当前 / 查看日志）。
- **资源** tab — 分段按钮切换 **收件箱**（临时截图）→ **草稿**（标注管理）→ **已发布**（资源预览）。有未发布的草稿模板时顶部出现横幅，「预览并发布…」先弹出只读的**发布预览**（将写入的文件 + 模板新增 / 覆盖 / 删除，仅按名字比较），确认后才写盘。
- 旧的四个工具窗口 id（`ok-script Tasks` / `Temp Shots` / `Annotation Management` / `Resource Preview`）保留一个版本作为隐藏的重定向别名。
- 卡片手势统一：单击 / Space 选中，双击 / Enter 主操作，`E` 编辑，`Ctrl/Cmd+C` 复制，`Delete` 删除，方向键移动；按钮全部为平台图标并带读屏名。
- **截到收件箱**：`Ctrl+Alt+Shift+O`（macOS `⌘⌥⇧O`）。不用 `Ctrl+Alt+S`（IDE Settings），核对方法见 [docs/keymap.md](docs/keymap.md)。
- 编辑器集成：`fL.xxx` / `self.pos.xxx` 悬停文档底部有「在资源中定位 · 查找引用」；右键「查找模板引用」把结果放进 Find 工具窗口；`.py` 引用了未发布模板时编辑器顶部出现横幅；Project 视图里图片右键「用标注编辑器打开」（仅 png / jpg / jpeg / webp）。

- **框资源与框画廊**：使用共用标注编辑器编辑 COCO 框资源、从模板生成框并显式发布；运行时画廊提供裁剪预览、来源跳转及 `self.pos` 表达式插入与复制。游戏加载由业务项目负责。当前两端状态见 [功能对齐表](https://github.com/AliceJump/ok-script-toolkit/blob/main/docs/feature-parity.md)。
<!-- Demo GIF: tool windows. Show the template gallery + temp shots area. Width 900.
     Record from the JetBrains sandbox IDE — the parent repo's GIFs are VS Code. -->
<!-- Suggested file: screenshots/tool-windows.gif -->
<!--
<p align="center">
  <img src="screenshots/tool-windows.gif" alt="Template gallery and temp shots tool windows" width="900">
</p>
-->

- **资源预览**（资源 · 已发布）— Template / Rect / Point 并排切换，卡片双击 / Enter 插入、`Ctrl/Cmd+C` 复制；右下角常驻插入、复制、原图按钮。侧栏与宽屏预览共用自适应网格、搜索、计数和加载状态。
- **标注管理**（资源 · 草稿）— 导入、截图、搜索和发布；原图双击 / Enter 打开标注编辑器，右下角直接查看、交换 Template 标注或删除图片。交换目标按缩略图选择，不同尺寸按比例缩放并确认；删除联动清理三类工作标注。
- **任务启动器** — 基于 schema 的参数表单（按 `configGroups` 分组），每任务参数覆盖支持自动保存和实时推送到运行中的执行器，单一长驻执行器进程：一次连接后由框架原生的 `TaskExecutor` 循环轮询全部已启用的触发任务。触发任务通过勾选框切换（入列/轮询），一次性任务入队执行一次，加上停止当前任务/关闭执行器/暂停/恢复和实时输出控制台。
- **全局配置接管与健康度** — 解析目标项目的全局配置组（ok 框架 GlobalConfig 的可见分组）并持久化参数快照，空闲态运行中心列出各配置组（悬停弹出只读摘要）；参数改动实时推送给运行中的执行器（`gparams`），启动执行器时经 `OK_TOOLKIT_GCONFIG` 注入全量快照。健康度条实时反映执行器状态（当前任务 / 执行队列 / 触发轮询 / 暂停），任务行悬停弹出只读摘要。
- **临时截图**（资源 · 收件箱）— 最多 10 张最近截图的暂存区。粘贴剪贴板图片、截取游戏窗口，或拖入/粘贴图片文件。舞台显示选中的截图或每 0.1s 循环播放全部；框选将归一化 `x,y,tox,toy` 坐标复制到剪贴板，缩略图可拖到素材管理器（或通过右键菜单发送）导入到 `ok_templates`。坐标框之后保留在画布上，带 8 个调整手柄并可拖动 — 每次创建或调整都会重新复制坐标；永远不会写入 COCO，点击框外（或退出模式）清除。
- **工具箱**（在任务启动器内，也是角色管理器的入口 — 全宽按钮位于工具箱顶部，与 VS Code toolbox 视图中的 `toolboxOpenCharacterManager` 按钮完全一致）：连接或断开游戏窗口（`connect_game.py`，自动启动游戏并通过 `configs/devices.json` 复用连接），以及调试浮层开关，被启动的任务沿用，在运行中的任务上实时切换，并由持久化的 overlay 宿主（`overlay_host.py`）支持 Alt+Right-click 坐标拾取。

### 编辑器
<!-- Demo GIF: character manager tab. Show the four tabs and filtering. Width 900.
     Record from the JetBrains sandbox IDE — the parent repo's GIFs are VS Code. -->
<!-- Suggested file: screenshots/character-manager.gif -->
<!--
<p align="center">
  <img src="screenshots/character-manager.gif" alt="Character manager editor tab" width="900">
</p>
-->

- **标注编辑器** — 编辑器页签内统一处理 Template / Rect / Point，完成编辑立即保存；支持数值编辑、显隐、多选、删除模式、撤销重做、复制粘贴、方向键微调和图片导航，快捷键可配置。坐标模式（`C`）复制归一化坐标，支持移动与八向缩放。保存失败显示重试并保留可恢复草稿。
- **角色管理器** — 作为完整编辑器标签页打开（而非工具窗口），因此拥有与 VS Code `ViewColumn.One` webview 相同的空间。由非物理的 `CharacterManagerFile` 加带 `FileEditorPolicy.HIDE_DEFAULT_EDITOR` 的 `FileEditorProvider` 承载。四个标签页 — 角色 / 效果 / locale / 问题 — 支持星级、元素、职业、技能类型、有强化组和有问题筛选，效果使用筛选（已用 / 未用 / 未定义），技能和强化组 CRUD，效果和分类插入到 `src/data/effects.py`，以及双击跳转到问题和效果的源码。

### 其他
- 项目约定文件：被调试项目根目录下的 `ok-script-toolkit.json`（**只读**，插件绝不写入）可以声明模板目录、运行时模板库路径、枚举路径/类名/引用别名、i18n（语言/PO 目录与开关）、角色数据位置、效果定义文件等团队约定，免去每个成员各配一遍。取值优先级为**个人设置 > 项目约定文件 > 内置默认** —— 项目文件是团队开箱默认，你手动改过就以你的为准。
  - 其中**运行时模板库**（ok 框架加载的那份 COCO）的路径还有一层「项目 `config.py` 已声明的事实」：`templates.cocoAnnotations` → `config.py` 的 `template_matching.coco_feature_json` → 依次探测 `assets/coco_annotations.json`、`ok_tasks/assets/coco_annotations.json`。最后那层就是引入约定文件之前的行为。素材面板自己的 `<模板目录>/coco_annotations.json` 是**另一个文件**，跟随 `templates.directory`。
- **项目约定 vs 我的设置**（Tools 菜单）：列出参与取值链的设置项，显示每一项的**生效值来自哪一层**（我的设置 / 项目约定 / 内置默认）。被个人设置覆盖过的项带「恢复」按钮，一键回到项目约定。存在的原因是上面那条优先级有个副作用 —— 一旦你手动改过，项目声明的那一项就对你永久失效、界面上毫无提示。
- 项目设置：路径、locale、别名和功能开关。
- UI 本地化：`en`、`zh_CN`、`zh_TW`、`ja`、`ko`、`es`。

## 构建

建议从父仓 `ok-script-toolkit` 连同子模块一起检出，在 `jetbrains/` 目录构建；
构建需要父仓的 `python/` 脚本目录位于本目录的上一级。独立检出子仓时，
先取得父仓的 `python/`，并给 Gradle 传入 `-PpythonScriptsDir=/父仓绝对路径/python`。
缺少脚本时 `buildPlugin` 会直接报错，避免生成无法运行任务的 ZIP。

使用本目录中自带的 wrapper：

```bash
# Windows
gradlew.bat buildPlugin

# macOS / Linux
./gradlew buildPlugin
```

插件 ZIP 输出到 `build/distributions/`。

构建目标为 **JDK 21 工具链**（与 CI 和 IntelliJ 2025.1 运行时匹配）。如果本地没有匹配的 JDK，会通过 foojay resolver 自动配置。如需复用本地 PyCharm 安装而非下载 SDK，传入 `-PplatformLocalPath=/absolute/path/to/PyCharm`（必须包含 `product-info.json`）。

## 发布
> [!IMPORTANT]
> 仓库根项目是唯一的发布协调者。本仓库中的版本必须与父仓库 `package.json` 匹配，只有向 `AliceJump/ok-script-toolkit` 推送新的 `vX.Y.Z` 标签才会发布 VS Code 和 JetBrains 两个版本。本仓库自身的 CI 验证源码变更但**不发布 Release**。

## Agent 技能

开发规范位于 `agents/skills/`：

- [`jetbrains-toolwindow-icons`](agents/skills/jetbrains-toolwindow-icons/SKILL.md) — 新 UI 工具窗图标规范（四件套命名、尺寸、色板颜色、变色机制）。

---

<div align="center">

**相关项目**

[主仓库](https://github.com/AliceJump/ok-script-toolkit) · [开发指南](https://github.com/AliceJump/ok-script-toolkit/blob/main/DEVELOPMENT.md) · [发布流程](https://github.com/AliceJump/ok-script-toolkit/blob/main/RELEASING.md)

</div>
