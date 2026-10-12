package com.aicode.feature.git.settings

import com.aicode.feature.git.data.DefaultCommitMessages
import com.aicode.feature.git.model.CommitMessageTemplate
import com.aicode.feature.git.service.CommonCommitMessageService
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

class CommonCommitMessageConfigurable : Configurable {
    private val service = CommonCommitMessageService.getInstance()
    private var savedMessages: List<CommitMessageTemplate> = emptyList()
    private val workingMessages = mutableListOf<CommitMessageTemplate>()
    private var panel: JPanel? = null
    private var searchField: SearchTextField? = null
    private var messageList: JBList<CommitMessageTemplate>? = null
    private var listModel: DefaultListModel<CommitMessageTemplate>? = null

    override fun getDisplayName() = "Common Commit Messages"

    override fun createComponent(): JComponent {
        val model = DefaultListModel<CommitMessageTemplate>()
        val list = JBList(model).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            fixedCellHeight = JBUI.scale(30)
            cellRenderer =
                object : ColoredListCellRenderer<CommitMessageTemplate>() {
                    override fun customizeCellRenderer(
                        list: JList<out CommitMessageTemplate>,
                        value: CommitMessageTemplate,
                        index: Int,
                        selected: Boolean,
                        hasFocus: Boolean,
                    ) {
                        border = JBUI.Borders.empty(0, 10)
                        append(value.formatted(), SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    }
                }
            emptyText.text = "No commit messages."
        }
        val search = SearchTextField(false).apply {
            textEditor.emptyText.text = "Search commit messages"
            textEditor.document.addDocumentListener(
                object : DocumentAdapter() {
                    override fun textChanged(event: DocumentEvent) = refreshList()
                }
            )
        }
        val toolbarPanel =
            ToolbarDecorator.createDecorator(list)
                .setAddAction { addMessage() }
                .setEditAction { editMessage() }
                .setEditActionUpdater { canModifySelectedMessage() }
                .setRemoveAction { removeMessage() }
                .setRemoveActionUpdater { canModifySelectedMessage() }
                .setMoveUpAction { moveSelectedMessage(-1) }
                .setMoveDownAction { moveSelectedMessage(1) }
                .addExtraAction(
                    object : AnAction(
                        "Restore Defaults",
                        "Restore missing built-in commit messages",
                        AllIcons.Actions.Rollback,
                    ) {
                        override fun actionPerformed(event: AnActionEvent) = restoreDefaultMessages()
                    }
                )
                .createPanel()
        listModel = model
        messageList = list
        searchField = search
        panel =
            JPanel(BorderLayout(0, JBUI.scale(8))).apply {
                add(search, BorderLayout.NORTH)
                add(toolbarPanel, BorderLayout.CENTER)
            }
        reset()
        return panel!!
    }

    override fun isModified(): Boolean = workingMessages != savedMessages

    override fun apply() {
        try {
            service.saveTemplates(workingMessages)
            savedMessages = workingMessages.toList()
        } catch (ex: IllegalStateException) {
            throw ConfigurationException(ex.message ?: "Failed to save commit messages.")
        }
    }

    override fun reset() {
        savedMessages =
            try {
                service.getTemplates()
            } catch (ex: IllegalStateException) {
                Messages.showErrorDialog(
                    ex.message ?: "Failed to load commit messages.",
                    "Common Commit Messages",
                )
                emptyList()
            }
        workingMessages.clear()
        workingMessages.addAll(savedMessages)
        refreshList()
    }

    override fun disposeUIResources() {
        panel = null
        searchField = null
        messageList = null
        listModel = null
        workingMessages.clear()
        savedMessages = emptyList()
    }

    private fun addMessage() {
        val value = promptForMessage("Add Commit Message", null) ?: return
        if (!validateUnique(value, null)) return
        workingMessages.add(value)
        refreshList(value)
    }

    private fun editMessage() {
        val oldValue = messageList?.selectedValue ?: return
        if (oldValue in DefaultCommitMessages.templates) return
        val value = promptForMessage("Edit Commit Message", oldValue) ?: return
        if (!validateUnique(value, oldValue)) return
        val index = workingMessages.indexOf(oldValue)
        if (index >= 0) workingMessages[index] = value
        refreshList(value)
    }

    private fun removeMessage() {
        val selected = messageList?.selectedValue ?: return
        if (selected in DefaultCommitMessages.templates) return
        workingMessages.remove(selected)
        refreshList()
    }

    private fun canModifySelectedMessage(): Boolean {
        val selected = messageList?.selectedValue ?: return false
        return selected !in DefaultCommitMessages.templates
    }

    private fun promptForMessage(title: String, initialValue: CommitMessageTemplate?): CommitMessageTemplate? =
        CommitMessageEditorDialog(title, initialValue).showAndGetValue()

