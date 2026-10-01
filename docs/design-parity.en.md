# JetBrains Design Parity: #9/#10 Swing Port and #7 Configuration Overrides

[简体中文](design-parity.md) | [English](design-parity.en.md)

> **Local verification, 2026-10-01:** this is a porting design and implementation reference, not a list of current missing features. Global configuration, account override editing, health indicators, and hover summaries are implemented. See the parent's [feature parity matrix](https://github.com/AliceJump/ok-script-toolkit/blob/main/docs/feature-parity.en.md) for current status. Code snippets illustrate rules and may not match current signatures verbatim.

> Related parent PRs: #8/#9 (run center, health bar, hover summaries), #10 (sidebar conventions and tokens), #7 (configuration overrides, phases 1–6). This specifies Kotlin/Swing equivalents for VS Code CSS tokens/webviews. The original audit was local `.workbuddy/design/jetbrains-parity-audit.md` (2026-09-25); `.workbuddy/` is untracked and local only, with conclusions incorporated here and in code.

## 0. Scope

| Parent capability | Current Kotlin status | Implementation |
|---|---|---|
| #7 `OK_TOOLKIT_GCONFIG` + `gparams` | **Implemented:** collection, persistence, startup injection, runtime pushes | `TaskLauncherService`, `GlobalSnapshotRules`, `TaskRunnerService`, `TaskLauncherToolWindowFactory` |
| #9 Health bar, run center, task hover | Health indicator, runtime overview, task summaries exist | `TaskLauncherToolWindowFactory`; layout follows current four-page UI |
| #9 Global configuration display | Configuration page edits global groups, not just metadata passthrough | Global forms/save flow in `TaskLauncherToolWindowFactory` |
| #10 Clickability categories and tokens | Shared theme and controls exist; actual visuals need IDE verification | `TaskLauncherTheme` |

Do not port 12 skins/3 layout switches from ok-ui-lab; Swing follows IDE themes.

## 1. Semantic Tokens → Swing Equivalents

Use `tasklauncher/TaskLauncherTheme.kt` as the single theme source; do not scatter hardcoded colors.

| VS Code token | Swing equivalent | Notes |
|---|---|---|
| `--ok` | `JBColor(0x369B47, 0x5FAD65)` | Existing COLOR_GOOD |
| `--run` | `JBColor(0x2E7DD1, 0x6CA6E8)` | Running; blue for connection/polling |
| `--warn` | `JBColor(0xB87700, 0xE8A33D)` | Existing COLOR_WARN |
| `--pause` | `JBColor(0x8A6D00, 0xC9A227)` | Pause, distinct yellow-brown from warning |
| `--err` | `JBColor(0xDB3B4B, 0xF26D6D)` | Existing COLOR_BAD |
| `--trigger` | `JBColor(0x7A5AF8, 0x9B8AFB)` | Existing COLOR_TRIGGER |
| `--text-primary` | `UIUtil.getLabelForeground()` | Always LAF, never hardcoded |
| `--text-muted` | `UIUtil.getLabelDisabledForeground()`, not `JBColor.foreground().darker()` | Secondary text |
| `--bg-control` | Platform LAF / default JButton | No custom button background |
| `--bg-row` | `UIUtil.getPanelBackground()`; hover uses a faded `UIUtil.getListSelectionBackground(false)` | Clickable rows |
| `--border` | `JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()`; fallback `BorderFactory.createLineBorder(JBColor.border())` | |
| `--radius-*` | `JBUI.CurrentTheme.Dialog.interiorWidth()` or fixed 8px chips | Preserve styleChip pattern |
| `--font-xs/sm/md/lg` | `JBFont.create(Label.font).deriveFont(...)`, smaller/standard | No px font sizes |

**Acceptance:** `grep -n "Color(0x" tasklauncher/*.kt` should find colors only in TaskLauncherTheme.kt.

## 2. Two Clickability Categories (#10)

| Category | Swing implementation | Uses |
|---|---|---|
| Actual buttons | `JButton`, platform LAF with surface/border | Toolbar, start/stop, logs, detail actions |
| Clickable rows | Borderless `JPanel` with light panel background and row hover | Run-center task rows, configuration summaries |

Like VS Code, **hover must not reveal clickability for the first time**. Rows have a light default surface; buttons and row headings must not share identical surfaces/borders.

## 3. Porting #9 Concepts

### 3.1 Health Bar (rc-health → statusBar)

`ExecutorState` provides status/paused/current/currentIsTrigger/onetimeQueue/controlError.

```text
[● health indicator] [status text] [current task chip, if any] …… [progress] [logs]
```

- 8px indicator: `--run` for unpaused connecting/running, `--pause` when paused, `--err` for controlError, `--ok` for idle with normal finishMessage, `--text-muted` for idle without a message.
- Status text follows existing syncRunnerState/statusText assembly.
- Current-task chip reuses styleChip, with different trigger/one-time border colors.

