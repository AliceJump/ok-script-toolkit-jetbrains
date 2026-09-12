package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.alicejump.okscripttoolkit.core.CharacterDataMutations
import com.alicejump.okscripttoolkit.core.CharacterDataPaths
import com.alicejump.okscripttoolkit.core.CharacterDataService
import com.alicejump.okscripttoolkit.core.CharacterDataSettings
import com.alicejump.okscripttoolkit.core.CharacterEffectRef
import com.alicejump.okscripttoolkit.core.CharacterDataSources
import com.alicejump.okscripttoolkit.core.CharacterEffectView
import com.alicejump.okscripttoolkit.core.CharacterEnhancementView
import com.alicejump.okscripttoolkit.core.CharacterIssue
import com.alicejump.okscripttoolkit.core.CharacterManagerSnapshot
import com.alicejump.okscripttoolkit.core.CharacterSkillView
import com.alicejump.okscripttoolkit.core.CharacterView
import com.alicejump.okscripttoolkit.core.EffectDataMutations
import com.alicejump.okscripttoolkit.core.FeatureTemplate
import com.alicejump.okscripttoolkit.core.IssueSeverity
import com.alicejump.okscripttoolkit.core.OkDataChangeListener
import com.alicejump.okscripttoolkit.core.OkDataChangeService
import com.alicejump.okscripttoolkit.core.OkProjectDataService
import com.alicejump.okscripttoolkit.core.SourceKind
import com.alicejump.okscripttoolkit.settings.OkScriptToolkitSettings
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Splitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.io.File
import java.util.concurrent.CompletableFuture
import javax.imageio.ImageIO
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableRowSorter

private fun msg(key: String, vararg params: Any): String =
    if (params.isEmpty()) OkScriptToolkitBundle.message(key) else OkScriptToolkitBundle.message(key, *params)

/**
 * 角色管理主面板。对齐 VSCode 版 Webview：
 * 顶部搜索 + 摘要，下面四个标签页（角色 / 效果 / 本地化 / 问题），
 * 角色页左侧带过滤器的列表、右侧详情，技能与强化组可直接增删改。
 */
class CharacterManagerPanel(private val project: Project) : com.intellij.openapi.Disposable {

    val mainPanel: JPanel = JPanel(BorderLayout())

    private val searchField = SearchTextField(false)
    private val summaryLabel = JBLabel()
    private val statusLabel = JBLabel()
    private val projectLabel = JBLabel()
    private val tabs = JTabbedPane()

    /** 效果页在 tabs 中的下标（focusEffect 用）。 */
    private val EFFECTS_TAB_INDEX = 1

    // ── 角色页 ────────────────────────────────────────────────
    private val starBox = JComboBox<String>()
    private val elementBox = JComboBox<String>()
    private val professionBox = JComboBox<String>()
    private val skillTypeBox = JComboBox<String>()
    private val enhancementOnlyBox = JCheckBox(msg("characterManager.filter.enhancementOnly"))
    private val issueOnlyBox = JCheckBox(msg("characterManager.filter.issueOnly"))
    private val characterModel = DefaultListModel<CharacterView>()
    private val characterList = JBList(characterModel)
    private val characterCountLabel = JBLabel()
    private var detailInner = JPanel(BorderLayout())

    // ── 效果页 ────────────────────────────────────────────────
    private val effectSearch = SearchTextField(false)
    private val effectCategoryBox = JComboBox<String>()
    private val effectUsageBox = JComboBox<EffectUsageFilter>()
    private val effectModel = DefaultListModel<CharacterEffectView>()
    private val effectList = JBList(effectModel)
    private val effectCountLabel = JBLabel()

    // ── 本地化 / 问题页 ───────────────────────────────────────
    private val localeModel = DefaultTableModel()
    private val localeTable = JTable(localeModel)
    private val issueSearch = SearchTextField(false)
    private val issueSeverityBox = JComboBox<IssueSeverity?>()
    private val issueCountLabel = JBLabel()
    private val issueModel = DefaultTableModel(
        arrayOf(
            msg("characterManager.column.severity"),
            msg("characterManager.column.code"),
            msg("characterManager.column.message"),
        ),
        0,
    )
    private val issueTable = JTable(issueModel)

    private var snapshot: CharacterManagerSnapshot? = null
    private var sources: CharacterDataSources? = null
    private var paths: CharacterDataPaths? = null
    private val avatars = mutableMapOf<String, Icon?>()
    private var selectedCharacterId: String? = null

    val preferredFocusedComponent: JComponent get() = searchField

    init {
        buildHeader()
        buildCharactersTab()
        buildEffectsTab()
        buildLocalesTab()
        buildIssuesTab()
        mainPanel.add(tabs, BorderLayout.CENTER)

        project.messageBus.connect(this).subscribe(OkDataChangeService.TOPIC, OkDataChangeListener { loadData() })
        loadData()
    }

    // ══ 构建 UI ═══════════════════════════════════════════════

