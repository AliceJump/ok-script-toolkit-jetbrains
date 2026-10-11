package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.alicejump.okscripttoolkit.core.OkDataChangeListener
import com.alicejump.okscripttoolkit.core.OkDataChangeService
import com.alicejump.okscripttoolkit.core.OkProjectDataService
import com.alicejump.okscripttoolkit.core.OkProjectDetection
import com.alicejump.okscripttoolkit.core.TemplateAssetDataService
import com.alicejump.okscripttoolkit.settings.OkScriptToolkitSettings
import com.alicejump.okscripttoolkit.tasklauncher.TaskLauncherPanel
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.InlineBanner
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.dsl.builder.SegmentedButton
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/** 合并后的唯一工具窗口 id（stripe 标题见 `toolwindow.stripe.ok-script`）。 */
internal const val OK_SCRIPT_TOOL_WINDOW_ID = "ok-script"

/** content tab：运行（任务树 + 下半区参数）/ 资源（收件箱 → 草稿 → 已发布）。 */
internal enum class OkTab(val titleKey: String) { RUN("okscript.tab.run"), RESOURCES("okscript.tab.resources") }

/** 资源 tab 内的三段，对齐 VS Code PR #41 的 inbox → draft → published。 */
internal enum class ResourceSection(val titleKey: String) {
    INBOX("okscript.resources.inbox"), DRAFT("okscript.resources.draft"), PUBLISHED("okscript.resources.published")
}

private val OK_TAB_KEY = Key.create<OkTab>("okScriptToolkit.tab")
internal val OK_RESOURCES_PANEL_KEY = Key.create<OkResourcesPanel>("okScriptToolkit.resourcesPanel")
internal val OK_TASK_PANEL_KEY = Key.create<TaskLauncherPanel>("okScriptToolkit.taskPanel")

class OkScriptToolWindowFactory : ToolWindowFactory, DumbAware {
    override suspend fun isApplicableAsync(project: Project): Boolean = OkProjectDetection.isOkScriptProject(project)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val factory = ContentFactory.getInstance()
        val tasks = TaskLauncherPanel(project)
        val run = factory.createContent(tasks.mainPanel, OkScriptToolkitBundle.message(OkTab.RUN.titleKey), false).apply {
            putUserData(OK_TAB_KEY, OkTab.RUN)
            putUserData(OK_TASK_PANEL_KEY, tasks)
            setDisposer { tasks.onDispose() }
            isCloseable = false
        }
        val resources = OkResourcesPanel(project)
        val res = factory.createContent(resources, OkScriptToolkitBundle.message(OkTab.RESOURCES.titleKey), false).apply {
            putUserData(OK_TAB_KEY, OkTab.RESOURCES)
            putUserData(OK_RESOURCES_PANEL_KEY, resources)
            setDisposer(resources)
            isCloseable = false
        }
        toolWindow.contentManager.addContent(run)
        toolWindow.contentManager.addContent(res)
    }
}

/** 统一导航入口：所有「打开 xx」动作、旧 id 别名、Getting Started 卡片都走这里。 */
internal object OkScriptToolWindows {
    fun show(project: Project, tab: OkTab, section: ResourceSection? = null, then: (Content) -> Unit = {}) {
        val window = ToolWindowManager.getInstance(project).getToolWindow(OK_SCRIPT_TOOL_WINDOW_ID) ?: return
        window.show {
            val content = window.contentManager.contents.firstOrNull { it.getUserData(OK_TAB_KEY) == tab } ?: return@show
            window.contentManager.setSelectedContent(content, true)
            if (section != null) content.getUserData(OK_RESOURCES_PANEL_KEY)?.select(section)
            then(content)
        }
    }

    fun withResources(project: Project, section: ResourceSection, action: (OkResourcesPanel) -> Unit) =
        show(project, OkTab.RESOURCES, section) { content -> content.getUserData(OK_RESOURCES_PANEL_KEY)?.let(action) }

    fun withTasks(project: Project, action: (TaskLauncherPanel) -> Unit) =
        show(project, OkTab.RUN) { content -> content.getUserData(OK_TASK_PANEL_KEY)?.let(action) }

    /** 旧工具窗口 id → 新位置（保留一个版本，见 [LegacyToolWindowAlias]）。 */
    val LEGACY_IDS: Map<String, Pair<OkTab, ResourceSection?>> = mapOf(
        "ok-script Tasks" to (OkTab.RUN to null),
        "ok-script Temp Shots" to (OkTab.RESOURCES to ResourceSection.INBOX),
        UNIFIED_ANNOTATION_TOOL_WINDOW_ID to (OkTab.RESOURCES to ResourceSection.DRAFT),
        UNIFIED_RESOURCE_PREVIEW_TOOL_WINDOW_ID to (OkTab.RESOURCES to ResourceSection.PUBLISHED),
    )

    fun showLegacy(project: Project, legacyId: String) {
        val (tab, section) = LEGACY_IDS[legacyId] ?: return
        show(project, tab, section)
    }
}

/**
 * 旧工具窗口 id 的重定向别名（保留一个版本）：不显示 stripe 按钮；
 * 被用户快捷键 / 其他插件 / 旧布局激活时，转到 ok-script 对应 tab 并隐藏自己。
 */
abstract class LegacyToolWindowAlias(private val legacyId: String) : ToolWindowFactory, DumbAware {
    override suspend fun isApplicableAsync(project: Project): Boolean = OkProjectDetection.isOkScriptProject(project)

