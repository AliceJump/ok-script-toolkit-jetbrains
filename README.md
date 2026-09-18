<div align="center">

# ok-script Toolkit for JetBrains

**The JetBrains Platform / PyCharm port of the [ok-script Toolkit](../README.md) VS Code extension.**

Brings ok-script language keys, OCR fixes, templates, effects and tasks into your IDE.

[![JetBrains Marketplace](https://img.shields.io/badge/JetBrains%20Marketplace-OK%20Script%20Toolkit-000000?logo=jetbrains&logoColor=white)](https://plugins.jetbrains.com/plugin/34091-ok-script-toolkit)
[![Version](https://img.shields.io/badge/version-1.4.0-blue)](gradle.properties)
[![Platform](https://img.shields.io/badge/Platform-2025.1%2B-000000?logo=jetbrains&logoColor=white)](gradle.properties)
[![Kotlin](https://img.shields.io/badge/Kotlin-JDK%2021-7F52FF?logo=kotlin&logoColor=white)](build.gradle.kts)
[![License](https://img.shields.io/badge/license-MIT-green)](LICENSE)

[Features](#features) · [Build](#build) · [Releases](#releases) · [Development](#agent-skills)

</div>

---

This repository is the JetBrains Platform/PyCharm port of the VS Code extension in
the repository root. Both editions share the same data sources and configuration
model, so an ok-script project behaves the same way in either IDE.

<!-- HERO: overall plugin demo GIF. Suggested 900px wide, single file < 5MB -->
<!-- Suggested file: screenshots/hero.gif -->
<!--
<p align="center">
  <img src="screenshots/hero.gif" alt="ok-script Toolkit for JetBrains demo" width="900">
</p>
-->

## Features

### Editing assistance

<!-- Demo GIF: code hints. Show self.lang inline hints + hover locale table. Width 900 -->
<!-- Suggested file: screenshots/code-hints.gif -->
<!--
<p align="center">
  <img src="screenshots/code-hints.gif" alt="self.lang completion and locale hover" width="900">
</p>
-->

| Feature | Description |
|---|---|
| `self.lang` completion | Completion and quick documentation for `self.lang.<module>.<key>` — a full per-locale table on hover, plus inline value hints with tooltips |
| OCR `match` | Completion and documentation from `ocr.po`, plus inline translation hints rendered after the call |
| Template completion | `fL` / `FeatureList` completion with pinned priority and quick documentation showing cropped previews |
| Effect IDs | `EffectType.XXX` and JSON/Python effect-ID completion (grouped by category), documentation, and inline description hints |

### Tool windows

<!-- Demo GIF: tool windows. Show the template gallery + temp shots area. Width 900 -->
<!-- Suggested file: screenshots/tool-windows.gif -->
<!--
<p align="center">
  <img src="screenshots/tool-windows.gif" alt="Template gallery and temp shots tool windows" width="900">
</p>
-->

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

### Editors

<!-- Demo GIF: character manager tab. Show the four tabs and filtering. Width 900 -->
<!-- Suggested file: screenshots/character-manager.gif -->
<!--
<p align="center">
  <img src="screenshots/character-manager.gif" alt="Character manager editor tab" width="900">
</p>
-->

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

### Miscellaneous

- Project settings for paths, locale, aliases, and feature toggles.
- UI localized in `en`, `zh_CN`, `zh_TW`, `ja`, `ko`, and `es`.

## Build

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

## Releases

> [!IMPORTANT]
> The repository root project is the single release coordinator. The version in
> this repository must match the parent `package.json`, and only a new `vX.Y.Z`
> tag pushed to `AliceJump/ok-script-toolkit` publishes both the VS Code and
> JetBrains distributions. This repository's own CI validates source changes but
> **does not publish releases**.

## Agent skills

Development conventions live in `agents/skills/`:

- [`jetbrains-toolwindow-icons`](agents/skills/jetbrains-toolwindow-icons/SKILL.md)
  — new UI tool-window icon spec (four-variant naming, sizes, palette colors,
  tinting mechanics).

---

<div align="center">

**Related**

[Main repository](https://github.com/AliceJump/ok-script-toolkit) · [Development guide](../DEVELOPMENT.md) · [Release process](../RELEASING.md)

</div>
