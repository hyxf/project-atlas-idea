package com.aicode.feature.terminal.settings

import com.aicode.feature.terminal.data.DefaultCommonCommands
import com.aicode.feature.terminal.model.CommonCommand
import com.aicode.feature.terminal.service.CommonCommandService
import com.aicode.feature.terminal.ui.CommonCommandDialog
import com.aicode.feature.terminal.ui.CommonCommandListCellRenderer
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.Messages
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

class CommonCommandConfigurable : Configurable {
    private val service = CommonCommandService.getInstance()
    private var savedCommands: List<CommonCommand> = emptyList()
    private val workingCommands = mutableListOf<CommonCommand>()
    private var panel: JPanel? = null
    private var searchField: SearchTextField? = null
    private var commandList: JBList<CommonCommand>? = null
    private var listModel: DefaultListModel<CommonCommand>? = null

    override fun getDisplayName() = "Common Commands"

    override fun createComponent(): JComponent {
        val model = DefaultListModel<CommonCommand>()
        val list = JBList(model).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            fixedCellHeight = JBUI.scale(52)
            cellRenderer = CommonCommandListCellRenderer()
            emptyText.text = "No commands."
        }
        val search = SearchTextField(false).apply {
            textEditor.emptyText.text = "Search commands"
            textEditor.document.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(event: DocumentEvent) = refreshList()
            })
        }
        val toolbarPanel = ToolbarDecorator.createDecorator(list)
            .setAddAction { addCommand() }
            .setEditAction { editCommand() }
            .setEditActionUpdater { canModifySelectedCommand() }
            .setRemoveAction { removeCommand() }
            .setRemoveActionUpdater { canModifySelectedCommand() }
            .setMoveUpAction { moveSelectedCommand(-1) }
            .setMoveDownAction { moveSelectedCommand(1) }
            .addExtraAction(object : AnAction(
                "Restore Defaults", "Restore missing built-in commands", AllIcons.Actions.Rollback,
            ) {
                override fun actionPerformed(event: AnActionEvent) = restoreDefaultCommands()
            }).createPanel()
        listModel = model
        commandList = list
        searchField = search
        panel = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            add(search, BorderLayout.NORTH)
            add(toolbarPanel, BorderLayout.CENTER)
        }
        reset()
        return panel!!
    }

    override fun isModified() = workingCommands != savedCommands

    override fun apply() {
        try {
            service.saveCommands(workingCommands)
            savedCommands = workingCommands.toList()
        } catch (ex: IllegalStateException) {
            throw ConfigurationException(ex.message ?: "Failed to save common commands.")
        }
    }

    override fun reset() {
        savedCommands = try {
            service.getCommands()
        } catch (ex: IllegalStateException) {
            Messages.showErrorDialog(ex.message ?: "Failed to load common commands.", "Common Commands")
            emptyList()
        }
        workingCommands.clear()
        workingCommands.addAll(savedCommands)
        refreshList()
    }

    override fun disposeUIResources() {
        panel = null
        searchField = null
        commandList = null
        listModel = null
        workingCommands.clear()
        savedCommands = emptyList()
    }

    private fun addCommand() {
        val value = CommonCommandDialog.showAdd() ?: return
        if (!validateUnique(value, null)) return
        workingCommands.add(value)
        refreshList(value)
    }

    private fun editCommand() {
        val oldValue = commandList?.selectedValue ?: return
        if (DefaultCommonCommands.contains(oldValue)) return
        val value = CommonCommandDialog.showEdit(oldValue) ?: return
        if (!validateUnique(value, oldValue)) return
        val index = workingCommands.indexOf(oldValue)
        if (index >= 0) workingCommands[index] = value
        refreshList(value)
    }

    private fun removeCommand() {
        val selected = commandList?.selectedValue ?: return
        if (DefaultCommonCommands.contains(selected)) return
        workingCommands.remove(selected)
        refreshList()
    }

    private fun canModifySelectedCommand(): Boolean {
        val selected = commandList?.selectedValue ?: return false
        return !DefaultCommonCommands.contains(selected)
    }

    private fun moveSelectedCommand(offset: Int) {
        val list = commandList ?: return
        val model = listModel ?: return
        val selectedIndex = list.selectedIndex
        val targetIndex = selectedIndex + offset
        if (selectedIndex !in 0 until model.size || targetIndex !in 0 until model.size) return
        val sourceIndex = workingCommands.indexOf(model.getElementAt(selectedIndex))
        val destinationIndex = workingCommands.indexOf(model.getElementAt(targetIndex))
        if (sourceIndex < 0 || destinationIndex < 0) return
        val selected = workingCommands[sourceIndex]
        workingCommands[sourceIndex] = workingCommands[destinationIndex]
        workingCommands[destinationIndex] = selected
        refreshList(selected)
    }

    private fun restoreDefaultCommands() {
        val missing = DefaultCommonCommands.missingFrom(workingCommands)
        if (missing.isEmpty()) return
        workingCommands.addAll(missing)
        refreshList(missing.first())
    }

    private fun validateUnique(value: CommonCommand, oldValue: CommonCommand?): Boolean {
        if (value.command != oldValue?.command && workingCommands.any { it.command == value.command }) {
            Messages.showErrorDialog("This command already exists.", "Duplicate Command")
            return false
        }
        return true
    }

    private fun refreshList(selectedValue: CommonCommand? = null) {
        val model = listModel ?: return
        val keyword = searchField?.text.orEmpty().trim()
        val filtered = if (keyword.isEmpty()) workingCommands else workingCommands.filter {
            it.command.contains(keyword, ignoreCase = true) || it.description.contains(keyword, ignoreCase = true)
        }
        model.clear()
        filtered.forEach(model::addElement)
        val selection = selectedValue?.let(filtered::indexOf) ?: -1
        if (selection >= 0) commandList?.selectedIndex = selection
    }
}
