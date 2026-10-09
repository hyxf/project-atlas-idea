package com.aicode.feature.ai.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindowManager

class OpenAICodeToolWindowAction : AnAction(), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
        if (toolWindow.isVisible) toolWindow.hide() else toolWindow.show()
    }

    override fun update(e: AnActionEvent) {
        val toolWindow = e.project?.let { ToolWindowManager.getInstance(it).getToolWindow(TOOL_WINDOW_ID) }
        e.presentation.isEnabled = toolWindow != null
        e.presentation.text = if (toolWindow?.isVisible == true) "Hide AICode" else "Show AICode"
        e.presentation.description = "Show or hide the AICode tool window"
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    private companion object {
        const val TOOL_WINDOW_ID = "Project Atlas: AICode"
    }
}
