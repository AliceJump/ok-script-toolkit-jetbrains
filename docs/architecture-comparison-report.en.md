# VS Code Extension vs JetBrains Plugin: Feature Trade-offs and Implementation Strategy Differences Summary Report

[中文](architecture-comparison-report.md) | **English**

>
>
>
> ⚠️ **This document is a 2026-09-06 snapshot and some of its conclusions are overturned.**
> It was based on the `parity-review.md` revision that was later found badly stale, so it
> lists features that were not yet built at the time but **have since been implemented** as
> "❌ Missing". Re-verified against the code on 2026-09-21: rows **#5 / #6 / #7 / #8 / #10 are
> all implemented**, and #9 is a **carrier difference** (the sub-repo uses IntelliJ's native
> Keymap instead of a plugin-specific shortcut setting); sections 6.2 / 6.3 / 6.4 explain why
> things are missing that **now exist**, so they are **history only**. Each section is
> annotated in place. **The architecture and trade-off analysis (sections 1, 3, 4, 5) still
> holds.** The code and the tests are authoritative; `parity-review.md` is the current table.

Based on `parity-review.md` (2026-09-06) and source code analysis, this report summarizes the differences in architecture design, feature completeness, performance optimization, UI/UX experience, and development/maintenance costs between the main repository (VS Code extension) and sub-repository (JetBrains plugin), and proposes future improvement suggestions.

> **Line counts below were re-measured on 2026-09-21** (the original batch was from
> 2026-09-06; `TaskLauncherToolWindowFactory.kt` has since grown from 925 to **2044** lines).

---

## Architecture Design Philosophy Differences

### VS Code Extension: Modular, Fine-Grained Design

  **Independent Data Sources**: Each data source (Lang, Feature, Effect, Character, TemplateAsset) has an independent TypeScript class (`LangData`, `FeatureData`, `EffectData`, `CharacterData`, `TemplateAssetData`), each managing its own lifecycle, caching, and refresh.
  **Worker Thread Pool**: Image processing (PNG crop/thumbnails, saveToAssets packing) uses independent Worker thread pools (`pngCropWorker.ts`, `assetPackWorker.ts`), with zero main thread blocking.
  **Webview Isolation**: Each tool panel (template gallery, task launcher, character management, asset management, annotation editor) runs in an independent Webview, communicating with the host through message passing, with UI and logic completely separated.
  **Multi-Layer Caching**: Thumbnail file cache + in-memory LRU + Worker warm-up; task schema disk cache; language data in-memory snapshot.
  **File Watching**: Monitors data file changes in real-time via `createFileSystemWatcher`, with 300ms debounce before selectively refreshing the corresponding data source.

**Key Files**: `extension.ts` (442 lines), `pngCrop.ts` (1080 lines), `assetPack.ts` (206 lines), `assetPackWorker.ts` (315 lines)

### JetBrains Plugin: Centralized, Unified Snapshot Design

  **Unified Data Center**: `OkProjectDataService` is the sole project-level data service, containing a `Snapshot` data class that merges the three major data sources (Lang, Feature, Effect) into a single immutable snapshot, with all consumers reading from the same snapshot.
  **Concurrency Safety**: `@Volatile` + `ConcurrentHashMap` + `AtomicLong` ensure multi-thread safety, but there is no Worker thread pool — image processing executes in a `CompletableFuture` thread pool.
  **Platform Services**: Leverages IntelliJ Platform's `@Service(Service.Level.PROJECT)` annotation for automatic lifecycle management, without manual dispose.
  **Single-Thread Thumbnail Loading**: the sub-repo loads thumbnails sequentially in
  `TemplatesToolWindowFactory`. **The single thread is deliberate**: decode one source image,
  crop all of its templates, release it — only one decoded image is ever held (a 2560×1440 ARGB
  bitmap is ~15MB); a thread pool would hold N at once. **The real bottleneck was fixed on
  2026-09-21**: previously **each template decoded its own copy of the source image**, and
  ok-end-field is 276 templates over **16** images (**17:1**) — the same image decoded 17 times.
  Now grouped by source image (`core/TemplateThumbBatch.kt`, unit-tested), matching the parent's
  `warmCropCache`, **and backed by a persistent disk cache** (`core/TemplateThumbCache.kt`):
  key = **source content sha1 + bbox + target height**, stored under
  `<IDE system>/ok-script-toolkit/template-thumbs/<project hash>/` (same convention as
  `TempScreenshotStore`; **never written into the project directory**). On a hit the source image
  is not decoded at all — only a few-KB PNG is read. The content hash is memoized by
  (size, mtime) so it is not recomputed per render, and content (not path) is the key because
  "same path, replaced image, stale thumbnail" is a bug the parent already hit.

