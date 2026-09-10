package com.aicode.feature.git.service

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import git4idea.GitTag
import git4idea.branch.GitBranchUtil
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.push.GitPushParamsImpl
import git4idea.repo.GitRemote
import git4idea.repo.GitRepository
import java.util.Collections

class GitTagService {
    private val git = Git.getInstance()

    @Throws(VcsException::class)
    fun getLocalTags(project: Project, repository: GitRepository): List<String> =
        GitBranchUtil.getAllTags(project, repository.root)

    @Throws(VcsException::class)
    fun getRemoteTags(
        project: Project,
        repository: GitRepository,
        remote: GitRemote,
    ): List<String> {
        val tags = linkedSetOf<String>()
        for (url in remote.pushUrls) {
            val handler = GitLineHandler(project, repository.root, GitCommand.LS_REMOTE)
            handler.addParameters("--tags", url)
            val result = git.runCommand(handler)
            result.throwOnError()
            result.output.mapNotNullTo(tags, ::parseTagRef)
        }
        return tags.toList()
    }

    @Throws(VcsException::class)
    fun resolveHead(repository: GitRepository): String =
        git.resolveReference(repository, "HEAD")?.asString()
            ?: throw VcsException("The repository has no resolvable HEAD commit")

    @Throws(VcsException::class)
    fun inspectReleaseState(
        project: Project,
        repository: GitRepository,
        remote: GitRemote,
    ): ReleaseState {
        repository.update()
        val reference = resolveHead(repository)
        val branch = repository.currentBranch
        val statusHandler = GitLineHandler(project, repository.root, GitCommand.STATUS)
        statusHandler.addParameters("--porcelain", "--untracked-files=normal")
        val statusResult = git.runCommand(statusHandler)
        statusResult.throwOnError()

        val trackInfo = branch?.let { repository.getBranchTrackInfo(it.name) }
        val trackedBranch = trackInfo?.remoteBranch
        var ahead = 0
        var behind = 0
        if (trackedBranch != null && trackInfo.remote.name == remote.name) {
            val divergenceHandler = GitLineHandler(project, repository.root, GitCommand.REV_LIST)
            divergenceHandler.addParameters("--left-right", "--count", "HEAD...${trackedBranch.name}")
            val divergenceResult = git.runCommand(divergenceHandler)
            divergenceResult.throwOnError()
            val divergence =
                parseDivergence(divergenceResult.output.firstOrNull().orEmpty())
                    ?: throw VcsException("Git returned an invalid branch divergence result")
            ahead = divergence.first
            behind = divergence.second
        }
        return ReleaseState(
            reference,
            branch?.name,
            statusResult.output.isNotEmpty(),
            trackedBranch?.name,
            trackInfo?.remote?.name,
            ahead,
            behind,
        )
    }

