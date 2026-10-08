package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.AnnotationUiBundle
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBTextField
import java.awt.GridLayout
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke

internal object AnnotationKeybindings {
    private const val PREF = "okScriptToolkit.annotation.keybindings"
    val defaults = linkedMapOf("drawBbox" to "r", "copyCoords" to "c", "deleteMode" to "d", "undo" to "ctrl+z",
        "redo" to "ctrl+y", "copy" to "ctrl+c", "paste" to "ctrl+v", "deleteSelected" to "Delete",
        "prevImage" to "ArrowLeft", "nextImage" to "ArrowRight", "modeTemplate" to "1", "modeRect" to "2", "modePoint" to "3", "cycleMode" to "m")
    fun current(): Map<String, String> {
        val json = runCatching { ObjectMapper().readTree(PropertiesComponent.getInstance().getValue(PREF, "{}")) }.getOrNull()
        return defaults.mapValues { (key, fallback) -> json?.path(key)?.takeIf { it.isTextual && stroke(it.asText()) != null }?.asText() ?: fallback }
    }
    fun save(values: Map<String, String>) = PropertiesComponent.getInstance().setValue(PREF, ObjectMapper().writeValueAsString(values))
    fun stroke(value: String): KeyStroke? {
        val parts = value.trim().lowercase().split('+')
        var modifiers = 0
        for (part in parts.dropLast(1)) modifiers = modifiers or when (part.trim()) {
            "ctrl", "control" -> InputEvent.CTRL_DOWN_MASK
            "meta", "cmd", "command" -> InputEvent.META_DOWN_MASK
            "alt" -> InputEvent.ALT_DOWN_MASK
            "shift" -> InputEvent.SHIFT_DOWN_MASK
            else -> return null
        }
        val key = parts.last().trim()
        val code = when (key) {
            "arrowleft" -> KeyEvent.VK_LEFT
            "arrowright" -> KeyEvent.VK_RIGHT
            "arrowup" -> KeyEvent.VK_UP
            "arrowdown" -> KeyEvent.VK_DOWN
            "delete" -> KeyEvent.VK_DELETE
            "escape" -> KeyEvent.VK_ESCAPE
            "space" -> KeyEvent.VK_SPACE
            else -> if (key.length == 1) KeyEvent.getExtendedKeyCodeForChar(key.uppercase()[0].code) else return null
        }
        return if (code == KeyEvent.VK_UNDEFINED) null else KeyStroke.getKeyStroke(code, modifiers)
    }
}

internal class AnnotationKeybindingsDialog(project: Project) : DialogWrapper(project) {
    private val fields = AnnotationKeybindings.current().mapValues { JBTextField(it.value, 16) }
    init { title = AnnotationUiBundle.message("keys.title"); init() }
    override fun createCenterPanel(): JComponent = JPanel(GridLayout(0, 2, 8, 4)).apply {
        fields.forEach { (key, field) ->
            val labelKey = "keys.$key"
            add(JLabel(AnnotationUiBundle.message(labelKey))); add(field)
        }
    }
    override fun doValidate(): ValidationInfo? {
        for (field in fields.values) if (AnnotationKeybindings.stroke(field.text) == null)
            return ValidationInfo(AnnotationUiBundle.message("keys.invalid"), field)
        val seen = mutableSetOf<KeyStroke>()
        for (field in fields.values) if (!seen.add(AnnotationKeybindings.stroke(field.text)!!))
            return ValidationInfo(AnnotationUiBundle.message("keys.duplicate"), field)
        return null
    }
    override fun doOKAction() { AnnotationKeybindings.save(fields.mapValues { it.value.text.trim() }); super.doOKAction() }
}
