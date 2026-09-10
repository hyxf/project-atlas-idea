package com.aicode.feature.terminal.action

import com.aicode.feature.terminal.service.CommonCommandService
import com.aicode.feature.terminal.ui.CommonCommandDialog
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import java.awt.datatransfer.DataFlavor

class AddCurrentCommandAction : AnAction(
    "Add Current Command",
    "Add a command using clipboard text as the initial value",
    null,
), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val command = CommonCommandDialog.showAdd(initialValueFromClipboard()) ?: return
        try {
            if (CommonCommandService.getInstance().addCommand(command)) {
                notify(project, "Added to Common Commands: ${command.command}", NotificationType.INFORMATION)
            } else {
                notify(project, "The command already exists in Common Commands.", NotificationType.INFORMATION)
            }
        } catch (ex: IllegalStateException) {
            LOG.warn("Failed to add the current Terminal command", ex)
            notify(project, ex.message ?: "Failed to save the current command.", NotificationType.ERROR)
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    internal fun initialValue(clipboardText: String?): String = clipboardText?.takeUnless(String::isBlank).orEmpty()

    private fun initialValueFromClipboard(): String =
        try {
            initialValue(CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor) as? String)
        } catch (ex: Exception) {
            LOG.debug("Failed to read text from the clipboard", ex)
            ""
        }

    private fun notify(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("AICode")
            .createNotification(message, type).notify(project)
    }

    companion object {
        private val LOG = Logger.getInstance(AddCurrentCommandAction::class.java)
    }
}
