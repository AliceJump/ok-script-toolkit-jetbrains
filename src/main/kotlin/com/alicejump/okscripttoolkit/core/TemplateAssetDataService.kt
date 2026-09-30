package com.alicejump.okscripttoolkit.core

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.alicejump.okscripttoolkit.settings.OkScriptToolkitSettings
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.awt.image.BufferedImage
import java.io.File
import java.util.Locale
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import javax.imageio.ImageIO

private val JSON = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

// ── COCO data classes ────────────────────────────────────────────

private class PackedPage(
    val imgIds: MutableList<Int> = mutableListOf(),
    val occupancy: MutableList<IntArray> = mutableListOf(),
)

private class Page(
    val W: Int,
    val H: Int,
    val items: MutableList<AnnotatedEntry> = mutableListOf(),
)

private data class AnnotatedEntry(val img: CocoImage, val ann: CocoAnnotation)

// ── Service ──────────────────────────────────────────────────────

@Service(Service.Level.PROJECT)
class TemplateAssetDataService(private val project: Project) : CocoAnnotationData() {
    companion object {
        private val LOG = Logger.getInstance(TemplateAssetDataService::class.java)

        fun serializeCoco(coco: CocoData): ObjectNode = CocoAnnotationData.serializeCoco(coco)
        fun parseCoco(root: JsonNode): CocoData = CocoAnnotationData.parseCoco(root)
        fun cocoPath(projectDir: String, templatesDir: String): Path = CocoAnnotationData.cocoPath(projectDir, templatesDir)
        fun templateDir(projectDir: String, templatesDir: String): Path = CocoAnnotationData.templateDir(projectDir, templatesDir)
    }

    private var projectDir: String = ""

    @Synchronized
    override fun load(projectDir: String, templatesDir: String): CocoData {
        this.projectDir = projectDir
        return super.load(projectDir, templatesDir)
    }

