package com.aicode.feature.terminal.ui

import com.aicode.feature.terminal.model.CommonCommand
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.Font
import javax.swing.JComponent
import javax.swing.ScrollPaneConstants

object CommonCommandDialog {
    fun showAdd(initialValue: String = ""): CommonCommand? =
        CommandEditorDialog("Add Command", CommonCommand(initialValue)).showAndGetValue()

    fun showEdit(initialValue: CommonCommand): CommonCommand? =
        CommandEditorDialog("Edit Command", initialValue).showAndGetValue()
}

private class CommandEditorDialog(
    title: String,
    initialValue: CommonCommand,
) : DialogWrapper(true) {
    private val original = initialValue
    private val commandEditor = JBTextArea(initialValue.command, 4, 56).apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(4, 8)
        accessibleContext.accessibleName = "Command"
    }
    private val descriptionEditor = JBTextArea(initialValue.description, 4, 56).apply {
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(4, 8)
        emptyText.text = "What does this command do?"
        accessibleContext.accessibleName = "Description"
    }
    private val tagsEditor = JBTextArea(initialValue.tags.joinToString(", "), 3, 56).apply {
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(4, 8)
        emptyText.text = "e.g. Git, Build"
        accessibleContext.accessibleName = "Tags"
    }
    private val centerPanel by lazy { buildCenterPanel() }

    init {
        this.title = title
        setOKButtonText("Save")
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
        .addComponent(fieldLabel("Tags (comma separated)"))
        .addComponent(scrollPane(tagsEditor))
        .panel
        .apply { border = JBUI.Borders.empty(8, 12, 4, 12) }

    override fun getPreferredFocusedComponent(): JComponent = commandEditor

    override fun doValidate(): ValidationInfo? =
        if (commandEditor.text.isBlank()) ValidationInfo("Command is required.", commandEditor) else null

    fun showAndGetValue(): CommonCommand? {
        if (!showAndGet()) return null
        return commandEditor.text.trim().takeIf(String::isNotEmpty)?.let {
            original.copy(command = it, description = descriptionEditor.text.trim(),
                tags = tagsEditor.text.split(',').map(String::trim).filter(String::isNotEmpty).distinct())
        }
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
