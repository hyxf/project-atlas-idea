package com.aicode.feature.update

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.IconLoader

class CheckForProjectAtlasUpdatesAction : AnAction(
    "Check for Project Atlas Updates",
    "Check for and install a Project Atlas update",
    ProjectAtlasUpdateIcons.CheckForUpdates,
), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let(ProjectAtlasPluginUpdater::checkForUpdate)
    }
}

private object ProjectAtlasUpdateIcons {
    @JvmField
    val CheckForUpdates = IconLoader.getIcon("/icons/update/checkForUpdates.svg", ProjectAtlasUpdateIcons::class.java)
}
