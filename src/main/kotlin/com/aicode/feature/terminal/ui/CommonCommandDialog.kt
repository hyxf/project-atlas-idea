package com.aicode.feature.terminal.ui

import com.aicode.feature.terminal.model.CommonCommand
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.JComponent
import javax.swing.JPanel

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
        border = JBUI.Borders.empty(6, 8)
    }
    private val descriptionEditor = JBTextArea(initialValue.description, 4, 56).apply {
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(6, 8)
        emptyText.text = "What does this command do?"
    }
    private val tagsEditor = JBTextArea(initialValue.tags.joinToString(", "), 3, 56).apply {
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(6, 8)
        emptyText.text = "e.g. Git, Build"
    }
    private val centerPanel by lazy { buildCenterPanel() }

    init {
        this.title = title
        setOKButtonText("Save")
        init()
    }

    override fun createCenterPanel(): JComponent = centerPanel

    private fun buildCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(12))).apply {
        preferredSize = Dimension(JBUI.scale(700), JBUI.scale(540))
        border = JBUI.Borders.empty(8, 8, 4, 8)

        add(section("Command", scrollPane(commandEditor, 4)), BorderLayout.CENTER)
        add(JPanel(BorderLayout(0, JBUI.scale(10))).apply {
            preferredSize = Dimension(0, JBUI.scale(275))
            add(section("Description", scrollPane(descriptionEditor, 4)), BorderLayout.CENTER)
            add(section("Tags", scrollPane(tagsEditor, 3)), BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)
    }

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

    private fun section(title: String, component: JComponent): JPanel = JPanel(BorderLayout(0, JBUI.scale(7))).apply {
        add(JBLabel(title).apply { font = JBFont.label().asBold() }, BorderLayout.NORTH)
        add(component, BorderLayout.CENTER)
    }

    private fun scrollPane(editor: JBTextArea, rows: Int) = JBScrollPane(editor).apply {
        val height = editor.getFontMetrics(editor.font).height * rows + JBUI.scale(16)
        preferredSize = Dimension(0, height)
        minimumSize = Dimension(0, height)
    }
}