    private fun buildHeader() {
        val refresh = ToolbarAction(AllIcons.Actions.Refresh, msg("characterManager.refresh")) { loadData() }
        val toolbar = com.intellij.openapi.actionSystem.ActionManager.getInstance()
            .createActionToolbar("ok-script-characters", com.intellij.openapi.actionSystem.DefaultActionGroup(refresh), true)
        toolbar.targetComponent = mainPanel

        searchField.textEditor.emptyText.text = msg("characterManager.search")
        searchField.addDocumentListener(documentListener { applyFilters() })

        val top = JPanel(BorderLayout(8, 0)).apply { border = JBUI.Borders.empty(6, 8) }
        top.add(searchField, BorderLayout.CENTER)
        top.add(toolbar.component, BorderLayout.EAST)

        val info = JPanel(BorderLayout(8, 0)).apply { border = JBUI.Borders.empty(0, 8, 6, 8) }
        projectLabel.font = JBUI.Fonts.smallFont()
        projectLabel.foreground = javax.swing.UIManager.getColor("Label.disabledForeground")
        info.add(summaryLabel, BorderLayout.CENTER)
        info.add(projectLabel, BorderLayout.EAST)

        val bottom = JPanel(BorderLayout()).apply { border = JBUI.Borders.empty(0, 8, 4, 8) }
        bottom.add(statusLabel, BorderLayout.WEST)

        val header = JPanel(BorderLayout())
        header.add(top, BorderLayout.NORTH)
        header.add(info, BorderLayout.CENTER)
        header.add(bottom, BorderLayout.SOUTH)
        mainPanel.add(header, BorderLayout.NORTH)
    }

    private fun buildCharactersTab() {
        characterList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        characterList.cellRenderer = CharacterCellRenderer()
        characterList.addListSelectionListener {
            val value = characterList.selectedValue ?: return@addListSelectionListener
            selectedCharacterId = value.characterId
            showCharacterDetail(value)
        }

        for (box in listOf(starBox, elementBox, professionBox, skillTypeBox)) {
            box.addActionListener { if (box.selectedItem is String) applyFilters() }
        }
        enhancementOnlyBox.addActionListener { applyFilters() }
        issueOnlyBox.addActionListener { applyFilters() }

        val filters = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(6)
            add(labelled(msg("characterManager.filter.star"), starBox))
            add(labelled(msg("characterManager.filter.element"), elementBox))
            add(labelled(msg("characterManager.filter.profession"), professionBox))
            add(labelled(msg("characterManager.filter.skillType"), skillTypeBox))
            add(enhancementOnlyBox)
            add(issueOnlyBox)
        }

        characterCountLabel.border = JBUI.Borders.empty(2, 8)
        val listWrap = JPanel(BorderLayout())
        listWrap.add(characterCountLabel, BorderLayout.NORTH)
        listWrap.add(JBScrollPane(characterList), BorderLayout.CENTER)

        val left = JPanel(BorderLayout())
        left.add(filters, BorderLayout.NORTH)
        left.add(listWrap, BorderLayout.CENTER)

        val detailScroll = JBScrollPane(detailInner)
        detailScroll.border = JBUI.Borders.empty(8)

