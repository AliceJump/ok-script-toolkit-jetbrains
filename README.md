# ok-script Lang Hints for JetBrains

JetBrains Platform/PyCharm port of the VS Code extension in the repository root.

## Implemented features

- `self.lang.<module>.<key>` completion and quick documentation.
- OCR `match` completion/documentation from `ocr.po`.
- `fL` / `FeatureList` template completion and quick documentation.
- `EffectType.XXX` and JSON/Python effect-ID completion/documentation.
- Python inline hints for language keys, OCR patterns, and effect IDs.
- Searchable native template gallery with insert/copy/open-source actions.
- Project settings for paths, locale, aliases, and feature toggles.

## Build

Use the bundled Wrapper from this directory:

- Windows: `gradlew.bat buildPlugin`
- macOS/Linux: `./gradlew buildPlugin`

The plugin ZIP is written to `build/distributions/`.

The default build downloads the configured PyCharm SDK. To reuse a local installation,
pass `-PplatformLocalPath=/absolute/path/to/PyCharm` and run Gradle with JDK 21.

## Releases

The repository root project is the single release coordinator. The version in this
repository must match the parent `package.json`, and only a new `vX.Y.Z` tag pushed to
`AliceJump/ok-script-toolkit` publishes both the VS Code and JetBrains distributions.
This repository's own CI validates source changes but does not publish releases.
