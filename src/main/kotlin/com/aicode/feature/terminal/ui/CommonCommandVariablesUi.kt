package com.aicode.feature.terminal.ui

import com.aicode.feature.terminal.model.CommonCommandVariable
import com.google.gson.JsonObject
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

object CommonCommandVariablesUi {
    fun edit(initial: List<CommonCommandVariable>, title: String = "Command Variables"): List<CommonCommandVariable>? =
        VariablesDialog(initial, title).showAndGetValue()
}

internal data class VariableDraft(
    var name: String = "",
    var label: String = "",
    var type: String = "text",
    var required: Boolean = true,
    var defaultSingle: String = "",
    var defaultMulti: String = "",
    var options: String = "",
    var pathKind: String = "any",
    val raw: JsonObject = JsonObject(),
) {
    override fun toString(): String = "${name.ifBlank { "New variable" }}  ·  $type"

    fun optionValues(): List<String> = splitValues(options).distinct()

    fun defaultValues(): List<String> = when (type) {
        "multiSelect" -> splitValues(defaultMulti).distinct()
        else -> defaultSingle.trim().takeIf(String::isNotEmpty)?.let(::listOf) ?: emptyList()
    }

    fun validationError(allNames: List<String>): String? = when {
        !NAME.matches(name.trim()) -> "Name must start with a letter or underscore and contain only letters, digits, or underscores."
        allNames.count { it.trim() == name.trim() } > 1 -> "Variable name '$name' is used more than once."
        type !in TYPES -> "Choose a supported variable type."
        type == "path" && pathKind !in PATH_KINDS -> "Choose a supported path kind."
        type in CHOICE_TYPES && optionValues().isEmpty() -> "Add at least one option."
        type in CHOICE_TYPES && defaultValues().any { it !in optionValues() } -> "Every default value must appear in Options."
        else -> null
    }

    fun toVariable(): CommonCommandVariable = CommonCommandVariable(
        name = name.trim(),
        type = type,
        label = label.trim(),
        required = required,
        defaultValue = defaultValues(),
        options = if (type in CHOICE_TYPES) optionValues() else emptyList(),
        pathKind = pathKind,
        raw = raw.deepCopy(),
    )

    companion object {
        private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
        private val TYPES = setOf("text", "select", "multiSelect", "path")
        private val CHOICE_TYPES = setOf("select", "multiSelect")
        private val PATH_KINDS = setOf("file", "folder", "any")

        fun from(variable: CommonCommandVariable): VariableDraft = VariableDraft(
            name = variable.name,
            label = variable.label,
            type = variable.type,
            required = variable.required,
            defaultSingle = if (variable.type == "multiSelect") "" else variable.defaultValue.firstOrNull().orEmpty(),
            defaultMulti = if (variable.type == "multiSelect") variable.defaultValue.joinToString("\n") else "",
            options = variable.options.joinToString("\n"),
            pathKind = variable.pathKind,
            raw = variable.raw.deepCopy(),
        )

        private fun splitValues(text: String): List<String> = text.split(Regex("[\\r\\n,]")).map(String::trim).filter(String::isNotEmpty)
    }
}

