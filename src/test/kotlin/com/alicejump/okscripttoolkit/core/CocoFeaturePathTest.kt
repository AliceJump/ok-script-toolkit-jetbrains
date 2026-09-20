package com.alicejump.okscripttoolkit.core

import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 运行时模板库路径解析的测试（`CocoFeaturePath`）。
 *
 * 背景：ok 框架加载的是 `config.py` 的 `template_matching.coco_feature_json` 指向的那份
 * COCO，而插件此前**硬编码探测** `assets/coco_annotations.json` /
 * `ok_tasks/assets/coco_annotations.json`。实测 6 个 ok 系项目全都声明了它，
 * 其中 **ok-infinity-nikki 声明的是 `assets/coco_detection.json`** ——
 * 旧代码在它那儿一个候选都探不到，模板库直接是空的。
 *
 * 这里钉住两条容易改坏的不变量：
 * 1. **优先级**：项目约定 > config.py > 探测候选（反了就是"项目说了不算"）；
 * 2. **首选可用时只用首选** —— 否则库搬到别处之后，旧位置的库会和新库一起被加载，
 *    同一个 feature 名出现两份（先到的那份胜出，静默）。
 *
 * 纯对象（不碰真实文件系统：存在性由调用方注入），符合本仓库 `src/test` 的既有约定。
 */
class CocoFeaturePathTest {

    private val ROOT = "X:/proj"
    private val A: Path = Paths.get(ROOT, "assets", "coco_annotations.json")
    private val B: Path = Paths.get(ROOT, "ok_tasks", "assets", "coco_annotations.json")

    private fun plan(declared: String? = null, fromConfigPy: String? = null) =
        CocoFeaturePath.plan(ROOT, declared, fromConfigPy)

    // ── 1. 三层的来源 ───────────────────────────────────────────────

    @Test
    fun `the layer names the winning source`() {
        val bare = plan()
        assertEquals(CocoFeaturePath.Layer.PROBE, bare.layer, "都没有声明时来源是「探测候选」（= 改动前的行为）")
        assertNull(bare.preferred, "没有首选")
        assertEquals(listOf(A, B), bare.probeCandidates, "探测候选就是那两个惯例位置，顺序不变")

        val declared = plan(declared = "custom/coco.json")
        assertEquals(CocoFeaturePath.Layer.CONVENTION, declared.layer, "项目约定声明生效")
        assertEquals(
            Paths.get(ROOT, "custom", "coco.json"),
            declared.preferred,
            "**声明值按相对项目根绝对化**",
        )
        assertEquals(listOf(A, B), declared.probeCandidates, "声明后探测候选仍然保留（首选不存在时要退回它）")

        val fromPy = plan(fromConfigPy = "assets/coco_detection.json")
        assertEquals(CocoFeaturePath.Layer.CONFIG_PY, fromPy.layer, "只有 config.py 声明时来源是 configPy")
        assertEquals(
            Paths.get(ROOT, "assets", "coco_detection.json"),
            fromPy.preferred,
            "**config.py 的值也按相对项目根绝对化** —— ok-infinity-nikki 就是这一种",
        )
    }

    @Test
    fun `the project convention beats config py`() {
        val both = plan(declared = "custom/coco.json", fromConfigPy = "assets/coco_detection.json")
        assertEquals(
            CocoFeaturePath.Layer.CONVENTION,
            both.layer,
            "**项目约定压过 config.py** —— 与全局取值链一致（项目文件在上、config.py 事实在下）",
        )
        assertEquals(Paths.get(ROOT, "custom", "coco.json"), both.preferred)
    }

    // ── 2. 边界写法 ─────────────────────────────────────────────────

    @Test
    fun `blank declarations count as not declared`() {
        assertEquals(CocoFeaturePath.Layer.PROBE, plan(declared = "   ").layer, "全空白的声明等同于没写")
        assertEquals(CocoFeaturePath.Layer.PROBE, plan(declared = "", fromConfigPy = "  ").layer, "两侧都是空白时退回探测")
        assertEquals(
            CocoFeaturePath.Layer.CONFIG_PY,
            plan(declared = "   ", fromConfigPy = "assets/x.json").layer,
            "**声明侧空白不会把 config.py 也一起挡掉** —— 空白只让它自己那一层缺席，链继续往下走",
        )
    }

    @Test
    fun `an absolute path from config py keeps its shape`() {
        // config.py 的值通常是 `os.path.join(项目根, ...)` 拼出来的，两种形态都要原样认。
        // 比 `Path` 而不是 `toString()`：`Path.toString()` 用的是平台分隔符
        // （Windows 上 `D:/x` 会渲染成 `D:\x`），那只是显示差异，指向的是同一个文件。
        val windows = plan(fromConfigPy = "D:/elsewhere/coco.json")
        assertEquals(
            Paths.get("D:/elsewhere/coco.json"),
            windows.preferred,
            "**Windows 绝对路径原样保留**（不做任何改写，改了就与项目声明的不是同一个文件）",
        )

        // ⚠️ 平台差异：`Paths.get("/srv/x").isAbsolute` 在 Windows 上是 **false**
        // （Windows 认为没有盘符/UNC 的 `/x` 只是 "rooted"，不算 absolute），在 POSIX 上是 true。
        // POSIX 绝对路径本来也只在那边的平台上才有意义，所以这条只在非 Windows 上跑 ——
        // 否则测的是 JDK 的平台差异，不是我们的逻辑。
        if (File.separatorChar != '\\') {
            val posix = plan(fromConfigPy = "/srv/coco.json")
            assertEquals(Paths.get("/srv/coco.json"), posix.preferred, "**POSIX 绝对路径的开头斜杠必须保住**")
        }
    }

