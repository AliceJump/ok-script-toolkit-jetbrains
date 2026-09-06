# -*- coding: utf-8 -*-
"""向角色面板注入头像列、技能 CRUD 对话框与删除流程。"""
import io

p = 'src/main/kotlin/com/alicejump/okscripttoolkit/ui/CharacterToolWindowFactory.kt'
with io.open(p, encoding='utf-8', newline='\n') as f:
    src = f.read()

# 去掉多余的假 renderer（保留 DefaultTableCellRenderer 版本）
old = '''        characterTable.columnModel.getColumn(0).cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
            ): Component = this
        }
        characterTable.columnModel.getColumn(0).cellRenderer = javax.swing.table.DefaultTableCellRenderer().apply {
            horizontalAlignment = SwingConstants.CENTER
        }'''
new = '''        characterTable.columnModel.getColumn(0).cellRenderer = object : javax.swing.table.DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
            ): Component {
                super.getTableCellRendererComponent(table, null, isSelected, hasFocus, row, column)
                icon = value as? Icon
                horizontalAlignment = SwingConstants.CENTER
                return this
            }
        }'''
assert old in src, 'renderer anchor not found'
src = src.replace(old, new, 1)

# avatars / skillFilePaths 字段
old = '''    private var snapshot: CharacterManagerSnapshot? = null
    private var currentCharacters = listOf<CharacterView>()'''
new = '''    private var snapshot: CharacterManagerSnapshot? = null
    private var currentCharacters = listOf<CharacterView>()
    private val avatars = mutableMapOf<String, Icon?>()
    private val skillFilePaths = mutableMapOf<String, String>()'''
assert old in src, 'fields anchor not found'
src = src.replace(old, new, 1)

# loadData：构建头像 + 技能文件路径
old = '''            CharacterDataService.load(paths, settings.displayLocale().ifBlank { "zh_CN" })
        }.thenAccept { result ->
            SwingUtilities.invokeLater {
                snapshot = result.snapshot
                currentCharacters = result.snapshot.characters
                updateUI()
            }
        }.exceptionally { throwable ->'''
new = '''            val loadResult = CharacterDataService.load(paths, settings.displayLocale().ifBlank { "zh_CN" })
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
            val skillPathMap = mutableMapOf<String, String>()
            loadResult.snapshot.characters.forEach { c ->
                val f = java.nio.file.Paths.get(paths.skillsDir, c.characterId + ".json").toFile()
                if (f.exists()) skillPathMap[c.characterId] = f.absolutePath
            }
            Triple(loadResult, avatarMap, skillPathMap)
        }.thenAccept { (result, avatarMap, skillPathMap) ->
            SwingUtilities.invokeLater {
                avatars.clear(); avatars.putAll(avatarMap)
                skillFilePaths.clear(); skillFilePaths.putAll(skillPathMap)
                snapshot = result.snapshot
                currentCharacters = result.snapshot.characters
                updateUI()
            }
        }.exceptionally { throwable ->'''
assert old in src, 'loadData anchor not found'
src = src.replace(old, new, 1)

# 表格填充（两处）加头像列
src = src.replace(
    'characterTableModel.addRow(arrayOf(char.name, char.star, char.element, char.skills.size))',
    'characterTableModel.addRow(arrayOf(avatars[char.characterId], char.name, char.star, char.element, char.skills.size))',
)

# dispose 前插入头像辅助与 CRUD 实现
old = '''    override fun dispose() {}'''
new = '''    private fun loadAvatarIcon(template: FeatureTemplate): Icon? = try {
        val file = template.imagePath.toFile()
        if (!file.exists()) null else {
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
        }
    } catch (e: Exception) {
        com.intellij.openapi.diagnostic.Logger.getInstance(CharacterManagerPanel::class.java)
            .warn("avatar render failed for " + template.name, e)
        null
    }

    // ── 技能 CRUD ──────────────────────────────────────────────

    private enum class SkillDialogMode { ADD, EDIT }

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

    override fun dispose() {}'''
assert old in src, 'dispose anchor not found'
src = src.replace(old, new, 1)

with io.open(p, 'w', encoding='utf-8', newline='\n') as f:
    f.write(src)
print('panel CRUD injected')
