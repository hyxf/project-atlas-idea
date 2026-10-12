package com.aicode.feature.git.service

import com.aicode.feature.git.model.ChangelogData
import com.aicode.feature.git.model.ChangelogData.Commit
import com.aicode.feature.git.model.ChangelogData.Release
import com.aicode.feature.git.model.SemVer
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepository

class GitChangelogService {
    private val git = Git.getInstance()

    @Throws(VcsException::class)
    fun read(project: Project, repository: GitRepository): ChangelogData {
        val tags = readTags(project, repository)
        validateLinearReleaseHistory(project, repository, tags)
        val releases = mutableListOf<Release>()
        val latestTag = tags.lastOrNull()?.name
        releases.add(
            Release(
                "Unreleased",
                "",
                readCommits(project, repository, latestTag?.let { "$it..HEAD" } ?: "HEAD"),
                true,
            )
        )
        for (index in tags.indices.reversed()) {
            val tag = tags[index]
            val range = if (index == 0) tag.name else "${tags[index - 1].name}..${tag.name}"
            releases.add(
                Release(
                    tag.name.substring(1),
                    tag.date,
                    readCommits(project, repository, range),
                    false,
                )
            )
        }
        return ChangelogData(java.util.List.copyOf(releases))
    }

    @Throws(VcsException::class)
    private fun validateLinearReleaseHistory(
        project: Project,
        repository: GitRepository,
        tags: List<Tag>,
    ) {
        for (index in 1 until tags.size) {
            val previous = tags[index - 1]
            val current = tags[index]
            val handler = GitLineHandler(project, repository.root, GitCommand.MERGE_BASE)
            handler.addParameters("--is-ancestor", previous.name, current.name)
            val result = git.runCommand(handler)
            if (result.success()) continue
            if (result.exitCode == 1)
                throw VcsException(
                    "Cannot generate an accurate changelog because ${previous.name} is not an ancestor of ${current.name}. " +
                        "The semantic-version tags do not form a linear release history."
                )
            result.throwOnError()
        }
    }

    @Throws(VcsException::class)
    private fun readTags(project: Project, repository: GitRepository): List<Tag> {
        val handler = GitLineHandler(project, repository.root, GitCommand.TAG)
        handler.addParameters(
            "--merged",
            "HEAD",
            "--format=%(refname:short)%09%(creatordate:short)",
        )
        val result = git.runCommand(handler)
        result.throwOnError()
        return result.output.mapNotNull(::parseTag).sortedBy { it.version }
    }

    @Throws(VcsException::class)
    private fun readCommits(
        project: Project,
        repository: GitRepository,
        revision: String,
    ): List<Commit> {
        val handler = GitLineHandler(project, repository.root, GitCommand.LOG)
        handler.addParameters("--no-merges", "--pretty=format:%H%x09%s", revision)
        val result = git.runCommand(handler)
        result.throwOnError()
        return result.output.mapNotNull(::parseCommit)
    }

    data class Tag(val name: String, val date: String, val version: SemVer) {
        fun name() = name

        fun date() = date

        fun version() = version
    }

    companion object {
        @JvmStatic
        fun parseTag(line: String): Tag? {
            val separator = line.indexOf('\t')
            val name = if (separator < 0) line else line.substring(0, separator)
            val version = SemVer.parseTag(name) ?: return null
            val date = if (separator < 0) "" else line.substring(separator + 1).trim()
            return Tag(name, date, version)
        }

        @JvmStatic
        fun parseCommit(line: String): Commit? {
            val separator = line.indexOf('\t')
            if (separator <= 0 || separator == line.length - 1) return null
            return Commit(line.substring(0, separator), line.substring(separator + 1).trim())
        }
    }
}
