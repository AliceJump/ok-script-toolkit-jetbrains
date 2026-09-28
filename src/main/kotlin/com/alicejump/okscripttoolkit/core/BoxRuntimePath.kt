package com.alicejump.okscripttoolkit.core

import java.nio.file.Path
import java.nio.file.Paths

/**
 * 运行时框文件的路径解析。与 VS Code 侧 [resolveBoxRuntimePlan] 一一对应，
 * 取值链对标 [CocoFeaturePath]：
 *
 * 1. 项目约定 `boxes.runtime`
 * 2. `config.py` 顶层 `boxes_json`
 * 3. 探测 `src/scene/boxes.json`
 *
 * 游戏加载器不读约定文件。插件索引时约定优先，和模板库一样。
 */
object BoxRuntimePath {
    enum class Layer { CONVENTION, CONFIG_PY, PROBE }

    val PROBE_CANDIDATES = listOf("src/scene/boxes.json")

    data class Plan(
        val preferred: Path?,
        val probeCandidates: List<Path>,
        val layer: Layer,
    )

    fun plan(rootDir: String, declared: String?, fromConfigPy: String?): Plan {
        val probeCandidates = PROBE_CANDIDATES.map { Paths.get(rootDir, *it.split("/").toTypedArray()) }
        val declaredPath = declared?.trim()?.takeIf { it.isNotEmpty() }
        if (declaredPath != null) {
            return Plan(absolute(rootDir, declaredPath), probeCandidates, Layer.CONVENTION)
        }
        val fromPy = fromConfigPy?.trim()?.takeIf { it.isNotEmpty() }
        if (fromPy != null) {
            return Plan(absolute(rootDir, fromPy), probeCandidates, Layer.CONFIG_PY)
        }
        return Plan(null, probeCandidates, Layer.PROBE)
    }

    fun effectiveFile(plan: Plan, exists: (Path) -> Boolean): Path? {
        val preferred = plan.preferred
        if (preferred != null && exists(preferred)) return preferred
        return plan.probeCandidates.firstOrNull(exists)
    }

    /** 发布写入点。文件可以尚不存在。 */
    fun writeTarget(plan: Plan): Path = plan.preferred ?: plan.probeCandidates.first()

    fun relPaths(plan: Plan, rootDir: String): List<String> {
        val all = (if (plan.preferred != null) listOf(plan.preferred) else emptyList()) + plan.probeCandidates
        val root = Paths.get(rootDir)
        return all.distinct().mapNotNull { abs ->
            runCatching { root.relativize(abs).toString().replace('\\', '/') }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() && !it.startsWith("..") }
        }
    }

    private fun absolute(rootDir: String, value: String): Path {
        val parsed = Paths.get(value)
        return if (parsed.isAbsolute) parsed else Paths.get(rootDir, value)
    }
}
