package com.alicejump.okscripttoolkit.core

import java.nio.file.Files
import java.nio.file.Path

/**
 * Small rollback boundary for a synchronous multi-file resource save.
 *
 * Each target is captured before the first store is mutated. If a later store fails, callers can
 * restore every target to the exact pre-save text (or delete a file that did not exist before).
 */
class ResourceFileTransaction private constructor(
    private val snapshots: List<Snapshot>,
) {
    private data class Snapshot(val path: Path, val text: String?)

    fun rollback(): Boolean {
        var ok = true
        for (snapshot in snapshots.asReversed()) {
            val restored = try {
                if (snapshot.text == null) {
                    Files.deleteIfExists(snapshot.path)
                    true
                } else {
                    writeAnnotationText(snapshot.path, snapshot.text)
                }
            } catch (_: Exception) {
                false
            }
            if (restored) AnnotationDataChanges.notify(snapshot.path) else ok = false
        }
        return ok
    }

    companion object {
        fun capture(paths: Iterable<Path>): ResourceFileTransaction? {
            val snapshots = mutableListOf<Snapshot>()
            val seen = linkedSetOf<Path>()
            for (raw in paths) {
                val path = raw.toAbsolutePath().normalize()
                if (!seen.add(path)) continue
                val text = try {
                    if (Files.notExists(path)) null else Files.readString(path)
                } catch (_: Exception) {
                    return null
                }
                snapshots += Snapshot(path, text)
            }
            return ResourceFileTransaction(snapshots)
        }
    }
}
