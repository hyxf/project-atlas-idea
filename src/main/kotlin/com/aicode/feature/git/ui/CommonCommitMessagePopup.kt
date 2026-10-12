package com.aicode.feature.git.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
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
import javax.swing.JList
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

class CommonCommitMessagePopup(
    private val project: Project,
    private val messages: List<String>,
    private val onChosen: (String) -> Unit,
) {
    private val listModel = DefaultListModel<String>()
    private val messageList = JBList(listModel)
    private val searchField = SearchTextField(false)
    private lateinit var popup: JBPopup

    init {
        searchField.border = JBUI.Borders.empty(0, 6)
        searchField.preferredSize =
            Dimension(searchField.preferredSize.width, JBUI.scale(36))
        searchField.textEditor.border = JBUI.Borders.empty()
        searchField.textEditor.emptyText.text = "Search common commit messages"
        searchField.textEditor.document.addDocumentListener(
            object : DocumentAdapter() {
                override fun textChanged(event: DocumentEvent) = refreshMessages()
            }
        )
        searchField.textEditor.addKeyListener(
            object : KeyAdapter() {
                override fun keyPressed(event: KeyEvent) {
                    when (event.keyCode) {
                        KeyEvent.VK_DOWN -> moveSelection(1)
                        KeyEvent.VK_UP -> moveSelection(-1)
                        KeyEvent.VK_ENTER -> chooseSelected()
                        else -> return
                    }
                    event.consume()
                }
            }
        )
        messageList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        messageList.fixedCellHeight = JBUI.scale(34)
        messageList.cellRenderer =
            object : ColoredListCellRenderer<String>() {
                override fun customizeCellRenderer(
                    list: JList<out String>,
                    value: String,
                    index: Int,
                    selected: Boolean,
                    hasFocus: Boolean,
                ) {
                    border = JBUI.Borders.empty(0, 10)
                    append(value, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                }
            }
        messageList.emptyText.text = "No commit messages found."
        messageList.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(event: MouseEvent) {
                    if (event.clickCount != 2) return
                    val index = messageList.locationToIndex(event.point)
                    val bounds = index.takeIf { it >= 0 }?.let { messageList.getCellBounds(it, it) }
                    if (bounds?.contains(event.point) == true) chooseSelected()
                }
            }
        )
        messageList.addKeyListener(
            object : KeyAdapter() {
                override fun keyPressed(event: KeyEvent) {
                    if (event.keyCode == KeyEvent.VK_ENTER) {
                        chooseSelected()
                        event.consume()
                    }
                }
            }
        )
        refreshMessages()
    }

    fun show() {
        val content =
            JPanel(BorderLayout(0, JBUI.scale(8))).apply {
                border = JBUI.Borders.empty(10)
                preferredSize = Dimension(JBUI.scale(640), JBUI.scale(360))
                add(searchField, BorderLayout.NORTH)
                add(
                    JPanel(BorderLayout()).apply {
                        border = JBUI.Borders.empty(4, 6, 6, 6)
                        add(JBScrollPane(messageList), BorderLayout.CENTER)
                    },
                    BorderLayout.CENTER,
                )
            }
        popup =
            JBPopupFactory.getInstance()
                .createComponentPopupBuilder(content, searchField.textEditor)
                .setRequestFocus(true)
                .setFocusable(true)
                .setResizable(true)
                .setMovable(true)
                .setCancelOnClickOutside(true)
                .setCancelOnWindowDeactivation(true)
                .setDimensionServiceKey(project, "AICode.CommonCommitMessagePopup", false)
                .createPopup()
        popup.showCenteredInCurrentWindow(project)
    }

    private fun refreshMessages() {
        val keyword = searchField.text.trim()
        val filtered =
            if (keyword.isEmpty()) messages
            else messages.filter { it.contains(keyword, ignoreCase = true) }
        listModel.clear()
        filtered.forEach(listModel::addElement)
        if (listModel.size > 0) messageList.selectedIndex = 0
    }

    private fun moveSelection(offset: Int) {
        if (listModel.isEmpty) return
        val current = messageList.selectedIndex.takeIf { it >= 0 } ?: 0
        messageList.selectedIndex = (current + offset).coerceIn(0, listModel.size - 1)
        messageList.ensureIndexIsVisible(messageList.selectedIndex)
    }

    private fun chooseSelected() {
        val selected = messageList.selectedValue ?: return
        onChosen(selected)
        if (::popup.isInitialized) popup.cancel()
    }
}