    fun publishTag(
        project: Project,
        repository: GitRepository,
        remote: GitRemote,
        tagName: String,
        reference: String,
    ): PublishResult {
        if (project.isDisposed || !repository.root.isValid)
            return PublishResult.failure(
                PublishStatus.CHECK_FAILED,
                "The Git repository is no longer available.",
            )
        val currentRemote: GitRemote
        try {
            repository.update()
            currentRemote =
                repository.remotes.firstOrNull { it.name == remote.name }
                    ?: return PublishResult.failure(
                        PublishStatus.CHECK_FAILED,
                        "Remote ${remote.name} is no longer available for push.",
                    )
            if (currentRemote.pushUrls.isEmpty())
                return PublishResult.failure(
                    PublishStatus.CHECK_FAILED,
                    "Remote ${remote.name} is no longer available for push.",
                )
            val currentHead = resolveHead(repository)
            if (currentHead != reference)
                return PublishResult.failure(
                    PublishStatus.TARGET_CHANGED,
                    "HEAD changed from ${abbreviate(reference)} to ${abbreviate(currentHead)} after confirmation.",
                )
            if (getLocalTags(project, repository).contains(tagName))
                return PublishResult.failure(
                    PublishStatus.LOCAL_TAG_EXISTS,
                    "Tag $tagName already exists locally.",
                )
        } catch (ex: ProcessCanceledException) {
            throw ex
        } catch (ex: Exception) {
            return PublishResult.failure(PublishStatus.CHECK_FAILED, safeMessage(ex))
        }

        val params =
            GitPushParamsImpl(
                currentRemote,
                tagPushRefspec(reference, tagName),
                false,
                false,
                false,
                null,
                Collections.emptyList(),
            )
        val pushResult =
            try {
                git.push(repository, params)
            } catch (ex: ProcessCanceledException) {
                return PublishResult.failure(
                    PublishStatus.PUSH_CANCELLED,
                    "Push was cancelled; no local tag was created, but the remote state may be uncertain.",
                )
            } catch (ex: RuntimeException) {
                return PublishResult.failure(PublishStatus.PUSH_FAILED, safeMessage(ex))
            }
        if (!pushResult.success()) {
            var message = pushResult.errorOutputAsJoinedString
            if (pushResult.isAuthenticationFailed) message = "Authentication failed. $message"
            return PublishResult.failure(PublishStatus.PUSH_FAILED, message)
        }

        val createResult =
            try {
                git.createNewTag(repository, tagName, null, reference)
            } catch (ex: ProcessCanceledException) {
                return PublishResult.failure(
                    PublishStatus.LOCAL_TAG_CREATE_FAILED,
                    "Remote tag $tagName was pushed successfully, but local tag creation was cancelled.",
                )
            } catch (ex: RuntimeException) {
                return PublishResult.failure(
                    PublishStatus.LOCAL_TAG_CREATE_FAILED,
                    "Remote tag $tagName was pushed successfully, but the local tag could not be created: ${safeMessage(ex)}",
                )
            }
        if (!createResult.success())
            return PublishResult.failure(
                PublishStatus.LOCAL_TAG_CREATE_FAILED,
                "Remote tag $tagName was pushed successfully, but the local tag could not be created: ${createResult.errorOutputAsJoinedString}",
            )
        refreshTags(repository)
        return PublishResult.success()
    }

    enum class PublishStatus {
        SUCCESS,
        LOCAL_TAG_EXISTS,
        TARGET_CHANGED,
        CHECK_FAILED,
        LOCAL_TAG_CREATE_FAILED,
        PUSH_CANCELLED,
        PUSH_FAILED,
    }

    data class PublishResult(val status: PublishStatus, val message: String) {
        fun status() = status

        fun message() = message

        companion object {
            @JvmStatic fun success() = PublishResult(PublishStatus.SUCCESS, "")

            @JvmStatic
            fun failure(status: PublishStatus, message: String) =
                PublishResult(status, message.ifBlank { "Unexpected Git error" })
        }
    }

    data class ReleaseState(
        val reference: String,
        val branch: String?,
        val hasUncommittedChanges: Boolean,
        val trackedBranch: String?,
        val trackingRemote: String?,
        val ahead: Int,
        val behind: Int,
    )

    companion object {
        private val LOG = Logger.getInstance(GitTagService::class.java)

        private fun refreshTags(repository: GitRepository) {
            try {
                repository.repositoryFiles.refreshTagsFiles()
            } catch (ex: RuntimeException) {
                LOG.warn("Failed to refresh Git tag files for ${repository.root.path}", ex)
            }
        }

        @JvmStatic
        fun hasRef(line: String, expectedRef: String): Boolean {
            val separator = line.lastIndexOf('\t')
            return separator >= 0 && line.substring(separator + 1) == expectedRef
        }

        @JvmStatic
        fun parseTagRef(line: String): String? {
            val separator = line.lastIndexOf('\t')
            if (separator < 0) return null
            val ref = line.substring(separator + 1)
            if (!ref.startsWith(GitTag.REFS_TAGS_PREFIX) || ref.endsWith("^{}")) return null
            return ref.removePrefix(GitTag.REFS_TAGS_PREFIX)
        }

        @JvmStatic
        fun parseDivergence(line: String): Pair<Int, Int>? {
            val counts = line.trim().split(Regex("\\s+"))
            if (counts.size != 2) return null
            val ahead = counts[0].toIntOrNull() ?: return null
            val behind = counts[1].toIntOrNull() ?: return null
            return ahead to behind
        }

        @JvmStatic
        fun tagPushRefspec(reference: String, tagName: String) =
            "$reference:${GitTag.REFS_TAGS_PREFIX}$tagName"

        private fun abbreviate(reference: String) =
            if (reference.length <= 12) reference else reference.substring(0, 12)

        private fun safeMessage(exception: Exception) =
            exception.message?.takeUnless { it.isBlank() } ?: "Unexpected Git error"
    }
}
