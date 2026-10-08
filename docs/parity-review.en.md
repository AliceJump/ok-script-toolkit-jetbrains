# Child and Main Repository Parity Record

[简体中文](parity-review.md) | [English](parity-review.en.md)

Verified on 2026-10-08. Main baseline `aea8b14`, child `main` `9eb4992`, both version `1.23.0`. This round extends [PR #28](https://github.com/AliceJump/ok-script-toolkit-jetbrains/pull/28) from `0e846f0` with parity fixes and main-host changes.

The full matrix, operation contracts, source entry points and verification status are maintained in [Feature Parity](https://github.com/AliceJump/ok-script-toolkit/blob/codex/complete-feature-parity/docs/feature-parity.en.md) on the companion main PR branch. They will be available on main after merge.

## Differences Closed

- Runtime Template cards preserve actual images, bbox and aliases. Restore insertion, copying, source viewing, search and automatic refresh. Wide and side previews share all three modes.
- Unified management connects source viewing, Template swaps, deletion, temporary screenshot drops, search and external-change refresh. Narrow toolbars wrap without clipping actions; stale asynchronous callbacks cannot replace current models.
- Publication restores enum path/class settings, framework path fallback and reference checks. Configuration precedes writes; Position failure or rejected overwrite stops Template writes. Position path provenance and override reset match the main host.
- Image names reserve records in all three authoring files. Screenshot sending uses the actual project root. Deleting a source cleans Template / Rect / Point references and restores changed files on failure. Template read errors stop deletion before Rect / Point cleanup, avoiding unnecessary writes and notifications.
- Publication messages and preview titles use six external language resources, shortcuts match the main host, and unregistered old text windows/publishing paths are removed.
- Standalone CI pins main `aea8b14`, including current Position Schema. Shared Python and Schema agree with main artifacts.

VS Code saves annotation edits immediately; the child dialog writes on Save and discards current edits on Cancel. Native layouts may differ, while sources, expressions, write scope and publication semantics agree. Business projects load outputs; the plugin does not create business migrations or application acknowledgement protocols.

## Verification and Delivery State

`gradlew test buildPlugin verifyPluginStructure verifyPluginConfiguration` passed: 485 tests, zero failures, errors or skips. Main full tests and VSIX packaging passed. Both artifacts contain 13 byte-identical Python scripts and Schema, complete language resources, and no Agent files, tests or developer documentation.

Changes are delivered through PRs and are not released before merge. Historical CI passed at `0e846f0`; old review coverage was `a9fd35f`, with a previous manual re-review rate-limited. That does not establish review coverage for new commits. The main PR gitlink pins this round's child commit. Merge child PR #28 first, then confirm or update the gitlink to a commit reachable from child `main` and verify main CI before merging the main PR.

No actual IDE, game screenshot or business-project runtime acceptance, or full Plugin Verifier API compatibility check was performed. Historical [Design Parity](design-parity.en.md) task lists are not current acceptance evidence.
