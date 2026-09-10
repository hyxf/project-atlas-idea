package com.aicode.feature.ai.action

import com.aicode.common.util.ClipboardService
import com.aicode.feature.ai.service.AICodeFileService
import com.aicode.feature.ai.util.MarkdownBuilder
import com.intellij.notification.*
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project

class CopyMarkdownAction : AnAction(), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = AICodeFileService.getInstance(project)
        val paths = service.readFilePaths()
        if (paths.isEmpty()) {
            notify(project, "No files in AICode context", NotificationType.WARNING)
            return
        }
        try {
            ClipboardService.copyToClipboard(
                MarkdownBuilder.buildMarkdown(project, paths, service::getFileFromPath)
            )
            notify(
                project,
                "AICode Markdown copied to clipboard (${paths.size} files)",
                NotificationType.INFORMATION,
            )
        } catch (ex: Exception) {
            notify(project, "Failed to export Markdown: " + ex.message, NotificationType.ERROR)
        }
    }

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible =
            e.project != null && file != null && !file.isDirectory && file.name == ".aicode.json"
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    private fun notify(project: Project, content: String, type: NotificationType) =
        Notifications.Bus.notify(
            Notification("AICode", "Project Atlas", content, type),
            project,
        )
}
