package com.aicode.feature.terminal.ui

import com.aicode.feature.terminal.model.CommonCommand
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

class CommonCommandPopup(
    private val project: Project,
    private val commands: List<CommonCommand>,
    private val onChosen: (CommonCommand) -> Unit,
) {
    private val listModel = DefaultListModel<CommonCommand>()
    private val commandList = JBList(listModel)
    private val searchField = SearchTextField(false)
    private lateinit var popup: JBPopup

    init {
        searchField.border = JBUI.Borders.empty(0, 6)
        searchField.preferredSize = Dimension(searchField.preferredSize.width, JBUI.scale(36))
        searchField.textEditor.border = JBUI.Borders.empty()
        searchField.textEditor.emptyText.text = "Search common commands"
        searchField.textEditor.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) = refreshCommands()
        })
        searchField.textEditor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(event: KeyEvent) {
                when (event.keyCode) {
                    KeyEvent.VK_DOWN -> moveSelection(1)
                    KeyEvent.VK_UP -> moveSelection(-1)
                    KeyEvent.VK_ENTER -> chooseSelected()
                    else -> return
                }
                event.consume()
            }
        })
        commandList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        commandList.fixedCellHeight = JBUI.scale(52)
        commandList.cellRenderer = CommonCommandListCellRenderer()
        commandList.emptyText.text = "No commands found."
        commandList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount != 2) return
                val index = commandList.locationToIndex(event.point)
                val bounds = index.takeIf { it >= 0 }?.let { commandList.getCellBounds(it, it) }
                if (bounds?.contains(event.point) == true) chooseSelected()
            }
        })
        commandList.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(event: KeyEvent) {
                if (event.keyCode == KeyEvent.VK_ENTER) {
                    chooseSelected()
                    event.consume()
                }
            }
        })
        refreshCommands()
    }

    fun show() {
        val content = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            border = JBUI.Borders.empty(10)
            preferredSize = Dimension(JBUI.scale(640), JBUI.scale(360))
            add(searchField, BorderLayout.NORTH)
            add(JBScrollPane(commandList), BorderLayout.CENTER)
        }
        popup = JBPopupFactory.getInstance().createComponentPopupBuilder(content, searchField.textEditor)
            .setRequestFocus(true).setFocusable(true).setResizable(true).setMovable(true)
            .setCancelOnClickOutside(true).setCancelOnWindowDeactivation(true)
            .setDimensionServiceKey(project, "AICode.CommonCommandPopup", false).createPopup()
        popup.showCenteredInCurrentWindow(project)
    }

    private fun refreshCommands() {
        val keyword = searchField.text.trim()
        val filtered = if (keyword.isEmpty()) commands else commands.filter {
            it.command.contains(keyword, ignoreCase = true) || it.description.contains(keyword, ignoreCase = true)
        }
        listModel.clear()
        filtered.forEach(listModel::addElement)
        if (listModel.size > 0) commandList.selectedIndex = 0
    }

    private fun moveSelection(offset: Int) {
        if (listModel.isEmpty) return
        val current = commandList.selectedIndex.takeIf { it >= 0 } ?: 0
        commandList.selectedIndex = (current + offset).coerceIn(0, listModel.size - 1)
        commandList.ensureIndexIsVisible(commandList.selectedIndex)
    }

    private fun chooseSelected() {
        val selected = commandList.selectedValue ?: return
        onChosen(selected)
        if (::popup.isInitialized) popup.cancel()
    }
}
