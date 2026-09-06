# ok-script Toolkit for JetBrains

JetBrains Platform/PyCharm port of the VS Code extension in the repository root.

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
- Character manager tool window with diagnostics and double-click
  jump-to-source for issues and effects.
- Task launcher tool window: schema-driven parameter forms (grouped by
  `configGroups`), per-task overrides with auto-save, run/stop/pause/resume,
  timeout, and a live output console.
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
