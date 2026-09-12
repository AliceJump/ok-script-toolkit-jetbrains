package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.alicejump.okscripttoolkit.core.*
import com.alicejump.okscripttoolkit.settings.OkScriptToolkitSettings
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import javax.imageio.ImageIO
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.util.concurrent.CompletableFuture
import javax.swing.*
import javax.swing.table.DefaultTableModel

class CharacterToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = CharacterManagerPanel(project)
        val content = ContentFactory.getInstance().createContent(panel.mainPanel, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

class CharacterManagerPanel(private val project: Project) : com.intellij.openapi.Disposable {

    val mainPanel: JPanel
    private val data = project.service<CharacterDataService>()

    private val characterTableModel = DefaultTableModel(
        arrayOf(
            "",
            OkScriptToolkitBundle.message("characterManager.column.name"),
            OkScriptToolkitBundle.message("characterManager.column.star"),
            OkScriptToolkitBundle.message("characterManager.column.element"),
            OkScriptToolkitBundle.message("characterManager.column.skills"),
        ),
        0,
    )
    private val characterTable = JTable(characterTableModel)
    private val searchField = JBTextField()
    private val statusLabel = JBLabel()
    private val statsLabel = JBLabel()

    private val detailPane = javax.swing.JEditorPane().apply {
        // JBHtmlPane 尚为 experimental API：用稳定 HTMLEditorKitBuilder 获得相同的
        // 主题化 HTML 渲染（IDE 样式表 + 自动换行）
        editorKit = com.intellij.util.ui.HTMLEditorKitBuilder().withWordWrapViewFactory().build()
        isEditable = false
        background = UIUtil.getPanelBackground()
    }
    private val issuesTableModel = DefaultTableModel(
        arrayOf(
            OkScriptToolkitBundle.message("characterManager.column.severity"),
            OkScriptToolkitBundle.message("characterManager.column.code"),
            OkScriptToolkitBundle.message("characterManager.column.message"),
        ),
        0,
    )
    private val issuesTable = JTable(issuesTableModel)
    private val effectsListModel = DefaultListModel<String>()
    private val effectsList = JList(effectsListModel)

    private var snapshot: CharacterManagerSnapshot? = null
    private var sources: CharacterDataSources? = null
    private var currentCharacters = listOf<CharacterView>()
    private val avatars = mutableMapOf<String, Icon?>()
    /** 角色 id → 技能文件绝对路径。必须由加载器实际扫描到的结果填充，
     *  不能按 `<characterId>.json` 猜——多数项目的文件名与 character_id 并不一致。 */
    private val skillFilePaths = mutableMapOf<String, String>()

    init {
        mainPanel = JPanel(BorderLayout())
        initUI()
        loadData()

        // 数据文件变化自动刷新（对齐 VSCode 版 watcher 派发）
        project.messageBus.connect(this).subscribe(
            OkDataChangeService.TOPIC,
            OkDataChangeListener { loadData() },
        )
    }

    private fun initUI() {
        val leftPanel = JPanel(BorderLayout())

        val searchPanel = JPanel(BorderLayout(4, 0))
        searchPanel.border = JBUI.Borders.empty(4)
        searchField.emptyText.text = OkScriptToolkitBundle.message("characterManager.search")
        searchField.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyReleased(e: java.awt.event.KeyEvent?) = applyFilter()
        })
        searchPanel.add(searchField, BorderLayout.CENTER)

        val refreshAction = ToolbarAction(AllIcons.Actions.Refresh, OkScriptToolkitBundle.message("characterManager.refresh")) { loadData() }
        val addSkillAction = ToolbarAction(AllIcons.General.Add, OkScriptToolkitBundle.message("characterManager.addSkill")) { runSkillDialog(mode = SkillDialogMode.ADD) }
        val editSkillAction = ToolbarAction(AllIcons.Actions.Edit, OkScriptToolkitBundle.message("characterManager.editSkill")) { runSkillDialog(mode = SkillDialogMode.EDIT) }
        val deleteSkillAction = ToolbarAction(AllIcons.Actions.GC, OkScriptToolkitBundle.message("characterManager.deleteSkill")) { deleteSelectedSkill() }
        val enhancementAction = ToolbarAction(AllIcons.Actions.Diff, OkScriptToolkitBundle.message("characterManager.enhancements")) { runEnhancementFlow() }
        val actionGroup = com.intellij.openapi.actionSystem.DefaultActionGroup(
            refreshAction, addSkillAction, editSkillAction, deleteSkillAction, enhancementAction,
        )
        val actionToolbar = com.intellij.openapi.actionSystem.ActionManager.getInstance()
            .createActionToolbar("ok-script-characters", actionGroup, true)
        actionToolbar.targetComponent = mainPanel
        searchPanel.add(actionToolbar.component, BorderLayout.EAST)
        leftPanel.add(searchPanel, BorderLayout.NORTH)

        characterTable.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        characterTable.showHorizontalLines = true
        characterTable.showVerticalLines = false
        characterTable.autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
        characterTable.rowHeight = 28
        characterTable.columnModel.getColumn(0).preferredWidth = 32
        characterTable.columnModel.getColumn(0).maxWidth = 36
        characterTable.columnModel.getColumn(1).preferredWidth = 150
        characterTable.columnModel.getColumn(2).preferredWidth = 40
        characterTable.columnModel.getColumn(3).preferredWidth = 60
        characterTable.columnModel.getColumn(4).preferredWidth = 50
        characterTable.columnModel.getColumn(0).cellRenderer = object : javax.swing.table.DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
            ): Component {
                super.getTableCellRendererComponent(table, null, isSelected, hasFocus, row, column)
                icon = value as? Icon
                horizontalAlignment = SwingConstants.CENTER
                return this
            }
        }
        characterTable.selectionModel.addListSelectionListener {
            val row = characterTable.selectedRow
            if (row >= 0 && row < currentCharacters.size) {
                showCharacterDetail(currentCharacters[row])
            }
        }
        leftPanel.add(JBScrollPane(characterTable), BorderLayout.CENTER)

        val statusPanel = JPanel(BorderLayout())
        statusPanel.border = JBUI.Borders.empty(2, 4)
        statusPanel.add(statusLabel, BorderLayout.CENTER)
        statusPanel.add(statsLabel, BorderLayout.EAST)
        leftPanel.add(statusPanel, BorderLayout.SOUTH)

        val rightPanel = JPanel(BorderLayout())
        val tabbedPane = JTabbedPane()

        detailPane.isEditable = false
        tabbedPane.addTab(OkScriptToolkitBundle.message("characterManager.detail"), JBScrollPane(detailPane))

        issuesTable.showHorizontalLines = true
        issuesTable.showVerticalLines = false
        issuesTable.autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
        issuesTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        issuesTable.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount != 2) return
                val row = issuesTable.selectedRow
                val snap = snapshot ?: return
                if (row in 0 until snap.issues.size) {
                    openIssueSource(snap.issues[row])
                }
            }
        })
        tabbedPane.addTab(OkScriptToolkitBundle.message("characterManager.issues"), JBScrollPane(issuesTable))

        effectsList.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
            ): Component {
                val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                if (value is String) {
                    val parts = value.split("|", limit = 2)
                    if (parts.size == 2) {
                        text = " ${parts[0].trim()}  ${parts[1].trim()}"
                    }
                }
                return c
            }
        }
        effectsList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        effectsList.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount != 2) return
                val index = effectsList.selectedIndex
                val snap = snapshot ?: return
                if (index in 0 until snap.effects.size) {
                    openEffectsFileAt(snap.effects[index].id)
                }
            }
        })
        tabbedPane.addTab(OkScriptToolkitBundle.message("characterManager.effects"), JBScrollPane(effectsList))

        rightPanel.add(tabbedPane, BorderLayout.CENTER)

        val splitPane = com.intellij.openapi.ui.Splitter(false, 0.4f)
        splitPane.firstComponent = leftPanel
        splitPane.secondComponent = rightPanel

        mainPanel.add(splitPane, BorderLayout.CENTER)
    }

    private fun loadData() {
        statusLabel.text = OkScriptToolkitBundle.message("characterManager.loading")
        CompletableFuture.supplyAsync {
            val settings = OkScriptToolkitSettings.getInstance(project)
            val projectDir = settings.characterProjectPath().ifBlank {
                project.basePath ?: ""
            }
            val paths = CharacterDataService.configuredPaths(projectDir, CharacterDataSettings(
                masterFile = settings.characterMasterFile(),
                skillsDirectory = settings.characterSkillsDirectory(),
                localeFile = settings.characterLocaleFile(),
                effectsFile = settings.effectsFile(),
            ))
            val loadResult = CharacterDataService.load(paths, settings.displayLocale().ifBlank { "zh_CN" })
            // 头像：avatarTemplateRegex 匹配模板名（去前缀后与 characterId/en 名比对）
            val gallery = project.service<OkProjectDataService>()
            val avatarRegex = runCatching { Regex(settings.characterAvatarTemplateRegex()) }.getOrNull()
            val avatarMap = mutableMapOf<String, Icon?>()
            if (avatarRegex != null) {
                for (char in loadResult.snapshot.characters) {
                    val candidate = char.master?.en ?: char.characterId
                    gallery.features().firstOrNull { tpl ->
                        val stripped = avatarRegex.find(tpl.name)?.let { tpl.name.replaceFirst(it.value, "") } ?: tpl.name
                        stripped.equals(candidate, ignoreCase = true) || tpl.name.equals(candidate, ignoreCase = true)
                    }?.let { template ->
                        avatarMap[char.characterId] = loadAvatarIcon(template)
                    }
                }
            }
            Pair(loadResult, avatarMap)
        }.thenAccept { (result, avatarMap) ->
            SwingUtilities.invokeLater {
                avatars.clear(); avatars.putAll(avatarMap)
                // 用加载器扫描到的真实路径：文件名可能与 character_id 不同（如 yvonne.json → yi_feng）
                skillFilePaths.clear(); skillFilePaths.putAll(result.sources.characterFiles)
                snapshot = result.snapshot
                sources = result.sources
                currentCharacters = result.snapshot.characters
                updateUI()
            }
        }.exceptionally { throwable ->
            SwingUtilities.invokeLater {
                statusLabel.text = OkScriptToolkitBundle.message("characterManager.error", throwable.message ?: "")
            }
            null
        }
    }

    private fun updateUI() {
        val snap = snapshot ?: return

        characterTableModel.rowCount = 0
        for (char in currentCharacters) {
            characterTableModel.addRow(arrayOf(avatars[char.characterId], char.name, char.star, char.element, char.skills.size))
        }

        issuesTableModel.rowCount = 0
        for (issue in snap.issues) {
            issuesTableModel.addRow(arrayOf(
                when (issue.severity) {
                    IssueSeverity.ERROR -> "E"
                    IssueSeverity.WARNING -> "W"
                    IssueSeverity.INFO -> "I"
                },
                issue.code,
                issue.message,
            ))
        }

        effectsListModel.clear()
        for (effect in snap.effects) {
            val usageCount = effect.usages.size
            effectsListModel.addElement("${effect.id} | ${effect.displayName} [${effect.category}] ($usageCount uses)")
        }

        val s = snap.summary
        statsLabel.text = OkScriptToolkitBundle.message(
            "characterManager.stats",
            s.characters,
            s.skills,
            s.definedEffects,
            s.errors,
            s.warnings,
            s.infos,
        )
        statusLabel.text = OkScriptToolkitBundle.message("characterManager.loaded", s.characters)
    }

    private fun applyFilter() {
        val query = searchField.text.trim().lowercase()
        val snap = snapshot ?: return
        currentCharacters = if (query.isEmpty()) {
            snap.characters
        } else {
            snap.characters.filter { char ->
                char.name.lowercase().contains(query) ||
                    char.characterId.lowercase().contains(query) ||
                    char.element.lowercase().contains(query) ||
                    char.profession.lowercase().contains(query)
            }
        }
        characterTableModel.rowCount = 0
        for (char in currentCharacters) {
            characterTableModel.addRow(arrayOf(avatars[char.characterId], char.name, char.star, char.element, char.skills.size))
        }
    }

    private fun showCharacterDetail(char: CharacterView) {
        detailPane.text = buildDetailHtml(char)
        detailPane.caretPosition = 0
    }

    /** HTML 详情（JBHtmlPane 主题适配渲染，替代等宽纯文本）。 */
    private fun buildDetailHtml(char: CharacterView): String {
        fun esc(v: Any?) = com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(v?.toString().orEmpty())
        val sb = StringBuilder()
        sb.append("<h2>").append(esc(char.name))
            .append(" <span style=\"color:gray\">").append(esc(char.characterId)).append("</span></h2>")
        sb.append("<p>★").append(char.star)
            .append(" &nbsp; ").append(esc(char.element))
            .append(" &nbsp; ").append(esc(char.profession))
            .append(" &nbsp; ").append(esc(char.weaponType)).append("</p>")
        char.master?.let {
            sb.append("<p style=\"color:gray\">master: zh=").append(esc(it.zh))
                .append(" / en=").append(esc(it.en)).append("</p>")
        }
        if (char.locales.isNotEmpty()) {
            sb.append("<p><b>Locales</b> ")
            val parts = char.locales.toSortedMap().entries.map { (locale, name) ->
                "<span style=\"color:gray\">${esc(locale)}:</span>${esc(name)}"
            }
            sb.append(parts.joinToString(" &nbsp; "))
            sb.append("</p>")
        }
        sb.append("<hr/><p><b>Skills (").append(char.skills.size).append(")</b></p>")
        for (skill in char.skills) {
            sb.append("<div style=\"margin:6px 0\">")
            sb.append("<b>").append(esc(skill.name)).append("</b> <span style=\"color:gray\">[")
                .append(esc(skill.source)).append("] ").append(esc(skill.skillId)).append("</span>")
            sb.append("<br/><span style=\"color:gray\">Type:</span> ").append(esc(skill.skillType))
                .append(" <span style=\"color:gray\">Element:</span> ").append(esc(skill.element))
            if (skill.description.isNotBlank()) {
                sb.append("<br/>").append(esc(skill.description))
            }
            if (skill.damageMultiplier.isNotBlank()) {
                sb.append("<br/><span style=\"color:gray\">DMG ").append(esc(skill.damageMultiplier))
                    .append(" · Stagger ").append(esc(skill.staggerValue))
                    .append(" · CD ").append(esc(skill.cooldown))
                    .append(" · SP ").append(esc(skill.spiritCost)).append("</span>")
            }
            if (skill.effects.isNotEmpty()) {
                sb.append("<br/><span style=\"color:gray\">Effects:</span> ")
                sb.append(skill.effects.joinToString(" &nbsp; ") { eff ->
                    esc(eff.effectId) + if (eff.inferred) " <i style=\"color:gray\">(inferred)</i>" else ""
                })
            }
            if (skill.enhancements.isNotEmpty()) {
                sb.append("<br/><span style=\"color:gray\">Enhancements:</span> ")
                sb.append(skill.enhancements.joinToString(" &nbsp; ") { enh ->
                    esc(enh.name) + " <span style=\"color:gray\">(trigger: " + esc(enh.triggerText) +
                        ", mode " + esc(enh.triggerEffectMode) + ")</span>"
                })
            }
            sb.append("</div>")
        }
        return "<html><body style=\"margin:8px\">" + sb + "</body></html>"
    }

    /** 双击问题跳转源文件并定位（对齐 VSCode 版 openSource）。 */
    private fun openIssueSource(issue: CharacterIssue) {
        val settings = OkScriptToolkitSettings.getInstance(project)
        val projectDir = settings.characterProjectPath().ifBlank { project.basePath ?: "" }
        if (projectDir.isBlank()) return
        val paths = CharacterDataService.configuredPaths(projectDir, CharacterDataSettings(
            masterFile = settings.characterMasterFile(),
            skillsDirectory = settings.characterSkillsDirectory(),
            localeFile = settings.characterLocaleFile(),
            effectsFile = settings.effectsFile(),
        ))
        val source = issue.source
        val src = sources
        val targetFile = when (source?.kind) {
            SourceKind.MASTER -> paths.masterFile
            SourceKind.LOCALE -> paths.localeFile
            SourceKind.EFFECTS -> paths.effectsFile
            SourceKind.CHARACTER -> {
                // 优先用扫描到的真实路径，其次按文件名查表，最后才退回 `<characterId>.json`
                source.fileName?.let { src?.characterFilesByName?.get(it) }
                    ?: source.characterId?.let { src?.characterFiles?.get(it) }
                    ?: source.characterId?.let { java.nio.file.Paths.get(paths.skillsDir, "$it.json").toString() }
                    ?: return
            }
            null -> return
        }
        val needle = source.effectId ?: source.skillId ?: source.characterId
        openFileAt(targetFile, needle)
    }

    private fun openEffectsFileAt(effectId: String) {
        val settings = OkScriptToolkitSettings.getInstance(project)
        val projectDir = settings.characterProjectPath().ifBlank { project.basePath ?: "" }
        if (projectDir.isBlank()) return
        val paths = CharacterDataService.configuredPaths(projectDir, CharacterDataSettings(
            masterFile = settings.characterMasterFile(),
            skillsDirectory = settings.characterSkillsDirectory(),
            localeFile = settings.characterLocaleFile(),
            effectsFile = settings.effectsFile(),
        ))
        openFileAt(paths.effectsFile, effectId)
    }

    private fun openFileAt(filePath: String, needle: String?) {
        val file = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .refreshAndFindFileByNioFile(java.nio.file.Paths.get(filePath))
            ?: return
        val descriptor = if (needle.isNullOrBlank()) {
            OpenFileDescriptor(project, file)
        } else {
            val document = com.intellij.openapi.fileEditor.FileDocumentManager
                .getInstance().getDocument(file)
            val index = document?.text?.indexOf(needle) ?: -1
            if (index >= 0) OpenFileDescriptor(project, file, index) else OpenFileDescriptor(project, file)
        }
        descriptor.navigate(true)
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
            val side = 24
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

    // ── 技能 CRUD ──────────────────────────────────────────────

    /** 编辑默认取该角色最后一个技能（详情面板按顺序展示，最后一个是当前浏览到的）。 */
    private fun selectedSkillId(): String? {
        val row = characterTable.selectedRow
        if (row < 0 || row >= currentCharacters.size) return null
        return currentCharacters[row].skills.lastOrNull()?.skillId
    }

    private fun runSkillDialog(mode: SkillDialogMode) {
        val row = characterTable.selectedRow
        if (row < 0 || row >= currentCharacters.size) {
            com.intellij.openapi.ui.Messages.showInfoMessage(
                project,
                OkScriptToolkitBundle.message("characterManager.selectCharacterFirst"),
                OkScriptToolkitBundle.message("characterManager.addSkill"),
            )
            return
        }
        val char = currentCharacters[row]
        val path = skillFilePaths[char.characterId]
        if (path == null) {
            com.intellij.openapi.ui.Messages.showInfoMessage(
                project,
                OkScriptToolkitBundle.message("characterManager.noSkillFile", char.name),
                OkScriptToolkitBundle.message("characterManager.addSkill"),
            )
            return
        }
        val editSkillId = if (mode == SkillDialogMode.EDIT) selectedSkillId() else null
        val dialog = SkillDialog(project, mode, char, editSkillId)
        if (!dialog.showAndGet()) return
        val form = dialog.formValues()
        val skillId = if (mode == SkillDialogMode.ADD) dialog.enteredSkillId() else (editSkillId ?: return)
        CompletableFuture.runAsync {
            try {
                if (mode == SkillDialogMode.ADD) {
                    com.alicejump.okscripttoolkit.core.CharacterDataMutations.addSkill(path, skillId, form)
                } else {
                    com.alicejump.okscripttoolkit.core.CharacterDataMutations.updateSkill(path, skillId, form)
                }
                SwingUtilities.invokeLater { loadData() }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    com.intellij.openapi.ui.Messages.showErrorDialog(
                        project, e.message ?: e.toString(),
                        OkScriptToolkitBundle.message("characterManager.mutationFailed"),
                    )
                }
            }
        }
    }

    private fun deleteSelectedSkill() {
        val row = characterTable.selectedRow
        if (row < 0 || row >= currentCharacters.size) return
        val char = currentCharacters[row]
        val path = skillFilePaths[char.characterId] ?: return
        val skillId = selectedSkillId() ?: return
        val confirm = com.intellij.openapi.ui.Messages.showYesNoDialog(
            project,
            OkScriptToolkitBundle.message("characterManager.deleteSkillConfirm", skillId, char.name),
            OkScriptToolkitBundle.message("characterManager.deleteSkill"),
            com.intellij.openapi.ui.Messages.getWarningIcon(),
        )
        if (confirm != com.intellij.openapi.ui.Messages.YES) return
        CompletableFuture.runAsync {
            try {
                com.alicejump.okscripttoolkit.core.CharacterDataMutations.deleteSkill(path, skillId)
                SwingUtilities.invokeLater { loadData() }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    com.intellij.openapi.ui.Messages.showErrorDialog(
                        project, e.message ?: e.toString(),
                        OkScriptToolkitBundle.message("characterManager.mutationFailed"),
                    )
                }
            }
        }
    }

    // ---- Enhancement editing ----

    private enum class EnhancementAction { ADD, EDIT, DELETE }

    private fun runEnhancementFlow() {
        val row = characterTable.selectedRow
        if (row < 0 || row >= currentCharacters.size) {
            com.intellij.openapi.ui.Messages.showInfoMessage(
                project,
                OkScriptToolkitBundle.message("characterManager.selectCharacterFirst"),
                OkScriptToolkitBundle.message("characterManager.enhancements"),
            )
            return
        }
        val char = currentCharacters[row]
        val path = skillFilePaths[char.characterId]
        if (path == null) {
            com.intellij.openapi.ui.Messages.showInfoMessage(
                project,
                OkScriptToolkitBundle.message("characterManager.noSkillFile", char.name),
                OkScriptToolkitBundle.message("characterManager.enhancements"),
            )
            return
        }
        if (char.skills.isEmpty()) {
            com.intellij.openapi.ui.Messages.showInfoMessage(
                project,
                OkScriptToolkitBundle.message("characterManager.noSkills", char.name),
                OkScriptToolkitBundle.message("characterManager.enhancements"),
            )
            return
        }

        val skillNames = char.skills.map { "${it.name} (${it.skillId})" }.toTypedArray()
        val skillIndex = ChooseDialog.show(
            project,
            OkScriptToolkitBundle.message("characterManager.enhancementSkillPrompt"),
            OkScriptToolkitBundle.message("characterManager.enhancements"),
            skillNames.toList(),
        ) ?: return
        val skill = char.skills[skillIndex]
        val skillId = skill.skillId

        val actions = arrayOf(
            OkScriptToolkitBundle.message("characterManager.addEnhancement"),
            OkScriptToolkitBundle.message("characterManager.editEnhancement"),
            OkScriptToolkitBundle.message("characterManager.deleteEnhancement"),
        )
        val actionIndex = ChooseDialog.show(
            project,
            OkScriptToolkitBundle.message("characterManager.enhancementActionPrompt", skill.name),
            OkScriptToolkitBundle.message("characterManager.enhancements"),
            actions.toList(),
        ) ?: return
        val action = EnhancementAction.values()[actionIndex]

        val enhCount = skill.enhancements.size
        if (action != EnhancementAction.ADD && enhCount == 0) {
            com.intellij.openapi.ui.Messages.showInfoMessage(
                project,
                OkScriptToolkitBundle.message("characterManager.noEnhancements", skill.name),
                OkScriptToolkitBundle.message("characterManager.enhancements"),
            )
            return
        }

        var enhancementIndex: Int? = null
        if (action != EnhancementAction.ADD) {
            val enhNames = skill.enhancements.map { it.name }.toTypedArray()
            val enhIdx = ChooseDialog.show(
                project,
                OkScriptToolkitBundle.message("characterManager.enhancementPickPrompt"),
                OkScriptToolkitBundle.message("characterManager.enhancements"),
                enhNames.toList(),
            ) ?: return
            enhancementIndex = enhIdx
        }

        var form: Map<String, String> = emptyMap()
        if (action != EnhancementAction.DELETE) {
            val existing = skill.enhancements.getOrNull(enhancementIndex ?: -1)
            val dialog = EnhancementDialog(project, existing?.name, existing?.triggerText)
            if (!dialog.showAndGet()) return
            form = dialog.formValues()
        } else {
            val confirm = com.intellij.openapi.ui.Messages.showYesNoDialog(
                project,
                OkScriptToolkitBundle.message("characterManager.deleteEnhancementConfirm", skill.enhancements[enhancementIndex!!].name),
                OkScriptToolkitBundle.message("characterManager.deleteEnhancement"),
                com.intellij.openapi.ui.Messages.getWarningIcon(),
            )
            if (confirm != com.intellij.openapi.ui.Messages.YES) return
        }

        CompletableFuture.runAsync {
            try {
                when (action) {
                    EnhancementAction.ADD -> com.alicejump.okscripttoolkit.core.CharacterDataMutations.addEnhancement(path, skillId, form)
                    EnhancementAction.EDIT -> com.alicejump.okscripttoolkit.core.CharacterDataMutations.updateEnhancement(path, skillId, enhancementIndex!!, form)
                    EnhancementAction.DELETE -> com.alicejump.okscripttoolkit.core.CharacterDataMutations.deleteEnhancement(path, skillId, enhancementIndex!!)
                }
                SwingUtilities.invokeLater { loadData() }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    com.intellij.openapi.ui.Messages.showErrorDialog(
                        project, e.message ?: e.toString(),
                        OkScriptToolkitBundle.message("characterManager.mutationFailed"),
                    )
                }
            }
        }
    }

    override fun dispose() {}
}
/**
 * Enhancement editing form (name / trigger_text / enhancement_effect).
 */
