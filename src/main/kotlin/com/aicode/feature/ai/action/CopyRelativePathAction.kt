package com.aicode.feature.ai.action

import com.aicode.common.util.ClipboardService
import com.aicode.feature.ai.service.AICodeFileService
import com.intellij.notification.*
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class CopyRelativePathAction : AnAction(), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) ?: return
        if (files.isEmpty()) return
        val paths = getRelativePaths(project, files)
        if (paths.size != files.size) {
            notify(
                project,
                "Only files inside the project root can be copied",
                NotificationType.WARNING,
            )
            return
        }
        try {
            ClipboardService.copyToClipboard(paths.joinToString("\n"))
            notify(
                project,
                if (paths.size == 1) "Copied relative path: ${paths[0]}"
                else "Copied ${paths.size} relative paths",
                NotificationType.INFORMATION,
            )
        } catch (ex: Exception) {
            notify(project, "Failed to copy relative path: " + ex.message, NotificationType.ERROR)
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        e.presentation.isEnabledAndVisible =
            project != null &&
                files != null &&
                files.isNotEmpty() &&
                getRelativePaths(project, files).size == files.size
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    private fun getRelativePaths(project: Project, files: Array<VirtualFile>): List<String> {
        val service = AICodeFileService.getInstance(project)
        val paths = ArrayList<String>(files.size)
        for (file in files) {
            val path = service.getRelativePath(file)
            if (path.isNullOrEmpty()) break
            paths.add(path)
        }
        return paths
    }

    private fun notify(project: Project, content: String, type: NotificationType) =
        Notifications.Bus.notify(
            Notification("AICode", "Project Atlas", content, type),
            project,
        )
}