**Key Files**: `OkProjectDataService.kt` (479 lines), `TaskLauncherToolWindowFactory.kt` (2044 lines), `TemplatesToolWindowFactory.kt` (476 lines)

|Dimension | VS Code | JetBrains |
|------|---------|-----------|
|Data Architecture |Multiple independent Data classes + 300ms debounce |Single Snapshot + file modification timestamps |
|Image Processing |Worker thread pool (2-4 cores) |CompletableFuture thread pool |
|UI Isolation | Webview（HTML/CSS/JS） |Swing JPanel (native) |
|Lifecycle Management |Manual disposables |IntelliJ @Service automatic management |

---

## Feature Completeness Differences

Aligned Features (11 Items)

|Feature | VS Code | JetBrains |
|------|---------|-----------|
|hover/inlay / Language key completion/hover/inlay | ✅ | ✅ |
|hover / OCR match completion/hover | ✅ | ✅ |
|hover/inlay（Python + JSON）/ Effect ID completion/hover/inlay (Python + JSON) | ✅ | ✅ |
|Template gallery (responsive grid, search, insert/copy) | ✅ | ✅ |
|Task launch (AST task list, schema detection, pause/resume) | ✅ | ✅ |
|Parameter controls (bool/dropdown/multi-select/cascade/conditional sequence) | ✅ | ✅ |
|Character panel (read-only section) | ✅ | ✅ |
|Asset library basic operations (import, grid browsing) | ✅ | ✅ |
|Annotation basic operations (view, add/delete annotations) | ✅ | ✅ |
|6-language UI | ✅ | ✅ |
|Settings | ✅ | ✅ |

JetBrains Missing/Simplified Features (Ranked by Severity)

>
> ⚠️ **This table reflects 2026-09-06.** Re-verified against the code on 2026-09-21: all five
> rows previously marked "❌ Missing" **are implemented** (the status column is updated in
> place; the description column is kept as-is for comparison). For the real remaining gaps see
> the "⚠️ TODO" table in `parity-review.md` — what is left there is low-priority UI shape only.

| # |Feature |Severity |Description |
|---|------|--------|------|
| 1 |saveToAssets packing export |Delivered |bin-packing multi-page composition + COCO rewrite |
| 2 |Game window screenshot capture |Delivered |probe auto-detection + screenshot and registration |
| 3 |Character CRUD (add/edit/delete skills) |Delivered |~~enhanced-group editing pending~~ enhanced-group editing is implemented too |
| 4 |Character avatar |Delivered |Table avatar column |
| 5 |Annotation editor advanced interaction |**Implemented** |all present in `AnnotationDialog.kt`; only "auto-save on change" is a deliberate trade-off (OK/Cancel semantics) |
| 6 |sub_configs sub-configuration tree |**Implemented** |collapsible tree + conditional visibility (rules extracted to `SchemaTreeOverlap.kt`, unit-tested) |
| 7 |Debug overlay |**Implemented** |toolbox overlay toggle + runtime parameter injection |
| 8 |File watcher auto-refresh |**Implemented** |VFS `BulkFileListener` + 300ms debounce + broadcast, symmetric with the parent |
| 9 |Annotation shortcut configuration |**Carrier difference** |the sub-repo declares shortcuts in `plugin.xml` and users remap them in IntelliJ's native Keymap; the parent's setting exists because webviews handle keys themselves |
| 10 |Conditional visibility system |**Implemented** |collapsible groups absorb inline visibility rules |

---

## Performance Optimization Strategy Differences

### VS Code Extension