private class VariablesDialog(initial: List<CommonCommandVariable>, dialogTitle: String) : DialogWrapper(true) {
    private val drafts = DefaultListModel<VariableDraft>().apply { initial.map(VariableDraft::from).forEach(::addElement) }
    private val list = JBList(drafts).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        fixedCellHeight = JBUI.scale(30)
        emptyText.text = "No variables"
        cellRenderer = object : ColoredListCellRenderer<VariableDraft>() {
            override fun customizeCellRenderer(
                list: JList<out VariableDraft>,
                value: VariableDraft,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                border = JBUI.Borders.empty(0, 10)
                append(value.name.ifBlank { "New variable" }, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                append("  ${typeLabel(value.type)}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
    }
    private val nameField = JBTextField()
    private val labelField = JBTextField().apply { emptyText.text = "Shown in the input prompt" }
    private val typeField = JComboBox(arrayOf("text", "select", "multiSelect", "path")).apply {
        renderer = choiceRenderer(::typeLabel)
    }
    private val requiredField = JBCheckBox("Required", true)
    private val defaultSingleField = JBTextField().apply { emptyText.text = "Optional" }
    private val defaultMultiField = JBTextArea(2, 30).apply { emptyText.text = "One default per line" }
    private val optionsField = JBTextArea(3, 30).apply { emptyText.text = "One option per line" }
    private val pathKindField = JComboBox(arrayOf("any", "file", "folder")).apply {
        renderer = choiceRenderer { it.replaceFirstChar(Char::uppercase) }
    }
    private val optionsRow = field("Options", JBScrollPane(optionsField))
    private val defaultSingleRow = field("Default value", defaultSingleField)
    private val defaultMultiRow = field("Default options", JBScrollPane(defaultMultiField))
    private val pathKindRow = field("Path kind", pathKindField)
    private val requiredRow = field("", requiredField)
    private val detailsLayout = CardLayout()
    private val details = JPanel(detailsLayout)
    private var currentIndex = -1
    private var loading = false
    private val centerPanel by lazy { buildCenterPanel() }

    init {
        title = dialogTitle
        setOKButtonText("Save")
        typeField.addActionListener {
            if (!loading && currentIndex in 0 until drafts.size()) {
                drafts[currentIndex].type = typeField.selectedItem as String
                list.repaint()
            }
            updateVisibleFields()
        }
        nameField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) {
                if (!loading && currentIndex in 0 until drafts.size()) {
                    drafts[currentIndex].name = nameField.text
                    list.repaint()
                }
            }
        })
        list.addListSelectionListener {
            if (!it.valueIsAdjusting && !loading) {
                storeCurrent()
                load(list.selectedIndex)
            }
        }
        init()
        if (!drafts.isEmpty) list.selectedIndex = 0 else showEmpty()
    }

    override fun createCenterPanel(): JComponent = centerPanel

