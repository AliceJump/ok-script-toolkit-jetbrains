package com.alicejump.okscripttoolkit.core

/**
 * Pure state for one open image/resource-kind editor session.
 *
 * [local] is always the user's own edit branch. When a conflict is pending the UI should render
 * [PendingAnnotationMerge.result].merged, but keep editing frozen until the conflict is resolved.
 * This prevents already auto-merged external fields from being mistaken for new local edits if
 * the authoring file changes again before the user finishes resolving the conflict.
 */
internal data class AnnotationSessionSyncState(
    val base: List<MergeShape>,
    val local: List<MergeShape>,
    val revision: String?,
    val dirty: Boolean,
    val pending: PendingAnnotationMerge? = null,
) {
    val displayShapes: List<MergeShape> get() = pending?.result?.merged ?: local
    val hasConflicts: Boolean get() = pending?.result?.conflicts?.isNotEmpty() == true
}

internal data class PendingAnnotationMerge(
    val externalBase: List<MergeShape>,
    val externalRevision: String?,
    val result: AnnotationMergeResult,
)

/** Keep editor-only ids monotonic across deletes, merges and undo/redo snapshots. */
internal fun nextAnnotationId(
    previousNextId: Int,
    shapes: List<MergeShape>,
    base: List<MergeShape>,
): Int {
    val maxKnownId = (shapes.asSequence() + base.asSequence()).maxOfOrNull { it.id } ?: 0
    return maxOf(previousNextId, maxKnownId + 1)
}

/**
 * Reconcile a valid external snapshot with an open session.
 *
 * - clean sessions follow disk immediately;
 * - dirty sessions three-way merge against their last accepted base;
 * - conflict sessions keep the original local branch and are recomputed against the newest disk
 *   revision, so repeated external writes never turn old external changes into fake local edits.
 */
internal fun reconcileAnnotationSession(
    mode: AnnotationMergeMode,
    state: AnnotationSessionSyncState,
    external: List<MergeShape>,
    externalRevision: String?,
): AnnotationSessionSyncState {
    if (externalRevision == state.revision && state.pending == null) return state

    if (!state.dirty) {
        return AnnotationSessionSyncState(
            base = external,
            local = external,
            revision = externalRevision,
            dirty = false,
        )
    }

    val localBranch = state.local
    val result = mergeAnnotations(mode, state.base, localBranch, external)
    if (result.conflicts.isEmpty()) {
        return AnnotationSessionSyncState(
            base = external,
            local = result.merged,
            revision = externalRevision,
            dirty = true,
        )
    }
    return state.copy(
        pending = PendingAnnotationMerge(
            externalBase = external,
            externalRevision = externalRevision,
            result = result,
        ),
    )
}

/**
 * Resolve the currently pending conflict set. A stale expected revision is rejected so callers can
 * reread disk and call [reconcileAnnotationSession] again instead of applying choices to old data.
 */
internal fun resolveAnnotationSessionConflicts(
    mode: AnnotationMergeMode,
    state: AnnotationSessionSyncState,
    choices: Map<String, AnnotationConflictChoice>,
    currentExternalRevision: String?,
): AnnotationSessionSyncState? {
    val pending = state.pending ?: return state
    if (currentExternalRevision != pending.externalRevision) return null
    val resolved = resolveAnnotationConflicts(mode, pending.result, choices) ?: return null
    return AnnotationSessionSyncState(
        base = pending.externalBase,
        local = resolved,
        revision = pending.externalRevision,
        dirty = true,
    )
}