|Strategy |Implementation |Effect |
|------|------|------|
|Worker thread pool (thumbnails) |Pure JS PNG decode + crop + resize |Zero main thread blocking, hover response < 10ms |
|Worker thread pool (saveToAssets) |PNG decode + level-6 deflate + disk write |Multi-page parallel rendering, 4K images ≈ 33MB/worker |
|Multi-layer caching |File cache (disk) + in-memory LRU + Worker warm-up |Warm-up all thumbnails on first activation |
|Selective cache invalidation |Clear by source directory |Only invalidate thumbnails corresponding to changed files |
|300ms debounce | `DEBOUNCE_MS = 300` |Avoid frequent refreshes |

### JetBrains Plugin

|Strategy |Implementation |Effect |
|------|------|------|
|Snapshot immutable snapshot | `@Volatile` + `ConcurrentHashMap` |Lock-free read operations |
|File modification timestamps | `fileStamps` + `lastRefreshAttempt` |Avoid redundant scanning |
|Single-thread thumbnail loading | `Executors.newSingleThreadExecutor` |Sequential loading, no concurrency contention |
|30s TTL cache | `okTemplateCocoCache` / `okTemplateCocoMissCache` |Reduce redundant COCO parsing |
|400ms debounce save | `javax.swing.Timer(400)` |Avoid high-frequency file writes on EDT |

**Performance Comparison Conclusion**: The VS Code extension achieves true parallel processing through Worker thread pools, suitable for image-intensive operations; the JetBrains plugin relies on platform thread pools and immutable snapshots, which is sufficient for lightweight scenarios but has limited image processing capability.

---

## UI/UX Experience Differences

### Webview vs Swing

|Dimension | VS Code (Webview) | JetBrains (Swing) |
|------|-------------------|-------------------|
|Rendering Technology | HTML/CSS/JS | JPanel + Graphics2D |
|Customization Level |Completely free (Canvas/WebGL) |Limited to Swing components |
|Annotation Editor |HTML5 Canvas + full interaction |**Swing `Graphics2D` + full interaction** — ~~None (basic view only)~~ **outdated** |
|Task Configuration Panel |Structured JSON editor |Typed controls (`JCheckBox` / `JComboBox` / `JSpinner` / `ModifyListDialog`) plus a JSON editor for conditional sequences — ~~JTextArea + JSON validation border~~ **outdated** |
|Responsive Layout | CSS Grid/Flexbox |GridLayout + manual column calculation |
|Theme Adaptation |Follows VS Code theme |JBColor auto-adapts to Light/Dark |
|Native Integration Level |Low (sandbox environment) |High (platform Action/Notification) |

### Native Integration vs Flexible Customization

|Capability | VS Code | JetBrains |
|------|---------|-----------|
|Tool Window |WebviewView (sidebar) |ToolWindow (dockable) |
|Shortcuts | keybindings JSON | KeyboardShortcut XML |
|Command Palette | registerCommand | AnAction + group |
|Notification | InformationMessage | NotificationGroup |
|Context Menu |Editor context menu | EditorPopupMenu |
|Settings UI | configuration JSON | ProjectConfigurable |
|Debug overlay | overlay HTML |Missing |

---

## Development and Maintenance Cost Differences

### Code Volume Comparison

