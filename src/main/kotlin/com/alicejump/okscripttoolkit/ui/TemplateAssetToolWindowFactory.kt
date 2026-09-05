package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.alicejump.okscripttoolkit.core.TemplateAssetDataService
import com.alicejump.okscripttoolkit.core.TemplateImage
import com.alicejump.okscripttoolkit.settings.OkScriptToolkitSettings
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.util.concurrent.CompletableFuture
import javax.swing.*
import javax.swing.border.EmptyBorder

class TemplateAssetToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = TemplateAssetPanel(project)
        val content = ContentFactory.getInstance().createContent(panel.mainPanel, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

class TemplateAssetPanel(private val project: Project) : com.intellij.openapi.Disposable {

    val mainPanel: JPanel
    private val data = project.service<TemplateAssetDataService>()

    private val searchField = JBTextField()
    private val gridPanel = JPanel()
    private val scrollPane = JBScrollPane(gridPanel)
    private val statusLabel = JBLabel()
    private val countLabel = JBLabel()
    private var images = listOf<TemplateImage>()
    private var currentFilter = ""

    companion object {
        private const val THUMB_HEIGHT = 96
        private const val GRID_COLS = 4
        private const val CELL_HGap = 8
        private const val CELL_VGap = 8
    }

    init {
        mainPanel = JPanel(BorderLayout())
        initUI()
        loadData()
    }

    private fun initUI() {
        val toolbar = JPanel(BorderLayout(4, 0))
        toolbar.border = JBUI.Borders.empty(4)

        searchField.emptyText.text = OkScriptToolkitBundle.message("templateAsset.search")
        searchField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = applyFilter()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = applyFilter()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = applyFilter()
        })
        toolbar.add(searchField, BorderLayout.CENTER)

        val btnPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))

        val importBtn = JButton(AllIcons.Actions.AddFile)
        importBtn.toolTipText = OkScriptToolkitBundle.message("templateAsset.import")
        importBtn.addActionListener { handleImport() }
        btnPanel.add(importBtn)

        val refreshBtn = JButton(AllIcons.Actions.Refresh)
        refreshBtn.toolTipText = OkScriptToolkitBundle.message("templateAsset.refresh")
        refreshBtn.addActionListener { loadData() }
        btnPanel.add(refreshBtn)

        toolbar.add(btnPanel, BorderLayout.EAST)

        gridPanel.layout = GridBagLayout()
        gridPanel.border = EmptyBorder(8, 8, 8, 8)

        val statusPanel = JPanel(BorderLayout())
        statusPanel.border = JBUI.Borders.empty(2, 4)
        statusPanel.add(statusLabel, BorderLayout.CENTER)
        statusPanel.add(countLabel, BorderLayout.EAST)

        mainPanel.add(toolbar, BorderLayout.NORTH)
        mainPanel.add(scrollPane, BorderLayout.CENTER)
        mainPanel.add(statusPanel, BorderLayout.SOUTH)
    }

    private fun loadData() {
        statusLabel.text = OkScriptToolkitBundle.message("templateAsset.loading")
        CompletableFuture.supplyAsync {
            val settings = OkScriptToolkitSettings.getInstance(project)
            val projectDir = project.basePath ?: ""
            data.load(projectDir, settings.okTemplatesDirectory())
            data.listImages()
        }.thenAccept { result ->
            SwingUtilities.invokeLater {
                images = result
                renderGrid()
            }
        }.exceptionally { throwable ->
            SwingUtilities.invokeLater {
                statusLabel.text = "Error: ${throwable.message}"
            }
            null
        }
    }

    private fun renderGrid() {
        gridPanel.removeAll()

        val filtered = if (currentFilter.isEmpty()) images else {
            images.filter { it.name.lowercase().contains(currentFilter) }
        }

        val gbc = GridBagConstraints().apply {
            insets = Insets(CELL_VGap, CELL_HGap, CELL_VGap, CELL_HGap)
            anchor = GridBagConstraints.NORTH
            fill = GridBagConstraints.NONE
        }

        for ((index, img) in filtered.withIndex()) {
            gbc.gridx = index % GRID_COLS
            gbc.gridy = index / GRID_COLS
            gbc.gridwidth = 1
            val card = createImageCard(img)
            gridPanel.add(card, gbc)
        }

        if (filtered.isEmpty()) {
            val emptyLabel = JBLabel(OkScriptToolkitBundle.message("templateAsset.empty"))
            gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = GRID_COLS
            gridPanel.add(emptyLabel, gbc)
        }

        countLabel.text = OkScriptToolkitBundle.message("templateAsset.count", filtered.size)
        statusLabel.text = "Loaded ${images.size} images"
        gridPanel.revalidate()
        gridPanel.repaint()
    }

    private fun createImageCard(img: TemplateImage): JPanel {
        val card = JPanel(BorderLayout())
        card.border = BorderFactory.createLineBorder(Color.LIGHT_GRAY)
        card.preferredSize = Dimension(140, THUMB_HEIGHT + 40)
        card.maximumSize = Dimension(140, THUMB_HEIGHT + 40)

        val thumbLabel = object : JBLabel() {
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                try {
                    val bi = javax.imageio.ImageIO.read(img.file)
                    if (bi != null) {
                        val scale = THUMB_HEIGHT.toDouble() / bi.height
                        val w = (bi.width * scale).toInt().coerceAtMost(120)
                        val h = THUMB_HEIGHT
                        val x = (width - w) / 2
                        g.drawImage(bi, x, 0, w, h, this)
                    }
                } catch (_: Exception) {
                    g.drawString("?", width / 2 - 5, THUMB_HEIGHT / 2)
                }
            }
            override fun getPreferredSize() = Dimension(140, THUMB_HEIGHT)
        }
        thumbLabel.border = EmptyBorder(2, 2, 2, 2)

        val annText = if (img.annotations.isNotEmpty()) " [${img.annotations.size} ann]" else ""
        val infoText = "<html><center><b>${img.name}</b><br>${img.width}x${img.height}$annText</center></html>"
        val infoLabel = JBLabel(infoText)
        infoLabel.horizontalAlignment = SwingConstants.CENTER
        infoLabel.font = infoLabel.font.deriveFont(10f)

        card.add(thumbLabel, BorderLayout.CENTER)
        card.add(infoLabel, BorderLayout.SOUTH)

        card.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) openInEditor(img)
            }
            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) showContextMenu(e, img)
            }
            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) showContextMenu(e, img)
            }
        })

        return card
    }

    private fun openInEditor(img: TemplateImage) {
        com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .refreshAndFindFileByNioFile(img.file.toPath())
            ?.let { file ->
                com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
                    .openFile(file, true)
            }
    }

    private fun showContextMenu(e: MouseEvent, img: TemplateImage) {
        val popup = JPopupMenu()

        val openItem = JMenuItem("Open Source Image")
        openItem.addActionListener {
            openInEditor(img)
        }
        popup.add(openItem)

        popup.addSeparator()

        val deleteItem = JMenuItem("Delete")
        deleteItem.addActionListener {
            val confirm = JOptionPane.showConfirmDialog(
                mainPanel,
                "Delete template '${img.name}'?",
                "Confirm Delete",
                JOptionPane.YES_NO_OPTION,
            )
            if (confirm == JOptionPane.YES_OPTION) {
                data.deleteImage(img.file)
                data.save()
                loadData()
            }
        }
        popup.add(deleteItem)

        popup.show(e.component, e.x, e.y)
    }

    private fun handleImport() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()
            .withTitle("Import Template Image")
        com.intellij.openapi.fileChooser.FileChooser.chooseFile(descriptor, project, null) { file ->
            val settings = OkScriptToolkitSettings.getInstance(project)
            val projectDir = project.basePath ?: return@chooseFile
            val targetDir = File(projectDir, settings.okTemplatesDirectory())
            val imported = data.importImage(File(file.path), targetDir)
            if (imported != null) {
                notify("Imported: ${imported.name}", NotificationType.INFORMATION)
                loadData()
            } else {
                notify("Failed to import image", NotificationType.ERROR)
            }
        }
    }

    private fun applyFilter() {
        currentFilter = searchField.text.trim().lowercase()
        renderGrid()
    }

    private fun notify(content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("okScriptToolkit")
            .createNotification(content, type)
            .notify(project)
    }

    override fun dispose() {}
}

class ShowTemplateAssetsAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            .getToolWindow("ok-script Templates")
            ?.show()
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
