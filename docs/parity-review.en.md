# Child and Main Repository Parity Record

[简体中文](parity-review.md) | [English](parity-review.en.md)

Verified on 2026-10-08. Main baseline `aea8b14`, child `main` `9eb4992`, both version `1.23.0`. This round extends [PR #28](https://github.com/AliceJump/ok-script-toolkit-jetbrains/pull/28) from `0e846f0` with parity fixes and main-host changes.

The full matrix, operation contracts, source entry points and verification status are maintained in [Feature Parity](https://github.com/AliceJump/ok-script-toolkit/blob/codex/complete-feature-parity/docs/feature-parity.en.md) on the companion main PR branch. They will be available on main after merge.

Current interaction changes ship through [JetBrains PR #30](https://github.com/AliceJump/ok-script-toolkit-jetbrains/pull/30) and [parent PR #37](https://github.com/AliceJump/ok-script-toolkit/pull/37), version `1.24.0`. Annotation management and previews follow VS Code. The PR #28 baselines and validation counts below remain historical evidence, not current PR review status.

## Differences Closed

- Runtime Template cards preserve actual images, bbox and aliases. Restore insertion, copying, source viewing, search and automatic refresh. Wide and side previews share all three modes.
- Unified management connects source viewing, Template swaps, deletion, temporary screenshot drops, search and external-change refresh. Narrow toolbars wrap without clipping actions; stale asynchronous callbacks cannot replace current models.
- Publication restores enum path/class settings, framework path fallback and reference checks. Configuration precedes writes; Position failure or rejected overwrite stops Template writes. Position path provenance and override reset match the main host.
- Image names reserve records in all three authoring files. Screenshot sending uses the actual project root. Deleting a source cleans Template / Rect / Point references and restores changed files on failure. Template read errors stop deletion before Rect / Point cleanup, avoiding unnecessary writes and notifications. Point cleanup preserves unrelated empty image registrations, fractional coordinates, original IDs and extension fields.
- Publication messages and preview titles use six external language resources, shortcuts match the main host, and unregistered old text windows/publishing paths are removed.
- Standalone CI pins main `aea8b14`, including current Position Schema. Shared Python and Schema agree with main artifacts.

Both hosts edit annotations in an editor tab and save authoring files after completed creation, editing, dragging, deletion, undo or redo. Saving does not publish resources. JetBrains exposes save errors and a retry action, preserving a plugin draft for recovery and external-change reconciliation when reopened. Business projects load outputs; the plugin does not create business migrations or application acknowledgement protocols.

## Verification and Delivery State

`gradlew test buildPlugin verifyPluginStructure verifyPluginConfiguration` passed: 487 tests, zero failures, errors or skips. Main full tests and VSIX packaging passed. Both artifacts contain 13 byte-identical Python scripts and Schema, complete language resources, and no Agent files, tests or developer documentation.

Changes are delivered through PRs and are not released before merge. Historical CI passed at `0e846f0`; old review coverage was `a9fd35f`, with a previous manual re-review rate-limited. That does not establish review coverage for new commits. The main PR gitlink pins this round's child commit. Merge child PR #30 first, then confirm or update the gitlink to a commit reachable from child `main` and verify main CI before merging the main PR.

No actual IDE, game screenshot or business-project runtime acceptance, or full Plugin Verifier API compatibility check was performed. Historical [Design Parity](design-parity.en.md) task lists are not current acceptance evidence.
