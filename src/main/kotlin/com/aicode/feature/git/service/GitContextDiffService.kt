package com.aicode.feature.git.service

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import git4idea.commands.Git
import git4idea.commands.GitBinaryHandler
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepository
import java.nio.charset.StandardCharsets

class GitContextDiffService {
    private val git = Git.getInstance()

    fun getBranchNames(repository: GitRepository): List<String> =
        (repository.branches.localBranches.map { it.name } +
                repository.branches.remoteBranches.map { it.name })
            .distinct()
            .sorted()

    fun getRemoteName(repository: GitRepository, branch: String): String? =
        findRemoteName(branch, repository.remotes.map { it.name })

    @Throws(VcsException::class)
    fun fetch(project: Project, repository: GitRepository, remoteName: String) {
        val handler = GitLineHandler(project, repository.root, GitCommand.FETCH)
        handler.addParameters(remoteName)
        git.runCommand(handler).throwOnError()
        repository.update()
    }

    @Throws(VcsException::class)
    fun compare(
        project: Project,
        repository: GitRepository,
        compareBranch: String,
        paths: List<String>,
    ): List<FileDiffResult> {
        if (paths.isEmpty()) return emptyList()
        val projectRoot = projectRoot(project) ?: throw VcsException("The project has no base directory")
        val projectPrefix =
            VfsUtilCore.getRelativePath(projectRoot, repository.root, '/')
                ?: throw VcsException("The project is outside the selected Git repository")
        val gitPaths = paths.map { path -> joinPath(projectPrefix, path) }
        val handler = GitBinaryHandler(project, repository.root, GitCommand.DIFF)
        handler.addParameters("--name-only", "--no-renames", "-z", compareBranch, "--")
        handler.addParameters(gitPaths)
        val changedPaths = parseZeroTerminatedPaths(handler.run()).toMutableList()

        val untrackedHandler = GitBinaryHandler(project, repository.root, GitCommand.LS_FILES)
        untrackedHandler.addParameters("--others", "-z", "--")
        untrackedHandler.addParameters(gitPaths)
        changedPaths.addAll(parseZeroTerminatedPaths(untrackedHandler.run()))

        return buildResults(paths, changedPaths, projectPrefix)
    }

    fun readFileAtBranch(
        project: Project,
        repository: GitRepository,
        branch: String,
        path: String,
    ): ByteArray? {
        val projectRoot = projectRoot(project) ?: return null
        val projectPrefix = VfsUtilCore.getRelativePath(projectRoot, repository.root, '/') ?: return null
        val gitPath = joinPath(projectPrefix, path)
        val existenceHandler = GitLineHandler(project, repository.root, GitCommand.LS_TREE)
        existenceHandler.addParameters(branch, "--", gitPath)
        val existenceResult = git.runCommand(existenceHandler)
        existenceResult.throwOnError()
        if (existenceResult.output.isEmpty()) return null
        return GitBinaryHandler(project, repository.root, GitCommand.SHOW).apply {
                addParameters("$branch:$gitPath")
            }
            .run()
    }

    companion object {
        private fun projectRoot(project: Project) =
            project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }

        @JvmStatic
        fun buildResults(
            paths: List<String>,
            changedPaths: List<String>,
            projectPrefix: String = "",
        ): List<FileDiffResult> {
            val changed = changedPaths.mapTo(HashSet()) { normalizePath(it) }
            return paths.map {
                FileDiffResult(it, normalizePath(joinPath(projectPrefix, it)) in changed)
            }
        }

        @JvmStatic
        fun parseZeroTerminatedPaths(output: ByteArray): List<String> {
            if (output.isEmpty()) return emptyList()
            val paths = ArrayList<String>()
            var start = 0
            output.indices.forEach { index ->
                if (output[index].toInt() == 0) {
                    if (index > start)
                        paths.add(String(output, start, index - start, StandardCharsets.UTF_8))
                    start = index + 1
                }
            }
            if (start < output.size)
                paths.add(String(output, start, output.size - start, StandardCharsets.UTF_8))
            return paths
        }

        @JvmStatic
        fun findRemoteName(branch: String, remoteNames: List<String>): String? =
            remoteNames
                .sortedByDescending { it.length }
                .firstOrNull { branch.startsWith("$it/") }

        private fun normalizePath(path: String) = path.replace('\\', '/').removePrefix("./")

        private fun joinPath(prefix: String, path: String) =
            if (prefix.isBlank()) path else "${prefix.trimEnd('/')}/${path.trimStart('/')}"
    }

    data class FileDiffResult(val path: String, val changed: Boolean)
}
