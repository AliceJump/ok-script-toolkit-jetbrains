package com.alicejump.okscripttoolkit.core

import java.nio.file.Path
import java.nio.file.Paths

/**
 * **运行时模板库**（`coco_annotations.json`）的路径解析。
 *
 * 与 VS Code 侧 `src/cocoFeaturePathPure.ts` 一一对应。
 *
 * ⚠️ 别和素材面板自己维护的 `<模板目录>/coco_annotations.json` 搞混 —— 那是**两个文件**：
 *
 * | 文件 | 是什么 | 谁读写 |
 * |---|---|---|
 * | `assets/coco_annotations.json`（或 config.py 指定的别处） | ok 框架加载的**运行时模板库** | 本对象；`OkProjectDataService` 读、`OkDataChangeService` 监听 |
 * | `<模板目录>/coco_annotations.json` | 素材面板自己的标注工作文件 | `TemplateAssetDataService` 读写，路径由 `templates.directory` 派生 |
 *
 * ok 框架读的是前者：`ok/__init__.py` 里
 * `self.config.get('template_matching').get('coco_feature_json')`。
 *
 * 取值链（高 → 低）：
 *
 * 1. 项目约定文件 `templates.cocoAnnotations`
 * 2. 项目 `config.py` 的 `template_matching.coco_feature_json`
 * 3. 依次探测 `assets/coco_annotations.json`、`ok_tasks/assets/coco_annotations.json`
 *
 * 第 ③ 层就是**改动前的硬编码行为**，也是这里的兜底 —— 所以声明缺席时行为与今天完全一致。
 * 这不是形式主义：实测 6 个 ok 系项目**全都**声明了它，其中 **ok-infinity-nikki 声明的是
 * `assets/coco_detection.json`** —— 旧代码在它那儿一个候选都探不到，模板库直接是空的。
 */
object CocoFeaturePath {

    /** 首选文件来自哪一层。 */
    enum class Layer {
        /** 项目约定文件 `templates.cocoAnnotations` */
        CONVENTION,

        /** 项目 `config.py` 的 `template_matching.coco_feature_json` */
        CONFIG_PY,

        /** 两个惯例位置（改动前的行为） */
        PROBE,
    }

    /**
     * 第 ③ 层的探测候选（相对项目根）。
     *
     * `assets/` 是 ok-script 标准位置；`ok_tasks/assets/` 是"自定义脚本"位置
     * （素材面板导出时的两个目标就是这两处）。
     */
    val PROBE_CANDIDATES = listOf(
        "assets/coco_annotations.json",
        "ok_tasks/assets/coco_annotations.json",
    )

    data class Plan(
        /** 首选文件；`null` = 没有任何声明，直接用 [probeCandidates] */
        val preferred: Path?,
        /** 首选不可用（或压根没声明）时按顺序探测的候选 */
        val probeCandidates: List<Path>,
        /** 首选来自哪一层 */
        val layer: Layer,
    )

    /**
     * 解析出候选计划（纯函数，不碰文件系统）。
     *
     * @param rootDir 项目根
     * @param declared 项目约定文件 `templates.cocoAnnotations`。**调用方需先归一化**
     *   （见 [TemplatesConvention.cocoAnnotationsOrNull]）—— 它是"相对项目根"字段，
     *   与 `templates.directory` 同一口径。
     * @param fromConfigPy `config.py` 的 `template_matching.coco_feature_json`。**原样传入** ——
     *   它可能是 `os.path.join(项目根, ...)` 拼出来的绝对路径，归一化会把开头斜杠吃掉。
     */
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

    /**
     * 声明值可能是相对项目根的，也可能是绝对路径 —— 两种都要认，
     * 且绝对路径**不做任何改写**（改了就与项目声明的不是同一个文件）。
     */
    private fun absolute(rootDir: String, value: String): Path {
        val parsed = Paths.get(value)
        return if (parsed.isAbsolute) parsed else Paths.get(rootDir, value)
    }

    /**
     * 实际要扫描的文件列表。
     *
     * ⚠️ **首选可用时只用首选**，不把探测候选也带上 —— 否则项目把库搬到别处之后，
     * 旧位置的库会和新库一起被加载，同一个 feature 名出现两份（先到的那份胜出，静默）。
     *
     * 首选**不可用**时退回探测候选：`config.py` 里声明的文件可能还没生成
     * （项目尚未标注过），此时"按惯例找一找"比"什么都不加载"有用得多，
     * 也正是"默认值为现值"。
     *
     * @param exists 由调用方注入，保持本对象可测（不碰真实文件系统）
     */
    fun effectiveFiles(plan: Plan, exists: (Path) -> Boolean): List<Path> {
        val preferred = plan.preferred
        if (preferred != null && exists(preferred)) return listOf(preferred)
        return plan.probeCandidates.filter(exists)
    }

    /**
     * **所有**候选的相对路径（相对项目根、`/` 分隔），含首选与探测候选。
     *
     * 用于文件监听与变更归属判定 —— 两者都**不能按存在性过滤**：
     * 监听要覆盖"文件还没创建"的情况，否则第一次生成库时不会触发刷新。
     */
    fun relPaths(plan: Plan, rootDir: String): List<String> {
        val all = (if (plan.preferred != null) listOf(plan.preferred) else emptyList()) + plan.probeCandidates
        val root = Paths.get(rootDir)
        return all.distinct().mapNotNull { abs ->
            runCatching { root.relativize(abs).toString().replace('\\', '/') }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() && !it.startsWith("..") }
        }
    }
}
