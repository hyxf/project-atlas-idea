package com.aicode.feature.git.action

import com.aicode.common.util.ClipboardService
import com.aicode.feature.git.util.GitRemoteUrlResolver
import com.intellij.notification.*
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class CopyGitRemoteUrlAction : AnAction(), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        try {
            val url = getRemoteUrl(project, e.getData(CommonDataKeys.VIRTUAL_FILE))
            if (url == null) {
                notify(
                    project,
                    "No origin remote was found for this Git repository",
                    NotificationType.WARNING,
                )
                return
            }
            ClipboardService.copyToClipboard(url)
            notify(project, "Copied Git remote URL: $url", NotificationType.INFORMATION)
        } catch (ex: RuntimeException) {
            LOG.warn("Failed to copy the Git remote URL", ex)
            notify(
                project,
                "Failed to copy the Git remote URL: " +
                    (ex.message?.takeUnless { it.isBlank() } ?: "unexpected error"),
                NotificationType.ERROR,
            )
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible =
            project != null && getRemoteUrl(project, e.getData(CommonDataKeys.VIRTUAL_FILE)) != null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    private fun getRemoteUrl(project: Project, selected: VirtualFile?) =
        GitRemoteUrlResolver.findRepository(project, selected)
            ?.let(GitRemoteUrlResolver::getOriginUrl)

    private fun notify(project: Project, message: String, type: NotificationType) =
        Notifications.Bus.notify(
            Notification("AICode", "Project Atlas", message, type),
            project,
        )

    companion object {
        private val LOG = Logger.getInstance(CopyGitRemoteUrlAction::class.java)
    }
}
