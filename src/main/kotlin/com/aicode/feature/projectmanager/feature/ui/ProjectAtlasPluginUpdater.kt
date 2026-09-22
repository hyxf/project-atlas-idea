package com.aicode.feature.projectmanager.feature.ui

import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.plugins.RepositoryHelper
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ex.ApplicationEx
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.updateSettings.impl.PluginDownloader
import com.intellij.openapi.util.text.StringUtil

/**
 * Checks the Project Atlas custom plugin repository and delegates installation to the IDE.
 *
 * The IDE's downloader handles proxy settings, plugin descriptor validation and scheduling
 * replacement of the currently loaded plugin for the next restart.
 */
object ProjectAtlasPluginUpdater {
    private const val PLUGIN_ID = "com.github.hyxf.project-atlas-idea"
    private const val UPDATE_REPOSITORY_URL = "https://hyxf.github.io/project-atlas-idea/updatePlugins.xml"

    private val logger = Logger.getInstance(ProjectAtlasPluginUpdater::class.java)
    private val pluginId = PluginId.getId(PLUGIN_ID)

    fun checkForUpdate(project: Project) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Checking for Project Atlas Updates", true) {
            private var result: CheckResult = CheckResult.Failed

            override fun run(indicator: ProgressIndicator) {
                result = runCatching { findUpdate(indicator) }
                    .onFailure { logger.warn("[ProjectAtlasUpdate] Failed to check for an update", it) }
                    .getOrElse { CheckResult.Failed }
            }

            override fun onSuccess() {
                if (project.isDisposed) return
                when (val currentResult = result) {
                    CheckResult.Latest -> NotificationGroupManager.getInstance()
                        .getNotificationGroup("AICode.ProjectManager")
                        .createNotification("Project Atlas Is Up to Date", "You are running the latest version.", NotificationType.INFORMATION)
                        .notify(project)

                    is CheckResult.Available -> confirmAndInstall(project, currentResult)
                    CheckResult.Failed -> notify(
                        project,
                        "Update Check Failed",
                        "Unable to reach the update service. Check your network connection and try again.",
                        NotificationType.ERROR,
                    )
                }
            }
        })
    }

    private fun findUpdate(indicator: ProgressIndicator): CheckResult {
        val installed = PluginManagerCore.getPlugin(pluginId)
            ?: throw IllegalStateException("Project Atlas is not installed")
        val latest = RepositoryHelper.loadPlugins(UPDATE_REPOSITORY_URL, indicator)
            .asSequence()
            .filter { it.pluginId == pluginId }
            .maxWithOrNull { left, right -> StringUtil.compareVersionNumbers(left.version, right.version) }
            ?: return CheckResult.Latest

        return if (StringUtil.compareVersionNumbers(latest.version, installed.version) > 0) {
            CheckResult.Available(installed.version, latest)
        } else {
            CheckResult.Latest
        }
    }

    private fun confirmAndInstall(project: Project, update: CheckResult.Available) {
        val changeNotes = update.plugin.changeNotes?.trim().orEmpty().ifBlank { "No release notes are available for this version." }
        NotificationGroupManager.getInstance().getNotificationGroup("AICode.ProjectManager")
            .createNotification(
                "A Project Atlas Update Is Available",
                "Installed version: ${update.currentVersion}<br>" +
                    "Available version: ${update.plugin.version}<br><br>" +
                    "What's new:<br>${escapeForNotification(changeNotes)}<br><br>" +
                    "Select Update Now to download and install. Restart the IDE to activate the update.",
                NotificationType.INFORMATION,
            )
            .addAction(NotificationAction.createSimpleExpiring("Update Now") {
                install(project, update.plugin)
            })
            .addAction(NotificationAction.createSimpleExpiring("Not Now") {})
            .notify(project)
    }

    private fun install(project: Project, plugin: IdeaPluginDescriptor) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Downloading and Installing Project Atlas Update", true) {
            private var installed = false

            override fun run(indicator: ProgressIndicator) {
                installed = runCatching {
                    val downloader = PluginDownloader.createDownloader(plugin, UPDATE_REPOSITORY_URL, currentIdeBuild())
                    downloader.prepareToInstall(indicator).also { prepared ->
                        if (prepared) downloader.install()
                    }
                }.onFailure { logger.warn("[ProjectAtlasUpdate] Failed to download or install an update", it) }
                    .getOrDefault(false)
            }

            override fun onSuccess() {
                if (!installed || project.isDisposed) {
                    if (!project.isDisposed) notify(
                        project,
                        "Update Installation Failed",
                        "Unable to download or prepare the update. Please try again later.",
                        NotificationType.ERROR,
                    )
                    return
                }
                NotificationGroupManager.getInstance().getNotificationGroup("AICode.ProjectManager")
                    .createNotification(
                        "Update Ready",
                        "Project Atlas ${plugin.version} is ready. Restart the IDE to use the new version.",
                        NotificationType.INFORMATION,
                    )
                    .addAction(NotificationAction.createSimpleExpiring("Restart Now") { restartIde() })
                    .addAction(NotificationAction.createSimpleExpiring("Restart Later") {})
                    .notify(project)
            }
        })
    }

    private fun currentIdeBuild() = ApplicationInfo.getInstance().build

    private fun restartIde() {
        (ApplicationManager.getApplication() as? ApplicationEx)?.restart(true)
    }

    private fun notify(project: Project, title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("AICode.ProjectManager")
            .createNotification(title, content, type)
            .notify(project)
    }

    private fun escapeForNotification(value: String): String = value
        .take(600)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\n", "<br>")

    private sealed interface CheckResult {
        data object Latest : CheckResult
        data object Failed : CheckResult
        data class Available(val currentVersion: String, val plugin: IdeaPluginDescriptor) : CheckResult
    }
}