### 3.2 Run Center (rc-queue → Idle Details)

When no task is selected, replace placeholder detail text with a read-only run center:

- Current task: key and trigger/one-time kind, otherwise idle.
- Queue: onetimeQueue rows styled as clickable rows; clicking selects the corresponding task.
- Trigger polling: enabledTriggers and checked states.
- Small heading/health indicator share statusBar data/colors.

Original implementation proposal: `RunCenterPanel` (nested or separate), replacing `showDetailPlaceholder()` with `showRunCenter()`.

### 3.3 Task Hover Summary

- After 800ms hover on trigger/one-time rows, show `JBPopup`: display name, kind, changed-key count, schema errors.
- **Read-only popup, no action buttons** (user requirement). Use default translucent `JBPopup` with `JBUI.insets` and rounded corners; Swing lacks backdrop-filter, so do not imitate blur.
- Suppress for 1.2s after Tab/row changes, matching the parent.

### 3.4 Global Configuration Hover (gpop)

The original run-center design lists `globalConfigGroups` (name/displayName/source), with hover field summaries (truncated displayKey/value), styled like §3.3.

## 4. Kotlin Configuration Override Implementation

### 4.1 Model (TaskLauncherService.kt)

```kotlin
data class GlobalConfigGroup(
    val name: String,
    val displayName: String? = null,
    val description: String? = null,
    val fields: List<TaskParamField> = emptyList(),   // Reuse task field model
    val source: String? = null,                        // framework | project_store
)
// Added to SchemaProbeResult:
val globalConfigGroups: List<GlobalConfigGroup> = emptyList(),
```

- Add `parseGlobalConfigGroups(node)` beside `parseSchemas`; connect both probe and cached-schema reads.
- Cache writes need no separate change: whole-data-class serialization includes it.

### 4.2 Persistence (tasks.json ProjectConfig)

```kotlin
data class ProjectConfig(
    val tasks: Map<String, TaskConfig> = emptyMap(),
    val enabledTriggers: List<String> = emptyList(),
    val globalConfigs: Map<String, Map<String, Any?>> = emptyMap(),  // {group: {key: value}}
)
```

- `parseTaskConfigStore` reads `globalConfigs`.
- `TaskConfigMerge.withGlobalConfigs(store, root, snapshots)` follows withTask/withEnabledTriggers: **only replace globalConfigs**, preserving tasks/enabledTriggers.
- `TaskLauncherService.saveGlobalConfigs(snapshots)` locks the complete sequence with storeLock and updates caches.

### 4.3 Materialization (`GlobalSnapshotRules.kt`, Pure/Testable)

Match consolePanel.ts materializeTaskSnapshot/materializeGlobalSnapshot semantics:

```kotlin
object GlobalSnapshotRules {
    /** Materialize fields; return (snapshot, added-key count).
     *  Empty existing snapshot: inherit f.value.
     *  Reprobe: retain existing/orphan keys; new keys use f.default ?: f.value. */
    fun materialize(existing: Map<String, Any?>, fields: List<TaskParamField>): Pair<Map<String, Any?>, Int>

    /** Reset all fields to f.default ?: f.value, regardless of existing. */
    fun resetToDefaults(existing: Map<String, Any?>, fields: List<TaskParamField>): Map<String, Any?>
}
```

### 4.4 Startup Injection (ensureExecutor)

```kotlin
val gconfig = taskService.loadTaskConfigs().projects[...]?.globalConfigs.orEmpty()
if (gconfig.isNotEmpty()) {
    env["OK_TOOLKIT_GCONFIG"] = objectMapper.writeValueAsString(gconfig)
}
```

### 4.5 Runtime Pushes (TaskRunnerService)

```kotlin
/** Push global snapshots immediately, matching VS Code gparams. */
fun pushGlobalParams(json: String): Boolean = sendCommand("gparams $json")
```

After global configuration save/flush, push with 400ms debounce only when `isRunning()`.

### 4.6 Current UI

- The configuration page edits and saves global groups using shared task parameter controls. Startup injection and runtime `gparams` are connected.
- `AccountEditorDialog` edits account overrides through shared Python account interfaces; no business configuration migration is added.
- Earlier run-center/hover design intent remains recorded here; current page structure and actions follow code and the feature parity matrix.

## 5. Tests

- `GlobalSnapshotRulesTest`: first value inheritance, reprobe defaults for new keys, orphan retention, reset to defaults.
- `TaskConfigMergeTest`: global changes preserve tasks/triggers; create missing roots.
- Pure objects require no IDE platform; existing test source set supports this.
- Six-language bundle key parity through `BundleParityTest`: health tooltips, run-center headings/empty states, configuration titles, push-failure logs.

## 6. Explicit Exclusions

- 12 skins/3 layouts remain ok-ui-lab-specific.
- Do not list initially postponed global forms or multi-account overrides as missing: both now have editors.
- Do not introduce business account lifecycle, historical parameter transfers, or migration recovery workflows.
