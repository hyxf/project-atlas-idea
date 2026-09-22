package com.aicode.feature.projectmanager.feature.ui

import com.intellij.CommonBundle
import com.intellij.ide.plugins.PluginNode
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.plugins.RepositoryHelper
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ex.ApplicationEx
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
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
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Checking Project Atlas Updates", true) {
            private var result: CheckResult = CheckResult.Failed

            override fun run(indicator: ProgressIndicator) {
                result = runCatching { findUpdate(indicator) }
                    .onFailure { logger.warn("[ProjectAtlasUpdate] Failed to check for an update", it) }
                    .getOrElse { CheckResult.Failed }
            }

            override fun onSuccess() {
                if (project.isDisposed) return
                when (val currentResult = result) {
                    CheckResult.Latest -> Messages.showInfoMessage(
                        project,
                        "Project Atlas is already up to date.",
                        "Project Atlas Update",
                    )

                    is CheckResult.Available -> confirmAndInstall(project, currentResult)
                    CheckResult.Failed -> Messages.showErrorDialog(
                        project,
                        "Could not check for Project Atlas updates. Please check your network connection and try again.",
                        "Project Atlas Update",
                    )
                }
            }
        })
    }

    private fun findUpdate(indicator: ProgressIndicator): CheckResult {
        val installed = PluginManagerCore.getPlugin(pluginId)
            ?: throw IllegalStateException("Project Atlas is not installed")
        val latest = RepositoryHelper.loadPlugins(UPDATE_REPOSITORY_URL, currentIdeBuild(), indicator)
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
        val changeNotes = update.plugin.changeNotes?.trim().orEmpty().ifBlank { "No release notes were provided." }
        val choice = Messages.showYesNoDialog(
            project,
            "A new Project Atlas version is available.\n\n" +
                "Current version: ${update.currentVersion}\n" +
                "New version: ${update.plugin.version}\n\n" +
                "What's new:\n$changeNotes\n\n" +
                "Download and install the update? The IDE must restart before it takes effect.",
            "Project Atlas Update Available",
            "Update and Restart",
            CommonBundle.getCancelButtonText(),
            Messages.getQuestionIcon(),
        )
        if (choice == Messages.YES) install(project, update.plugin)
    }

    private fun install(project: Project, plugin: PluginNode) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Downloading Project Atlas Update", true) {
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
                    if (!project.isDisposed) Messages.showErrorDialog(
                        project,
                        "The Project Atlas update could not be installed.",
                        "Project Atlas Update",
                    )
                    return
                }
                val restart = Messages.showYesNoDialog(
                    project,
                    "The Project Atlas update has been installed. Restart the IDE now to use the new version?",
                    "Restart Required",
                    "Restart Now",
                    "Later",
                    Messages.getInformationIcon(),
                )
                if (restart == Messages.YES) {
                    (ApplicationManager.getApplication() as? ApplicationEx)?.restart(true)
                }
            }
        })
    }

    private fun currentIdeBuild() = ApplicationInfo.getInstance().build

    private sealed interface CheckResult {
        data object Latest : CheckResult
        data object Failed : CheckResult
        data class Available(val currentVersion: String, val plugin: PluginNode) : CheckResult
    }
}
