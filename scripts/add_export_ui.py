# -*- coding: utf-8 -*-
"""向素材面板注入 saveToAssets 导出按钮与流程（对齐 VSCode 版 handleSaveToAssets）。"""
import io

p = 'src/main/kotlin/com/alicejump/okscripttoolkit/ui/TemplateAssetToolWindowFactory.kt'
with io.open(p, encoding='utf-8', newline='\n') as f:
    src = f.read()

# 1) 工具栏加导出按钮
old = '''        val importAction = ToolbarAction(AllIcons.Actions.AddFile, OkScriptToolkitBundle.message("templateAsset.import")) { handleImport() }
        val screenshotAction = ToolbarAction(AllIcons.Actions.Preview, OkScriptToolkitBundle.message("templateAsset.screenshot")) { handleScreenshot() }
        val refreshAction = ToolbarAction(AllIcons.Actions.Refresh, OkScriptToolkitBundle.message("templateAsset.refresh")) { loadData() }
        val actionGroup = com.intellij.openapi.actionSystem.DefaultActionGroup(importAction, screenshotAction, refreshAction)'''
new = '''        val importAction = ToolbarAction(AllIcons.Actions.AddFile, OkScriptToolkitBundle.message("templateAsset.import")) { handleImport() }
        val screenshotAction = ToolbarAction(AllIcons.Actions.Preview, OkScriptToolkitBundle.message("templateAsset.screenshot")) { handleScreenshot() }
        val exportAction = ToolbarAction(AllIcons.Actions.Export, OkScriptToolkitBundle.message("templateAsset.export")) { handleSaveToAssets() }
        val refreshAction = ToolbarAction(AllIcons.Actions.Refresh, OkScriptToolkitBundle.message("templateAsset.refresh")) { loadData() }
        val actionGroup = com.intellij.openapi.actionSystem.DefaultActionGroup(importAction, screenshotAction, exportAction, refreshAction)'''
assert old in src, 'toolbar anchor not found'
src = src.replace(old, new, 1)

# 2) handleSaveToAssets 方法
anchor = '    private fun applyFilter() {'
method = '''    /**
     * saveToAssets 导出（对齐 VSCode 版）：选择目标（assets / ok_tasks/assets）、
     * 可选生成 LabelEnum.py，后台 bin-packing 合成 pages 并重写目标 COCO。
     */
    private fun handleSaveToAssets() {
        val annotatedCount = images.count { it.annotations.isNotEmpty() }
        if (annotatedCount == 0) {
            notify(OkScriptToolkitBundle.message("templateAsset.exportNoAnnotations"), NotificationType.WARNING)
            return
        }
        val projectDir = OkScriptToolkitSettings.getInstance(project).okScriptProjectPath().ifBlank {
            project.basePath ?: ""
        }
        if (projectDir.isBlank()) {
            notify(OkScriptToolkitBundle.message("taskLauncher.noProject"), NotificationType.WARNING)
            return
        }

        val options = arrayOf("assets", "ok_tasks/assets")
        val chosen = com.intellij.openapi.ui.Messages.showChooseDialog(
            project,
            OkScriptToolkitBundle.message("templateAsset.exportTargetPrompt", annotatedCount),
            OkScriptToolkitBundle.message("templateAsset.export"),
            com.intellij.icons.AllIcons.General.Information,
            options,
            options[0],
        )
        if (chosen == null) return
        val targetFolder = java.nio.file.Paths.get(projectDir, chosen.replace("/", java.io.File.separator)).toString()

        val generateEnum = com.intellij.openapi.ui.Messages.showYesNoDialog(
            project,
            OkScriptToolkitBundle.message("templateAsset.exportEnumPrompt"),
            OkScriptToolkitBundle.message("templateAsset.export"),
            com.intellij.openapi.ui.Messages.getQuestionIcon(),
        ) == com.intellij.openapi.ui.Messages.YES

        var enumPath: String? = null
        if (generateEnum) {
            val defaultPath = java.nio.file.Paths.get(targetFolder, "LabelEnum.py").toString()
            val input = com.intellij.openapi.ui.Messages.showInputDialog(
                project,
                OkScriptToolkitBundle.message("templateAsset.exportEnumPathPrompt"),
                OkScriptToolkitBundle.message("templateAsset.exportEnumTitle"),
                com.intellij.openapi.ui.Messages.getInformationIcon(),
                defaultPath,
                null,
            ) ?: return
            enumPath = input.trim()
        }

        statusLabel.text = OkScriptToolkitBundle.message("templateAsset.exportRunning")
        progressBar.isIndeterminate = true
        progressBar.isVisible = true

        com.intellij.openapi.progress.ProgressManager.getInstance().run(
            object : com.intellij.openapi.progress.Task.Backgroundable(
                project,
                OkScriptToolkitBundle.message("templateAsset.export"),
                false,
            ) {
                override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                    try {
                        data.load(
                            projectDir,
                            OkScriptToolkitSettings.getInstance(project).okTemplatesDirectory(),
                        )
                        data.saveToAssets(targetFolder, generateEnum, enumPath) { done, total ->
                            indicator.fraction = if (total > 0) done.toDouble() / total else 0.0
                            indicator.text = OkScriptToolkitBundle.message(
                                "templateAsset.exportProgress", done, total,
                            )
                        }
                        com.intellij.notification.NotificationGroupManager.getInstance()
                            .getNotificationGroup("okScriptToolkit")
                            .createNotification(
                                OkScriptToolkitBundle.message("templateAsset.exportDone", targetFolder),
                                NotificationType.INFORMATION,
                            )
                            .notify(project)
                    } catch (e: Exception) {
                        com.intellij.notification.NotificationGroupManager.getInstance()
                            .getNotificationGroup("okScriptToolkit")
                            .createNotification(
                                OkScriptToolkitBundle.message("templateAsset.exportFailed", e.message ?: e.toString()),
                                NotificationType.ERROR,
                            )
                            .notify(project)
                    } finally {
                        javax.swing.SwingUtilities.invokeLater {
                            progressBar.isIndeterminate = false
                            progressBar.isVisible = false
                            loadData()
                        }
                    }
                }
            },
        )
    }

    private fun applyFilter() {'''
assert anchor in src, 'applyFilter anchor not found'
src = src.replace(anchor, method, 1)

# 3) progressBar 字段（素材面板此前没有）
old_progress = '''    private val statusLabel = JBLabel()
    private val countLabel = JBLabel()'''
new_progress = '''    private val statusLabel = JBLabel()
    private val countLabel = JBLabel()
    private val progressBar = JProgressBar()'''
assert old_progress in src
src = src.replace(old_progress, new_progress, 1)

old_status = '''        val statusPanel = JPanel(BorderLayout())
        statusPanel.border = JBUI.Borders.empty(2, 4)
        statusPanel.add(statusLabel, BorderLayout.CENTER)
        statusPanel.add(countLabel, BorderLayout.EAST)'''
new_status = '''        val statusPanel = JPanel(BorderLayout())
        statusPanel.border = JBUI.Borders.empty(2, 4)
        statusPanel.add(statusLabel, BorderLayout.CENTER)
        statusPanel.add(countLabel, BorderLayout.EAST)
        progressBar.preferredSize = Dimension(200, 16)
        progressBar.isVisible = false
        statusPanel.add(progressBar, BorderLayout.SOUTH)'''
assert old_status in src
src = src.replace(old_status, new_status, 1)

with io.open(p, 'w', encoding='utf-8', newline='\n') as f:
    f.write(src)
print('export flow added')
