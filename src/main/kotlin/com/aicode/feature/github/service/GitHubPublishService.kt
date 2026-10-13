package com.aicode.feature.github.service

import com.aicode.feature.github.model.GitHubConfiguration
import com.aicode.feature.github.model.GitHubRepository
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import git4idea.GitUtil
import git4idea.checkin.GitCheckinEnvironment
import git4idea.checkin.GitCommitOptions
import git4idea.changes.GitChangeUtils
import git4idea.commands.Git
import git4idea.push.GitPushParamsImpl
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryImpl
import git4idea.util.GitFileUtils
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

data class PublishPreparation(
    val path: Path,
    val name: String,
    val existingGit: Boolean,
    val head: String?,
    val branch: String?,
    val hasUncommittedChanges: Boolean,
    val fileSnapshot: List<String>,
    val sensitivePaths: List<String>,
)

class PublishFailure(val stage: String, message: String, val repository: GitHubRepository? = null) : Exception(message)

/** All repository operations go through Git4Idea; no process or shell is started here. */
class GitHubPublishService(private val project: Project, private val api: GitHubApiClient = GitHubApiClient()) {
    private val git = Git.getInstance()

    fun targetExists(configuration: GitHubConfiguration, name: String): Boolean = api.repositoryExists(configuration, name)

