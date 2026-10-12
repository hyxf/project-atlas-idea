package com.aicode.feature.terminal.ui

import com.aicode.feature.terminal.model.CommonCommand
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

class CommonCommandListCellRenderer : JPanel(BorderLayout(0, JBUI.scale(2))), ListCellRenderer<CommonCommand> {
    private val commandLabel = JBLabel()
    private val descriptionLabel = JBLabel()

    init {
        border = JBUI.Borders.empty(5, 10)
        add(commandLabel, BorderLayout.NORTH)
        add(descriptionLabel, BorderLayout.SOUTH)
        isOpaque = true
    }

    override fun getListCellRendererComponent(
        list: JList<out CommonCommand>,
        value: CommonCommand,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        commandLabel.text = value.command
        descriptionLabel.text = value.description.ifEmpty { "No description" }
        commandLabel.font = list.font.deriveFont(Font.BOLD, list.font.size2D + 1f)
        descriptionLabel.font = list.font.deriveFont(Font.PLAIN, (list.font.size2D - 1f).coerceAtLeast(10f))
        background = if (isSelected) list.selectionBackground else list.background
        commandLabel.foreground = if (isSelected) list.selectionForeground else list.foreground
        descriptionLabel.foreground = if (isSelected) list.selectionForeground else UIUtil.getContextHelpForeground()
        commandLabel.background = background
        descriptionLabel.background = background
        return this
    }
}
