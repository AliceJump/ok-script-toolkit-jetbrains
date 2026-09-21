# 子仓库与主仓库功能差异审查（2026-09-21 更新） / Sub-repo vs Main-repo Feature Parity Review (Updated 2026-09-21)

<div align="center">

[![简体中文](https://img.shields.io/badge/Language-%E7%AE%80%E4%BD%93%E4%B8%AD%E6%96%87-6E7681?style=for-the-badge)](parity-review.md) [![English](https://img.shields.io/badge/Language-English%20%E2%9C%93-2EA043?style=for-the-badge)](parity-review.en.md)

</div>

对照基准：主仓库 VSCode 扩展 v1.8.0、子仓库 JetBrains 插件 v1.8.0。
下次审查请覆盖本表并更新状态。

Baseline: main repo VSCode extension v1.8.0, sub-repo JetBrains plugin v1.8.0.
Next review should override this table and update the status.

> The previous revision (2026-09-07, baseline v1.4.0) was badly stale: it listed the
> advanced annotation-editor interactions as TODO, but most were implemented afterwards
> without a doc update. This revision re-verified every row against the code.
>
> **2026-09-20 follow-up**: even this document's per-row conclusions drift. The same pass
> found the "6-language UI fully aligned" claim false (4 bundles were each missing 13 keys —
> see the ⚠️ note below). Now fixed, with a build-time check. **Treat this table as
> unverified; the code and the tests are authoritative.**
>
> **2026-09-21 follow-up**: the project-convention-file section had drifted again — it listed
> only 4 wired fields while all of them are now wired (including the new `config.py` fact
> layer). That section is rewritten here. Baseline bumped v1.7.1 → v1.8.0.

---

## Summary

All 10 feature domains (editor language features, template gallery, template assets, task launcher,
parameter controls, character manager, toolbox, annotation editor, temp screenshots, screenshot capture)
**have a counterpart implementation in the sub-repo — nothing is wholly missing.**

Remaining gaps are two kinds: **one annotation-editor save-semantics difference**, and
**four low-priority UI-shape differences** (carrier differences, not missing functionality — see below).

## ✅ Aligned

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
- **Project convention file `ok-script-toolkit.json`**: lives in the **debugged project's** root and is
  shared by both ends (**read-only** — the plugin never writes it). Precedence is
  **my settings > project convention file > facts declared in the project's `config.py` > built-in default**.
  **Every field is wired up** (finished 2026-09-21): `labelEnum.path` / `labelEnum.name` /
  `labelEnum.aliases` / `templates.directory` / `templates.cocoAnnotations` / `i18n.enabled` /
  `i18n.langDirectory` / `i18n.poDirectory` / `i18n.poDomains` / `characters.projectPath` /
  `characters.masterFile` / `characters.skillsDirectory` / `characters.localeFile` /
  `characters.avatarTemplateRegex` / `effects.file`.
  - The **`config.py` fact layer** is used by exactly one field so far, `templates.cocoAnnotations`:
    it is the path of the **runtime template library** the ok framework loads
    (`ok/__init__.py` reads `template_matching.coco_feature_json`). Chain:
    `templates.cocoAnnotations` → `config.py` → probe `assets/coco_annotations.json` then
    `ok_tasks/assets/coco_annotations.json` (the pre-convention-file behaviour).
    All 6 ok-family projects surveyed declare it, and one of them uses `coco_detection.json` —
    without this chain the plugin found **no candidate at all there, so the library was empty**.
  - ⚠️ The asset panel's own `<templates dir>/coco_annotations.json` is a **different file**;
    its path comes from `templates.directory` and is unaffected by `templates.cocoAnnotations`.
  - **`labelEnum.path` / `labelEnum.name` have a personal-preference layer** (settings
    `labelEnumPath` / `labelEnumName`). These two are more dangerous than the others: they decide
    **where the file is written and what the class is called**, and project code imports the class
    by name (`from src.data.feature_list import FeatureList`) — a wrong override is a project-wide
    `ImportError`. So both ends check for a class-name change **before overwriting an existing file**
    (`core/LabelEnumGuard.kt` ↔ `src/labelEnumGuard.ts`): silent when the file is new or the name is
    unchanged, but when the name *would* change they scan the project for files importing the old
    name and report how many would break. **Only the class name is checked, not the path** — moving
    the file leaves the old one in place, so imports of the old module keep working.
  - ⚠️ The "Change path…" dialog writes into the setting (stored project-relative), so
    "remembered after the first time" behaves the same on both ends; leaving it empty **drops the
    override and falls back to the project convention** (same rule as an empty `labelEnum.aliases`).
    VS Code used to keep this layer in `globalState` (global, invisible in the UI, leaking across
    projects) — that is gone.
  - ⚠️ **Pick the normalization by field type**: relative paths go through `normalizeRelPath`;
    absolute paths (`characters.projectPath`) and regexes (`characters.avatarTemplateRegex`)
    must **not** be normalized — the former loses its leading slash, the latter turns `\d` into `/d`.
    Both failures are silent: they just stop matching.
  Both ends have a **source-tracing panel** (parent command `showConventionSources`; sub-repo Tools menu
  `ShowConventionSources`) that lists which layer each effective value comes from and offers a Revert
  button on rows you have overridden. The two implementations are symmetric — the pure objects
  (`projectConfigPure.ts` / `core/ProjectConvention.kt`) both produce `{ value, layer }`, and the
  **layer comes from the chain itself rather than being recomputed in the UI** (a recomputation would
  drift from the effective value, silently). The panel's "what the project file says" is likewise
  produced by **re-running the same chain** with the personal value blanked out, so the displayed
  and effective values share one normalization.
  ⚠️ Settings on both ends carry **non-empty defaults**, so before wiring a new setting you must first
  obtain a "did the user actually change this" signal (parent `inspect()`, sub-repo `overriddenKeys`),
  otherwise the project declaration is **permanently shadowed, silently**.
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
  - ⚠️ 2026-09-20 reviewed and fixed: **the reader turned an explicit JSON `null` into the literal
    string `"null"`**. Jackson's `get()` returns a `NullNode` (not Kotlin `null`) when a field's value
    is `null`, and `NullNode.asText()` returns the **literal `"null"`** — so the common idiom
    `node.get(k)?.asText() ?: fallback` neither triggers the fallback nor avoids the bogus value.
    Observed on the skill `element`: the parent's `sanitizeSkill` writes
    `optionalString(...) || null`, so an empty element really is `"element": null`, and the sub-repo
    displayed it as the element name "null". The parent's `stringValue` (`characterData.ts:577`)
    falls back to the character element. Fixed by adding `JsonNodeExt.textOrNull()` / `textOr()` and
    applying it to every string read in the character/skill/effect-ref/enhancement paths
    (covered by `JsonNodeExtTest` + `CharacterDataServiceTest`).
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

## ⚠️ TODO

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

## Differences (carrier, not missing functionality)

- The sub-repo is **pure Kotlin + Swing**, no JCEF/webview; the parent uses webview HTML.
- Annotation editor: parent is a standalone panel (`src/annotationPanel.ts`); sub-repo is
  `AnnotationDialog`, opened only from the template asset manager.
- Character manager: parent is a webview; sub-repo is a fileType `okcharacters` editor tab.
- Asset packing: parent uses a worker pool with hand-written PNG/JPEG/BMP codecs; sub-repo renders
  on the same thread with AWT + `ImageIO` (no parallelism, only an `onProgress` callback);
  packing algorithm and naming match.

## Storage path comparison

| Item | VSCode (parent) | JetBrains (sub-repo) |
|---|---|---|
| Config sandbox | `<workspace>/.vscode/ok-script-toolkit` | `<project>/.idea/ok-script-toolkit` |
| Task config / enabled set | `.vscode/ok-script-toolkit-tasks.json` | `.idea/ok-script-toolkit-tasks.json` |
| Schema cache | `.vscode/ok-script-toolkit-schema.json` | `.idea/ok-script-toolkit-schema.json` |
| Toolbox state | workspace `.vscode` | `.idea/ok-script-toolkit-toolbox.json` |
| Temp screenshots | extension `globalStorage` (workspace-hash isolated) | `<IDE system>/ok-script-toolkit/temp-screenshots/<project hash>` |