class EnhancementDialog(
    project: Project,
    initialName: String?,
    initialTrigger: String?,
) : com.intellij.openapi.ui.DialogWrapper(project) {

    private val nameField = javax.swing.JTextField(initialName ?: "")
    private val triggerField = javax.swing.JTextField(initialTrigger ?: "")
    private val effectField = javax.swing.JTextField()

    init {
        title = OkScriptToolkitBundle.message("characterManager.enhancements")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val form = JPanel(java.awt.GridBagLayout())
        var row = 0
        fun addField(label: String, comp: JComponent) {
            form.add(JLabel(label), java.awt.GridBagConstraints().apply {
                gridx = 0; gridy = row; anchor = java.awt.GridBagConstraints.WEST
                insets = java.awt.Insets(3, 6, 3, 4)
            })
            form.add(comp, java.awt.GridBagConstraints().apply {
                gridx = 1; gridy = row; fill = java.awt.GridBagConstraints.HORIZONTAL
                weightx = 1.0
                insets = java.awt.Insets(3, 4, 3, 6)
            })
            row++
        }
        addField(OkScriptToolkitBundle.message("characterManager.fieldEnhName"), nameField)
        addField(OkScriptToolkitBundle.message("characterManager.fieldTriggerText"), triggerField)
        addField(OkScriptToolkitBundle.message("characterManager.fieldEnhEffect"), effectField)
        return form
    }

    fun formValues(): Map<String, String> = mapOf(
        "name" to nameField.text.trim(),
        "trigger_text" to triggerField.text.trim(),
        "enhancement_effect" to effectField.text.trim(),
        "effects" to "",
    )
}

