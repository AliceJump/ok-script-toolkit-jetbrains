package com.alicejump.okscripttoolkit.tasklauncher

import com.alicejump.okscripttoolkit.OkScriptToolkitBundle
import com.alicejump.okscripttoolkit.core.AccountStoreData
import com.alicejump.okscripttoolkit.core.AccountStoreService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Dialog
import java.awt.FlowLayout
import java.awt.GridLayout
import java.util.concurrent.CompletableFuture
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities

/**
 * Project account store editor. The account store is the only writer; every successful write
 * returns a fresh snapshot. All Swing updates happen on the EDT.
 */
internal class AccountEditorDialog(
    parent: Component,
    private val service: AccountStoreService,
    private val projectDir: String,
    private var info: TaskLauncherService.MultiAccountInfo,
    private var schemas: Map<String, TaskLauncherService.TaskSchema>,
    private var globalGroups: List<TaskLauncherService.GlobalConfigGroup>,
    private val onAccountListSaved: () -> Unit,
) {
    private val mapper = ObjectMapper()
    private var data: AccountStoreData? = null
    private var busy = false
    private val dialog = JDialog(
        SwingUtilities.getWindowAncestor(parent),
        msg("taskLauncher.accounts"),
        Dialog.ModalityType.MODELESS,
    )

    private val accountListArea = JTextArea(8, 54)
    private val listSave = JButton(msg("annotation.save"))
    private val overrideAccount = JComboBox<String>()
    private val overrideTarget = JComboBox<Target>()
    private val overrideSummary = JLabel()
    private val overrideEdit = JButton(msg("taskLauncher.accountEditOverride"))
    private val overrideClear = JButton(msg("taskLauncher.accountClearOverride"))
    private val mapAccount = JComboBox<String>()
    private val mapArea = JTextArea(9, 54)
    private val mapSave = JButton(msg("annotation.save"))
    private val reload = JButton(msg("taskLauncher.accountReload"))
    private val status = JLabel()

    private data class Target(
        val id: String,
        val label: String,
        val storageName: String,
        val fields: List<TaskLauncherService.TaskParamField>,
    ) {
        override fun toString(): String = label
    }

    init {
        accountListArea.lineWrap = false
        mapArea.lineWrap = false
        val tabs = JTabbedPane().apply {
            addTab(msg("taskLauncher.accountList"), buildListTab())
            addTab(msg("taskLauncher.accountOverrides"), buildOverrideTab())
            addTab(msg("taskLauncher.accountMap"), buildMapTab())
        }
        val close = JButton(msg("annotation.cancel")).apply { addActionListener { dialog.dispose() } }
        val bottom = JPanel(BorderLayout(8, 0)).apply {
            add(status, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
                add(reload)
                add(close)
            }, BorderLayout.EAST)
        }
        dialog.contentPane = JPanel(BorderLayout(8, 8)).apply {
            add(tabs, BorderLayout.CENTER)
            add(bottom, BorderLayout.SOUTH)
        }
        dialog.minimumSize = Dimension(640, 420)
        dialog.setSize(760, 560)
        dialog.setLocationRelativeTo(parent)
        listSave.addActionListener {
            perform(service.setListText(projectDir, accountListArea.text), "taskLauncher.accountSaved") {
                onAccountListSaved()
            }
        }
        overrideAccount.addActionListener { updateOverrideSummary() }
        overrideTarget.addActionListener { updateOverrideSummary() }
        overrideEdit.addActionListener { editOverride() }
        overrideClear.addActionListener { clearOverride() }
        mapAccount.addActionListener { loadMapText() }
        mapSave.addActionListener {
            val account = mapAccount.selectedItem as? String ?: return@addActionListener
            perform(service.setMapContent(projectDir, account, mapArea.text), "taskLauncher.accountSaved")
        }
        reload.addActionListener { load() }
        setBusy(false)
    }

    fun open() {
        dialog.isVisible = true
        load()
    }

    fun isOpen(): Boolean = dialog.isDisplayable

    fun focus() {
        dialog.toFront()
        dialog.requestFocus()
    }

    fun close() {
        dialog.dispose()
    }

    fun updateMetadata(
        info: TaskLauncherService.MultiAccountInfo,
        schemas: Map<String, TaskLauncherService.TaskSchema>,
        globalGroups: List<TaskLauncherService.GlobalConfigGroup>,
    ) {
        this.info = info
        this.schemas = schemas
        this.globalGroups = globalGroups
        refreshTargets()
    }

    private fun buildListTab(): JPanel = JPanel(BorderLayout(8, 8)).apply {
        border = javax.swing.BorderFactory.createEmptyBorder(12, 12, 12, 12)
        add(JLabel(msg("taskLauncher.accountListHint")), BorderLayout.NORTH)
        add(JBScrollPane(accountListArea), BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply { add(listSave) }, BorderLayout.SOUTH)
    }

    private fun buildOverrideTab(): JPanel = JPanel(BorderLayout(8, 8)).apply {
        border = javax.swing.BorderFactory.createEmptyBorder(12, 12, 12, 12)
        val selectors = JPanel(GridLayout(2, 2, 8, 8)).apply {
            add(JLabel(msg("taskLauncher.accountName")))
            add(overrideAccount)
            add(JLabel(msg("taskLauncher.accountTarget")))
            add(overrideTarget)
        }
        add(selectors, BorderLayout.NORTH)
        add(overrideSummary, BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            add(overrideClear)
            add(overrideEdit)
        }, BorderLayout.SOUTH)
    }

    private fun buildMapTab(): JPanel = JPanel(BorderLayout(8, 8)).apply {
        border = javax.swing.BorderFactory.createEmptyBorder(12, 12, 12, 12)
        add(JPanel(BorderLayout(8, 0)).apply {
            add(JLabel(msg("taskLauncher.accountName")), BorderLayout.WEST)
            add(mapAccount, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        add(JBScrollPane(mapArea), BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply {
            add(JLabel(msg("taskLauncher.accountMapHint")), BorderLayout.WEST)
            add(mapSave, BorderLayout.EAST)
        }, BorderLayout.SOUTH)
    }

    private fun load() {
        perform(service.load(projectDir), "taskLauncher.accountLoaded")
    }

    private fun perform(
        future: CompletableFuture<AccountStoreData>,
        successKey: String,
        afterSuccess: () -> Unit = {},
    ) {
        setBusy(true)
        status.text = msg("taskLauncher.accountLoading")
        future.whenComplete { result, failure ->
            SwingUtilities.invokeLater {
                if (!dialog.isDisplayable) return@invokeLater
                setBusy(false)
                if (failure != null) {
                    val error = (failure.cause ?: failure).message.orEmpty()
                    status.text = msg("taskLauncher.accountOperationFailed", error)
                    JOptionPane.showMessageDialog(
                        dialog,
                        status.text,
                        msg("taskLauncher.accounts"),
                        JOptionPane.ERROR_MESSAGE,
                    )
                } else if (result != null) {
                    refreshData(result)
                    status.text = msg(successKey)
                    afterSuccess()
                }
            }
        }
    }

    private fun refreshData(fresh: AccountStoreData) {
        val oldOverride = overrideAccount.selectedItem as? String
        val oldMap = mapAccount.selectedItem as? String
        data = fresh
        accountListArea.text = fresh.accountListText
        val names = fresh.accountListText.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()
        overrideAccount.removeAllItems()
        mapAccount.removeAllItems()
        names.forEach {
            overrideAccount.addItem(it)
            mapAccount.addItem(it)
        }
        if (oldOverride != null && names.contains(oldOverride)) overrideAccount.selectedItem = oldOverride
        if (oldMap != null && names.contains(oldMap)) mapAccount.selectedItem = oldMap
        refreshTargets()
        loadMapText()
    }

    private fun refreshTargets() {
        val previous = (overrideTarget.selectedItem as? Target)?.id
        overrideTarget.removeAllItems()
        val enabled = info.enabledTasks
        val targets = mutableListOf<Target>()
        for ((key, entry) in enabled) {
            if (entry.keys.isEmpty()) continue
            if (entry.global) {
                val group = globalGroups.firstOrNull { it.name == key } ?: continue
                targets.add(Target(
                    id = "global:" + key,
                    label = group.displayName ?: group.name,
                    storageName = entry.storageName,
                    fields = group.fields.filter { it.key in entry.keys },
                ))
            } else {
                val schema = schemas[key] ?: continue
                if (schema.broken) continue
                val fields = schema.fields.filter { it.key in entry.keys }
                if (fields.isEmpty()) continue
                targets.add(Target(
                    id = "task:" + key,
                    label = schema.displayName ?: key.substringAfter("::", key),
                    storageName = entry.storageName,
                    fields = fields,
                ))
            }
        }
        targets.sortedWith(compareBy<Target>({ !it.id.startsWith("task:") }, { it.label })).forEach {
            overrideTarget.addItem(it)
        }
        if (previous != null) {
            for (index in 0 until overrideTarget.itemCount) {
                if (overrideTarget.getItemAt(index).id == previous) {
                    overrideTarget.selectedIndex = index
                    break
                }
            }
        }
        updateOverrideSummary()
    }

    private fun updateOverrideSummary() {
        val account = overrideAccount.selectedItem as? String
        val target = overrideTarget.selectedItem as? Target
        val existing = if (account != null && target != null) overrideFor(account, target) else emptyMap()
        overrideSummary.text = when {
            account == null -> msg("taskLauncher.accountNoAccounts")
            target == null -> msg("taskLauncher.accountNoTargets")
            else -> msg("taskLauncher.accountOverrideSummary", existing.size, target.fields.size)
        }
        overrideEdit.isEnabled = !busy && account != null && target != null
        overrideClear.isEnabled = overrideEdit.isEnabled
    }

    private fun editOverride() {
        val account = overrideAccount.selectedItem as? String ?: return
        val target = overrideTarget.selectedItem as? Target ?: return
        val existing = overrideFor(account, target)
        val group = TaskLauncherService.GlobalConfigGroup(
            name = target.storageName,
            displayName = target.label,
            fields = target.fields.map { it.copy(value = it.default ?: it.value) },
        )
        val edited = GlobalConfigEditor.show(dialog, group, existing, sparse = true) ?: return
        if (edited.values == existing) return
        perform(service.setOverride(projectDir, account, target.storageName, edited.values), "taskLauncher.accountSaved")
    }

    private fun clearOverride() {
        val account = overrideAccount.selectedItem as? String ?: return
        val target = overrideTarget.selectedItem as? Target ?: return
        perform(service.clearOverride(projectDir, account, target.storageName), "taskLauncher.accountCleared")
    }

    private fun overrideFor(account: String, target: Target): Map<String, Any?> {
        val snapshot = data ?: return emptyMap()
        val id = resolveAccountId(snapshot, account)
        if (id.isEmpty()) return emptyMap()
        val node = snapshot.accounts.path(id).path(target.storageName)
        if (!node.isObject) return emptyMap()
        @Suppress("UNCHECKED_CAST")
        val raw = mapper.convertValue(node, Map::class.java) as? Map<String, Any?> ?: return emptyMap()
        val fieldsByKey = target.fields.associateBy { it.key }
        return raw.mapValues { (key, value) -> coerceValue(fieldsByKey[key], value) }
    }

    private fun coerceValue(field: TaskLauncherService.TaskParamField?, value: Any?): Any? {
        val reference = field?.default ?: field?.value
        if (value !is String) return value
        return when (reference) {
            is Boolean -> when (value.trim().lowercase()) {
                "true", "1" -> true
                "false", "0", "" -> false
                else -> value
            }
            is Int -> value.toIntOrNull() ?: value
            is Long -> value.toLongOrNull() ?: value
            is Number -> value.toDoubleOrNull() ?: value
            else -> value
        }
    }

    private fun loadMapText() {
        val account = mapAccount.selectedItem as? String
        val snapshot = data
        val id = if (account != null && snapshot != null) resolveAccountId(snapshot, account) else ""
        mapArea.text = if (id.isNotEmpty()) snapshot?.mapContents?.path(id)?.asText("") ?: "" else ""
        mapSave.isEnabled = !busy && account != null
    }

    private fun resolveAccountId(snapshot: AccountStoreData, username: String): String {
        val entries = snapshot.registry.fields()
        while (entries.hasNext()) {
            val (id, meta) = entries.next()
            if (meta.path("username").asText() == username) return id
            val aliases: JsonNode = meta.path("aliases")
            if (aliases.isArray && aliases.any { it.asText() == username }) return id
        }
        return username.takeIf { snapshot.accounts.has(it) }.orEmpty()
    }

    private fun setBusy(value: Boolean) {
        busy = value
        reload.isEnabled = !value
        accountListArea.isEnabled = !value
        listSave.isEnabled = !value && data != null
        overrideAccount.isEnabled = !value && overrideAccount.itemCount > 0
        overrideTarget.isEnabled = !value && overrideTarget.itemCount > 0
        mapAccount.isEnabled = !value && mapAccount.itemCount > 0
        mapArea.isEnabled = !value && mapAccount.itemCount > 0
        mapSave.isEnabled = !value && mapAccount.itemCount > 0
        updateOverrideSummary()
    }

    private fun msg(key: String, vararg args: Any): String = OkScriptToolkitBundle.message(key, *args)
}
