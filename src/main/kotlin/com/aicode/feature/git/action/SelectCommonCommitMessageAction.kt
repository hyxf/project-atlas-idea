package com.aicode.feature.git.action

import com.aicode.feature.git.ui.CommonCommitMessagePopup
import com.aicode.feature.git.service.CommonCommitMessageService
import com.aicode.feature.git.icons.GitIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsDataKeys

class SelectCommonCommitMessageAction : AnAction(
    "Common Commit Messages...",
    "Select a predefined commit message",
    GitIcons.COMMON_COMMIT_MESSAGE,
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val dataContext = e.dataContext
        val commitMessageControl = VcsDataKeys.COMMIT_MESSAGE_CONTROL.getData(dataContext) ?: return
        val messages =
            try {
                CommonCommitMessageService.getInstance().getMessages()
            } catch (ex: IllegalStateException) {
                notify(project, ex.message ?: "Failed to load common commit messages.", NotificationType.ERROR)
                return
            }
        if (messages.isEmpty()) {
            notify(
                project,
                "Configure messages in Settings | Tools | Common Commit Messages.",
                NotificationType.INFORMATION,
            )
            return
        }
        CommonCommitMessagePopup(project, messages, commitMessageControl::setCommitMessage).show()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible =
            e.project != null && VcsDataKeys.COMMIT_MESSAGE_CONTROL.getData(e.dataContext) != null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    private fun notify(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("AICode")
            .createNotification(message, type)
            .notify(project)
    }
}