        val splitter = Splitter(false, 0.32f)
        splitter.firstComponent = left
        splitter.secondComponent = detailScroll
        tabs.addTab(msg("characterManager.tab.characters"), splitter)
    }

    private fun buildEffectsTab() {
        effectList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        effectList.cellRenderer = EffectCellRenderer()
        effectList.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount != 2) return
                val value = effectList.selectedValue ?: return
                openEffectsFileAt(value.id)
            }

            override fun mousePressed(e: java.awt.event.MouseEvent) = maybePopup(e)
            override fun mouseReleased(e: java.awt.event.MouseEvent) = maybePopup(e)

            /** 右键菜单：复制效果 ID（VSCode 里是点 ID 直接复制） */
            private fun maybePopup(e: java.awt.event.MouseEvent) {
                if (!e.isPopupTrigger) return
                val index = effectList.locationToIndex(e.point)
                if (index < 0) return
                effectList.selectedIndex = index
                val value = effectList.selectedValue ?: return
                JPopupMenu().apply {
                    add(JMenuItem(msg("characterManager.copyEffectId")).apply {
                        addActionListener { copyText(value.id) }
                    })
                    add(JMenuItem(msg("characterManager.openSource")).apply {
                        addActionListener { openEffectsFileAt(value.id) }
                    })
                }.show(e.component, e.x, e.y)
            }
        })

        effectSearch.textEditor.emptyText.text = msg("characterManager.effect.search")
        effectSearch.addDocumentListener(documentListener { renderEffects() })
        effectCategoryBox.addActionListener { if (effectCategoryBox.selectedItem is String) renderEffects() }
        effectUsageBox.renderer = object : javax.swing.DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
            ): Component {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                text = usageLabel(value as? EffectUsageFilter)
                return this
            }
        }
        for (usage in EffectUsageFilter.entries) effectUsageBox.addItem(usage)
        effectUsageBox.addActionListener { renderEffects() }

        val addCategory = JButton(msg("characterManager.effect.addCategory"))
        addCategory.toolTipText = msg("characterManager.effect.addCategoryHint")
        addCategory.addActionListener { runAddEffectCategory() }
        val addEffect = JButton(msg("characterManager.effect.add"))
        addEffect.toolTipText = msg("characterManager.effect.addHint")
        addEffect.addActionListener { runAddEffect() }

        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            border = JBUI.Borders.empty(6)
            add(effectSearch)
            add(effectCategoryBox)
            add(effectUsageBox)
            add(addCategory)
            add(addEffect)
            add(effectCountLabel)
        }
        val panel = JPanel(BorderLayout())
        panel.add(toolbar, BorderLayout.NORTH)
        panel.add(JBScrollPane(effectList), BorderLayout.CENTER)
        tabs.addTab(msg("characterManager.tab.effects"), panel)
    }

    private fun buildLocalesTab() {
        localeTable.autoResizeMode = JTable.AUTO_RESIZE_OFF
        localeTable.rowSorter = TableRowSorter(localeModel)
        // 双击打开该角色的语言源文件（对齐 VSCode 里点角色名跳 assets/lang/characters.json）
        localeTable.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount != 2) return
                val row = localeTable.selectedRow
                if (row < 0) return
                val id = localeModel.getValueAt(localeTable.convertRowIndexToModel(row), 0) ?: return
                openFileAt(paths?.localeFile ?: return, id.toString())
            }
        })
        val panel = JPanel(BorderLayout())
        panel.add(JBScrollPane(localeTable), BorderLayout.CENTER)
        tabs.addTab(msg("characterManager.tab.locales"), panel)
    }

    private fun buildIssuesTab() {
        issueTable.autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
        issueTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        issueTable.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount != 2) return
                val row = issueTable.selectedRow
                if (row < 0) return
                val snap = snapshot ?: return
                val issues = filteredIssues(snap)
                val modelRow = issueTable.convertRowIndexToModel(row)
                if (modelRow in issues.indices) openIssueSource(issues[modelRow])
            }
        })
        issueSearch.textEditor.emptyText.text = msg("characterManager.issue.search")
        issueSearch.addDocumentListener(documentListener { renderIssues() })
        issueSeverityBox.renderer = object : javax.swing.DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
            ): Component {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                text = severityLabel(value as? IssueSeverity)
                return this
            }
        }
        issueSeverityBox.addItem(null)
        for (severity in IssueSeverity.entries) issueSeverityBox.addItem(severity)
        issueSeverityBox.addActionListener { renderIssues() }

        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            border = JBUI.Borders.empty(6)
            add(issueSearch)
            add(issueSeverityBox)
            add(issueCountLabel)
        }
        val panel = JPanel(BorderLayout())
        panel.add(toolbar, BorderLayout.NORTH)
        panel.add(JBScrollPane(issueTable), BorderLayout.CENTER)
        tabs.addTab(msg("characterManager.tab.issues"), panel)
    }

    // ══ 数据加载 ══════════════════════════════════════════════

    private fun loadData() {
        statusLabel.text = msg("characterManager.loading")
        CompletableFuture.supplyAsync {
            val settings = OkScriptToolkitSettings.getInstance(project)
            val projectDir = settings.characterProjectPath().ifBlank { project.basePath ?: "" }
            val resolved = CharacterDataService.configuredPaths(projectDir, CharacterDataSettings(
                masterFile = settings.characterMasterFile(),
                skillsDirectory = settings.characterSkillsDirectory(),
                localeFile = settings.characterLocaleFile(),
                effectsFile = settings.effectsFile(),
            ))
            if (projectDir.isBlank() || !File(projectDir).isDirectory) {
                return@supplyAsync LoadResult(null, null, resolved, emptyMap(), "")
            }
            val result = CharacterDataService.load(resolved, settings.displayLocale().ifBlank { "zh_CN" })
            val avatarMap = mutableMapOf<String, Icon?>()
            runCatching { Regex(settings.characterAvatarTemplateRegex()) }.getOrNull()?.let { avatarRegex ->
                val gallery = project.service<OkProjectDataService>()
                for (char in result.snapshot.characters) {
                    val candidate = char.master?.en ?: char.characterId
                    gallery.features().firstOrNull { tpl ->
                        val stripped = avatarRegex.find(tpl.name)?.let { tpl.name.replaceFirst(it.value, "") } ?: tpl.name
                        stripped.equals(candidate, ignoreCase = true) || tpl.name.equals(candidate, ignoreCase = true)
                    }?.let { avatarMap[char.characterId] = loadAvatarIcon(it) }
                }
            }
            LoadResult(result.snapshot, result.sources, resolved, avatarMap, projectDir)
        }.thenAccept { result ->
            SwingUtilities.invokeLater {
                avatars.clear(); avatars.putAll(result.avatars)
                snapshot = result.snapshot
                sources = result.sources
                paths = result.paths
                projectLabel.text = result.projectDir
                if (result.snapshot == null) {
                    summaryLabel.text = ""
                    statusLabel.text = msg("characterManager.noProject")
                    characterModel.clear()
                    effectModel.clear()
                    issueModel.rowCount = 0
                    showEmptyDetail()
                } else {
                    renderAll()
                }
            }
        }.exceptionally { throwable ->
            SwingUtilities.invokeLater { statusLabel.text = msg("characterManager.error", throwable.message ?: "") }
            null
        }
    }

    private class LoadResult(
        val snapshot: CharacterManagerSnapshot?,
        val sources: CharacterDataSources?,
        val paths: CharacterDataPaths,
        val avatars: Map<String, Icon?>,
        val projectDir: String,
    )

    private fun renderAll() {
        val snap = snapshot ?: return
        val s = snap.summary
        summaryLabel.text = msg("characterManager.stats", s.characters, s.skills, s.definedEffects, s.errors, s.warnings, s.infos)
        statusLabel.text = msg("characterManager.loaded", s.characters)
        refillFilterBoxes(snap)
        applyFilters()
        renderEffects()
        renderIssues()
        renderTabBadges(snap)
    }

    /** 标签页上的数量徽标（对齐 VSCode 的 charactersBadge / effectsBadge / localesBadge / issuesBadge）。 */
    private fun renderTabBadges(snap: CharacterManagerSnapshot) {
        val counts = listOf(
            snap.characters.size,
            snap.effects.size,
            snap.summary.locales.size,
            snap.summary.errors + snap.summary.warnings + snap.summary.infos,
        )
        val keys = listOf(
            "characterManager.tab.characters",
            "characterManager.tab.effects",
            "characterManager.tab.locales",
            "characterManager.tab.issues",
        )
        for (index in keys.indices) {
            if (index < tabs.tabCount) tabs.setTitleAt(index, "${msg(keys[index])} (${counts[index]})")
        }
    }

    private fun refillFilterBoxes(snap: CharacterManagerSnapshot) {
        val all = msg("characterManager.filter.all")
        refill(starBox, CharacterFilters.values(CharacterFilters::starValue, snap.characters), all)
        refill(elementBox, CharacterFilters.values(CharacterFilters::elementValue, snap.characters), all)
        refill(professionBox, CharacterFilters.values(CharacterFilters::professionValue, snap.characters), all)
        val types = snap.characters.flatMap { c -> c.skills.map { it.skillType } }
            .filter { it.isNotBlank() }.distinct().sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
        refill(skillTypeBox, types, all)
        refill(effectCategoryBox, snap.effectCategories.filter { it.isNotBlank() }, all)
    }

    private fun refill(box: JComboBox<String>, values: List<String>, allLabel: String) {
        val previous = box.selectedItem as? String
        box.renderer = object : javax.swing.DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
            ): Component {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                if (value == "") text = allLabel
                return this
            }
        }
        box.removeAllItems()
        box.addItem("")
        values.forEach { box.addItem(it) }
        if (previous != null && values.contains(previous)) box.selectedItem = previous
    }

    // ══ 过滤与渲染 ════════════════════════════════════════════

    private fun selected(box: JComboBox<String>): String = (box.selectedItem as? String).orEmpty()

    private fun currentCriteria() = CharacterFilterCriteria(
        query = searchField.text,
        star = selected(starBox),
        element = selected(elementBox),
        profession = selected(professionBox),
        skillType = selected(skillTypeBox),
        enhancementOnly = enhancementOnlyBox.isSelected,
        issueOnly = issueOnlyBox.isSelected,
    )

    private fun applyFilters() {
        val snap = snapshot ?: return
        val criteria = currentCriteria()
        val filtered = snap.characters.filter { CharacterFilters.matches(it, criteria) }
        characterModel.clear()
        filtered.forEach { characterModel.addElement(it) }
        characterCountLabel.text = msg("characterManager.charactersCount", filtered.size, snap.characters.size)
        val keep = selectedCharacterId?.let { id -> filtered.firstOrNull { it.characterId == id } }
        if (keep != null) {
            characterList.setSelectedValue(keep, true)
        } else if (filtered.isNotEmpty()) {
            selectedCharacterId = filtered[0].characterId
            characterList.selectedIndex = 0
        } else {
            selectedCharacterId = null
            showEmptyDetail()
        }
        renderLocales()
    }

    private fun renderEffects() {
        val snap = snapshot ?: return
        val criteria = EffectFilterCriteria(
            query = effectSearch.text.ifBlank { searchField.text },
            category = selected(effectCategoryBox),
            usage = effectUsageBox.selectedItem as? EffectUsageFilter ?: EffectUsageFilter.ALL,
        )
        val filtered = snap.effects.filter { CharacterFilters.matches(it, criteria) }
        effectModel.clear()
        filtered.forEach { effectModel.addElement(it) }
        effectCountLabel.text = msg("characterManager.effectsCount", filtered.size, snap.effects.size)
    }

    private fun renderLocales() {
        val snap = snapshot ?: return
        val criteria = currentCriteria()
        val rows = snap.characters.filter { CharacterFilters.matches(it, criteria) }
        val locales = snap.summary.locales
        val header: Array<Any> = (listOf<Any>(msg("characterManager.column.characterId")) + locales).toTypedArray()
        val missing = msg("characterManager.missing")
        val body: Array<Array<Any>> = rows.map { char ->
            (listOf<Any>(char.characterId) +
                locales.map { char.locales[it].orEmpty().ifBlank { missing } }).toTypedArray()
        }.toTypedArray()
        localeModel.setDataVector(body, header)
    }

    private fun filteredIssues(snap: CharacterManagerSnapshot) = snap.issues.filter { issue ->
        CharacterFilters.issueMatches(
            issue,
            issueSearch.text.ifBlank { searchField.text },
            issueSeverityBox.selectedItem as? IssueSeverity,
        )
    }

    private fun renderIssues() {
        val snap = snapshot ?: return
        val filtered = filteredIssues(snap)
        issueModel.rowCount = 0
        for (issue in filtered) {
            issueModel.addRow(arrayOf(severityLabel(issue.severity), issue.code, issue.message))
        }
        issueCountLabel.text = msg("characterManager.issuesCount", filtered.size, snap.issues.size)
    }

    // ══ 角色详情 ══════════════════════════════════════════════

    private fun showEmptyDetail() {
        detailInner.removeAll()
        detailInner.add(JBLabel(msg("characterManager.empty")).apply { horizontalAlignment = SwingConstants.CENTER })
        detailInner.revalidate()
        detailInner.repaint()
    }

    private fun showCharacterDetail(char: CharacterView) {
        val body = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
        }

        val head = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply { alignmentX = Component.LEFT_ALIGNMENT }
        avatars[char.characterId]?.let { head.add(JBLabel(it)) }
        val title = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        title.add(JBLabel("${char.name}  ★${char.star}").apply {
            font = JBUI.Fonts.label().deriveFont(java.awt.Font.BOLD)
        })
        title.add(subLabel(msg("characterManager.detailMeta", char.characterId, char.element, char.profession, char.weaponType)))
        head.add(title)
        body.add(head)

        char.master?.let { body.add(lineLabel(msg("characterManager.detailMaster", it.zh, it.en, it.stars))) }
        if (char.locales.isNotEmpty()) {
            body.add(lineLabel(msg("characterManager.detailLocales",
                char.locales.entries.sortedBy { it.key }.joinToString("   ") { (k, v) -> "$k: $v" })))
        }
        if (char.issueCount > 0) {
            body.add(lineLabel(msg("characterManager.detailIssues", char.issueCount, char.errorCount)))
        }
        // 打开角色 JSON（对齐 VSCode 详情里的 openCharacterJson）：做成无边框链接按钮
        sources?.characterFiles?.get(char.characterId)?.let { path ->
            val link = JButton(File(path).name, AllIcons.Actions.OpenNewTab).apply {
                toolTipText = msg("characterManager.openCharacterJson")
                isBorderPainted = false
                isContentAreaFilled = false
                isOpaque = false
                addActionListener { openFileAt(path, char.characterId) }
            }
            body.add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                add(link)
            })
        }

        val addSkill = JButton(msg("characterManager.addSkill"), AllIcons.General.Add).apply {
            toolTipText = msg("characterManager.addSkill")
            alignmentX = Component.LEFT_ALIGNMENT
            addActionListener { runSkillDialog(char, SkillDialogMode.ADD, null) }
        }
        body.add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            add(JBLabel(msg("characterManager.detailSkills", char.skills.size)).apply {
                font = JBUI.Fonts.label().deriveFont(java.awt.Font.BOLD)
            })
            add(addSkill)
        })
        for (skill in char.skills) body.add(skillCard(char, skill))

        detailInner.removeAll()
        detailInner.add(body, BorderLayout.NORTH)
        detailInner.revalidate()
        detailInner.repaint()
    }

    private fun skillCard(char: CharacterView, skill: CharacterSkillView): JComponent {
        val card = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = javax.swing.BorderFactory.createCompoundBorder(
                javax.swing.BorderFactory.createLineBorder(javax.swing.UIManager.getColor("Separator.foreground")),
                JBUI.Borders.empty(6),
            )
            alignmentX = Component.LEFT_ALIGNMENT
        }

        val titleRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { alignmentX = Component.LEFT_ALIGNMENT }
        titleRow.add(JBLabel(skill.name).apply { font = JBUI.Fonts.label().deriveFont(java.awt.Font.BOLD) })
        titleRow.add(subLabel("[${skill.skillType}] ${skill.skillId} · ${skill.source}"))
        titleRow.add(iconButton(AllIcons.Actions.Copy, msg("characterManager.copyId")) { copyText(skill.skillId) })
        if (skill.source == "custom") {
            titleRow.add(iconButton(AllIcons.Actions.Edit, msg("characterManager.editSkill")) {
                runSkillDialog(char, SkillDialogMode.EDIT, skill.skillId)
            })
            titleRow.add(iconButton(AllIcons.Actions.GC, msg("characterManager.deleteSkill")) {
                runDeleteSkill(char, skill.skillId)
            })
        }
        titleRow.add(iconButton(AllIcons.General.Add, msg("characterManager.addEnhancement")) {
            runEnhancementDialog(char, skill.skillId, null, null)
        })
        card.add(titleRow)

        val meta = mutableListOf<String>()
        if (skill.element.isNotBlank()) meta += msg("characterManager.fieldElement") + skill.element
        if (skill.damageMultiplier.isNotBlank()) meta += "DMG ${skill.damageMultiplier}"
        meta += "Stagger ${skill.staggerValue}"
        if (skill.cooldown.isNotBlank()) meta += "CD ${skill.cooldown}"
        meta += "SP ${skill.spiritCost}"
        card.add(subRow(meta.joinToString(" · ")))
        if (skill.description.isNotBlank()) card.add(textRow(skill.description))
        card.add(effectChipRow(msg("characterManager.detailEffects"), skill.effects))
        for ((index, enh) in skill.enhancements.withIndex()) {
            card.add(enhancementCard(char, skill.skillId, enh, index))
        }
        return card
    }

    private fun enhancementCard(
        char: CharacterView,
        skillId: String,
        enh: CharacterEnhancementView,
        index: Int,
    ): JComponent {
        val card = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyLeft(10)
        }
        val row = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { alignmentX = Component.LEFT_ALIGNMENT }
        row.add(JBLabel("↳ ${enh.name}"))
        if (enh.visiblePulse) row.add(subLabel(msg("characterManager.detailPulse")))
        // 多个触发依赖效果时才需要区分 all/any（与 VSCode 一致：1 个时直接铺开）
        if (enh.triggerEffects.size > 1) row.add(triggerModeBadge(enh.triggerEffectMode))
        if (enh.triggerText.isNotBlank()) row.add(subLabel(msg("characterManager.detailTrigger", enh.triggerText)))
        if (enh.enhancementEffect.isNotBlank()) row.add(subLabel(enh.enhancementEffect))
        row.add(iconButton(AllIcons.Actions.Edit, msg("characterManager.editEnhancement")) {
            runEnhancementDialog(char, skillId, enh, index)
        })
        row.add(iconButton(AllIcons.Actions.GC, msg("characterManager.deleteEnhancement")) {
            runDeleteEnhancement(char, skillId, enh.name, index)
        })
        card.add(row)
        if (enh.triggerEffects.isNotEmpty()) {
            card.add(effectChipRow(msg("characterManager.detailTriggerEffects"), enh.triggerEffects))
        }
        card.add(effectChipRow(msg("characterManager.detailOutputEffects"), enh.effects))
        return card
    }

    // ══ 变更 ══════════════════════════════════════════════════

    private fun runSkillDialog(char: CharacterView, mode: SkillDialogMode, editSkillId: String?) {
        val path = requireSkillFile(char) ?: return
        val snap = snapshot ?: return
        val dialog = SkillDialog(
            project, mode, char, editSkillId,
            effectOptions(),
            snap.characters.flatMap { it.skills.map { skill -> skill.skillType } }.distinct(),
            snap.characters.map { it.element }.distinct(),
        )
        if (!dialog.showAndGet()) return
        val form = dialog.formValues()
        val skillId = if (mode == SkillDialogMode.ADD) dialog.enteredSkillId() else (editSkillId ?: return)
        if (skillId.isBlank()) {
            notifyError(msg("characterManager.skillIdRequired"))
            return
        }
        mutate {
            if (mode == SkillDialogMode.ADD) CharacterDataMutations.addSkill(path, skillId, form)
            else CharacterDataMutations.updateSkill(path, skillId, form)
        }
    }

    private fun runDeleteSkill(char: CharacterView, skillId: String) {
        val path = requireSkillFile(char) ?: return
        if (!confirm(msg("characterManager.deleteSkillConfirm", skillId, char.name), msg("characterManager.deleteSkill"))) return
        mutate { CharacterDataMutations.deleteSkill(path, skillId) }
    }

    private fun runEnhancementDialog(
        char: CharacterView,
        skillId: String,
        existing: CharacterEnhancementView?,
        index: Int?,
    ) {
        val path = requireSkillFile(char) ?: return
        val dialog = EnhancementDialog(
            project,
            effectOptions(),
            CharacterEnhancementSeed(
                name = existing?.name.orEmpty(),
                triggerText = existing?.triggerText.orEmpty(),
                triggerEffectMode = existing?.triggerEffectMode ?: "all",
                triggerEffects = existing?.triggerEffects?.map { it.toEffectParam() } ?: emptyList(),
                outputEffects = existing?.effects?.map { it.toEffectParam() } ?: emptyList(),
                enhancementEffect = existing?.enhancementEffect.orEmpty(),
                visiblePulse = existing?.visiblePulse ?: false,
            ),
        )
        if (!dialog.showAndGet()) return
        val form = dialog.formValues()
        mutate {
            if (index == null) CharacterDataMutations.addEnhancement(path, skillId, form)
            else CharacterDataMutations.updateEnhancement(path, skillId, index, form)
        }
    }

    private fun runDeleteEnhancement(char: CharacterView, skillId: String, name: String, index: Int) {
        val path = requireSkillFile(char) ?: return
        if (!confirm(msg("characterManager.deleteEnhancementConfirm", name), msg("characterManager.deleteEnhancement"))) return
        mutate { CharacterDataMutations.deleteEnhancement(path, skillId, index) }
    }

    private fun runAddEffectCategory() {
        val category = com.intellij.openapi.ui.Messages.showInputDialog(
            project,
            msg("characterManager.effect.categoryPrompt"),
            msg("characterManager.effect.addCategory"),
            null,
        ) ?: return
        val file = requireEffectsFile() ?: return
        mutate { EffectDataMutations.addCategory(file, category) }
    }

    private fun runAddEffect() {
        val snap = snapshot ?: return
        val file = requireEffectsFile() ?: return
        val dialog = EffectDialog(project, snap.effectCategories)
        if (!dialog.showAndGet()) return
        val (id, description, category) = dialog.values()
        mutate { EffectDataMutations.addEffect(file, id, description, category) }
    }

    private fun confirm(message: String, title: String): Boolean =
        com.intellij.openapi.ui.Messages.showYesNoDialog(
            project, message, title, com.intellij.openapi.ui.Messages.getWarningIcon(),
        ) == com.intellij.openapi.ui.Messages.YES

    private fun requireSkillFile(char: CharacterView): String? {
        val path = sources?.characterFiles?.get(char.characterId)
        if (path == null) notifyError(msg("characterManager.noSkillFile", char.name))
        return path
    }

    private fun requireEffectsFile(): String? {
        val file = paths?.effectsFile
        if (file.isNullOrBlank() || !File(file).exists()) {
            notifyError(msg("characterManager.effect.fileMissing", file.orEmpty()))
            return null
        }
        return file
    }

    /** 多选器的候选项。快照里的 effects 已包含「被引用但未定义」的 ID（defined=false）。 */
    private fun effectOptions(): List<EffectOption> {
        val snap = snapshot ?: return emptyList()
        return snap.effects.toEffectOptions()
    }

    private fun mutate(block: () -> Unit) {
        CompletableFuture.runAsync {
            try {
                block()
                SwingUtilities.invokeLater { loadData() }
            } catch (e: Exception) {
                SwingUtilities.invokeLater { notifyError(e.message ?: e.toString()) }
            }
        }
    }

    // ══ 打开源文件 ════════════════════════════════════════════

    private fun openIssueSource(issue: CharacterIssue) {
        val resolved = paths ?: return
        val src = sources
        val source = issue.source
        val targetFile = when (source?.kind) {
            SourceKind.MASTER -> resolved.masterFile
            SourceKind.LOCALE -> resolved.localeFile
            SourceKind.EFFECTS -> resolved.effectsFile
            SourceKind.CHARACTER -> source.fileName?.let { src?.characterFilesByName?.get(it) }
                ?: source.characterId?.let { src?.characterFiles?.get(it) }
                ?: source.characterId?.let { java.nio.file.Paths.get(resolved.skillsDir, "$it.json").toString() }
                ?: return
            null -> return
        }
        openFileAt(targetFile, source.effectId ?: source.skillId ?: source.characterId)
    }

    private fun openEffectsFileAt(effectId: String) {
        openFileAt(paths?.effectsFile ?: return, effectId)
    }

    private fun openFileAt(filePath: String, needle: String?) {
        val file = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .refreshAndFindFileByNioFile(java.nio.file.Paths.get(filePath)) ?: return
        val descriptor = if (needle.isNullOrBlank()) {
            com.intellij.openapi.fileEditor.OpenFileDescriptor(project, file)
        } else {
            val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file)
            val index = document?.text?.indexOf(needle) ?: -1
            if (index >= 0) com.intellij.openapi.fileEditor.OpenFileDescriptor(project, file, index)
            else com.intellij.openapi.fileEditor.OpenFileDescriptor(project, file)
        }
        descriptor.navigate(true)
    }

    private fun copyText(value: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(value))
        statusLabel.text = msg("characterManager.copied", value)
    }

    private fun notifyError(text: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("okScriptToolkit")
            .createNotification(msg("characterManager.mutationFailed"), text, NotificationType.ERROR)
            .notify(project)
        statusLabel.text = text
    }

    // ══ 小工具 ════════════════════════════════════════════════

    private fun documentListener(onChange: () -> Unit) = object : javax.swing.event.DocumentListener {
        override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = onChange()
        override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = onChange()
        override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = onChange()
    }

    private fun labelled(text: String, box: JComboBox<String>): JComponent {
        val panel = JPanel(BorderLayout(6, 0)).apply { alignmentX = Component.LEFT_ALIGNMENT }
        panel.add(JBLabel(text), BorderLayout.WEST)
        panel.add(box, BorderLayout.CENTER)
        panel.maximumSize = Dimension(Int.MAX_VALUE, box.preferredSize.height + 4)
        return panel
    }

    private fun subLabel(text: String) = JBLabel(text).apply {
        font = JBUI.Fonts.smallFont()
        foreground = javax.swing.UIManager.getColor("Label.disabledForeground")
    }

    private fun flowPanel(text: String, small: Boolean): JComponent {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 1)).apply { alignmentX = Component.LEFT_ALIGNMENT }
        panel.add(if (small) subLabel(text) else JBLabel(text))
        return panel
    }

    private fun subRow(text: String) = flowPanel(text, small = true)
    private fun textRow(text: String) = flowPanel(text, small = false)
    private fun lineLabel(text: String) = flowPanel(text, small = true)

    /**
     * 效果胶囊（对齐 VSCode 的 `.chip.effect`）：显示名 + ID + 参数，
     * 未定义/推测/减益各有颜色，点击跳到效果页并定位该 ID。
     */
    private fun effectChip(ref: CharacterEffectRef): JComponent {
        val definition = snapshot?.effects?.firstOrNull { it.id == ref.effectId }
        val display = ref.displayName.ifBlank { definition?.displayName ?: ref.effectId }
        val params = listOf(
            ref.value?.let { "value=$it" },
            ref.duration?.toString()?.takeIf { it.isNotBlank() }?.let { "duration=$it" },
            ref.count?.let { "count=$it" },
            ref.target?.let { "target=$it" },
        ).filterNotNull().joinToString(" · ")
        val label = buildString {
            append(display)
            if (display != ref.effectId) append(" · ").append(ref.effectId)
            if (params.isNotBlank()) append("  ").append(params)
            if (ref.inferred) append(" *")
        }
        val chip = JButton(label).apply {
            toolTipText = buildString {
                appendLine(definition?.description ?: msg("characterManager.effect.undefined"))
                append(ref.effectId)
                if (params.isNotBlank()) append("\n").append(params)
                if (ref.inferred) append("\n").append(msg("characterManager.detailInferred"))
                append("\n").append(msg("characterManager.effect.search"))
            }
            font = JBUI.Fonts.smallFont()
            isBorderPainted = true
            isFocusPainted = false
            margin = JBUI.insets(1, 6, 1, 6)
            addActionListener { focusEffect(ref.effectId) }
        }
        if (!ref.known || definition?.defined == false) {
            chip.foreground = com.intellij.ui.JBColor.namedColor("Label.errorForeground", 0xC0392B)
        } else if (isNegativeEffect(ref, definition?.category.orEmpty())) {
            chip.foreground = com.intellij.ui.JBColor.namedColor("Label.infoForeground", 0x8E6E2E)
        }
        return chip
    }

    /** 与 VSCode isNegativeEffect 一致：层数负数、CONSUME_/CLEAR_/DEBUFF_ 前缀、减益/消耗/清除分类。 */
    private fun isNegativeEffect(ref: CharacterEffectRef, category: String): Boolean =
        (ref.count ?: 0) < 0 ||
            Regex("^(?:CONSUME_|CLEAR_|DEBUFF_)").containsMatchIn(ref.effectId) ||
            category.contains("减益") || category.contains("消耗") || category.contains("清除")

    /** 一行效果胶囊；没有效果时显示「无」。 */
    private fun effectChipRow(label: String, refs: List<CharacterEffectRef>): JComponent {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 1)).apply { alignmentX = Component.LEFT_ALIGNMENT }
        panel.add(subLabel(label))
        if (refs.isEmpty()) {
            panel.add(subLabel(msg("characterManager.detailNone")))
            return panel
        }
        refs.forEach { panel.add(effectChip(it)) }
        return panel
    }

    /** 触发模式徽标（VSCode 里是带图标的 ALL / ANY 竖条）。 */
    private fun triggerModeBadge(mode: String): JComponent = JBLabel(
        if (mode.equals("any", ignoreCase = true)) "ANY" else "ALL",
    ).apply {
        font = JBUI.Fonts.smallFont().deriveFont(java.awt.Font.BOLD)
        border = javax.swing.BorderFactory.createCompoundBorder(
            javax.swing.BorderFactory.createLineBorder(javax.swing.UIManager.getColor("Separator.foreground")),
            JBUI.Borders.empty(1, 5, 1, 5),
        )
        toolTipText = if (mode.equals("any", ignoreCase = true)) {
            msg("characterManager.triggerModeAnyHint")
        } else {
            msg("characterManager.triggerModeAllHint")
        }
        alignmentX = Component.LEFT_ALIGNMENT
    }

    /** 跳到效果页并定位某个效果 ID（对齐 VSCode focusEffect）。 */
    private fun focusEffect(effectId: String) {
        tabs.selectedIndex = EFFECTS_TAB_INDEX
        effectSearch.text = effectId
        renderEffects()
    }

    private fun iconButton(icon: Icon, tooltip: String, action: () -> Unit) = JButton(icon).apply {
        toolTipText = tooltip
        isBorderPainted = false
        isContentAreaFilled = false
        isFocusPainted = false
        preferredSize = Dimension(22, 22)
        addActionListener { action() }
    }

    private fun usageLabel(usage: EffectUsageFilter?) = when (usage) {
        EffectUsageFilter.ALL -> msg("characterManager.effect.usageAll")
        EffectUsageFilter.USED -> msg("characterManager.effect.usageUsed")
        EffectUsageFilter.UNUSED -> msg("characterManager.effect.usageUnused")
        EffectUsageFilter.UNKNOWN -> msg("characterManager.effect.usageUnknown")
        null -> ""
    }

    private fun severityLabel(severity: IssueSeverity?) = when (severity) {
        IssueSeverity.ERROR -> "E"
        IssueSeverity.WARNING -> "W"
        IssueSeverity.INFO -> "I"
        null -> msg("characterManager.filter.all")
    }

    private fun loadAvatarIcon(template: FeatureTemplate): Icon? {
        return try {
            val file = template.imagePath.toFile()
            if (!file.exists()) return null
            val original = ImageIO.read(file) ?: return null
            val x = template.bbox[0].coerceIn(0, original.width - 1)
            val y = template.bbox[1].coerceIn(0, original.height - 1)
            val w = template.bbox[2].coerceAtMost(original.width - x)
            val h = template.bbox[3].coerceAtMost(original.height - y)
            if (w <= 0 || h <= 0) return null
            val crop = original.getSubimage(x, y, w, h)
            val side = 32
            val thumb = java.awt.image.BufferedImage(side, side, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            val g = thumb.createGraphics() as java.awt.Graphics2D
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            val scale = side.toDouble() / minOf(w, h)
            val dw = (w * scale).toInt().coerceAtLeast(1)
            val dh = (h * scale).toInt().coerceAtLeast(1)
            g.drawImage(crop, (side - dw) / 2, (side - dh) / 2, dw, dh, null)
            g.dispose()
            ImageIcon(thumb)
        } catch (e: Exception) {
            com.intellij.openapi.diagnostic.Logger.getInstance(CharacterManagerPanel::class.java)
                .warn("avatar render failed for " + template.name, e)
            null
        }
    }

    override fun dispose() {}

    // ══ 列表渲染器 ════════════════════════════════════════════

    private inner class CharacterCellRenderer : javax.swing.DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
        ): Component {
            val char = value as? CharacterView
            val label = super.getListCellRendererComponent(
                list, char?.let { "${it.name}  ★${it.star}" } ?: value, index, isSelected, cellHasFocus,
            )
            if (label is javax.swing.JLabel) {
                label.icon = char?.let { avatars[it.characterId] }
                label.border = JBUI.Borders.empty(2, 4)
                if (char != null && char.issueCount > 0) label.text = "${label.text}   ⚠${char.issueCount}"
            }
            return label
        }
    }

    private inner class EffectCellRenderer : javax.swing.DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
        ): Component {
            val effect = value as? CharacterEffectView
                ?: return super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            val title = effect.displayName.ifBlank { effect.description.ifBlank { effect.id } }
            val category = if (effect.defined) effect.category else msg("characterManager.effect.undefined")
            val shown = effect.usages.take(3).joinToString(" / ") { "${it.characterName} · ${it.skillName}" }
            val more = if (effect.usages.size > 3) msg("characterManager.effect.moreUsages", effect.usages.size - 3) else ""
            val html = "<html><b>${esc(title)}</b> <font color='gray'>${esc(effect.id)}</font>" +
                "<br/><font color='gray' size='2'>${esc(category)}" +
                (if (shown.isNotBlank() || more.isNotBlank()) " · ${esc(shown)}${esc(more)}" else "") +
                "</font></html>"
            return super.getListCellRendererComponent(list, html, index, isSelected, cellHasFocus)
        }

        private fun esc(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
