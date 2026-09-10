package com.aicode.feature.terminal.ui

import com.aicode.feature.terminal.model.CommonCommand
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
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
    private val commandEditor =
        JBTextArea(initialValue.command, 6, 56).apply {
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty(8)
        }
    private val descriptionEditor = JBTextArea(initialValue.description, 2, 56).apply {
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(6, 8)
        emptyText.text = "Briefly explain what this command does"
    }

    init {
        this.title = title
        setOKButtonText("Save")
        init()
    }

    override fun createCenterPanel(): JComponent =
        JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            minimumSize = Dimension(JBUI.scale(420), JBUI.scale(140))
            preferredSize = Dimension(JBUI.scale(520), JBUI.scale(220))
            maximumSize = Dimension(JBUI.scale(640), JBUI.scale(300))
            add(JPanel(BorderLayout(0, JBUI.scale(4))).apply {
                add(JBLabel("Command"), BorderLayout.NORTH)
                add(
                    JBScrollPane(commandEditor).apply {
                        border = JBUI.Borders.customLine(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground())
                    },
                    BorderLayout.CENTER,
                )
            }, BorderLayout.CENTER)
            add(JPanel(BorderLayout(0, JBUI.scale(4))).apply {
                border = JBUI.Borders.emptyTop(8)
                add(JBLabel("Description"), BorderLayout.NORTH)
                add(
                    JBScrollPane(descriptionEditor).apply {
                        border = JBUI.Borders.customLine(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground())
                    },
                    BorderLayout.CENTER,
                )
            }, BorderLayout.SOUTH)
        }

    override fun getPreferredFocusedComponent(): JComponent = commandEditor

    override fun doValidate(): ValidationInfo? =
        if (commandEditor.text.isBlank()) ValidationInfo("Command is required.", commandEditor) else null

    fun showAndGetValue(): CommonCommand? {
        if (!showAndGet()) return null
        return commandEditor.text.trim().takeIf(String::isNotEmpty)?.let {
            CommonCommand(it, descriptionEditor.text.trim())
        }
    }
}
