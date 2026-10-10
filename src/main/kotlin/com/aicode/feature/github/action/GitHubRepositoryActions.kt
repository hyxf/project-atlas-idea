package com.aicode.feature.github.action

import com.aicode.feature.github.ui.GitHubRepositoriesPanel
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager

private const val GITHUB_TOOL_WINDOW_ID = "Project Atlas: GitHub Repositories"

abstract class GitHubRepositoryAction(text: String, description: String, icon: javax.swing.Icon? = null) :
    AnAction(text, description, icon), DumbAware {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    protected fun withPanel(project: Project?, action: (GitHubRepositoriesPanel) -> Unit) {
        if (project == null || project.isDisposed) return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(GITHUB_TOOL_WINDOW_ID) ?: return
        val panel = toolWindow.githubPanel()
        if (panel != null) {
            action(panel)
        } else {
            toolWindow.activate({ toolWindow.githubPanel()?.let(action) }, true)
        }
    }

    protected fun panel(project: Project?): GitHubRepositoriesPanel? = project
        ?.takeUnless { it.isDisposed }
        ?.let { ToolWindowManager.getInstance(it).getToolWindow(GITHUB_TOOL_WINDOW_ID)?.githubPanel() }
}

class RefreshGitHubRepositoriesAction : GitHubRepositoryAction(
    "Refresh GitHub Repositories", "Synchronize every repository from GitHub", AllIcons.Actions.Refresh,
) {
    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.isEnabled = e.presentation.isEnabled && panel(e.project)?.isRefreshing() != true
    }

    override fun actionPerformed(e: AnActionEvent) = withPanel(e.project) { it.refreshFromAction() }
}

class ExpandGitHubRepositoriesAction : GitHubRepositoryAction(
    "Expand All GitHub Repositories", "Expand all visibility, language, and repository nodes", AllIcons.Actions.Expandall,
) {
    override fun actionPerformed(e: AnActionEvent) = withPanel(e.project) { it.expandFromAction() }
}

class CollapseGitHubRepositoriesAction : GitHubRepositoryAction(
    "Collapse All GitHub Repositories", "Collapse language groups", AllIcons.Actions.Collapseall,
) {
    override fun actionPerformed(e: AnActionEvent) = withPanel(e.project) { it.collapseFromAction() }
}

class OpenGitHubConfigurationAction : GitHubRepositoryAction(
    "Open GitHub Configuration", "Open github.json", AllIcons.Actions.Edit,
) {
    override fun actionPerformed(e: AnActionEvent) = withPanel(e.project) { it.openConfigurationFromAction() }
}

class OpenGitHubSettingsAction : GitHubRepositoryAction(
    "GitHub Settings", "Edit GitHub token, user, and proxy settings", AllIcons.General.Settings,
) {
    override fun actionPerformed(e: AnActionEvent) = withPanel(e.project) { it.openSettingsFromAction() }
}

private fun ToolWindow.githubPanel(): GitHubRepositoriesPanel? =
    contentManager.contents.firstOrNull()?.component as? GitHubRepositoriesPanel
