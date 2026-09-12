# ok-script Toolkit for JetBrains

JetBrains Platform/PyCharm port of the VS Code extension in the repository root.

[![JetBrains Plugin](https://img.shields.io/badge/JetBrains%20Marketplace-OK%20Script%20Toolkit-000000?logo=jetbrains&logoColor=white)](https://plugins.jetbrains.com/plugin/34091-ok-script-toolkit)

## Implemented features

- `self.lang.<module>.<key>` completion and quick documentation (full
  per-locale table on hover, inline value hints with tooltips).
- OCR `match` completion/documentation from `ocr.po`, plus inline
  translation hints after the call.
- `fL` / `FeatureList` template completion with pinned priority and quick
  documentation with cropped previews.
- `EffectType.XXX` and JSON/Python effect-ID completion (grouped by
  category)/documentation/inline description hints.
- Template gallery tool window with bbox-cropped thumbnails, insert/copy,
  and red-box annotated source image preview.
- Template asset manager tool window (import/delete images with COCO sync).
- Temp shots tool window: a scratch area for at most 10 recent screenshots.
  Paste a clipboard image, capture the game window, or drop/paste image files.
  The stage shows the selected shot or cycles all of them every 0.1s; box-select
  copies normalized `x,y,tox,toy` coordinates to the clipboard, and thumbnails
  can be dragged onto the asset manager (or sent from the context menu) to import
  into `ok_templates`. The coordinate box stays on the canvas afterwards with 8
  resize handles and can be dragged around — each create or adjustment re-copies
  the coordinates; it is never written to COCO, and clicking outside the box
  (or leaving the mode) clears it.
- The annotation editor gained a coords mode (`C`) that box-selects and copies
  normalized `x,y,tox,toy` without creating an annotation box; the same
  adjustable-box behaviour applies.
- Character manager opened as a full editor tab (not a tool window) so it gets
  the same room as the VS Code `ViewColumn.One` webview. It is carried by a
  non-physical `CharacterManagerFile` plus a `FileEditorProvider` with
  `FileEditorPolicy.HIDE_DEFAULT_EDITOR`. Four tabs — characters / effects /
  locales / issues — with star, element, profession, skill-type,
  has-enhancements and has-issues filters, an effect usage filter
  (used / unused / undefined), skill and enhancement CRUD, effect and category
  insertion into `src/data/effects.py`, and double-click jump-to-source for
  issues and effects.
- Task launcher tool window: schema-driven parameter forms (grouped by
  `configGroups`), per-task overrides with auto-save, run/stop/pause/resume,
  timeout, and a live output console.
- Toolbox in the task launcher (also the entry point for the character
  manager — the toolbar has a characters button next to the task controls):
  connect/disconnect the game window
  (`connect_game.py`, auto-starts the game and reuses the connection for every
  task via `configs/devices.json`) and a debug-overlay toggle that is adopted
  by launched tasks, toggled live on running tasks, and backed by a persistent
  overlay host (`overlay_host.py`) for Alt+Right-click coordinate picking.
- Project settings for paths, locale, aliases, and feature toggles.
- UI localized in en, zh_CN, zh_TW, ja, ko, es.

## Agent skills

Development conventions live in `agents/skills/`:

- `agents/skills/jetbrains-toolwindow-icons/SKILL.md` — New UI tool-window
  icon spec (four-variant naming, sizes, palette colors, tinting mechanics).

## Build

Use the bundled Wrapper from this directory:

- Windows: `gradlew.bat buildPlugin`
- macOS/Linux: `./gradlew buildPlugin`

The plugin ZIP is written to `build/distributions/`.

The build targets the JDK 21 toolchain (matching CI and the IntelliJ 2025.1 runtime).
If no matching JDK is installed locally it is provisioned automatically via the
foojay resolver. To reuse a local PyCharm installation instead of downloading the
SDK, pass `-PplatformLocalPath=/absolute/path/to/PyCharm` (must contain
`product-info.json`).

## Releases

The repository root project is the single release coordinator. The version in this
repository must match the parent `package.json`, and only a new `vX.Y.Z` tag pushed to
`AliceJump/ok-script-toolkit` publishes both the VS Code and JetBrains distributions.
This repository's own CI validates source changes but does not publish releases.
