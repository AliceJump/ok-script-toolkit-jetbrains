package com.alicejump.okscripttoolkit.core

import com.intellij.openapi.diagnostic.Logger
import java.awt.image.BufferedImage
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.imageio.ImageIO

/** Content-addressed annotated previews under the project's thumbnail cache. */
internal class AnnotatedImageCache(private val directory: Path) {
    companion object {
        private val LOG = Logger.getInstance(AnnotatedImageCache::class.java)
        private const val MAX_FILES = 1000

        /** Hashing the key also keeps untrusted template names and path separators out of file names. */
        fun fileNameOf(contentHash: String, bbox: IntArray): String {
            val input = "v3|$contentHash|${bbox.joinToString(",")}".toByteArray(Charsets.UTF_8)
            val digest = MessageDigest.getInstance("SHA-1").digest(input)
                .joinToString("") { "%02x".format(it) }
                .take(16)
            return "a2_$digest.png"
        }
    }

    init {
        runCatching {
            Files.createDirectories(directory)
            sweepIfOversized()
        }.onFailure { LOG.warn("Failed to prepare annotated image cache", it) }
    }

    fun cachedPath(contentHash: String, bbox: IntArray): Path? = try {
        val target = directory.resolve(fileNameOf(contentHash, bbox))
        target.takeIf {
            Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) &&
                Files.size(it) > 0 &&
                ImageIO.read(it.toFile()) != null
        }
    } catch (_: Exception) {
        null
    }

    fun store(contentHash: String, bbox: IntArray, image: BufferedImage): Path? {
        cachedPath(contentHash, bbox)?.let { return it }
        var temp: Path? = null
        return try {
            Files.createDirectories(directory)
            val target = directory.resolve(fileNameOf(contentHash, bbox))
            val staged = Files.createTempFile(directory, ".annotated-", ".tmp")
            temp = staged
            if (!ImageIO.write(image, "png", staged.toFile())) return null
            try {
                Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING)
            }
            target
        } catch (e: Exception) {
            LOG.warn("Failed to cache annotated image", e)
            null
        } finally {
            temp?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    private fun sweepIfOversized() {
        Files.newDirectoryStream(directory, "a2_*.png").use { files ->
            val entries = files.toList()
            if (entries.size <= MAX_FILES) return
            entries.sortedBy { Files.getLastModifiedTime(it).toMillis() }
                .take(entries.size - MAX_FILES)
                .forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }
}
