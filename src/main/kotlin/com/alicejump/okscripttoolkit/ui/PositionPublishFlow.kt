package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.core.ConfigurablePositionPublisher
import com.alicejump.okscripttoolkit.core.ConventionLayer
import com.alicejump.okscripttoolkit.core.PositionPublishPreferences
import com.alicejump.okscripttoolkit.core.PositionPublishTarget
import com.alicejump.okscripttoolkit.core.PositionPublishTargets
import com.alicejump.okscripttoolkit.core.PositionPublisherService
import com.alicejump.okscripttoolkit.core.positionPublishTargetInputError
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

data class PositionPublishPlan(
    val format: PositionPublisherService.Format,
    val target: String,
)

/** Position part of the unified Publish flow, kept separate so both path sources and write behavior stay explicit. */
object PositionPublishFlow {
    fun configure(project: Project): PositionPublishPlan? {
        while (true) {
            val json = PositionPublishTargets.resolve(project, PositionPublishTargets.Kind.JSON)
            val python = PositionPublishTargets.resolve(project, PositionPublishTargets.Kind.PYTHON)
            val pythonDirectory = python.value.trimEnd('/')
            val choice = ChooseDialog.show(
                project,
                "Position export format — paths can be configured below:",
                "Publish",
                listOf(
                    "JSON — ${json.value} · ${sourceLabel(json.layer)}",
                    "Python data + parser — $pythonDirectory/ScreenRatio.py + PositionMap.py · ${sourceLabel(python.layer)}",
                    "⚙ JSON output path — ${json.value} · ${sourceLabel(json.layer)}",
                    "⚙ Python output directory — ${python.value} · ${sourceLabel(python.layer)}",
                ),
            ) ?: return null
            when (choice) {
                0 -> return PositionPublishPlan(PositionPublisherService.Format.JSON, json.value)
                1 -> return PositionPublishPlan(PositionPublisherService.Format.PYTHON, python.value)
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
            add("Set my override — Personal preference (highest priority)")
            if (hasPersonal) add("Reset my override — fall back to Project convention → Default")
        }
        val action = ChooseDialog.show(
            project,
            if (kind == PositionPublishTargets.Kind.JSON) {
                "Configure Position JSON path · ${sourceLabel(current.layer)}"
            } else {
                "Configure Position Python output directory · ${sourceLabel(current.layer)}"
            },
            "Publish",
            actions,
        ) ?: return
        if (hasPersonal && action == 1) {
            PositionPublishTargets.setPersonal(project, kind, null)
            return
        }

        val title = if (kind == PositionPublishTargets.Kind.JSON) {
            "Position JSON path relative to the project root:"
        } else {
            "Position Python output directory relative to the project root:"
        }
        val input = Messages.showInputDialog(
            project,
            "$title\n\nLeave empty to restore the project convention / default.",
            "Publish",
            Messages.getQuestionIcon(),
            current.value,
            null,
        ) ?: return
        val error = positionPublishTargetInputError(input)
        if (error != null) {
            Messages.showWarningDialog(project, error, "Publish")
            return
        }
        PositionPublishTargets.setPersonal(project, kind, input)
    }

    fun publish(
        project: Project,
        rect: Boolean,
        point: Boolean,
        plan: PositionPublishPlan,
    ) {
        val selection = PositionPublisherService.Selection(rect, point)
        if (selection.rect != selection.point) {
            val answer = Messages.showYesNoDialog(
                project,
                "Publishing only the selected position type replaces the complete Position output and removes unselected positions.\n\nContinue?",
                "Publish",
                Messages.getWarningIcon(),
            )
            if (answer != Messages.YES) return
        }

        var result = ConfigurablePositionPublisher.publish(project, plan.format, selection, plan.target, false)
        if (result.conflicts.isNotEmpty()) {
            val answer = Messages.showYesNoDialog(
                project,
                "These existing files require explicit overwrite confirmation:\n${result.conflicts.joinToString("\n")}\n\nOverwrite them?",
                "Publish",
                Messages.getWarningIcon(),
            )
            if (answer != Messages.YES) return
            result = ConfigurablePositionPublisher.publish(project, plan.format, selection, plan.target, true)
        }

        if (result.errors.isNotEmpty()) {
            Messages.showErrorDialog(project, result.errors.joinToString("\n"), "Publish")
        } else {
            Messages.showInfoMessage(project, "Written:\n${result.written.joinToString("\n")}", "Publish")
        }
    }

    private fun sourceLabel(layer: ConventionLayer): String = when (layer) {
        ConventionLayer.PERSONAL -> "Personal"
        ConventionLayer.PROJECT -> "Project"
        ConventionLayer.BUILTIN -> "Default"
    }
}
