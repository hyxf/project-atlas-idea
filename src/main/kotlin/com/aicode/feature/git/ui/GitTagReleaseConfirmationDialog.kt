package com.aicode.feature.git.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import javax.swing.JComponent
import javax.swing.ScrollPaneConstants

class GitTagReleaseConfirmationDialog(project: Project, message: String) :
    DialogWrapper(project, true) {
    private val visibleRows = message.lineSequence().count().coerceIn(MIN_VISIBLE_ROWS, MAX_VISIBLE_ROWS)
    private val messageArea =
        JBTextArea(message, visibleRows, VISIBLE_COLUMNS).apply {
            isEditable = false
            isFocusable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty(4, 8)
        }

    init {
        title = "Confirm Git Tag Release"
        setOKButtonText("Confirm Release")
        init()
    }

    override fun createCenterPanel(): JComponent =
        JBScrollPane(
            messageArea,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
        ).apply {
            border = JBUI.Borders.empty()
            viewport.isOpaque = false
            isOpaque = false
        }

    private companion object {
        const val MIN_VISIBLE_ROWS = 10
        const val MAX_VISIBLE_ROWS = 12
        const val VISIBLE_COLUMNS = 68
    }
}
