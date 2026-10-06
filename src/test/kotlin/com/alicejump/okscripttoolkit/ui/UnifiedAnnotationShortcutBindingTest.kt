package com.alicejump.okscripttoolkit.ui

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnifiedAnnotationShortcutBindingTest {
    @Test
    fun `uses M as the only direct annotation mode shortcut`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/com/alicejump/okscripttoolkit/ui/UnifiedAnnotationUi.kt"),
        )
        assertTrue(source.contains("KeyEvent.VK_M, 0), \"cycleMode\""))
        assertFalse(source.contains("KeyEvent.VK_1, 0), \"template\""))
        assertFalse(source.contains("KeyEvent.VK_2, 0), \"rect\""))
        assertFalse(source.contains("KeyEvent.VK_3, 0), \"point\""))
    }
}
