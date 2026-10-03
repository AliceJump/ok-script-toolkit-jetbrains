package com.alicejump.okscripttoolkit.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class ResourceFileTransactionTest {
    @Test
    fun `rollback restores existing files and removes newly created files`() {
        val root = Files.createTempDirectory("ok-resource-transaction-")
        try {
            val existing = root.resolve("existing.json")
            val created = root.resolve("created.json")
            Files.writeString(existing, "before\n")

            val transaction = assertNotNull(ResourceFileTransaction.capture(listOf(existing, created)))
            Files.writeString(existing, "after\n")
            Files.writeString(created, "new\n")

            assertEquals(true, transaction.rollback())
            assertEquals("before\n", Files.readString(existing))
            assertFalse(Files.exists(created))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