    /**
     * saveToAssets 打包导出（对齐 VSCode 版与 ok-script FeatureSet.compress_coco 语义）：
     * 只处理有标注图片 -> 按原始尺寸分组 -> 组内 bbox 互不重叠的原图共享同一页
     * 画布（原坐标粘贴）-> 每页输出 images/<n>.png -> 重写 target/coco_annotations.json
     * （bbox 保留原坐标、清理无引用分类）-> 可选生成 LabelEnum.py。
     * 在后台线程调用；onProgress 汇报 (done, total)。
     */
    fun saveToAssets(
        targetFolder: String,
        generateEnum: Boolean,
        enumPath: String?,
        onProgress: (Int, Int) -> Unit,
    ) {
        // 枚举输出路径**先算出来、先校验，在所有写入之前**：越界就抛，此时 assets/COCO
        // 一个字节都还没动。放到写完 COCO 之后再校验的话，用户会看到"一半成功" ——
        // 资产已经更新、枚举没生成，而且文件被丢到了项目外面。
        //
        // `takeIf { it.isNotBlank() }` 是**必须的防御**，不是风格问题：调用方若传进来一个
        // 空串（"用户留空 = 跳过"），下面的 `enumPath ?: 默认` 判断不出来（空串不是 null），
        // 会一路传到 `File("")` 上 —— 那是个看不出原因的 FileNotFoundException。
        val enumFile = if (generateEnum) {
            enumPath?.takeIf { it.isNotBlank() } ?: Paths.get(targetFolder, "LabelEnum.py").toString()
        } else {
            null
        }
        if (enumFile != null) {
            require(isPathInsideRoot(projectDir, enumFile)) {
                OkScriptToolkitBundle.message("templateAsset.exportEnumPathInvalid")
            }
        }

        val targetImagesDir = Paths.get(targetFolder, "images")

        // 1. 只处理有标注的图片
        val annotatedImages = cocoData.images.filter { img ->
            cocoData.annotations.any { it.imageId == img.id }
        }

        // 2. 按原始尺寸分组（头读尺寸优先，失败回退 COCO 记录值）
        val dimGroups = linkedMapOf<String, MutableList<AnnotatedEntry>>()

        for (img in annotatedImages) {
            val srcFile = templateFolder?.resolve(img.fileName)?.toFile() ?: continue
            if (!srcFile.exists()) continue
            var imgW = img.width
            var imgH = img.height
            readImageHeaderSize(srcFile)?.let { (w, h) ->
                imgW = w
                imgH = h
            }
            for (ann in cocoData.annotationsForImage(img.id)) {
                val key = "${imgW}x${imgH}"
                dimGroups.getOrPut(key) { mutableListOf() }.add(AnnotatedEntry(img.copy(width = imgW, height = imgH), ann))
            }
        }

        // 3. 组内 bin-packing：bbox 互不重叠的原图共享同一页
        val pageList = mutableListOf<Page>()
        for ((_, entries) in dimGroups) {
            val W = entries.first().img.width
            val H = entries.first().img.height

            val imgRects = linkedMapOf<Int, MutableList<IntArray>>()
            for (e in entries) {
                imgRects.getOrPut(e.img.id) { mutableListOf() }.add(
                    intArrayOf(e.ann.bbox[0], e.ann.bbox[1], e.ann.bbox[0] + e.ann.bbox[2], e.ann.bbox[1] + e.ann.bbox[3]),
                )
            }

            val pages = mutableListOf<PackedPage>()
            for (imgId in imgRects.keys) {
                val rects = imgRects[imgId] ?: continue
                var assigned = false
                for (page in pages) {
                    val conflict = rects.any { cRect ->
                        page.occupancy.any { pRect ->
                            cRect[0] < pRect[2] && cRect[2] > pRect[0] && cRect[1] < pRect[3] && cRect[3] > pRect[1]
                        }
                    }
                    if (!conflict) {
                        page.imgIds.add(imgId)
                        page.occupancy.addAll(rects)
                        assigned = true
                        break
                    }
                }
                if (!assigned) {
                    pages.add(PackedPage(mutableListOf(imgId), rects.toMutableList()))
                }
            }

            for (page in pages) {
                val ids = page.imgIds.toHashSet()
                pageList.add(Page(W, H, entries.filter { it.img.id in ids }.toMutableList<AnnotatedEntry>()))
            }
        }

        // 4. 确定性命名：第 i 页 -> images/<i+1>.png；COCO 元数据先构建
        val newImages = mutableListOf<CocoImage>()
        val newAnnotations = mutableListOf<CocoAnnotation>()
        var nextAnnId = 1
        for ((pageIndex, page) in pageList.withIndex()) {
            val packedImgId = pageIndex + 1
            newImages.add(CocoImage(packedImgId, "images/$packedImgId.png", page.W, page.H))
            for (it in page.items) {
                val bw = it.ann.bbox[2]
                val bh = it.ann.bbox[3]
                newAnnotations.add(
                    CocoAnnotation(nextAnnId++, packedImgId, it.ann.categoryId, it.ann.bbox.copyOf(), bw * bh),
                )
            }
        }

        // 图片与 COCO 写进目标目录内；枚举稍后在其目标目录所在卷单独暂存。
        val targetDir = Paths.get(targetFolder)
        Files.createDirectories(targetDir)
        val stagingRoot = Files.createTempDirectory(targetDir, ".ok-toolkit-export-")
        val stagedImagesDir = stagingRoot.resolve("images")
        var enumStagingRoot: Path? = null
        var preserveStaging = false
        try {
        Files.createDirectories(stagedImagesDir)
        // 5. 渲染全部页：白底画布 + 原坐标粘贴（同源图多 bbox 只解码一次）
        val total = pageList.size
        var completed = 0
        onProgress(completed, total)
        for ((pageIndex, page) in pageList.withIndex()) {
            val canvas = java.awt.image.BufferedImage(page.W, page.H, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val canvasG = canvas.createGraphics()
            canvasG.color = java.awt.Color.WHITE
            canvasG.fillRect(0, 0, page.W, page.H)
            canvasG.dispose()

            val byImage = linkedMapOf<String, MutableList<IntArray>>()
            for (it in page.items) {
                byImage.getOrPut(it.img.fileName) { mutableListOf() }.add(it.ann.bbox.copyOf())
            }
            for ((fileName, rects) in byImage) {
                val srcFile = templateFolder?.resolve(fileName)?.toFile() ?: continue
                val decoded = runCatching { ImageIO.read(srcFile) }.getOrNull() ?: continue
                val g = canvas.createGraphics()
                for (r in rects) {
                    val x1 = r[0].coerceAtLeast(0)
                    val y1 = r[1].coerceAtLeast(0)
                    val x2 = (r[0] + r[2]).coerceAtMost(page.W)
                    val y2 = (r[1] + r[3]).coerceAtMost(page.H)
                    if (x2 > x1 && y2 > y1) {
                        g.drawImage(decoded.getSubimage(x1, y1, x2 - x1, y2 - y1), x1, y1, null)
                    }
                }
                g.dispose()
            }

            val outPath = stagedImagesDir.resolve("${pageIndex + 1}.png")
            Files.createDirectories(outPath.parent)
            check(ImageIO.write(canvas, "png", outPath.toFile())) { "PNG writer unavailable" }
            completed++
            onProgress(completed, total)
        }

        // 6. 重写 target 的 coco_annotations.json（清理无引用分类）
        val usedCatIds = newAnnotations.map { it.categoryId }.toSet()
        val croppedCoco = CocoData(
            newImages.toMutableList(),
            newAnnotations.toMutableList(),
            cocoData.categories.filter { it.id in usedCatIds }.toMutableList(),
        )

        val cocoTarget = targetDir.resolve("coco_annotations.json")
        val stagedCoco = stagingRoot.resolve("coco_annotations.json")
        JSON.writerWithDefaultPrettyPrinter().writeValue(stagedCoco.toFile(), serializeCoco(croppedCoco))

        val enumTarget = enumFile?.let { Paths.get(it) }
        val enumInImages = enumTarget?.toAbsolutePath()?.normalize()
            ?.startsWith(targetImagesDir.toAbsolutePath().normalize()) == true
        var stagedEnum: Path? = null
        if (enumFile != null) {
            val labels = croppedCoco.categories.map { it.name }.sorted()
            stagedEnum = if (enumInImages) {
                stagedImagesDir.resolve(targetImagesDir.toAbsolutePath().normalize().relativize(enumTarget!!.toAbsolutePath().normalize()))
            } else {
                // enumTarget 可能位于另一卷；临时文件与旧版备份都放到它的父目录。
                val enumParent = enumTarget!!.toAbsolutePath().normalize().parent
                Files.createDirectories(enumParent)
                enumStagingRoot = Files.createTempDirectory(enumParent, ".ok-toolkit-enum-")
                enumStagingRoot!!.resolve("new").resolve(enumTarget.fileName)
            }
            generateLabelEnum(stagedEnum.toString(), labels)
        }

        // UI 的进度回调会检查取消；最后一次回调后仍可能在生成 COCO/枚举期间取消。
        onProgress(completed, total)

        // 备份旧产物再逐项替换；任何提交错误都逆序恢复。提交开始后不再检查取消。
        data class ExportMove(
            val destination: Path,
            val staged: Path,
            val backup: Path,
            var hadOriginal: Boolean = false,
            var installed: Boolean = false,
        )
        val moves = mutableListOf(
            ExportMove(targetImagesDir, stagedImagesDir, stagingRoot.resolve("old-images")),
            ExportMove(cocoTarget, stagedCoco, stagingRoot.resolve("old-coco.json")),
        )
        if (enumTarget != null && stagedEnum != null && !enumInImages) {
            moves.add(ExportMove(enumTarget, stagedEnum, enumStagingRoot!!.resolve("old").resolve(enumTarget.fileName)))
        }
        try {
            for (move in moves) {
                move.destination.parent?.let { Files.createDirectories(it) }
                if (Files.exists(move.destination)) {
                    Files.createDirectories(move.backup.parent)
                    Files.move(move.destination, move.backup)
                    move.hadOriginal = true
                }
                Files.move(move.staged, move.destination)
                move.installed = true
            }
        } catch (error: Throwable) {
            val rollbackErrors = mutableListOf<String>()
            for (move in moves.asReversed()) {
                try {
                    if (move.installed) Files.move(move.destination, move.staged)
                    if (move.hadOriginal) Files.move(move.backup, move.destination)
                } catch (rollbackError: Throwable) {
                    rollbackErrors.add(rollbackError.toString())
                }
            }
            if (rollbackErrors.isNotEmpty()) {
                preserveStaging = true
                val backupLocations = listOfNotNull(stagingRoot, enumStagingRoot).joinToString(", ")
                throw IllegalStateException(
                    "Export failed and rollback was incomplete; backups remain at $backupLocations: ${rollbackErrors.joinToString("; ")}",
                    error,
                )
            }
            throw error
        }
        } finally {
            // 回滚不完整时保留备份以便人工恢复；清理失败不改变已完成的提交结果。
            if (!preserveStaging) {
                stagingRoot.toFile().deleteRecursively()
                enumStagingRoot?.toFile()?.deleteRecursively()
            }
        }
    }

    /**
     * 由分类标签生成 Python 枚举文件（对齐 VSCode 版 generateLabelEnum）。
     *
     * 标签来自用户输入的分类名，会**直接拼进 Python 源码**，所以两处都必须处理：
     *
     * 1. **值**走 [pythonStringLiteral] 显式转义（不能直接用 Jackson 的 writeValueAsString ——
     *    JSON 与 Python 的单引号转义规则不重合）；
     * 2. **成员名**走 [memberNameFor] 规范化成合法标识符。分类名带空格 / 连字符 / 中文时，
     *    `    洗手 台 = '...'` 这种行会让整个文件 `SyntaxError`，用户拿到的枚举文件直接不能用。
     */
    fun generateLabelEnum(filePath: String, labels: List<String>) {
        val file = File(filePath)
        file.parentFile?.mkdirs()
        // 类名走取值链：**个人偏好（IDE 设置）> 项目约定 `labelEnum.name` > 文件名推导**。
        // 解耦的意义：文件可以叫 feature_labels.py，而类叫 FeatureList。
        // （旧写法只有 basename 一条路，想叫 FeatureList 就必须把文件命名成 FeatureList.py。）
        //
        // ⚠️ 个人覆盖会改掉写进源码的类名，而项目的代码按名字 import。那道闸不在这一层：
        // 覆盖前的确认在 `ui/TemplateAssetToolWindowFactory`（UI 层）做，见 [LabelEnumGuard]。
        val rawClassName = OkScriptToolkitSettings.getInstance(project).labelEnumName(filePath).value
        // 类名同样进源码：非法标识符直接退回一个安全的默认名，而不是生成坏文件。
        // 走 `writableClassName` 而**不是**内联一个正则 —— 面板的写入前校验要用**同一个**函数
        // 算"将要写入的类名"，两处各写一遍会让警告内容与实际写进去的东西不符。
        val className = LabelEnumGuard.writableClassName(rawClassName)
        val content = buildString {
            append("from enum import Enum\n\n\n")
            append("class ").append(className).append("(str, Enum):\n")
            if (labels.isEmpty()) {
                // 空枚举的类体不能什么都没有 —— 否则是 IndentationError: expected an indented block
                append("    pass\n")
            }
            val usedMemberNames = mutableSetOf<String>()
            for (label in labels) {
                val baseName = memberNameFor(label)
                var memberName = baseName
                var suffix = 2
                while (memberName in usedMemberNames) memberName = "${baseName}_${suffix++}"
                usedMemberNames.add(memberName)
                append("    ").append(memberName).append(" = ").append(pythonStringLiteral(label)).append('\n')
            }
        }
        file.writeText(content, Charsets.UTF_8)
    }


}

/** 枚举成员只使用 ASCII 字母开头，避免 Enum 将前导下划线解释为私有或保留名。 */
private val PYTHON_ENUM_MEMBER = Regex("[A-Za-z][A-Za-z0-9_]*")

/**
 * 把一段用户输入变成合法的 Python 单引号字符串字面量（含引号）。
 *
 * 为什么不用 Jackson 的 `writeValueAsString`：JSON 与 Python 的字符串转义规则**不完全重合**。
 * JSON 里单引号无需转义，而 Python 单引号字面量里 `\'` 是必需的。所以这里逐字符显式转义。
 *
 * 与 VSCode 版 `templateAssetData.ts` 的 `pythonStringLiteral` 是同一份规则，
 * 改动需两端同步（本仓库的"跨仓一致性"约定）。
 */
internal fun pythonStringLiteral(value: String): String {
    val out = StringBuilder(value.length + 2)
    out.append('\'')
    for (ch in value) {
        when (ch) {
            '\\' -> out.append("\\\\")
            '\'' -> out.append("\\'")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            else -> if (ch.code < 0x20 || ch.code == 0x7f) {
                // 控制字符（\x00-\x1f 与 \x7f）一律走 \xNN，避免源文件里出现裸控制字符
                out.append("\\x").append(ch.code.toString(16).padStart(2, '0'))
            } else {
                out.append(ch)
            }
        }
    }
    out.append('\'')
    return out.toString()
}

/**
 * 把分类名转成合法的 Python 枚举成员名。
 *
 * Python 标识符不允许空格、连字符、数字开头；非 ASCII 中文虽然**语法上**能当标识符，
 * 但枚举成员会被 `LabelEnum.洗手台` 这样引用，中文成员名在大多数工具链里都是坑，
 * 因此统一规范化成 `cat_<hex>` 形式的纯 ASCII 名。
 *
 * **成员名与值相互独立**：值保留原始标签（[pythonStringLiteral]），
 * 所以 `LabelEnum.cat_6d17_53f0.value == '洗手台'` 依然成立 —— 规范化不丢信息。
 */
internal fun memberNameFor(label: String): String {
    // 逐码点替换，避免非 BMP 字符在 JS 与 JVM 上分别变成两个和一个下划线。
    val codepoints = label.codePoints().toArray()
    val ascii = buildString {
        for (codepoint in codepoints) {
            append(
                if (codepoint in 65..90 || codepoint in 97..122 || codepoint in 48..57 || codepoint == 95)
                    codepoint.toChar() else '_'
            )
        }
    }
    if (PYTHON_ENUM_MEMBER.matches(ascii)) {
        // Enum.mro 是内建方法名，不能用作成员名。
        return if (ascii in LabelEnumGuard.PYTHON_KEYWORDS || ascii == "mro") "${ascii}_" else ascii
    }

    // 数字开头、前导下划线（Enum 的私有/保留名）及全非 ASCII 标签统一编码。
    // 保留原始值，编码只影响源码中的成员名。
    val suffix = codepoints.joinToString("_") { it.toString(16) }
    val prefix = if (ascii.firstOrNull()?.isDigit() == true) "n" else "cat"
    val candidate = if (suffix.isEmpty()) prefix else "${prefix}_$suffix"
    return candidate
}
