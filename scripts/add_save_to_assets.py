# -*- coding: utf-8 -*-
"""向 TemplateAssetDataService 注入 saveToAssets / readImageHeaderSize / generateLabelEnum。"""
import io

p = 'src/main/kotlin/com/alicejump/okscripttoolkit/core/TemplateAssetDataService.kt'
with io.open(p, encoding='utf-8', newline='') as f:
    src = f.read()

old = '    fun readImageDimensions(file: File): Pair<Int, Int> {'

new = '''    /** 只读图片头取尺寸（ImageReader 不解码像素），失败返回 null。 */
    fun readImageHeaderSize(file: File): Pair<Int, Int>? {
        return try {
            val iis = javax.imageio.ImageIO.createImageInputStream(file)
            val readers = javax.imageio.ImageIO.getImageReaders(iis)
            if (!readers.hasNext()) { iis.close(); return null }
            val reader = readers.next()
            reader.input = iis
            try {
                val w = reader.getWidth(0)
                val h = reader.getHeight(0)
                w to h
            } finally {
                reader.dispose()
                iis.close()
            }
        } catch (_: Exception) { null }
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
        val targetImagesDir = Paths.get(targetFolder, "images")

        // 清空目标目录中的旧图片（重新生成前清理）
        if (Files.isDirectory(targetImagesDir)) {
            Files.list(targetImagesDir).use { stream ->
                stream.forEach { runCatching { Files.deleteIfExists(it) } }
            }
        }
        Files.createDirectories(targetImagesDir)

        // 1. 只处理有标注的图片
        val annotatedImages = cocoData.images.filter { img ->
            cocoData.annotations.any { it.imageId == img.id }
        }

        // 2. 按原始尺寸分组（头读尺寸优先，失败回退 COCO 记录值）
        data class AnnotatedEntry(val img: CocoImage, val ann: CocoAnnotation)
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
                pageList.add(Page(W, H, entries.filter { it.img.id in ids }.toMutableList()))
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

            val outPath = targetImagesDir.resolve("${pageIndex + 1}.png")
            Files.createDirectories(outPath.parent)
            ImageIO.write(canvas, "png", outPath.toFile())
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

        val cocoTarget = Paths.get(targetFolder, "coco_annotations.json").toFile()
        cocoTarget.parentFile?.mkdirs()
        JSON.writerWithDefaultPrettyPrinter().writeValue(cocoTarget, serializeCoco(croppedCoco))

        if (generateEnum) {
            val labels = croppedCoco.categories.map { it.name }.sorted()
            val enumFile = enumPath ?: Paths.get(targetFolder, "LabelEnum.py").toString()
            generateLabelEnum(enumFile, labels)
        }
    }

    /** 由分类标签生成 Python 枚举文件（对齐 VSCode 版 generateLabelEnum）。 */
    fun generateLabelEnum(filePath: String, labels: List<String>) {
        val file = File(filePath)
        file.parentFile?.mkdirs()
        val className = file.nameWithoutExtension
        val content = buildString {
            append("from enum import Enum\\n\\n\\n")
            append("class ").append(className).append("(str, Enum):\\n")
            for (label in labels) {
                append("    ").append(label).append(" = '").append(label).append("'\\n")
            }
        }
        file.writeText(content, Charsets.UTF_8)
    }

    fun readImageDimensions(file: File): Pair<Int, Int> {'''

assert old in src, 'anchor not found'
src = src.replace(old, new, 1)

# PackedPage/Page 数据类放在文件级（data class 不能在实例方法内部定义顶层使用？可以在函数内但放到类外更干净——放 companion 后的类级区域）
src = src.replace('''// ── Service ──────────────────────────────────────────────────────''',
'''// ── saveToAssets 内部结构 ────────────────────────────────────────

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

// ── Service ──────────────────────────────────────────────────────''')

with io.open(p, 'w', encoding='utf-8', newline='\n') as f:
    f.write(src)
print('saveToAssets injected')
