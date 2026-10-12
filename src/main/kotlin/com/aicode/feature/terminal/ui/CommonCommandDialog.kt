package com.aicode.feature.terminal.ui

import com.aicode.feature.terminal.model.CommonCommand
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.CheckBoxList
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.JComponent
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.event.DocumentEvent

object CommonCommandDialog {
    fun showAdd(initialValue: String = "", availableTags: Collection<String> = emptyList()): CommonCommand? =
        CommandEditorDialog("Add Command", CommonCommand(initialValue), availableTags).showAndGetValue()

    fun showEdit(initialValue: CommonCommand, availableTags: Collection<String> = emptyList()): CommonCommand? =
        CommandEditorDialog("Edit Command", initialValue, availableTags).showAndGetValue()
}

private class CommandEditorDialog(
    title: String,
    initialValue: CommonCommand,
    availableTags: Collection<String>,
) : DialogWrapper(true) {
    private val original = initialValue
    private val commandEditor = JBTextArea(initialValue.command, 3, 56).apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(4, 8)
        accessibleContext.accessibleName = "Command"
    }
    private val descriptionEditor = JBTextArea(initialValue.description, 3, 56).apply {
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(4, 8)
        emptyText.text = "What does this command do?"
        accessibleContext.accessibleName = "Description"
    }
    private val tagList = CheckBoxList<String>().apply {
        (availableTags + initialValue.tags).map(String::trim).filter(String::isNotBlank)
            .distinctBy(String::lowercase).sortedWith(String.CASE_INSENSITIVE_ORDER)
            .forEach { tag -> addItem(tag, tag, initialValue.tags.any { it.equals(tag, ignoreCase = true) }) }
        border = JBUI.Borders.empty(4, 8)
        emptyText.text = "No tags yet. Add one below."
        accessibleContext.accessibleName = "Available tags"
    }
    private val newTagEditor = JBTextField().apply {
        emptyText.text = "Enter a new tag"
        accessibleContext.accessibleName = "New tag"
    }
    private val addTagButton = JButton("Add").apply {
        isEnabled = false
        addActionListener { addTypedTag() }
    }
    private val tagsPanel = JPanel(BorderLayout(JBUI.scale(6), JBUI.scale(6))).apply {
        preferredSize = Dimension(JBUI.scale(620), JBUI.scale(170))
        add(JBScrollPane(tagList), BorderLayout.CENTER)
        add(JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            add(newTagEditor, BorderLayout.CENTER)
            add(addTagButton, BorderLayout.EAST)
        }, BorderLayout.SOUTH)
    }
    private val centerPanel by lazy { buildCenterPanel() }

    init {
        this.title = title
        setOKButtonText("Save")
        newTagEditor.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) {
                addTagButton.isEnabled = newTagEditor.text.trim().isNotEmpty()
            }
        })
        newTagEditor.addActionListener { addTypedTag() }
        init()
    }

    override fun createCenterPanel(): JComponent = centerPanel

    private fun buildCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addComponent(fieldLabel("Command"))
        .addComponent(scrollPane(commandEditor))
        .addVerticalGap(JBUI.scale(12))
        .addComponent(fieldLabel("Description"))
        .addComponent(scrollPane(descriptionEditor))
        .addVerticalGap(JBUI.scale(12))
        .addComponent(fieldLabel("Tags"))
        .addComponent(tagsPanel)
        .panel
        .apply { border = JBUI.Borders.empty(8, 12, 4, 12) }

    override fun getPreferredFocusedComponent(): JComponent = commandEditor

    override fun doValidate(): ValidationInfo? =
        if (commandEditor.text.isBlank()) ValidationInfo("Command is required.", commandEditor) else null

    fun showAndGetValue(): CommonCommand? {
        if (!showAndGet()) return null
        return commandEditor.text.trim().takeIf(String::isNotEmpty)?.let {
            original.copy(command = it, description = descriptionEditor.text.trim(),
                tags = (0 until tagList.model.size).filter(tagList::isItemSelected)
                    .mapNotNull(tagList::getItemAt))
        }
    }

    private fun addTypedTag() {
        val tag = newTagEditor.text.trim()
        if (tag.isEmpty()) return
        val existingTag = (0 until tagList.model.size).mapNotNull(tagList::getItemAt).firstOrNull {
            it.equals(tag, ignoreCase = true)
        }
        if (existingTag != null) {
            tagList.setItemSelected(existingTag, true)
        } else {
            tagList.addItem(tag, tag, true)
        }
        newTagEditor.text = ""
    }

    private fun fieldLabel(text: String) = JBLabel(text).apply { font = JBFont.label().asBold() }

    private fun scrollPane(editor: JBTextArea) = JBScrollPane(
        editor,
        ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
    ).apply {
        preferredSize = Dimension(JBUI.scale(620), editor.preferredSize.height + JBUI.scale(8))
    }
}
