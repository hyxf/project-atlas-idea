package com.aicode.feature.git.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel

class ChangelogPreviewDialog(
    project: Project,
    content: String,
    private val replacingUnmanagedFile: Boolean,
) : DialogWrapper(project) {
    private val editor = JBTextArea(content)

    init {
        editor.lineWrap = false
        title = "Preview CHANGELOG.md"
        setOKButtonText(if (replacingUnmanagedFile) "Replace File" else "Write File")
        init()
    }

    fun getContent(): String = editor.text

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 8))
        if (replacingUnmanagedFile)
            panel.add(
                JBLabel(
                    "This existing file has no AICode markers. Confirming will replace its entire content."
                ),
                BorderLayout.NORTH,
            )
        val scrollPane = JBScrollPane(editor)
        scrollPane.preferredSize = Dimension(780, 560)
        panel.add(scrollPane, BorderLayout.CENTER)
        return panel
    }
}
