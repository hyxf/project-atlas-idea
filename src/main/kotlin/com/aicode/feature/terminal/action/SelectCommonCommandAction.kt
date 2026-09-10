package com.aicode.feature.terminal.action

import com.aicode.feature.terminal.service.CommonCommandService
import com.aicode.feature.terminal.ui.CommonCommandPopup
import com.aicode.feature.terminal.util.TerminalTextInserter
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.Content
import java.awt.Component

class SelectCommonCommandAction : AnAction(
    "Common Commands...",
    "Select a common command and insert it into Terminal",
    null,
), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        if (!isTerminalToolWindowVisible(project)) return
        val targetContent = resolveTargetContent(e.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT))
        val commands = try {
            CommonCommandService.getInstance().getCommands()
        } catch (ex: IllegalStateException) {
            notify(project, ex.message ?: "Failed to load common commands.", NotificationType.ERROR)
            return
        }
        if (commands.isEmpty()) {
            notify(project, "Configure commands in Settings | Tools | Common Commands.", NotificationType.INFORMATION)
            return
        }
        CommonCommandPopup(project, commands) { command ->
            try {
                if (!TerminalTextInserter.insert(project, command.command, targetContent)) {
                    notify(project, "Open a terminal session before inserting a command.", NotificationType.WARNING)
                }
            } catch (ex: Exception) {
                LOG.warn("Failed to insert a common command into Terminal", ex)
                notify(project, ex.message ?: "Failed to insert the command into Terminal.", NotificationType.ERROR)
            }
        }.show()
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val toolWindow = e.getData(PlatformDataKeys.TOOL_WINDOW)
        e.presentation.isEnabledAndVisible =
            project != null &&
                isTerminalToolWindowVisible(project) &&
                (toolWindow == null || toolWindow.id == TERMINAL_TOOL_WINDOW_ID)
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    private fun notify(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("AICode")
            .createNotification(message, type).notify(project)
    }

    private fun isTerminalToolWindowVisible(project: Project): Boolean =
        ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_TOOL_WINDOW_ID)?.isVisible == true

    internal fun resolveTargetContent(contextComponent: Component?): Content? {
        var component = contextComponent
        while (component != null) {
            val getter = component.javaClass.methods.firstOrNull {
                it.name == "getContent" && it.parameterCount == 0 && Content::class.java.isAssignableFrom(it.returnType)
            }
            if (getter != null) {
                try {
                    return getter.invoke(component) as? Content
                } catch (ex: ReflectiveOperationException) {
                    LOG.debug("Failed to resolve Tool Window content from the context component", ex)
                    return null
                }
            }
            component = component.parent
        }
        return null
    }

    companion object {
        private val LOG = Logger.getInstance(SelectCommonCommandAction::class.java)
        private const val TERMINAL_TOOL_WINDOW_ID = "Terminal"
    }
}
