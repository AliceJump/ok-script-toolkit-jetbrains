package com.alicejump.okscripttoolkit.core

internal enum class AnnotationMergeMode { TEMPLATE, RECT, POINT }

internal data class MergeShape(
    val id: Int,
    val name: String,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
)

internal enum class AnnotationConflictKind { MODIFY_MODIFY, DELETE_MODIFY, ADD_ADD }

internal data class AnnotationConflict(
    val key: String,
    val kind: AnnotationConflictKind,
    val fields: Set<String>,
    val base: MergeShape? = null,
    val local: MergeShape? = null,
    val external: MergeShape? = null,
)

internal data class AnnotationMergeResult(
    val merged: List<MergeShape>,
    val conflicts: List<AnnotationConflict>,
)

private val MERGE_FIELDS = listOf("name", "x", "y", "w", "h")

private fun MergeShape.value(field: String): Any = when (field) {
    "name" -> name
    "x" -> x
    "y" -> y
    "w" -> w
    "h" -> h
    else -> error("unknown merge field: $field")
}

private fun MergeShape.withValue(field: String, value: Any): MergeShape = when (field) {
    "name" -> copy(name = value as String)
    "x" -> copy(x = value as Int)
    "y" -> copy(y = value as Int)
    "w" -> copy(w = value as Int)
    "h" -> copy(h = value as Int)
    else -> error("unknown merge field: $field")
}

private fun sameContent(a: MergeShape?, b: MergeShape?): Boolean {
    if (a == null || b == null) return a == b
    return MERGE_FIELDS.all { a.value(it) == b.value(it) }
}

private fun geometryEqual(a: MergeShape, b: MergeShape): Boolean =
    a.x == b.x && a.y == b.y && a.w == b.w && a.h == b.h

private fun findMatch(
    base: MergeShape,
    candidates: List<MergeShape>,
    used: Set<Int>,
    mode: AnnotationMergeMode,
): Int {
    fun first(predicate: (MergeShape) -> Boolean): Int =
        candidates.indices.firstOrNull { it !in used && predicate(candidates[it]) } ?: -1

    if (mode == AnnotationMergeMode.TEMPLATE) {
        first { it.id == base.id }.takeIf { it >= 0 }?.let { return it }
    }
    first { it.name.trim() == base.name.trim() }.takeIf { it >= 0 }?.let { return it }
    if (mode == AnnotationMergeMode.TEMPLATE) {
        first { geometryEqual(it, base) }.takeIf { it >= 0 }?.let { return it }
    }
    return -1
}

private data class ExistingMerge(val shape: MergeShape? = null, val conflict: AnnotationConflict? = null)

private fun mergeExisting(
    key: String,
    base: MergeShape,
    local: MergeShape?,
    external: MergeShape?,
): ExistingMerge {
    if (local == null && external == null) return ExistingMerge()
    if (local == null) {
        if (sameContent(base, external)) return ExistingMerge()
        return ExistingMerge(conflict = AnnotationConflict(
            key, AnnotationConflictKind.DELETE_MODIFY,
            MERGE_FIELDS.filterTo(linkedSetOf()) { external != null && base.value(it) != external.value(it) },
            base = base, external = external,
        ))
    }
    if (external == null) {
        if (sameContent(base, local)) return ExistingMerge()
        return ExistingMerge(shape = local, conflict = AnnotationConflict(
            key, AnnotationConflictKind.DELETE_MODIFY,
            MERGE_FIELDS.filterTo(linkedSetOf()) { base.value(it) != local.value(it) },
            base = base, local = local,
        ))
    }
    if (sameContent(local, external)) return ExistingMerge(local)
    if (sameContent(base, local)) return ExistingMerge(external)
    if (sameContent(base, external)) return ExistingMerge(local)

    var merged: MergeShape = local
    val conflicts = linkedSetOf<String>()
    for (field in MERGE_FIELDS) {
        val b = base.value(field)
        val l = local.value(field)
        val e = external.value(field)
        when {
            l == e -> Unit
            l == b -> merged = merged.withValue(field, e)
            e == b -> Unit
            else -> conflicts += field
        }
    }
    return if (conflicts.isEmpty()) ExistingMerge(merged) else ExistingMerge(
        merged,
        AnnotationConflict(key, AnnotationConflictKind.MODIFY_MODIFY, conflicts, base, local, external),
    )
}

private fun findAddedMatch(
    item: MergeShape,
    candidates: List<MergeShape>,
    used: Set<Int>,
    mode: AnnotationMergeMode,
): Int {
    candidates.indices.firstOrNull { it !in used && candidates[it].name.trim() == item.name.trim() }?.let { return it }
    if (mode == AnnotationMergeMode.TEMPLATE) {
        candidates.indices.firstOrNull {
            it !in used && (candidates[it].id == item.id || geometryEqual(candidates[it], item))
        }?.let { return it }
    }
    return -1
}

/** Three-way merge for one image and one resource type. Conflicting fields keep the local value in [merged]. */
internal fun mergeAnnotations(
    mode: AnnotationMergeMode,
    base: List<MergeShape>,
    local: List<MergeShape>,
    external: List<MergeShape>,
): AnnotationMergeResult {
    val merged = mutableListOf<MergeShape>()
    val conflicts = mutableListOf<AnnotationConflict>()
    val usedLocal = mutableSetOf<Int>()
    val usedExternal = mutableSetOf<Int>()

    for (baseItem in base) {
        val li = findMatch(baseItem, local, usedLocal, mode)
        val ei = findMatch(baseItem, external, usedExternal, mode)
        if (li >= 0) usedLocal += li
        if (ei >= 0) usedExternal += ei
        val result = mergeExisting(
            if (mode == AnnotationMergeMode.TEMPLATE) "template:${baseItem.id}:${baseItem.name}" else "${mode.name.lowercase()}:${baseItem.name.trim()}",
            baseItem,
            local.getOrNull(li),
            external.getOrNull(ei),
        )
        result.shape?.let(merged::add)
        result.conflict?.let(conflicts::add)
    }

    for (li in local.indices) {
        if (li in usedLocal) continue
        val localItem = local[li]
        val ei = findAddedMatch(localItem, external, usedExternal, mode)
        if (ei < 0) {
            merged += localItem
            continue
        }
        usedExternal += ei
        val externalItem = external[ei]
        if (sameContent(localItem, externalItem)) {
            merged += localItem
        } else {
            merged += localItem
            conflicts += AnnotationConflict(
                if (mode == AnnotationMergeMode.TEMPLATE) "template:new:${localItem.name}" else "${mode.name.lowercase()}:${localItem.name.trim()}",
                AnnotationConflictKind.ADD_ADD,
                MERGE_FIELDS.filterTo(linkedSetOf()) { localItem.value(it) != externalItem.value(it) },
                local = localItem,
                external = externalItem,
            )
        }
    }

    external.indices.filter { it !in usedExternal }.forEach { merged += external[it] }
    return AnnotationMergeResult(merged, conflicts)
}