    fun prepare(path: Path): PublishPreparation {
        val root = path.toAbsolutePath().normalize()
        if (!Files.isDirectory(root)) throw PublishFailure("Project selection", "Project directory is unavailable: $root")
        val name = root.fileName?.toString().orEmpty()
        if (!name.matches(Regex("[A-Za-z0-9._-]+")) || name == "." || name == "..") {
            throw PublishFailure("Project selection", "Directory name '$name' is not a valid GitHub repository name.")
        }
        val gitRoot = GitUtil.findGitRootFor(root)
        if (gitRoot == null && Files.exists(root.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
            throw PublishFailure("Git check", "A .git entry exists but Git4Idea cannot identify a valid repository. Review it before publishing.")
        }
        if (gitRoot != null && Path.of(gitRoot.path).toAbsolutePath().normalize() != root) {
            throw PublishFailure("Git check", "This directory belongs to a different Git repository: ${gitRoot.path}")
        }
        val files = snapshot(root)
        if (files.isEmpty()) throw PublishFailure("Content check", "The project has no files to publish.")
        val repository = if (gitRoot != null) repository(root) else null
        repository?.update()
        if (repository != null && repository.remotes.isNotEmpty()) {
            throw PublishFailure("Remote check", "This project already has a Git remote. No remote was changed.")
        }
        val head = repository?.currentRevision
        val branch = repository?.currentBranch?.name
        if (repository != null && head.isNullOrBlank()) {
            throw PublishFailure("Commit and branch check", "No commits yet. Commit your changes in IDEA, then publish again.")
        }
        if (repository != null && branch.isNullOrBlank()) {
            throw PublishFailure("Commit and branch check", "Detached HEAD. Switch to a local branch in IDEA, then publish again.")
        }
        val changed = repository?.let { GitUtil.hasLocalChanges(true, project, it.root) } ?: false
        val sensitive = files.filter(::looksSensitive).take(20)
        return PublishPreparation(root, name, repository != null, head, branch, changed, files, sensitive)
    }

    fun publish(prepared: PublishPreparation, configuration: GitHubConfiguration, progress: (String) -> Unit): GitHubRepository {
        var created: GitHubRepository? = null
        try {
            progress("Checking the local project again…")
            val current = prepare(prepared.path)
            if (current != prepared) throw PublishFailure("State check", "The project changed after confirmation. Review it and retry.")
            progress("Checking the target GitHub account…")
            if (atStage("Repository lookup") { api.repositoryExists(configuration, prepared.name) }) {
                throw PublishFailure("Repository lookup", "${configuration.user}/${prepared.name} already exists. No local Git settings were changed.")
            }
            val repo = if (prepared.existingGit) repository(prepared.path) else {
                progress("Initializing Git and creating the initial commit…")
                initializeAndCommit(prepared)
            }
            repo.update()
            if (repo.remotes.isNotEmpty()) throw PublishFailure("Remote check", "A Git remote was added while publishing. No remote was changed.")
            val branch = repo.currentBranch?.name
                ?: throw PublishFailure("Branch check", "There is no named current branch after the initial commit.")
            val head = repo.currentRevision
                ?: throw PublishFailure("Commit check", "There is no commit to push.")
            if (prepared.existingGit && (head != prepared.head || branch != prepared.branch)) {
                throw PublishFailure("State check", "HEAD or the current branch changed after confirmation. Retry publishing.")
            }
            progress("Creating the private GitHub repository…")
            created = atStage("GitHub creation") { api.createPrivateRepository(configuration, prepared.name) }
            progress("Adding the origin remote…")
            val beforeRemote = repository(prepared.path).apply { update() }
            if (beforeRemote.remotes.isNotEmpty()) throw PublishFailure("Remote check", "A Git remote appeared after GitHub creation.", created)
            if (beforeRemote.currentRevision != head || beforeRemote.currentBranch?.name != branch ||
                snapshot(prepared.path) != prepared.fileSnapshot) {
                throw PublishFailure("State check", "HEAD, branch, or project files changed after GitHub creation.", created)
            }
            atStage("Add remote", created) { git.addRemote(beforeRemote, "origin", created.sshUrl).throwOnError() }
            progress("Pushing the current branch…")
            val pushRepo = repository(prepared.path).apply { update() }
            val origin = pushRepo.remotes.singleOrNull { it.name == "origin" }
                ?: throw PublishFailure("Push check", "The origin remote is unavailable.", created)
            if (pushRepo.remotes.size != 1 || origin.urls.singleOrNull() != created.sshUrl ||
                pushRepo.currentRevision != head || pushRepo.currentBranch?.name != branch ||
                snapshot(prepared.path) != prepared.fileSnapshot) {
                throw PublishFailure("Push check", "The local branch, commit, remote, or project files changed before push.", created)
            }
            if (prepared.existingGit && GitUtil.hasLocalChanges(true, project, pushRepo.root) != prepared.hasUncommittedChanges) {
                throw PublishFailure("Push check", "The working tree changed before push. Review it and retry.", created)
            }
            val params = GitPushParamsImpl(origin, "refs/heads/$branch:refs/heads/$branch", false, true, false, null, emptyList())
            val result = atStage("Push", created) { git.push(pushRepo, params) }
            if (!result.success()) throw PublishFailure("Push", result.errorOutputAsJoinedString.ifBlank { "Git push failed." }, created)
            return created
        } catch (failure: PublishFailure) {
            throw failure
        } catch (error: Exception) {
            throw PublishFailure(if (created == null) "Preparation or GitHub creation" else "Remote or push", error.message ?: error.javaClass.simpleName, created)
        }
    }

    private fun initializeAndCommit(prepared: PublishPreparation): GitRepository {
        if (GitUtil.findGitRootFor(prepared.path) != null || snapshot(prepared.path) != prepared.fileSnapshot) {
            throw PublishFailure("Git initialization", "The project changed before Git initialization. Review it and retry.")
        }
        val root = virtualRoot(prepared.path)
        atStage("Git initialization") { git.init(project, root).throwOnError() }
        root.refresh(false, true)
        val repository = repository(prepared.path)
        if (repository.remotes.isNotEmpty()) throw PublishFailure("Remote check", "A remote appeared after Git initialization.")
        if (snapshot(prepared.path) != prepared.fileSnapshot) {
            throw PublishFailure("Initial commit", "Project files changed after Git initialization. Review them before retrying.")
        }
        val children = root.children.filter { it.name != ".git" }
        if (children.isEmpty()) throw PublishFailure("Initial commit", "There are no project files to commit.")
        atStage("Initial staging") { GitFileUtils.addFiles(project, root, children) }
        val staged = atStage("Initial staging") { GitChangeUtils.getStagedChanges(project, root) }
        if (staged.isEmpty()) throw PublishFailure("Initial staging", "No project files were staged. Check ignore rules and retry.")
        if (snapshot(prepared.path) != prepared.fileSnapshot) {
            throw PublishFailure("Initial commit", "Project files changed before the initial commit. Review staged files before retrying.")
        }
        val messageFile = GitCheckinEnvironment.createCommitMessageFile(project, root, "Initial commit")
        try {
            val errors = atStage("Initial commit") {
                GitCheckinEnvironment.commitUsingIndex(
                    project, repository,
                    staged.map { GitCheckinEnvironment.ChangedPath(it.beforePath, it.afterPath) },
                    emptySet(), messageFile, GitCommitOptions(),
                )
            }
            if (errors.isNotEmpty()) throw PublishFailure("Initial commit", errors.joinToString("; ") { it.message.orEmpty() })
        } finally {
            Files.deleteIfExists(messageFile.toPath())
        }
        repository.update()
        if (repository.currentRevision.isNullOrBlank()) throw PublishFailure("Initial commit", "The initial commit did not produce a valid HEAD.")
        return repository
    }

    private fun repository(path: Path): GitRepository =
        GitRepositoryImpl.getInstance(virtualRoot(path), project, false)

    private fun virtualRoot(path: Path) =
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
            ?: throw PublishFailure("Git check", "The project directory is not visible to the IDE: $path")

    private fun snapshot(root: Path): List<String> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) && !it.startsWith(root.resolve(".git")) }
            .map { "${root.relativize(it)}:${Files.size(it)}:${Files.getLastModifiedTime(it).toMillis()}" }
            .sorted().toList()
    }

    private fun looksSensitive(file: String): Boolean {
        val name = file.substringBefore(':').replace('\\', '/').substringAfterLast('/').lowercase()
        return name == ".env" || name.startsWith(".env.") || name.endsWith(".pem") ||
            name.endsWith(".key") || name == "id_rsa" || name == "id_ed25519" ||
            name == "credentials.json" || name == "secrets.json"
    }

    private inline fun <T> atStage(stage: String, repository: GitHubRepository? = null, action: () -> T): T =
        try { action() } catch (failure: PublishFailure) { throw failure }
        catch (error: Exception) { throw PublishFailure(stage, error.message ?: error.javaClass.simpleName, repository) }
}
