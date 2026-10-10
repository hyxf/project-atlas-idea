package com.aicode.feature.terminal.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindowManager

class OpenCommonCommandsAction : AnAction("Common Commands", "Open Project Atlas Common Commands", null), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let { ToolWindowManager.getInstance(it).getToolWindow("Project Atlas: Common Commands")?.show() }
    }
    override fun update(e: AnActionEvent) { e.presentation.isEnabled = e.project != null }
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}