    private fun moveSelectedMessage(offset: Int) {
        val list = messageList ?: return
        val model = listModel ?: return
        val selectedIndex = list.selectedIndex
        val targetIndex = selectedIndex + offset
        if (selectedIndex !in 0 until model.size || targetIndex !in 0 until model.size) return

        val selected = model.getElementAt(selectedIndex)
        val target = model.getElementAt(targetIndex)
        val sourceIndex = workingMessages.indexOf(selected)
        val destinationIndex = workingMessages.indexOf(target)
        if (sourceIndex < 0 || destinationIndex < 0) return

        workingMessages[sourceIndex] = target
        workingMessages[destinationIndex] = selected
        refreshList(selected)
    }

    private fun restoreDefaultMessages() {
        val missing = DefaultCommitMessages.missingFrom(workingMessages)
        if (missing.isEmpty()) return
        workingMessages.addAll(missing)
        refreshList(missing.first())
    }

    private fun validateUnique(value: CommitMessageTemplate, oldValue: CommitMessageTemplate?): Boolean {
        if (value != oldValue && workingMessages.any { it.formatted() == value.formatted() }) {
            Messages.showErrorDialog("This commit message already exists.", "Duplicate Commit Message")
            return false
        }
        return true
    }

    private fun refreshList(selectedValue: CommitMessageTemplate? = null) {
        val model = listModel ?: return
        val keyword = searchField?.text.orEmpty().trim()
        val filtered =
            if (keyword.isEmpty()) workingMessages
            else workingMessages.filter { it.formatted().contains(keyword, ignoreCase = true) }
        model.clear()
        filtered.forEach(model::addElement)
        val selection = selectedValue?.let(filtered::indexOf) ?: -1
        if (selection >= 0) messageList?.selectedIndex = selection
    }
}

private class CommitMessageEditorDialog(
    title: String,
    initialValue: CommitMessageTemplate?,
) : DialogWrapper(true) {
    private val typeSelector = JComboBox(COMMIT_TYPES.toTypedArray()).apply {
        selectedItem = COMMIT_TYPES.firstOrNull { it.id == initialValue?.type } ?: COMMIT_TYPES.first()
    }
    private val scopeEditor = JBTextField(initialValue?.scope.orEmpty())
    private val subjectEditor =
        JBTextArea(initialValue?.subject.orEmpty(), 4, 50).apply {
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty(6, 8)
        }

    init {
        this.title = title
        setOKButtonText("Save")
        init()
    }

    override fun createCenterPanel(): JComponent =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(fullWidthLabel("Type:"))
            add(Box.createVerticalStrut(JBUI.scale(4)))
            add(fullWidth(typeSelector))
            add(Box.createVerticalStrut(JBUI.scale(10)))
            add(fullWidthLabel("Scope (optional):"))
            add(Box.createVerticalStrut(JBUI.scale(4)))
            add(fullWidth(scopeEditor))
            add(Box.createVerticalStrut(JBUI.scale(10)))
            add(fullWidthLabel("Subject:"))
            add(Box.createVerticalStrut(JBUI.scale(4)))
            add(
                fullWidth(
                    JBScrollPane(subjectEditor).apply {
                        preferredSize = Dimension(JBUI.scale(560), JBUI.scale(110))
                    }
                )
            )
        }

    override fun getPreferredFocusedComponent(): JComponent = subjectEditor

    override fun doValidate(): ValidationInfo? =
        if (subjectEditor.text.isBlank()) ValidationInfo("Subject is required.", subjectEditor) else null

    fun showAndGetValue(): CommitMessageTemplate? {
        if (!showAndGet()) return null
        val subject = subjectEditor.text.trim().takeIf(String::isNotEmpty) ?: return null
        val type = (typeSelector.selectedItem as CommitTypeOption).id
        return CommitMessageTemplate(
            type = type,
            scope = scopeEditor.text.trim().takeIf(String::isNotEmpty),
            subject = subject,
        )
    }

    private fun <T : JComponent> fullWidth(component: T): T =
        component.apply {
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }

    private fun fullWidthLabel(text: String): JLabel =
        JLabel(text).apply { alignmentX = Component.LEFT_ALIGNMENT }

    companion object {
        private val COMMIT_TYPES =
            listOf(
                CommitTypeOption("feat", "新增功能"),
                CommitTypeOption("fix", "修复 Bug"),
                CommitTypeOption("docs", "仅文档变更"),
                CommitTypeOption("style", "代码格式调整（不影响运行）"),
                CommitTypeOption("refactor", "代码重构（非新增功能或修复）"),
                CommitTypeOption("perf", "性能优化"),
                CommitTypeOption("test", "增加或修改测试用例"),
                CommitTypeOption("build", "构建系统或外部依赖变更"),
                CommitTypeOption("ci", "CI 配置文件和脚本变更"),
                CommitTypeOption("chore", "杂务（不修改 src 或 test）"),
                CommitTypeOption("revert", "撤销之前的 commit"),
            )
    }
}

private data class CommitTypeOption(
    val id: String,
    val description: String,
) {
    override fun toString(): String = "$id — $description"
}
