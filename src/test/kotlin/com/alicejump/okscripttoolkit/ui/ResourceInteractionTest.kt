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
    @Test
    fun `narrow card shows both edges of a wide thumbnail`() {
        SwingUtilities.invokeAndWait {
            val source = BufferedImage(240, 96, BufferedImage.TYPE_INT_RGB)
            source.createGraphics().also { graphics ->
                graphics.color = java.awt.Color.RED; graphics.fillRect(0, 0, 120, 96)
                graphics.color = java.awt.Color.BLUE; graphics.fillRect(120, 0, 120, 96)
                graphics.dispose()
            }
            val label = ResourceThumbnailLabel(ImageIcon(source))
            label.setSize(118, 96)
            val screen = BufferedImage(118, 96, BufferedImage.TYPE_INT_RGB)
            screen.createGraphics().also { label.paint(it); it.dispose() }
            assertEquals(java.awt.Color.RED.rgb, screen.getRGB(1, 48))
            assertEquals(java.awt.Color.BLUE.rgb, screen.getRGB(116, 48))
        }
    }

    private fun descendants(container: Container): List<java.awt.Component> = container.components.flatMap {
        listOf(it) + if (it is Container) descendants(it) else emptyList()
    }

    @Test
    fun `card buttons bind to their own resource without selecting a row`() {
        SwingUtilities.invokeAndWait {
            val calls = mutableListOf<String>()
            val grid = ResourceThumbnailGrid<String>(
                visual = { CardVisual(it, it, "", it, Path.of("$it.png"), intArrayOf(0, 0, 10, 10)) },
                actions = { item -> listOf(ResourceCardAction(com.intellij.icons.AllIcons.Actions.GC, "Delete $item") { calls += item }) },
                primary = { calls += "open:$it" }, load = { _, _ -> },
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
                actions = { emptyList() }, primary = {},
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

    private fun cards(grid: java.awt.Container) = descendants(grid).filterIsInstance<JPanel>().filter { it.isFocusable && it.accessibleContext.accessibleName != null }

    private fun press(card: javax.swing.JComponent, code: Int, modifiers: Int = 0) {
        val stroke = javax.swing.KeyStroke.getKeyStroke(code, modifiers)
        val key = card.getInputMap(javax.swing.JComponent.WHEN_FOCUSED).get(stroke) ?: error("no binding for $stroke")
        card.actionMap.get(key).actionPerformed(java.awt.event.ActionEvent(card, 0, key.toString()))
    }

    @Test
    fun `single click only selects and double click runs the primary action`() {
        SwingUtilities.invokeAndWait {
            val calls = mutableListOf<String>()
            val grid = ResourceThumbnailGrid<String>(
                visual = { CardVisual(it, it, "", it, Path.of("$it.png"), intArrayOf(0, 0, 10, 10)) },
                actions = { emptyList() }, primary = { calls += "primary:$it" }, load = { _, _ -> },
                onSelect = { calls += "select:$it" },
            )
            grid.setItems(listOf("first", "second"))
            val card = cards(grid)[1]
            card.mouseListeners.forEach { it.mouseClicked(MouseEvent(card, MouseEvent.MOUSE_CLICKED, 0, 0, 2, 2, 1, false, MouseEvent.BUTTON1)) }
            assertEquals(listOf("select:second"), calls)
            assertEquals("second", grid.selected)
            card.mouseListeners.forEach { it.mouseClicked(MouseEvent(card, MouseEvent.MOUSE_CLICKED, 0, 0, 2, 2, 2, false, MouseEvent.BUTTON1)) }
            assertEquals(listOf("select:second", "primary:second"), calls)
            grid.dispose()
        }
    }

    @Test
    fun `keyboard gestures match the VS Code card contract`() {
        SwingUtilities.invokeAndWait {
            val calls = mutableListOf<String>()
            val grid = ResourceThumbnailGrid<String>(
                visual = { CardVisual(it, it, "", it, Path.of("$it.png"), intArrayOf(0, 0, 10, 10)) },
                actions = { emptyList() }, primary = { calls += "primary:$it" }, load = { _, _ -> },
                edit = { calls += "edit:$it" }, copy = { calls += "copy:$it" }, delete = { calls += "delete:$it" },
            )
            grid.setItems(listOf("a", "b", "c"))
            val (a, b, _) = cards(grid)
            press(a, java.awt.event.KeyEvent.VK_ENTER)
            press(a, java.awt.event.KeyEvent.VK_E)
            press(a, java.awt.event.KeyEvent.VK_C, java.awt.Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx)
            press(a, java.awt.event.KeyEvent.VK_DELETE)
            assertEquals(listOf("primary:a", "edit:a", "copy:a", "delete:a"), calls)
            press(a, java.awt.event.KeyEvent.VK_RIGHT)
            assertEquals("b", grid.selected)
            press(b, java.awt.event.KeyEvent.VK_END)
            assertEquals("c", grid.selected)
            assertEquals(listOf("a", "b", "c"), cards(grid).map { it.accessibleContext.accessibleName })
            grid.dispose()
        }
    }

    @Test
    fun `selection survives refresh and empty grid shows status text`() {
        SwingUtilities.invokeAndWait {
            val grid = ResourceThumbnailGrid<String>(
                visual = { CardVisual(it, it, "", it, Path.of("$it.png"), intArrayOf(0, 0, 10, 10)) },
                actions = { emptyList() }, primary = {}, load = { _, _ -> },
            )
            grid.setItems(listOf("a", "b"))
            assertTrue(grid.reveal { it == "b" })
            grid.setItems(listOf("b", "c"))
            assertEquals("b", grid.selected)
            grid.setItems(emptyList())
            assertTrue(grid.emptyMessage.isNotBlank())
            grid.showError("broken")
            assertEquals("broken", grid.emptyMessage)
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
