package com.aicode.feature.terminal.ui

import com.aicode.feature.terminal.model.CommonCommand
import com.aicode.feature.terminal.model.CommonCommandVariable
import com.aicode.feature.terminal.service.CommonCommandResolver
import com.aicode.feature.terminal.service.CommonCommandService
import com.aicode.feature.terminal.util.TerminalTextInserter
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.ui.Messages
import com.intellij.ui.content.Content
import javax.swing.JFileChooser
import javax.swing.JCheckBox
import javax.swing.JOptionPane
import javax.swing.JPanel
import java.awt.GridLayout

object CommonCommandRunner {
    fun run(project: Project, command: CommonCommand, execute: Boolean, targetContent: Content? = null) {
        val service = CommonCommandService.getInstance()
        val shell = TerminalTextInserter.shellPath(project, execute, targetContent)
        val resolved = CommonCommandResolver.resolve(command, service.read().variables, shell, ::prompt) ?: return
        val editorFile = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
        val directory = editorFile?.let { ProjectFileIndex.getInstance(project).getContentRootForFile(it)?.path }
            ?: project.basePath
        if (!TerminalTextInserter.insertOrCreate(project, resolved, directory, execute, targetContent)) {
            error("Could not open a Terminal session.")
        }
    }

    private fun prompt(variable: CommonCommandVariable): List<String>? {
        val title = variable.label.ifBlank { variable.name }
        if (variable.type == "path") {
            val chooser = JFileChooser().apply {
                fileSelectionMode = when (variable.pathKind) {
                    "file" -> JFileChooser.FILES_ONLY
                    "folder" -> JFileChooser.DIRECTORIES_ONLY
                    else -> JFileChooser.FILES_AND_DIRECTORIES
                }
                variable.defaultValue.firstOrNull()?.let { selectedFile = java.io.File(it) }
            }
            if (variable.defaultValue.isNotEmpty() || !variable.required) {
                val hasDefault = variable.defaultValue.isNotEmpty()
                val choice = Messages.showYesNoCancelDialog(
                    if (hasDefault) "Choose a path or use the default." else "Choose a path or leave the value empty.",
                    title, "Choose Path", if (hasDefault) "Use Default" else "No Value", "Cancel", null)
                if (choice == Messages.CANCEL) return null
                if (choice == Messages.NO) return variable.defaultValue
            }
            return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) listOf(chooser.selectedFile.absolutePath) else null
        }
        if (variable.type == "select") {
            val options = if (variable.required) variable.options else listOf("<No value>") + variable.options
            val index = Messages.showChooseDialog("Select $title", "Common Command", options.toTypedArray(),
                variable.defaultValue.firstOrNull() ?: options.first(), null)
            return when {
                index < 0 -> null
                !variable.required && index == 0 -> emptyList()
                else -> listOf(options[index])
            }
        }
        if (variable.type == "multiSelect") {
            val boxes = variable.options.map { JCheckBox(it, it in variable.defaultValue) }
            val panel = JPanel(GridLayout(0, 1)).apply { boxes.forEach(::add) }
            if (JOptionPane.showConfirmDialog(null, panel, title, JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return null
            return boxes.filter(JCheckBox::isSelected).map(JCheckBox::getText)
        }
        val value = Messages.showInputDialog("${if (variable.required) "Required" else "Optional"} value", title,
            null, variable.defaultValue.firstOrNull().orEmpty(), null) ?: return null
        return if (value.isEmpty() && !variable.required) emptyList() else listOf(value)
    }
}
