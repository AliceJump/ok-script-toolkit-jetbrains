package com.alicejump.okscripttoolkit.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path

/** Position publish target defaults, shared with the VS Code publisher. */
object PositionPublishDefaults {
    const val JSON_PATH = "src/scene/positions.json"
    const val PYTHON_DIRECTORY = "src/scene"
}

/** `ok-script-toolkit.json.position` project convention. */
data class PositionPublishConvention(
    val jsonPath: String? = null,
    val pythonDirectory: String? = null,
) {
    fun jsonResolved(personal: String?): ResolvedSetting<String> = resolveSetting(
        normalizeRelPath(personal),
        normalizeRelPath(jsonPath),
        PositionPublishDefaults.JSON_PATH,
    )

    fun pythonDirectoryResolved(personal: String?): ResolvedSetting<String> = resolveSetting(
        normalizeRelPath(personal),
        normalizeRelPath(pythonDirectory),
        PositionPublishDefaults.PYTHON_DIRECTORY,
    )

    companion object {
        private val JSON = ObjectMapper()

        fun parse(node: JsonNode?): PositionPublishConvention {
            if (node == null || !node.isObject) return PositionPublishConvention()
            return PositionPublishConvention(
                jsonPath = node.get("jsonPath")?.takeIf { it.isTextual }?.asText(),
                pythonDirectory = node.get("pythonDirectory")?.takeIf { it.isTextual }?.asText(),
            )
        }

        fun parseFile(file: Path): PositionPublishConvention = try {
            parse(JSON.readTree(file.toFile())?.get("position"))
        } catch (_: Exception) {
            PositionPublishConvention()
        }
    }
}

/**
 * Personal Position publish overrides.
 *
 * Empty values mean "no personal override", so the same shared precedence as Template applies:
 * personal IDE preference > `ok-script-toolkit.json` project convention > built-in default.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "com.alicejump.okscripttoolkit.core.PositionPublishPreferences",
    storages = [Storage("ok-script-toolkit.xml")],
)
class PositionPublishPreferences :
    SimplePersistentStateComponent<PositionPublishPreferences.SettingsState>(SettingsState()) {

    class SettingsState : BaseState() {
        var jsonPath by string("")
        var pythonDirectory by string("")
    }

    fun rawJsonPath(): String = state.jsonPath.orEmpty()
    fun rawPythonDirectory(): String = state.pythonDirectory.orEmpty()

    fun setJsonPath(value: String?) {
        state.jsonPath = normalizeRelPath(value).orEmpty()
    }

    fun setPythonDirectory(value: String?) {
        state.pythonDirectory = normalizeRelPath(value).orEmpty()
    }
}

data class PositionPublishTarget(
    val value: String,
    val layer: ConventionLayer,
    val personalValue: String?,
    val projectValue: String?,
)

/** Resolve Position output paths through the same personal > project > builtin chain as Template. */
object PositionPublishTargets {
    enum class Kind { JSON, PYTHON }

    fun resolve(project: Project, kind: Kind): PositionPublishTarget {
        val root = project.service<OkProjectDataService>().rootPath()
        val convention = root?.resolve(ProjectConventionConfig.PROJECT_CONFIG_FILE)
            ?.let(PositionPublishConvention::parseFile)
            ?: PositionPublishConvention()
        val prefs = project.service<PositionPublishPreferences>()
        val personal = when (kind) {
            Kind.JSON -> normalizeRelPath(prefs.rawJsonPath())
            Kind.PYTHON -> normalizeRelPath(prefs.rawPythonDirectory())
        }
        val declared = when (kind) {
            Kind.JSON -> normalizeRelPath(convention.jsonPath)
            Kind.PYTHON -> normalizeRelPath(convention.pythonDirectory)
        }
        val resolved = when (kind) {
            Kind.JSON -> convention.jsonResolved(personal)
            Kind.PYTHON -> convention.pythonDirectoryResolved(personal)
        }
        return PositionPublishTarget(resolved.value, resolved.layer, personal, declared)
    }

    fun setPersonal(project: Project, kind: Kind, value: String?) {
        val prefs = project.service<PositionPublishPreferences>()
        when (kind) {
            Kind.JSON -> prefs.setJsonPath(value)
            Kind.PYTHON -> prefs.setPythonDirectory(value)
        }
    }
}