    private fun buildCenterPanel(): JComponent {
        val listPanel = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            preferredSize = Dimension(JBUI.scale(210), JBUI.scale(350))
            add(header("Variables"), BorderLayout.NORTH)
            add(ToolbarDecorator.createDecorator(list)
                .setAddAction { addVariable() }
                .setRemoveAction { removeVariable() }
                .setRemoveActionUpdater { list.selectedIndex >= 0 }
                .disableUpDownActions()
                .createPanel(), BorderLayout.CENTER)
        }
        val editor = JPanel(GridBagLayout()).apply {
            border = JBUI.Borders.empty(4, 0, 0, 4)
            listOf(
                field("Name", nameField),
                field("Display label", labelField),
                field("Type", typeField),
                requiredRow,
                optionsRow,
                defaultSingleRow,
                defaultMultiRow,
                pathKindRow,
            ).forEachIndexed { index, row ->
                add(row, GridBagConstraints().apply {
                    gridx = 0
                    gridy = index
                    weightx = 1.0
                    fill = GridBagConstraints.HORIZONTAL
                    insets = Insets(0, 0, JBUI.scale(12), 0)
                })
            }
            add(JPanel(), GridBagConstraints().apply {
                gridx = 0
                gridy = 8
                weightx = 1.0
                weighty = 1.0
                fill = GridBagConstraints.BOTH
            })
        }
        details.add(JPanel(BorderLayout()).apply {
            add(JBLabel("Select or add a variable.").apply { foreground = JBColor.GRAY }, BorderLayout.NORTH)
        }, "empty")
        details.add(editor, "editor")
        val main = JPanel(BorderLayout(JBUI.scale(16), 0)).apply {
            preferredSize = Dimension(JBUI.scale(760), JBUI.scale(350))
            border = JBUI.Borders.empty(8, 8, 4, 8)
            add(listPanel, BorderLayout.WEST)
            add(JPanel(BorderLayout(0, JBUI.scale(8))).apply {
                add(header("Variable details"), BorderLayout.NORTH)
                add(details, BorderLayout.CENTER)
            }, BorderLayout.CENTER)
        }
        return main
    }

    override fun getPreferredFocusedComponent(): JComponent = list

    override fun doValidate(): ValidationInfo? {
        storeCurrent()
        val names = (0 until drafts.size()).map { drafts[it].name }
        for (index in 0 until drafts.size()) {
            val error = drafts[index].validationError(names) ?: continue
            list.selectedIndex = index
            val component = when {
                error.startsWith("Name") || error.startsWith("Variable name") -> nameField
                error.contains("variable type") -> typeField
                error.contains("path kind") -> pathKindField
                error.startsWith("Add") -> optionsField
                else -> if (drafts[index].type == "multiSelect") defaultMultiField else defaultSingleField
            }
            return ValidationInfo(error, component)
        }
        return null
    }

    fun showAndGetValue(): List<CommonCommandVariable>? =
        if (showAndGet()) (0 until drafts.size()).map { drafts[it].toVariable() } else null

    private fun addVariable() {
        storeCurrent()
        drafts.addElement(VariableDraft())
        list.selectedIndex = drafts.size() - 1
        nameField.requestFocusInWindow()
    }

    private fun removeVariable() {
        val index = list.selectedIndex.takeIf { it >= 0 } ?: return
        loading = true
        drafts.remove(index)
        currentIndex = -1
        loading = false
        if (drafts.isEmpty) {
            list.clearSelection()
            showEmpty()
        } else {
            list.selectedIndex = index.coerceAtMost(drafts.size() - 1)
            load(list.selectedIndex)
        }
    }

    private fun storeCurrent() {
        if (loading || currentIndex !in 0 until drafts.size()) return
        val draft = drafts[currentIndex]
        draft.name = nameField.text
        draft.label = labelField.text
        draft.type = typeField.selectedItem as String
        draft.required = requiredField.isSelected
        draft.defaultSingle = defaultSingleField.text
        draft.defaultMulti = defaultMultiField.text
        draft.options = optionsField.text
        draft.pathKind = pathKindField.selectedItem as String
        list.repaint()
    }

    private fun load(index: Int) {
        currentIndex = index
        if (index !in 0 until drafts.size()) { showEmpty(); return }
        loading = true
        val draft = drafts[index]
        nameField.text = draft.name
        labelField.text = draft.label
        typeField.selectedItem = draft.type
        requiredField.isSelected = draft.required
        defaultSingleField.text = draft.defaultSingle
        defaultMultiField.text = draft.defaultMulti
        optionsField.text = draft.options
        pathKindField.selectedItem = draft.pathKind
        updateVisibleFields()
        detailsLayout.show(details, "editor")
        loading = false
    }

    private fun showEmpty() {
        currentIndex = -1
        detailsLayout.show(details, "empty")
    }

    private fun updateVisibleFields() {
        val type = typeField.selectedItem as? String ?: return
        optionsRow.isVisible = type == "select" || type == "multiSelect"
        defaultSingleRow.isVisible = type != "multiSelect"
        defaultMultiRow.isVisible = type == "multiSelect"
        pathKindRow.isVisible = type == "path"
        details.revalidate()
        details.repaint()
    }

    private fun field(label: String, component: JComponent): JPanel = JPanel(BorderLayout(JBUI.scale(10), 0)).apply {
        add(JBLabel(label).apply {
            preferredSize = Dimension(JBUI.scale(105), preferredSize.height)
        }, BorderLayout.WEST)
        add(component, BorderLayout.CENTER)
    }

    private fun header(title: String): JBLabel = JBLabel(title).apply { font = JBFont.label().asBold() }

    private fun typeLabel(type: String): String = when (type) {
        "multiSelect" -> "Multi-select"
        else -> type.replaceFirstChar(Char::uppercase)
    }

    private fun choiceRenderer(label: (String) -> String): DefaultListCellRenderer = object : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
        ): java.awt.Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            text = (value as? String)?.let(label).orEmpty()
            return this
        }
    }
}
