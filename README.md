<div align="center">

<img src="icon.png" alt="ok-script Toolkit for JetBrains" width="128" height="128">

# ok-script Toolkit for JetBrains

**The JetBrains Platform / PyCharm port of the [ok-script Toolkit](../README.md) VS Code extension.**

**ok-script Toolkit VS Code 扩展的 JetBrains Platform / PyCharm 移植版。**

Brings ok-script language keys, OCR fixes, templates, effects and tasks into your IDE.

将 ok-script 语言键、OCR 修正、模板、效果和任务带入你的 IDE。

[![JetBrains Marketplace](https://img.shields.io/badge/JetBrains%20Marketplace-OK%20Script%20Toolkit-000000?logo=jetbrains&logoColor=white)](https://plugins.jetbrains.com/plugin/34091-ok-script-toolkit)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/34091)](https://plugins.jetbrains.com/plugin/34091-ok-script-toolkit)
[![Version](https://img.shields.io/badge/version-1.7.1-blue)](gradle.properties)
[![Platform](https://img.shields.io/badge/Platform-2025.1%2B-000000?logo=jetbrains&logoColor=white)](gradle.properties)
[![Kotlin](https://img.shields.io/badge/Kotlin-JDK%2021-7F52FF?logo=kotlin&logoColor=white)](build.gradle.kts)
[![License](https://img.shields.io/badge/license-MIT-green)](LICENSE)

[Install](#install) · [Features](#features) · [Build](#build) · [Releases](#releases) · [Development](#agent-skills)

</div>

---

### 中文

本仓库是仓库根目录中 VS Code 扩展的 JetBrains Platform/PyCharm 移植版。两个版本共享相同的数据源和配置模型，因此 ok-script 项目在任一 IDE 中的行为一致。

### English

This repository is the JetBrains Platform/PyCharm port of the VS Code extension in
the repository root. Both editions share the same data sources and configuration
model, so an ok-script project behaves the same way in either IDE.

<!-- HERO: JetBrains-edition demo GIF. Suggested 900px wide, single file < 5MB.
     Do NOT reuse the parent repo's screenshots/ — those record the VS Code
     edition (webview UI), which looks nothing like this Swing-based plugin.
     Record a fresh one from the sandbox IDE to enable this block. -->
<!-- Suggested file: screenshots/hero.gif -->
<!--
<p align="center">
  <img src="screenshots/hero.gif" alt="ok-script Toolkit for JetBrains demo" width="900">
</p>
-->

## 安装 / Install

### 中文

从 JetBrains Marketplace 安装 **ok-script Toolkit**：

- **在 IDE 中** — <kbd>Settings</kbd> → <kbd>Plugins</kbd> → <kbd>Marketplace</kbd>，搜索 `ok-script Toolkit`。
- **在网页上** — 打开 [插件页面](https://plugins.jetbrains.com/plugin/34091-ok-script-toolkit)，点击 **Install to IDE**。

需要平台版本 **2025.1** 或更高，以及支持 Python 的 IDE — PyCharm，或安装了 Python 插件的 IntelliJ IDEA Ultimate。

如需从源码构建插件，见 [构建](#构建)。

### English

Install **ok-script Toolkit** from JetBrains Marketplace:

- **In your IDE** — <kbd>Settings</kbd> → <kbd>Plugins</kbd> → <kbd>Marketplace</kbd>, then search for `ok-script Toolkit`.
- **On the web** — open the [plugin page](https://plugins.jetbrains.com/plugin/34091-ok-script-toolkit) and use **Install to IDE**.

Requires platform **2025.1** or newer, and a Python-capable IDE — PyCharm, or IntelliJ IDEA Ultimate with the Python plugin.

To build the plugin from source instead, see [Build](#构建).

## 功能 / Features

### 编辑辅助 / Editing assistance

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

| Feature | Description |
|---|---|
| `self.lang` completion | Completion and quick documentation for `self.lang.<module>.<key>` — a full per-locale table on hover, plus inline value hints with tooltips |
| OCR `match` | Completion and documentation from `ocr.po`, plus inline translation hints rendered after the call |
| Template completion | `fL` / `FeatureList` completion with pinned priority and quick documentation showing cropped previews |
| Effect IDs | `EffectType.XXX` and JSON/Python effect-ID completion (grouped by category), documentation, and inline description hints |

### 工具窗口 / Tool windows

<!-- Demo GIF: tool windows. Show the template gallery + temp shots area. Width 900.
     Record from the JetBrains sandbox IDE — the parent repo's GIFs are VS Code. -->
<!-- Suggested file: screenshots/tool-windows.gif -->
<!--
<p align="center">
  <img src="screenshots/tool-windows.gif" alt="Template gallery and temp shots tool windows" width="900">
</p>
-->

- **模板画廊** — 带 bbox 裁剪的缩略图，支持插入/复制操作，以及源图片的红框标注预览。
- **模板素材管理器** — 导入和删除图片，自动同步 COCO。
- **任务启动器** — 基于 schema 的参数表单（按 `configGroups` 分组），每任务参数覆盖支持自动保存和实时推送到运行中的执行器，单一长驻执行器进程：一次连接后由框架原生的 `TaskExecutor` 循环轮询全部已启用的触发任务。触发任务通过勾选框切换（入列/轮询），一次性任务入队执行一次，加上停止当前任务/关闭执行器/暂停/恢复和实时输出控制台。
- **临时截图** — 最多 10 张最近截图的暂存区。粘贴剪贴板图片、截取游戏窗口，或拖入/粘贴图片文件。舞台显示选中的截图或每 0.1s 循环播放全部；框选将归一化 `x,y,tox,toy` 坐标复制到剪贴板，缩略图可拖到素材管理器（或通过右键菜单发送）导入到 `ok_templates`。坐标框之后保留在画布上，带 8 个调整手柄并可拖动 — 每次创建或调整都会重新复制坐标；永远不会写入 COCO，点击框外（或退出模式）清除。
- **工具箱**（在任务启动器内，也是角色管理器的入口 — 全宽按钮位于工具箱顶部，与 VS Code toolbox 视图中的 `toolboxOpenCharacterManager` 按钮完全一致）：连接或断开游戏窗口（`connect_game.py`，自动启动游戏并通过 `configs/devices.json` 复用连接），以及调试浮层开关，被启动的任务沿用，在运行中的任务上实时切换，并由持久化的 overlay 宿主（`overlay_host.py`）支持 Alt+Right-click 坐标拾取。

- **Template gallery** — bbox-cropped thumbnails with insert/copy actions, and a
  red-box annotated preview of the source image.
- **Template asset manager** — import and delete images with automatic COCO sync.
- **Task launcher** — schema-driven parameter forms (grouped by `configGroups`),
  per-task overrides with auto-save and live push into the running executor, and a
  single long-lived executor process: one connection, then the framework's own
  `TaskExecutor` loop polls every enabled trigger task in rotation. Trigger tasks
  are toggled on/off with a checkbox (enqueued / polling), one-time tasks are
  enqueued to run once, plus stop-current-task / close-executor / pause / resume
  and a live output console.
- **Temp shots** — a scratch area for at most 10 recent screenshots. Paste a
  clipboard image, capture the game window, or drop/paste image files. The stage
  shows the selected shot or cycles all of them every 0.1s; box-select copies
  normalized `x,y,tox,toy` coordinates to the clipboard, and thumbnails can be
  dragged onto the asset manager (or sent from the context menu) to import into
  `ok_templates`. The coordinate box stays on the canvas afterwards with 8 resize
  handles and can be dragged around — each create or adjustment re-copies the
  coordinates; it is never written to COCO, and clicking outside the box (or
  leaving the mode) clears it.
- **Toolbox** (inside the task launcher, and the entry point for the character
  manager — a full-width button sits on top of the toolbox, exactly like the
  `toolboxOpenCharacterManager` button in the VS Code toolbox view): connect or
  disconnect the game window (`connect_game.py`, auto-starts the game and reuses
  the connection for every task via `configs/devices.json`), and a debug-overlay
  toggle that is adopted by launched tasks, toggled live on running tasks, and
  backed by a persistent overlay host (`overlay_host.py`) for Alt+Right-click
  coordinate picking.

### 编辑器 / Editors

<!-- Demo GIF: character manager tab. Show the four tabs and filtering. Width 900.
     Record from the JetBrains sandbox IDE — the parent repo's GIFs are VS Code. -->
<!-- Suggested file: screenshots/character-manager.gif -->
<!--
<p align="center">
  <img src="screenshots/character-manager.gif" alt="Character manager editor tab" width="900">
</p>
-->

- **标注编辑器** — 画框、删除，以及坐标模式（`C`），框选并复制归一化 `x,y,tox,toy` 而不创建标注框；同样的可调整框行为适用。
- **角色管理器** — 作为完整编辑器标签页打开（而非工具窗口），因此拥有与 VS Code `ViewColumn.One` webview 相同的空间。由非物理的 `CharacterManagerFile` 加带 `FileEditorPolicy.HIDE_DEFAULT_EDITOR` 的 `FileEditorProvider` 承载。四个标签页 — 角色 / 效果 / locale / 问题 — 支持星级、元素、职业、技能类型、有强化组和有问题筛选，效果使用筛选（已用 / 未用 / 未定义），技能和强化组 CRUD，效果和分类插入到 `src/data/effects.py`，以及双击跳转到问题和效果的源码。

- **Annotation editor** — drawing, deletion, and a coords mode (`C`) that
  box-selects and copies normalized `x,y,tox,toy` without creating an annotation
  box; the same adjustable-box behaviour applies.
- **Character manager** — opened as a full editor tab rather than a tool window,
  so it gets the same room as the VS Code `ViewColumn.One` webview. It is carried
  by a non-physical `CharacterManagerFile` plus a `FileEditorProvider` with
  `FileEditorPolicy.HIDE_DEFAULT_EDITOR`. Four tabs — characters / effects /
  locales / issues — with star, element, profession, skill-type, has-enhancements
  and has-issues filters, an effect usage filter (used / unused / undefined),
  skill and enhancement CRUD, effect and category insertion into
  `src/data/effects.py`, and double-click jump-to-source for issues and effects.

### 其他 / Miscellaneous

- 项目设置：路径、locale、别名和功能开关。
- UI 本地化：`en`、`zh_CN`、`zh_TW`、`ja`、`ko`、`es`。

- Project settings for paths, locale, aliases, and feature toggles.
- UI localized in `en`, `zh_CN`, `zh_TW`, `ja`, `ko`, and `es`.

## 构建 / Build

### 中文

使用本目录中自带的 wrapper：

```bash
# Windows
gradlew.bat buildPlugin

# macOS / Linux
./gradlew buildPlugin
```

插件 ZIP 输出到 `build/distributions/`。

构建目标为 **JDK 21 工具链**（与 CI 和 IntelliJ 2025.1 运行时匹配）。如果本地没有匹配的 JDK，会通过 foojay resolver 自动配置。如需复用本地 PyCharm 安装而非下载 SDK，传入 `-PplatformLocalPath=/absolute/path/to/PyCharm`（必须包含 `product-info.json`）。

### English

Use the bundled wrapper from this directory:

```bash
# Windows
gradlew.bat buildPlugin

# macOS / Linux
./gradlew buildPlugin
```

The plugin ZIP is written to `build/distributions/`.

The build targets the **JDK 21 toolchain** (matching CI and the IntelliJ 2025.1
runtime). If no matching JDK is installed locally it is provisioned automatically
via the foojay resolver. To reuse a local PyCharm installation instead of
downloading the SDK, pass `-PplatformLocalPath=/absolute/path/to/PyCharm` (must
contain `product-info.json`).

## 发布 / Releases

> [!IMPORTANT]
> 仓库根项目是唯一的发布协调者。本仓库中的版本必须与父仓库 `package.json` 匹配，只有向 `AliceJump/ok-script-toolkit` 推送新的 `vX.Y.Z` 标签才会发布 VS Code 和 JetBrains 两个版本。本仓库自身的 CI 验证源码变更但**不发布 Release**。

> [!IMPORTANT]
> The repository root project is the single release coordinator. The version in
> this repository must match the parent `package.json`, and only a new `vX.Y.Z`
> tag pushed to `AliceJump/ok-script-toolkit` publishes both the VS Code and
> JetBrains distributions. This repository's own CI validates source changes but
> **does not publish releases**.

## Agent 技能 / Agent skills

### 中文

开发规范位于 `agents/skills/`：

- [`jetbrains-toolwindow-icons`](agents/skills/jetbrains-toolwindow-icons/SKILL.md) — 新 UI 工具窗图标规范（四件套命名、尺寸、色板颜色、变色机制）。

### English

Development conventions live in `agents/skills/`:

- [`jetbrains-toolwindow-icons`](agents/skills/jetbrains-toolwindow-icons/SKILL.md)
  — new UI tool-window icon spec (four-variant naming, sizes, palette colors,
  tinting mechanics).

---

<div align="center">

**相关项目 / Related**

[主仓库 / Main repository](https://github.com/AliceJump/ok-script-toolkit) · [开发指南 / Development guide](../DEVELOPMENT.md) · [发布流程 / Release process](../RELEASING.md)

</div>