package com.aicode.feature.terminal.ui

import com.aicode.feature.terminal.model.CommonCommand
import com.aicode.feature.terminal.model.CommonCommandVariable
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.GridLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
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
        border = JBUI.Borders.empty(8)
    }
    private val descriptionEditor = JBTextArea(initialValue.description, 2, 56).apply {
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(6, 8)
        emptyText.text = "What does this command do?"
    }
    private val tagsEditor = JBTextField(initialValue.tags.joinToString(", ")).apply {
        emptyText.text = "e.g. Git, Build"
    }
    private var variables: List<CommonCommandVariable> = initialValue.variables
    private val variablesModel = DefaultListModel<CommonCommandVariable>().apply {
        initialValue.variables.forEach(::addElement)
    }
    private val variablesList = JBList(variablesModel).apply {
        fixedCellHeight = JBUI.scale(30)
        emptyText.text = "No command variables"
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2 && javax.swing.SwingUtilities.isLeftMouseButton(event)) editVariables()
            }
        })
        cellRenderer = object : ColoredListCellRenderer<CommonCommandVariable>() {
            override fun customizeCellRenderer(
                list: JList<out CommonCommandVariable>,
                value: CommonCommandVariable,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                border = JBUI.Borders.empty(0, 8)
                append("\${${value.name}}", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                if (value.label.isNotBlank()) append("  ${value.label}", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                val kind = if (value.type == "path") "Path: ${value.pathKind}" else when (value.type) {
                    "multiSelect" -> "Multi-select"
                    else -> value.type.replaceFirstChar(Char::uppercase)
                }
                append("  ·  $kind  ·  ${if (value.required) "Required" else "Optional"}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
    }
    private val variablesLabel = JBLabel()
    private val variablesButton = JButton("Edit Variables…").apply { addActionListener { editVariables() } }
    private val centerPanel by lazy { buildCenterPanel() }

    init {
        this.title = title
        setOKButtonText("Save")
        refreshVariables()
        init()
    }

    override fun createCenterPanel(): JComponent = centerPanel

    private fun buildCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(16))).apply {
        preferredSize = Dimension(JBUI.scale(780), JBUI.scale(320))
        border = JBUI.Borders.empty(8, 8, 4, 8)

        add(section("Command", JBScrollPane(commandEditor)), BorderLayout.CENTER)
        add(JPanel(GridLayout(1, 2, JBUI.scale(18), 0)).apply {
            preferredSize = Dimension(0, JBUI.scale(170))
            add(JPanel(BorderLayout(0, JBUI.scale(12))).apply {
                add(section("Description", JBScrollPane(descriptionEditor)), BorderLayout.CENTER)
                add(section("Tags", tagsEditor), BorderLayout.SOUTH)
            })
            add(JPanel(BorderLayout(0, JBUI.scale(7))).apply {
                add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                    add(variablesLabel.apply { font = JBFont.label().asBold() }, BorderLayout.WEST)
                    add(variablesButton, BorderLayout.EAST)
                }, BorderLayout.NORTH)
                add(JBScrollPane(variablesList), BorderLayout.CENTER)
            })
        }, BorderLayout.SOUTH)
    }

    override fun getPreferredFocusedComponent(): JComponent = commandEditor

    override fun doValidate(): ValidationInfo? =
        if (commandEditor.text.isBlank()) ValidationInfo("Command is required.", commandEditor) else null

    fun showAndGetValue(): CommonCommand? {
        if (!showAndGet()) return null
        return commandEditor.text.trim().takeIf(String::isNotEmpty)?.let {
            original.copy(command = it, description = descriptionEditor.text.trim(),
                tags = tagsEditor.text.split(',').map(String::trim).filter(String::isNotEmpty).distinct(),
                variables = variables)
        }
    }

    private fun refreshVariables() {
        variablesLabel.text = "Variables (${variables.size})"
        variablesButton.text = if (variables.isEmpty()) "Add Variables…" else "Edit Variables…"
        variablesModel.clear()
        variables.forEach(variablesModel::addElement)
    }

    private fun editVariables() {
        CommonCommandVariablesUi.edit(variables)?.let {
            variables = it
            refreshVariables()
        }
    }

    private fun section(title: String, component: JComponent): JPanel = JPanel(BorderLayout(0, JBUI.scale(7))).apply {
        add(JBLabel(title).apply { font = JBFont.label().asBold() }, BorderLayout.NORTH)
        add(component, BorderLayout.CENTER)
    }
}