|Dimension | VS Code | JetBrains |
|------|---------|-----------|
|Host source (TS/Kt) |11,065 lines (30 files) |16,337 lines (55 files) |
|Webview UI code |5,877 lines (28 files, HTML/CSS/JS) |— (native Swing UI, no separate assets) |
|Python helper scripts |2,316 lines (7 files) |— (reuses the parent's `python/` bundle) |
|Test code |4,430 lines (18 files) |5,318 lines (34 files) |
|**Total** |**~23,700 lines** |**~21,700 lines** |

>
> ⚠️ **Conclusion reversed**: the 2026-09-06 revision recorded ~11,700 / ~6,176, implying the
> sub-repo was the lighter implementation. Today the totals are comparable and the sub-repo's
> **host source is larger** (16.3k vs 11.1k) — Swing has no HTML/CSS to lean on, so the UI is
> Kotlin too. The old "sub-repo is simpler" impression no longer holds.

### Maintainability Comparison

|Dimension | VS Code | JetBrains |
|------|---------|-----------|
|Module Coupling |Low (independent Data classes, Webview message passing) |Medium (Snapshot couples all data) |
|Type Safety |TypeScript strong typing |Kotlin strong typing + data class |
|Error Handling | try/catch + Promise rejection | try/catch + CompletableFuture |
|Internationalization | l10n directory + package.nls | ResourceBundle |
|Release Process | vsce package + Marketplace | Gradle buildPlugin + Marketplace |
|Version Sync | npm run version:sync | gradle.properties |

### Test Coverage Comparison

|Dimension | VS Code | JetBrains |
|------|---------|-----------|
|Unit test files |18 (13 Node scripts + 5 Python) | 34 / 34 |
|Test lines |4,430 lines |5,318 lines |
|Test entry point |`npm test` (10 suites chained) |`./gradlew test` (250 cases) |
|Integration tests |None |None |
|E2E tests |None |None |

**Conclusion**: ~~both sides have low test coverage~~ **outdated** — both now have systematic
suites: the parent's `npm test` chains 10 suites (bundle parity, the convention-file chain,
packaging leakage, the executor sandbox) and the sub-repo has 250 cases (pure-object units
plus source-scanning guards). The shared practice is "invariant + easy to break silently →
extract a pure object and unit-test it", with **destructive controls** (mutate the compiled
artifact and prove the assertion actually constrains something). What is still absent is
end-to-end testing (requires a real IDE and a real game) — neither side does it.

---

## Key Trade-off Decision Analysis

>
> ⚠️ **Sections 6.2 / 6.3 / 6.4 explain why things are missing that now exist** (re-verified
> 2026-09-21). They are kept as a **historical record of the trade-off reasoning** — the platform
> differences and complexity judgements still hold, only the conclusion ("so we skipped it")
> does not. **Do not read them as current status.** The same applies to 6.1 apart from
> "auto-save on change".

### Reasons for Annotation Editor Feature Gap

**VS Code version (complete)**: `annotationPanel.ts` (261 lines) + `media/annotationPanel/` (HTML/CSS/JS), implemented using HTML5 Canvas:
- undo/redo stack, copy/paste clipboard, 8-way resize handles
- Drag-move, mouse wheel zoom+pan, cross-image navigation (left/right arrows)
- Double-click value editing, auto-save on change
- Configurable shortcuts (`annotationKeybindings` setting)

**JetBrains version (basic only)**: Integrated in `TemplateAssetPanel`, using Swing JPanel:
- Only supports viewing annotations, adding/deleting annotations
- No undo/redo, no copy/paste, no resize handles
- No zoom-pan, no cross-image navigation

**Trade-off Reasons**:
1. **Development Cost**: Canvas annotation editor implementation complexity is high (~260 lines TS + ~1500 lines JS), Swing rewrite workload is enormous
2. **Platform Limitations**: IntelliJ has no built-in image annotation components, must be built from scratch
3. **Priority**: Annotation editor is a low-frequency operation, priority is lower than core language features and task launching

### Reasons for Missing Debug Overlay Feature

**VS Code version**: `overlayActive` state in `taskLauncher.ts` + Webview message passing + `run_executor.py` environment variable injection

**JetBrains version**: Completely missing

**Trade-off Reasons**:
1. **Different Runtime Injection Mechanisms**: VS Code passes via environment variable `OK_LANG_HINTS_INJECT`, while JetBrains' `TaskLauncherService.buildRunTaskCommand` does not implement this mechanism
2. **UI Level**: VS Code's overlay is a floating div in Webview, while Swing requires implementing JWindow/JDialog floating windows
3. **Limited Usage Scenarios**: Debug overlay is mainly for development-time real-time preview, not a core feature

### Reasons for Missing Conditional Visibility System

**VS Code version**: Task parameter panel supports the `condition` field, dynamically showing/hiding fields based on other parameter values

**JetBrains version**: No condition logic in `createFieldComponent`

**Trade-off Reasons**:
1. **Schema data already returned**: `TaskSchema.configGroups` is already passed, but the UI layer has not implemented condition listening
2. **Implementation Complexity**: Requires registering ChangeListener for each field, building dependency graphs, and updating visibility in real-time
3. **Current configGroups grouping already meets most needs**

### Reasons for Missing File Watching Mechanism

**VS Code version**: `createFileSystemWatcher` + `getAffectedSources` precise classification + 300ms debounce refresh

