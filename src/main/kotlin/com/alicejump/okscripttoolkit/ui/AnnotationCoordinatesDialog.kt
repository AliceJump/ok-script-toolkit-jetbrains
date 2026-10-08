package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.AnnotationUiBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBTextField
import java.awt.GridLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

internal data class AnnotationCoordinates(val name: String, val x: Int, val y: Int, val w: Int, val h: Int)

internal fun annotationCoordinatesInBounds(x: Int, y: Int, w: Int, h: Int, width: Int, height: Int, point: Boolean): Boolean =
    x >= 0 && y >= 0 && x <= width && y <= height && if (point) w == 0 && h == 0 else
        w > 0 && h > 0 && w <= width - x && h <= height - y

internal class AnnotationCoordinatesDialog(
    project: Project, name: String, x: Int, y: Int, w: Int, h: Int,
    private val imageWidth: Int, private val imageHeight: Int, private val point: Boolean,
    private val validateName: (String) -> String?,
) : DialogWrapper(project) {
    private val nameField = JBTextField(name, 24)
    private val coordinates = listOf(x, y, w, h).map { JBTextField(it.toString(), 10) }
    init { title = AnnotationUiBundle.message("tool.numeric"); init() }
    override fun createCenterPanel(): JComponent = JPanel(GridLayout(0, 2, 8, 4)).apply {
        add(JLabel(AnnotationUiBundle.message("field.name"))); add(nameField)
        listOf("X", "Y", "W", "H").take(if (point) 2 else 4).forEachIndexed { i, label -> add(JLabel(label)); add(coordinates[i]) }
    }
    override fun getPreferredFocusedComponent(): JComponent = nameField
    override fun doValidate(): ValidationInfo? {
        validateName(nameField.text.trim())?.let { return ValidationInfo(it, nameField) }
        val values = coordinates.take(if (point) 2 else 4).map { it.text.trim().toIntOrNull() }
        val invalid = values.indexOfFirst { it == null }
        if (invalid >= 0) return ValidationInfo(AnnotationUiBundle.message("field.integer"), coordinates[invalid])
        val v = value()
        if (!annotationCoordinatesInBounds(v.x, v.y, v.w, v.h, imageWidth, imageHeight, point))
            return ValidationInfo(AnnotationUiBundle.message("field.bounds"), coordinates[0])
        return null
    }
    fun value() = AnnotationCoordinates(nameField.text.trim(), coordinates[0].text.trim().toInt(), coordinates[1].text.trim().toInt(),
        if (point) 0 else coordinates[2].text.trim().toInt(), if (point) 0 else coordinates[3].text.trim().toInt())
}
