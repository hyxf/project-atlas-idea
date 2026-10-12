package com.aicode.feature.ai.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class AICodeToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val content = ContentFactory.getInstance().createContent(AICodePanel(project), "", false)
        toolWindow.contentManager.addContent(content)
    }
}