/** User-entered Position targets must be project-relative and cannot contain traversal segments. */
fun positionPublishTargetInputError(value: String?): String? {
    val raw = value?.trim().orEmpty()
    if (raw.isEmpty()) return null // Empty explicitly means reset personal override.
    if (raw.startsWith('/') || raw.startsWith('\\') || Regex("^[A-Za-z]:").containsMatchIn(raw)) {
        return "Position output path must be relative to the project root."
    }
    if (raw.replace('\\', '/').split('/').contains("..")) {
        return "Position output path must stay within the project root."
    }
    return null
}

/**
 * The built-in JSON target keeps the historical replacement behavior. A configurable JSON target
 * is broader authority, so replacing an existing file requires an explicit confirmation first.
 */
internal fun jsonTargetRequiresOverwriteConfirmation(relativeTarget: String, exists: Boolean): Boolean =
    exists && normalizeRelPath(relativeTarget) != PositionPublishDefaults.JSON_PATH

private fun targetInsideRoot(root: Path, relative: String): Path? {
    val normalizedRoot = root.toAbsolutePath().normalize()
    val target = normalizedRoot.resolve(relative).normalize()
    if (target == normalizedRoot || !target.startsWith(normalizedRoot)) return null

    val realRoot = runCatching { normalizedRoot.toRealPath() }.getOrNull() ?: return null
    var existing = target
    while (!Files.exists(existing)) {
        existing = existing.parent ?: return null
    }
    val realExisting = runCatching { existing.toRealPath() }.getOrNull() ?: return null
    if (!realExisting.startsWith(realRoot)) return null
    return target
}

/**
 * Writes the already-shared Position runtime to the configured target while preserving handwritten
 * Python protection, explicit confirmation for existing custom JSON targets, and rollback behavior.
 */
object ConfigurablePositionPublisher {
    fun publish(
        project: Project,
        format: PositionPublisherService.Format,
        selection: PositionPublisherService.Selection,
        relativeTarget: String,
        overwriteHandwritten: Boolean = false,
    ): PositionPublisherService.Result {
        val published = project.service<PositionPublisherService>().collect(selection)
        if (published.errors.isNotEmpty()) return PositionPublisherService.Result(errors = published.errors)
        if (published.positions.isEmpty()) return PositionPublisherService.Result(errors = listOf("empty"))
        val root = project.service<OkProjectDataService>().rootPath()
            ?: return PositionPublisherService.Result(errors = listOf("root"))

        return when (format) {
            PositionPublisherService.Format.JSON -> {
                val target = targetInsideRoot(root, relativeTarget)
                    ?: return PositionPublisherService.Result(errors = listOf("outside"))
                if (jsonTargetRequiresOverwriteConfirmation(relativeTarget, Files.isRegularFile(target)) && !overwriteHandwritten) {
                    return PositionPublisherService.Result(conflicts = listOf(target))
                }
                if (!writeAnnotationText(target, PositionResource.serializeJson(published))) {
                    PositionPublisherService.Result(errors = listOf("write"))
                } else {
                    PositionPublisherService.Result(written = listOf(target))
                }
            }

            PositionPublisherService.Format.PYTHON -> {
                val directory = targetInsideRoot(root, relativeTarget)
                    ?: return PositionPublisherService.Result(errors = listOf("outside"))
                val screen = directory.resolve("ScreenRatio.py")
                val map = directory.resolve("PositionMap.py")
                val conflicts = listOf(screen, map).filter {
                    Files.isRegularFile(it) && !runCatching {
                        Files.readString(it).startsWith(PositionResource.GENERATED_MARKER)
                    }.getOrDefault(false)
                }
                if (conflicts.isNotEmpty() && !overwriteHandwritten) {
                    return PositionPublisherService.Result(conflicts = conflicts)
                }

                val transaction = ResourceFileTransaction.capture(listOf(screen, map))
                    ?: return PositionPublisherService.Result(errors = listOf("backup"))
                if (!writeAnnotationText(screen, PositionResource.serializeScreenRatioPython())) {
                    return PositionPublisherService.Result(errors = listOf("write:ScreenRatio.py"))
                }
                if (!writeAnnotationText(map, PositionResource.serializePositionMapPython(published))) {
                    val restored = transaction.rollback()
                    return PositionPublisherService.Result(
                        errors = listOf(if (restored) "write:PositionMap.py" else "write:PositionMap.py:rollback"),
                    )
                }
                PositionPublisherService.Result(written = listOf(screen, map))
            }
        }
    }
}
