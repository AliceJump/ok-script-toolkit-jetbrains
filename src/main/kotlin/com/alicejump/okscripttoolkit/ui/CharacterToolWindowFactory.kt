package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.alicejump.okscripttoolkit.core.*
import com.alicejump.okscripttoolkit.settings.OkScriptToolkitSettings
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
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

    private val characterTableModel = DefaultTableModel(arrayOf("Name", "Star", "Element", "Skills"), 0)
    private val characterTable = JTable(characterTableModel)
    private val searchField = JBTextField()
    private val statusLabel = JBLabel()
    private val statsLabel = JBLabel()

    private val detailPane = JTextPane()
    private val issuesTableModel = DefaultTableModel(arrayOf("Sev", "Code", "Message"), 0)
    private val issuesTable = JTable(issuesTableModel)
    private val effectsListModel = DefaultListModel<String>()
    private val effectsList = JList(effectsListModel)

    private var snapshot: CharacterManagerSnapshot? = null
    private var currentCharacters = listOf<CharacterView>()

    init {
        mainPanel = JPanel(BorderLayout())
        initUI()
        loadData()
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

        val refreshBtn = JButton(AllIcons.Actions.Refresh)
        refreshBtn.toolTipText = OkScriptToolkitBundle.message("characterManager.refresh")
        refreshBtn.addActionListener { loadData() }
        searchPanel.add(refreshBtn, BorderLayout.EAST)
        leftPanel.add(searchPanel, BorderLayout.NORTH)

        characterTable.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        characterTable.showHorizontalLines = true
        characterTable.showVerticalLines = false
        characterTable.autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
        characterTable.columnModel.getColumn(0).preferredWidth = 150
        characterTable.columnModel.getColumn(1).preferredWidth = 40
        characterTable.columnModel.getColumn(2).preferredWidth = 60
        characterTable.columnModel.getColumn(3).preferredWidth = 50
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
        detailPane.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
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

        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightPanel)
        splitPane.resizeWeight = 0.4

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
            CharacterDataService.load(paths, settings.displayLocale().ifBlank { "zh_CN" })
        }.thenAccept { result ->
            SwingUtilities.invokeLater {
                snapshot = result.snapshot
                currentCharacters = result.snapshot.characters
                updateUI()
            }
        }.exceptionally { throwable ->
            SwingUtilities.invokeLater {
                statusLabel.text = "Error: ${throwable.message}"
            }
            null
        }
    }

    private fun updateUI() {
        val snap = snapshot ?: return

        characterTableModel.rowCount = 0
        for (char in currentCharacters) {
            characterTableModel.addRow(arrayOf(char.name, char.star, char.element, char.skills.size))
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
        statsLabel.text = "${s.characters} chars, ${s.skills} skills, ${s.definedEffects} effects, ${s.errors}E/${s.warnings}W/${s.infos}I"
        statusLabel.text = "Loaded ${s.characters} characters"
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
            characterTableModel.addRow(arrayOf(char.name, char.star, char.element, char.skills.size))
        }
    }

    private fun showCharacterDetail(char: CharacterView) {
        val sb = StringBuilder()
        sb.appendLine("=== ${char.name} (${char.characterId}) ===")
        sb.appendLine("Star: ${char.star}  Element: ${char.element}  Profession: ${char.profession}  Weapon: ${char.weaponType}")
        char.master?.let {
            sb.appendLine("Master: zh=${it.zh}  en=${it.en}")
        }
        if (char.locales.isNotEmpty()) {
            sb.appendLine("Locales:")
            for ((locale, name) in char.locales.toSortedMap()) {
                sb.appendLine("  $locale: $name")
            }
        }
        sb.appendLine()
        sb.appendLine("--- Skills (${char.skills.size}) ---")
        for (skill in char.skills) {
            sb.appendLine()
            sb.appendLine("  [${skill.source}] ${skill.name} (${skill.skillId})")
            sb.appendLine("  Type: ${skill.skillType}  Element: ${skill.element}")
            if (skill.description.isNotBlank()) {
                sb.appendLine("  Desc: ${skill.description}")
            }
            if (skill.damageMultiplier.isNotBlank()) {
                sb.appendLine("  Damage: ${skill.damageMultiplier}  Stagger: ${skill.staggerValue}  CD: ${skill.cooldown}  SP: ${skill.spiritCost}")
            }
            if (skill.effects.isNotEmpty()) {
                sb.appendLine("  Effects:")
                for (eff in skill.effects) {
                    sb.appendLine("    - ${eff.effectId}${if (eff.inferred) " (inferred)" else ""}")
                }
            }
            if (skill.enhancements.isNotEmpty()) {
                sb.appendLine("  Enhancements:")
                for (enh in skill.enhancements) {
                    sb.appendLine("    ${enh.name}: trigger='${enh.triggerText}' mode=${enh.triggerEffectMode}")
                }
            }
        }

        detailPane.text = sb.toString()
        detailPane.caretPosition = 0
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
        val targetFile = when (source?.kind) {
            SourceKind.MASTER -> paths.masterFile
            SourceKind.LOCALE -> paths.localeFile
            SourceKind.EFFECTS -> paths.effectsFile
            SourceKind.CHARACTER -> {
                val name = source.fileName
                    ?: source.characterId?.let { "$it.json" }
                    ?: return
                java.nio.file.Paths.get(paths.skillsDir, name).toString()
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

    override fun dispose() {}
}

class ShowCharacterManagerAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            .getToolWindow("ok-script Characters")
            ?.show()
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