    override fun init(toolWindow: ToolWindow) {
        @Suppress("DEPRECATION")
        toolWindow.isShowStripeButton = false
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val note = JBLabel(OkScriptToolkitBundle.message("okscript.legacyMoved"), SwingConstants.CENTER)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(note, "", false))
        redirect(project, toolWindow)
        project.messageBus.connect(toolWindow.disposable).subscribe(
            com.intellij.openapi.wm.ex.ToolWindowManagerListener.TOPIC,
            object : com.intellij.openapi.wm.ex.ToolWindowManagerListener {
                override fun toolWindowShown(shown: ToolWindow) { if (shown === toolWindow) redirect(project, toolWindow) }
            },
        )
    }

    private fun redirect(project: Project, toolWindow: ToolWindow) {
        ApplicationManager.getApplication().invokeLater({
            toolWindow.hide()
            OkScriptToolWindows.showLegacy(project, legacyId)
        }, project.disposed)
    }
}

class LegacyTasksToolWindowAlias : LegacyToolWindowAlias("ok-script Tasks")
class LegacyTempShotsToolWindowAlias : LegacyToolWindowAlias("ok-script Temp Shots")
class LegacyAnnotationToolWindowAlias : LegacyToolWindowAlias(UNIFIED_ANNOTATION_TOOL_WINDOW_ID)
class LegacyPreviewToolWindowAlias : LegacyToolWindowAlias(UNIFIED_RESOURCE_PREVIEW_TOOL_WINDOW_ID)

/**
 * 资源 tab：合并原「临时截图 / 标注管理 / 资源预览」三个工具窗口。
 * 顶部分段按钮切换三段；有未发布草稿时显示 InlineBanner（预览并发布…）。
 * 三个面板原样复用，内部行为不变。
 */
internal class OkResourcesPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val inbox = TempScreenshotPanel(project)
    private val drafts = PublishingAnnotationManagerPanel(project)
    private val published = UnifiedResourcePreview(project)
    private val cards = CardLayout()
    private val body = JPanel(cards)
    private val bannerHost = JPanel(BorderLayout())
    private lateinit var segments: SegmentedButton<ResourceSection>
    @Volatile private var disposed = false
    var current: ResourceSection = ResourceSection.DRAFT
        private set
    /** 最近一次统计的「只在草稿里」的模板名（测试 / 横幅使用）。 */
    @Volatile var unpublishedNames: List<String> = emptyList()
        private set

    init {
        Disposer.register(this, inbox)
        Disposer.register(this, drafts)
        Disposer.register(this, published)
        body.add(inbox.mainPanel, ResourceSection.INBOX.name)
        body.add(drafts, ResourceSection.DRAFT.name)
        body.add(published.component, ResourceSection.PUBLISHED.name)
        val header = panel {
            row {
                segments = segmentedButton(ResourceSection.entries) { section -> text = OkScriptToolkitBundle.message(section.titleKey) }
                    .whenItemSelected(this@OkResourcesPanel) { section -> showSection(section) }
            }
        }.apply { border = JBUI.Borders.empty(2, 8, 0, 8) }
        val north = JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(bannerHost, BorderLayout.SOUTH)
        }
        add(north, BorderLayout.NORTH)
        add(body, BorderLayout.CENTER)
        segments.selectedItem = current
        showSection(current)
        project.service<OkDataChangeService>()
        project.messageBus.connect(this).subscribe(OkDataChangeService.TOPIC, OkDataChangeListener { refreshBanner() })
        refreshBanner()
    }

    fun select(section: ResourceSection) {
        if (::segments.isInitialized && segments.selectedItem != section) segments.selectedItem = section
        showSection(section)
    }

    private fun showSection(section: ResourceSection) {
        current = section
        cards.show(body, section.name)
    }

    /** 截到收件箱（Ctrl+Alt+Shift+K 的落点）。 */
    fun captureToInbox() { select(ResourceSection.INBOX); inbox.captureNow() }

    /** 在已发布里按模板名定位（悬停链接「在资源中定位」）。 */
    fun reveal(name: String): Boolean { select(ResourceSection.PUBLISHED); return published.reveal(name) }

    fun publish() { select(ResourceSection.DRAFT); drafts.publishNow() }

    /** 旧「截图并打开标注管理」：截图直接登记进草稿。 */
    fun screenshotToDrafts() { select(ResourceSection.DRAFT); drafts.screenshotNow() }

    private fun refreshBanner() {
        if (disposed || project.isDisposed) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val names = runCatching { unpublishedTemplateNames(project) }.getOrDefault(emptyList())
            ApplicationManager.getApplication().invokeLater({
                if (disposed) return@invokeLater
                unpublishedNames = names
                bannerHost.removeAll()
                if (names.isNotEmpty()) bannerHost.add(unpublishedBanner(names.size))
                bannerHost.revalidate(); bannerHost.repaint()
            }, project.disposed)
        }
    }

    private fun unpublishedBanner(count: Int): JComponent =
        InlineBanner(OkScriptToolkitBundle.message("okscript.resources.unpublished", count), EditorNotificationPanel.Status.Warning).apply {
            showCloseButton(false)
            addAction(OkScriptToolkitBundle.message("okscript.resources.previewPublish")) { publish() }
            border = JBUI.Borders.empty(4, 8)
        }

    override fun dispose() { disposed = true }
}

/** 草稿里有、已发布里没有的模板名（按名字比较；发布会重新裁切装箱，坐标不可比）。 */
internal fun unpublishedTemplateNames(project: Project): List<String> {
    val root = project.service<OkProjectDataService>().rootPath() ?: return emptyList()
    val data = project.service<TemplateAssetDataService>()
    val coco = data.load(root.toString(), OkScriptToolkitSettings.getInstance(project).okTemplatesDirectory())
    if (data.readErrors.isNotEmpty()) return emptyList()
    val published = project.service<OkProjectDataService>().features().map { it.name }
    return PublishPreview.draftOnly(PublishPreview.draftNames(coco), published)
}
