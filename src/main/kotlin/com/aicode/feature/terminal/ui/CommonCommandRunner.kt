package com.aicode.feature.terminal.ui

import com.aicode.feature.terminal.model.CommonCommand
import com.aicode.feature.terminal.util.TerminalTextInserter
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.ui.content.Content

object CommonCommandRunner {
    fun insert(project: Project, command: CommonCommand, targetContent: Content? = null) {
        val editorFile = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
        val directory = editorFile?.let { ProjectFileIndex.getInstance(project).getContentRootForFile(it)?.path }
            ?: project.basePath
        if (!TerminalTextInserter.insertOrCreate(project, command.command, directory, targetContent)) {
            error("Could not open a Terminal session.")
        }
    }
}
