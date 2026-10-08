package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.AnnotationUiBundle
import com.alicejump.okscripttoolkit.core.ConfigurablePositionPublisher
import com.alicejump.okscripttoolkit.core.ConventionLayer
import com.alicejump.okscripttoolkit.core.OkProjectDataService
import com.alicejump.okscripttoolkit.core.PositionPublishPreferences
import com.alicejump.okscripttoolkit.core.PositionPublishTarget
import com.alicejump.okscripttoolkit.core.PositionPublishTargets
import com.alicejump.okscripttoolkit.core.PositionPublisherService
import com.alicejump.okscripttoolkit.core.pathInsideRoot
import com.alicejump.okscripttoolkit.core.positionPublishTargetInputError
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

data class PositionPublishPlan(val format: PositionPublisherService.Format, val target: String)

/** 配置与取消决定先完成，实际发布返回结果供统一发布链决定是否继续。 */
object PositionPublishFlow {
    fun configure(project: Project, rect: Boolean = true, point: Boolean = true): PositionPublishPlan? {
        while (true) {
            val json = PositionPublishTargets.resolve(project, PositionPublishTargets.Kind.JSON)
            val python = PositionPublishTargets.resolve(project, PositionPublishTargets.Kind.PYTHON)
            val choice = ChooseDialog.show(project, message("position.format"), message("publish.title"), listOf(
                message("position.json", json.value, sourceLabel(json.layer)),
                message("position.python", python.value.trimEnd('/'), sourceLabel(python.layer)),
                message("position.editJson", json.value, sourceLabel(json.layer)),
                message("position.editPython", python.value, sourceLabel(python.layer)),
            )) ?: return null
            when (choice) {
                0, 1 -> {
                    if (rect != point && Messages.showYesNoDialog(project, message("position.selectionWarning"),
                            message("publish.title"), Messages.getWarningIcon()) != Messages.YES) return null
                    return if (choice == 0) PositionPublishPlan(PositionPublisherService.Format.JSON, json.value)
                        else PositionPublishPlan(PositionPublisherService.Format.PYTHON, python.value)
                }
                2 -> editTarget(project, PositionPublishTargets.Kind.JSON, json)
                3 -> editTarget(project, PositionPublishTargets.Kind.PYTHON, python)
            }
        }
    }

    private fun editTarget(project: Project, kind: PositionPublishTargets.Kind, current: PositionPublishTarget) {
        val prefs = project.service<PositionPublishPreferences>()
        val hasPersonal = when (kind) {
            PositionPublishTargets.Kind.JSON -> prefs.rawJsonPath().isNotBlank()
            PositionPublishTargets.Kind.PYTHON -> prefs.rawPythonDirectory().isNotBlank()
        }
        val actions = buildList {
            add(message("position.setOverride"))
            if (hasPersonal) add(message("position.resetOverride"))
        }
        val prompt = if (kind == PositionPublishTargets.Kind.JSON) "position.configureJson" else "position.configurePython"
        val action = ChooseDialog.show(project, message(prompt, sourceLabel(current.layer)), message("publish.title"), actions) ?: return
        if (hasPersonal && action == 1) { PositionPublishTargets.setPersonal(project, kind, null); return }
        val inputPrompt = if (kind == PositionPublishTargets.Kind.JSON) "position.inputJson" else "position.inputPython"
        val input = Messages.showInputDialog(project, message(inputPrompt), message("publish.title"),
            Messages.getQuestionIcon(), current.value, null) ?: return
        val root = project.service<OkProjectDataService>().rootPath() ?: return
        val error = positionPublishTargetInputError(input)
            ?: if (input.isNotBlank() && pathInsideRoot(root, root.resolve(input)) == null) "outside" else null
        if (error != null) {
            Messages.showWarningDialog(project, message(if (error == "relative") "position.pathRelative" else "position.pathOutside"),
                message("publish.title"))
            return
        }
        PositionPublishTargets.setPersonal(project, kind, input)
    }

    fun publish(project: Project, rect: Boolean, point: Boolean, plan: PositionPublishPlan): Boolean {
        val selection = PositionPublisherService.Selection(rect, point)
        var result = ConfigurablePositionPublisher.publish(project, plan.format, selection, plan.target, false)
        if (result.conflicts.isNotEmpty()) {
            val files = result.conflicts.joinToString("\n")
            if (Messages.showYesNoDialog(project, message("position.overwrite", files), message("publish.title"),
                    Messages.getWarningIcon()) != Messages.YES) return false
            result = ConfigurablePositionPublisher.publish(project, plan.format, selection, plan.target, true)
        }
        if (result.errors.isNotEmpty()) {
            Messages.showErrorDialog(project, message("position.failed", result.errors.joinToString(", ")), message("publish.title"))
            return false
        }
        Messages.showInfoMessage(project, message("position.done", result.written.joinToString("\n")), message("publish.title"))
        return true
    }

    private fun sourceLabel(layer: ConventionLayer) = message(when (layer) {
        ConventionLayer.PERSONAL -> "source.personal"
        ConventionLayer.PROJECT -> "source.project"
        ConventionLayer.BUILTIN -> "source.default"
    })
    private fun message(key: String, vararg values: Any) = AnnotationUiBundle.message(key, *values)
}