**JetBrains version**: Item 7 in `parity-review.md` explicitly records "no file watcher"

**Trade-off Reasons**:
1. **Platform Differences**: IntelliJ has `VirtualFileListener` and `BulkFileListener`, but they require manual registration and management
2. **Snapshot Model Limitations**: `OkProjectDataService`'s `refresh()` method is implemented, but lacks a trigger entry point
3. **User Habits**: JetBrains users are accustomed to manual refresh (Refresh button), real-time refresh may bring performance overhead

---

## Future Improvement Suggestions

### Features JetBrains Plugin Needs to Prioritize

>
> ⚠️ **All 7 rows in the original table are done** (re-verified 2026-09-21). Replaced with the
> **actual remaining** gaps, matching the "⚠️ TODO" table in `parity-review.md` — all low priority.

|Priority |Feature |Value |
|--------|------|------|
| P2 |Standalone annotation-editor command (the parent has `openAnnotationEditor`; the sub-repo can only reach it from the asset manager) |Low (one extra step) |
| P3 |Dual entry to the large gallery from the editor |Low |
| P3 |Task card-style UI |Low (carrier difference) |
| P3 |`lastPythonEditor` tracking |Low |
| — |Annotation editor "auto-save on change" |**Deliberate trade-off, not a defect**: the sub-repo uses OK/Cancel semantics |

### Feature Alignment Priority Between Both Sides

**All completed** (re-verified 2026-09-21):

|Phase |Item |Status |
|---|---|---|
|1 |saveToAssets export | ✅ |
|1 |Screenshot capture | ✅ |
|1 |Character CRUD + avatar + enhanced-group editing | ✅ |
|1 |Annotation editor advanced interaction | ✅ (except "auto-save on change", a deliberate trade-off)|
|1 |File watcher auto-refresh | ✅ `OkDataChangeService` |
|2 |sub_configs sub-configuration tree | ✅ |
|2 |Conditional visibility system | ✅ |
|2 |Debug overlay | ✅ |
|2 |Annotation shortcut configuration | ✅ (via IntelliJ's native Keymap — a carrier difference)|

**Phase 3 (Experience Optimization) — still open**:
2. Character panel status bar localization
3. Large gallery dual entry points
4. Task card-style UI

---

## Summary

The design differences between the VS Code extension and JetBrains plugin are essentially a reflection of **platform capability differences**:

  **VS Code**: The Web platform provides modern web technologies such as Canvas/WebGL/WebWorker, suitable for building rich interactive UIs and parallel computing, but limited by Webview sandbox and message passing overhead.
  **JetBrains**: IntelliJ Platform provides mature Swing components and platform services (VirtualFileListener, NotificationGroup, ToolWindow), suitable for building native integration experiences, but image processing and rich interactive UIs require more manual implementation.


Both sides are fully aligned on core language features (completion/hover/inlay).
~~The main gaps are concentrated in **image-intensive operations** (annotation editor, thumbnail
concurrency) and **dynamic UI interaction** (conditional visibility, debug overlay). The sub-repo
should prioritize **file watching** and **annotation editor advanced interaction**, which affect
daily workflows the most.~~
**— This conclusion is outdated (re-verified 2026-09-21)**: all four items named above
(annotation editor advanced interaction, file watching, conditional visibility, debug overlay)
**are implemented**, see §7.2. What remains is low-priority UI shape (large-gallery dual entry,
task card-style UI) and one **deliberate trade-off** (OK/Cancel instead of auto-save). The only
genuine open items are performance work such as **concurrent thumbnail loading**, and end-to-end
tests, which neither side has.
~~Both sides are fully aligned on core language features (completion/hover/inlay), with the main gaps concentrated in **image-intensive operations** (annotation editor, thumbnail concurrency) and **dynamic UI interactions** (conditional visibility, debug overlay). The JetBrains plugin's prioritized gap-filling should focus on **file watching** and **annotation editor advanced interaction**, as these two features have the greatest impact on daily workflows.~~
**Outdated — see the corrected paragraph above and §7.2.**

---

after re-verifying every row against the code** (line counts, code volume, test scale, the
"missing" list, the §6 historical note, §7 recommendations and the summary).*
Data sources: parity-review.md, source code analysis*