enum class SkillDialogMode { ADD, EDIT }

class ShowCharacterManagerAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            .getToolWindow("ok-script Characters")
            ?.show()
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}


/** 技能新增/编辑表单（对齐 CharacterSkillView 的核心字段）。 */
class SkillDialog(
    private val project: Project,
    private val mode: SkillDialogMode,
    char: CharacterView,
    editSkillId: String?,
) : com.intellij.openapi.ui.DialogWrapper(project) {

    private val skillIdField = com.intellij.ui.components.JBTextField(editSkillId ?: "")
    private val nameField = com.intellij.ui.components.JBTextField()
    private val skillTypeField = com.intellij.ui.components.JBTextField()
    private val elementField = com.intellij.ui.components.JBTextField()
    private val descriptionArea = com.intellij.ui.components.JBTextArea(3, 24)
    private val damageField = com.intellij.ui.components.JBTextField()
    private val staggerField = com.intellij.ui.components.JBTextField()
    private val cooldownField = com.intellij.ui.components.JBTextField()
    private val spiritField = com.intellij.ui.components.JBTextField()

    init {
        title = OkScriptToolkitBundle.message(
            if (mode == SkillDialogMode.ADD) "characterManager.addSkill" else "characterManager.editSkill",
        )
        setOKButtonText(OkScriptToolkitBundle.message("annotation.ok"))
        val existing = editSkillId?.let { id -> char.skills.firstOrNull { it.skillId == id } }
        existing?.let { s ->
            nameField.text = s.name
            skillTypeField.text = s.skillType
            elementField.text = s.element
            descriptionArea.text = s.description
            damageField.text = s.damageMultiplier.toString()
            staggerField.text = s.staggerValue.toString()
            cooldownField.text = s.cooldown.toString()
            spiritField.text = s.spiritCost.toString()
        }
        init()
    }

    override fun createCenterPanel(): JComponent {
        val form = JPanel(java.awt.GridBagLayout())
        var row = 0
        fun addRow(label: String, component: JComponent) {
            form.add(JLabel(label), java.awt.GridBagConstraints().apply {
                gridx = 0; gridy = row; anchor = java.awt.GridBagConstraints.WEST
                insets = java.awt.Insets(3, 4, 3, 6)
            })
            form.add(component, java.awt.GridBagConstraints().apply {
                gridx = 1; gridy = row; fill = java.awt.GridBagConstraints.HORIZONTAL
                weightx = 1.0; insets = java.awt.Insets(3, 0, 3, 4)
            })
            row++
        }
        addRow("skill_id", skillIdField)
        addRow("name", nameField)
        addRow("skill_type", skillTypeField)
        addRow("element", elementField)
        addRow("description", com.intellij.ui.components.JBScrollPane(descriptionArea).apply { preferredSize = java.awt.Dimension(240, 60) })
        addRow("damage_multiplier", damageField)
        addRow("stagger_value", staggerField)
        addRow("cooldown", cooldownField)
        addRow("spirit_cost", spiritField)
        return form
    }

    fun enteredSkillId(): String = skillIdField.text.trim()

    fun formValues(): Map<String, String> = mapOf(
        "name" to nameField.text.trim(),
        "skill_type" to skillTypeField.text.trim(),
        "element" to elementField.text.trim(),
        "description" to descriptionArea.text.trim(),
        "damage_multiplier" to damageField.text.trim(),
        "stagger_value" to staggerField.text.trim(),
        "cooldown" to cooldownField.text.trim(),
        "spirit_cost" to spiritField.text.trim(),
    ).filterValues { it.isNotEmpty() }
}