    // ── 3. 实际要扫描哪些文件 ───────────────────────────────────────

    @Test
    fun `a usable preferred file is used alone`() {
        val declared = plan(declared = "custom/coco.json")
        val onlyPreferred = CocoFeaturePath.effectiveFiles(declared) { it == declared.preferred || it == A }
        assertEquals(
            listOf(declared.preferred),
            onlyPreferred,
            "**首选可用时只用首选** —— 不能把惯例位置的旧库也带上，否则同名 feature 出现两份",
        )
    }

    @Test
    fun `a missing preferred file falls back to the probe candidates`() {
        val declared = plan(declared = "custom/coco.json")
        assertEquals(
            listOf(A),
            CocoFeaturePath.effectiveFiles(declared) { it == A },
            "首选不存在时退回**存在**的探测候选（config.py 里声明的文件可能还没生成）",
        )
        assertEquals(
            emptyList(),
            CocoFeaturePath.effectiveFiles(declared) { false },
            "一个都不存在时返回空列表（不抛异常、也不编造路径）",
        )
    }

    @Test
    fun `with no declaration every existing conventional candidate is loaded`() {
        val bare = plan()
        assertEquals(
            listOf(A, B),
            CocoFeaturePath.effectiveFiles(bare) { true },
            "没声明时两个惯例位置都存在就都加载（与改动前完全一致）",
        )
        assertEquals(
            listOf(B),
            CocoFeaturePath.effectiveFiles(bare) { it == B },
            "没声明时只存在第二个候选，就只加载它",
        )
    }

    // ── 4. 监听与变更归属用的相对路径 ───────────────────────────────

    @Test
    fun `relPaths covers the preferred file and every probe candidate`() {
        val declared = plan(declared = "custom/coco.json")
        assertEquals(
            listOf("custom/coco.json", "assets/coco_annotations.json", "ok_tasks/assets/coco_annotations.json"),
            CocoFeaturePath.relPaths(declared, ROOT),
            "**首选与探测候选都要覆盖** —— 监听不能按存在性过滤，否则第一次生成库时不会触发刷新",
        )
        assertEquals(
            listOf("assets/coco_annotations.json", "ok_tasks/assets/coco_annotations.json"),
            CocoFeaturePath.relPaths(plan(), ROOT),
            "没声明时就是两个惯例位置",
        )
        assertEquals(
            listOf("assets/coco_annotations.json", "ok_tasks/assets/coco_annotations.json"),
            CocoFeaturePath.relPaths(plan(fromConfigPy = "D:/outside/coco.json"), ROOT),
            "**项目外的首选被剔除**（`relativize` 出来的 `../` 不能当监听目标），" +
                "但两个惯例候选仍在 —— 首选不在项目内时它们才是真正会被读到的文件",
        )
    }

    // ── 5. 破坏性对照 ───────────────────────────────────────────────

    /**
     * 对照：把"首选可用时只用首选"改成"首选 + 探测候选"，演示库搬家后两份一起被加载。
     * 用纯对象构造出那个错误形态，证明上面那条断言确实在约束它。
     */
    @Test
    fun `regression guard - also loading the probe candidates duplicates features`() {
        val declared = plan(declared = "custom/coco.json")
        val preferred = declared.preferred!!
        val exists: (Path) -> Boolean = { it == preferred || it == A }
        assertEquals(1, CocoFeaturePath.effectiveFiles(declared, exists).size, "真实现：只加载首选")

        val wrong = listOf(preferred) + declared.probeCandidates.filter(exists)
        assertEquals(
            2,
            wrong.size,
            "对照：把探测候选也带上就变成两份 —— 同名 feature 会静默取先到的那份",
        )
        assertTrue(wrong.size != CocoFeaturePath.effectiveFiles(declared, exists).size, "对照与真实现确实不同")
    }

    /**
     * 对照：优先级反转（config.py 压过项目约定）—— 项目在约定文件里指定的库会被无视。
     */
    @Test
    fun `regression guard - reversing the precedence ignores the project declaration`() {
        val truth = plan(declared = "custom/coco.json", fromConfigPy = "assets/coco_detection.json")
        assertEquals(CocoFeaturePath.Layer.CONVENTION, truth.layer, "真实现：项目约定胜出")

        val reversed = CocoFeaturePath.plan(ROOT, null, "assets/coco_detection.json")
        assertEquals(
            Paths.get(ROOT, "assets", "coco_detection.json"),
            reversed.preferred,
            "对照：拿掉项目约定分支后 config.py 生效 —— 与真实现指向的不是同一个文件",
        )
        assertTrue(reversed.preferred != truth.preferred, "对照：两者的首选确实不同")
    }
}
