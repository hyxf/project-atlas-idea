package com.aicode.feature.git.action

import com.aicode.feature.git.util.GitRemoteUrlResolver
import com.aicode.feature.git.util.GitRemoteUrlResolver.HostingPlatform
import com.aicode.feature.git.util.GitRemoteUrlResolver.PathType
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.*
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import git4idea.repo.GitRepository
import java.util.Locale

open class OpenGitRemoteAction protected constructor(private val target: Target) :
    AnAction(null, null, AllIcons.General.Web), DumbAware {
    constructor() : this(Target.REPOSITORY)

    enum class Target {
        REPOSITORY,
        BRANCH,
        DIRECTORY,
        FILE,
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        try {
            val selected = e.getData(CommonDataKeys.VIRTUAL_FILE)
            val repository = GitRemoteUrlResolver.findRepository(project, selected)
            if (repository == null) {
                notify(
                    project,
                    "The project is not inside a Git repository",
                    NotificationType.WARNING,
                )
                return
            }
            val origin = GitRemoteUrlResolver.getOriginUrl(repository)
            if (origin == null) {
                notify(
                    project,
                    "No origin remote was found for this Git repository",
                    NotificationType.WARNING,
                )
                return
            }
            val branch =
                if (target == Target.REPOSITORY) null
                else GitRemoteUrlResolver.getCurrentBranch(repository)
            if (target != Target.REPOSITORY && branch == null)
                notify(
                    project,
                    "HEAD is detached; opening the repository home page",
                    NotificationType.WARNING,
                )
            val relativePath =
                if (branch == null) null
                else
                    when (target) {
                        Target.DIRECTORY -> selectedDirectory(repository, selected)
                        Target.FILE -> selectedFile(repository, selected)
                        else -> null
                    }
            if (
                (target == Target.DIRECTORY || target == Target.FILE) &&
                    branch != null &&
                    relativePath == null
            ) {
                notify(
                    project,
                    "The selected ${if (target == Target.DIRECTORY) "directory" else "file"} is not inside the target Git repository",
                    NotificationType.WARNING,
                )
                return
            }
            val platform =
                if (branch == null) HostingPlatform.UNKNOWN
                else resolveHostingPlatform(project, origin) ?: return
            val pathType = if (target == Target.FILE) PathType.FILE else PathType.DIRECTORY
            val webUrl =
                GitRemoteUrlResolver.toWebUrl(origin, branch, relativePath, platform, pathType)
            if (webUrl == null) {
                notify(
                    project,
                    "The origin remote URL format is not supported",
                    NotificationType.ERROR,
                )
                return
            }
            BrowserUtil.browse(webUrl)
        } catch (ex: RuntimeException) {
            LOG.warn("Failed to resolve the Git remote web URL", ex)
            notify(
                project,
                "Failed to open the Git remote: " + safeMessage(ex),
                NotificationType.ERROR,
            )
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val selected = e.getData(CommonDataKeys.VIRTUAL_FILE)
        var available = false
        if (project != null) {
            val repository = GitRemoteUrlResolver.findRepository(project, selected)
            available = repository != null && GitRemoteUrlResolver.getOriginUrl(repository) != null
            if (repository != null) {
                if (available && target != Target.REPOSITORY)
                    available = GitRemoteUrlResolver.getCurrentBranch(repository) != null
                if (available && target == Target.DIRECTORY)
                    available = selectedDirectory(repository, selected) != null
                if (available && target == Target.FILE)
                    available = selectedFile(repository, selected) != null
            }
        }
        e.presentation.isEnabledAndVisible = available
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    companion object {
        private val LOG = Logger.getInstance(OpenGitRemoteAction::class.java)
        private const val PREFIX = "aicode.gitRemote.hostingPlatform."

        private fun resolveHostingPlatform(project: Project, origin: String): HostingPlatform? {
            val detected = GitRemoteUrlResolver.detectHostingPlatform(origin)
            if (detected != HostingPlatform.UNKNOWN) return detected
            val host = GitRemoteUrlResolver.getHost(origin)
            if (host.isNullOrBlank()) return HostingPlatform.UNKNOWN
            val properties = PropertiesComponent.getInstance(project)
            val key = PREFIX + host.lowercase(Locale.ROOT)
            properties.getValue(key)?.let {
                try {
                    return HostingPlatform.valueOf(it)
                } catch (_: IllegalArgumentException) {
                    properties.unsetValue(key)
                }
            }
            val options =
                arrayOf(
                    "GitLab (/-/tree/)",
                    "GitHub / Gitee / Codeup (/tree/)",
                    "Bitbucket (/src/)",
                    Messages.getCancelButton(),
                )
            val selected =
                when (
                    Messages.showDialog(
                        project,
                        "Select the hosting platform used by $host. The choice will be remembered for this project.",
                        "Select Git Hosting Platform",
                        options,
                        0,
                        Messages.getQuestionIcon(),
                    )
                ) {
                    0 -> HostingPlatform.GITLAB
                    1 -> HostingPlatform.GITHUB
                    2 -> HostingPlatform.BITBUCKET
                    else -> null
                }
            if (selected != null) properties.setValue(key, selected.name)
            return selected
        }

        private fun selectedDirectory(repository: GitRepository, selected: VirtualFile?): String? {
            val directory = if (selected?.isDirectory == true) selected else selected?.parent
            return directory?.let { VfsUtilCore.getRelativePath(it, repository.root, '/') }
        }

        private fun selectedFile(repository: GitRepository, selected: VirtualFile?): String? =
            selected
                ?.takeUnless { it.isDirectory }
                ?.let { VfsUtilCore.getRelativePath(it, repository.root, '/') }

        private fun safeMessage(ex: Exception) =
            ex.message?.takeUnless { it.isBlank() } ?: "unexpected error"

        private fun notify(project: Project, message: String, type: NotificationType) =
            Notifications.Bus.notify(
                Notification("AICode", "Project Atlas", message, type),
                project,
            )
    }
}
