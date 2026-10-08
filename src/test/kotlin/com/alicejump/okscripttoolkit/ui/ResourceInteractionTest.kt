package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.core.TemplateThumbPipeline
import java.awt.Container
import java.awt.Dimension
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ResourceInteractionTest {
    private fun descendants(container: Container): List<java.awt.Component> = container.components.flatMap {
        listOf(it) + if (it is Container) descendants(it) else emptyList()
    }

    @Test
    fun `card buttons bind to their own resource without selecting a row`() {
        SwingUtilities.invokeAndWait {
            val calls = mutableListOf<String>()
            val grid = ResourceThumbnailGrid<String>(
                visual = { CardVisual(it, it, "", it, Path.of("$it.png"), intArrayOf(0, 0, 10, 10)) },
                actions = { item -> listOf(ResourceCardAction("×", "Delete $item") { calls += item }) },
                onSingle = { calls += "open:$it" }, load = { _, _ -> },
            )
            grid.setItems(listOf("first", "second"))
            descendants(grid).filterIsInstance<JButton>().single { it.toolTipText == "Delete second" }.doClick(0)
            assertEquals(listOf("second"), calls)
            grid.setItems(listOf("first"))
            descendants(grid).filterIsInstance<JButton>().single { it.toolTipText == "Delete first" }.doClick(0)
            assertEquals(listOf("second", "first"), calls)
            grid.dispose()
        }
    }

    @Test
    fun `late thumbnail cannot replace a newer resource snapshot`() {
        SwingUtilities.invokeAndWait {
            val callbacks = mutableListOf<(String, ImageIcon?) -> Unit>()
            val grid = ResourceThumbnailGrid<String>(
                visual = { CardVisual(it, it, "", it, Path.of("$it.png"), intArrayOf(0, 0, 10, 10)) },
                actions = { emptyList() }, onSingle = {},
                load = { _: List<TemplateThumbPipeline.Request<String>>, callback -> callbacks += callback },
            )
            grid.setItems(listOf("same"))
            grid.setItems(listOf("same"), invalidate = true)
            val old = ImageIcon(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB))
            val fresh = ImageIcon(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB))
            callbacks[1]("same", fresh)
            callbacks[0]("same", old)
            assertEquals(fresh, descendants(grid).filterIsInstance<JLabel>().single { it.icon != null }.icon)
            grid.dispose()
        }
    }

    @Test
    fun `filter and direct buttons cancel pending preview insertion`() {
        SwingUtilities.invokeAndWait {
            val grid = ResourceThumbnailGrid<String>(
                visual = { CardVisual(it, it, "", it, Path.of("$it.png"), intArrayOf(0, 0, 10, 10)) },
                actions = { listOf(ResourceCardAction("⧉", "Copy") {}) }, onSingle = {}, onDouble = {}, load = { _, _ -> },
            )
            grid.setItems(listOf("first"))
            val card = descendants(grid).filterIsInstance<JPanel>().single { it.getClientProperty("thumbnailClickTimer") != null }
            card.mouseListeners.forEach { it.mouseClicked(MouseEvent(card, MouseEvent.MOUSE_CLICKED, 0, 0, 2, 2, 1, false, MouseEvent.BUTTON1)) }
            val timer = card.getClientProperty("thumbnailClickTimer") as Timer
            assertTrue(timer.isRunning)
            assertEquals(500, timer.initialDelay)
            descendants(grid).filterIsInstance<JButton>().single { it.toolTipText == "Copy" }.doClick(0)
            assertFalse(timer.isRunning)
            timer.start()
            grid.setItems(emptyList())
            assertFalse(timer.isRunning)
            grid.dispose()
        }
    }

    @Test
    fun `grid maintains equal card widths and shrinks after a wide layout`() {
        val grid = JPanel(ResourceGridLayout())
        repeat(5) { grid.add(JPanel()) }
        grid.setSize(500, 500); grid.doLayout()
        assertEquals(1, grid.components.map { it.width }.distinct().size)
        assertEquals(grid.components.first().width, grid.components.last().width)
        grid.setSize(180, 500); grid.doLayout()
        assertTrue(grid.components.all { it.x + it.width <= 180 })
        assertEquals(Dimension(118, 1), grid.minimumSize)
    }

    @Test
    fun `coordinate validation prevents overflow and accepts point and edge boxes`() {
        assertTrue(annotationCoordinatesInBounds(90, 90, 10, 10, 100, 100, false))
        assertFalse(annotationCoordinatesInBounds(90, 90, Int.MAX_VALUE, 10, 100, 100, false))
        assertFalse(annotationCoordinatesInBounds(100, 100, 0, 0, 100, 100, false))
        assertTrue(annotationCoordinatesInBounds(100, 100, 0, 0, 100, 100, true))
        assertFalse(annotationCoordinatesInBounds(-1, 0, 0, 0, 100, 100, true))
    }

    @Test
    fun `default shortcuts are valid and publish availability excludes an empty picker`() {
        AnnotationKeybindings.defaults.values.forEach { assertNotNull(AnnotationKeybindings.stroke(it)) }
        assertEquals("1", AnnotationKeybindings.defaults["modeTemplate"])
        assertFalse(publishAvailability(0, 0, 0).any)
        assertTrue(publishAvailability(0, 0, 1).any)
    }
}
