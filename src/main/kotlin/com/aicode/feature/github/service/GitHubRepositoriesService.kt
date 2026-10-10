package com.aicode.feature.github.service

import com.aicode.feature.github.model.GitHubConfiguration
import com.aicode.feature.github.model.GitHubRepository
import com.aicode.feature.github.persistence.GitHubConfigurationStore
import com.aicode.feature.github.persistence.RepositoryJsonStore
import com.aicode.feature.github.persistence.SavedGitRepository
import com.aicode.feature.projectmanager.feature.project.ProjectManagerService
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

class GitHubRepositoriesService(
    private val configurationStore: GitHubConfigurationStore = GitHubConfigurationStore(),
    private val repositoryStore: RepositoryJsonStore = RepositoryJsonStore(),
    private val api: GitHubApiClient = GitHubApiClient(),
    private val clone: GitCloneService = GitCloneService(),
    private val projects: ProjectManagerService? = null,
) {
    private val refreshing = AtomicBoolean(false)

    fun cached(): List<GitHubRepository> = configurationStore.repositories().map { it.first }
    fun configuration(): GitHubConfiguration = configurationStore.configuration()
    fun savedRepositories(): List<SavedGitRepository> = repositoryStore.repositories()

    fun refresh(): Int {
        check(refreshing.compareAndSet(false, true)) { "GitHub repositories are already refreshing." }
        try {
            val config = configurationStore.configuration()
            val result = api.repositories(config)
            configurationStore.replaceRepositories(result)
            return result.size
        } finally { refreshing.set(false) }
    }

    fun addRepository(repository: GitHubRepository, tags: List<String>): Boolean = repositoryStore.addIfMissing(
        SavedGitRepository(repository.owner, repository.name, repository.sshUrl, tags, repository.description),
    )

    fun defaultCloneParent(parent: Path = Path.of(System.getProperty("user.home"), "ProjectAtlas")): Path {
        Files.createDirectories(parent)
        require(Files.isDirectory(parent)) { "Default clone parent is not a directory: $parent" }
        return parent.toAbsolutePath().normalize()
    }

    fun resolveCloneTarget(repository: GitHubRepository, parent: Path): Path {
        val name = repository.name.trim()
        require(name.isNotEmpty() && name != "." && name != ".." && !name.contains('/') && !name.contains('\\')) {
            "Repository name cannot be used as a directory name."
        }
        require(Files.isDirectory(parent)) { "Clone parent is not a directory: $parent" }
        val target = parent.resolve(name).toAbsolutePath().normalize()
        require(target != target.root) { "The file system root cannot be used as a clone target." }
        if (Files.exists(target)) require(Files.isDirectory(target) && Files.list(target).use { !it.findAny().isPresent }) {
            "Clone target already exists and is not empty: $target"
        }
        return target
    }

    fun cloneRepository(repository: GitHubRepository, parent: Path, cancelled: AtomicBoolean, progress: (String) -> Unit,
                        shouldCancel: () -> Boolean = { false }): Path {
        val config = configurationStore.configuration()
        val target = resolveCloneTarget(repository, parent)
        val targetExisted = Files.exists(target)
        if (targetExisted) require(Files.isDirectory(target) && Files.list(target).use { !it.findAny().isPresent }) {
            "Clone target already exists and is not empty: $target"
        }
        try {
            clone.clone(repository.sshUrl, parent, target, config, cancelled, progress, shouldCancel)
        } catch (error: CloneCancelledException) {
            val cleaned = cleanup(target, targetExisted)
            throw CloneCancelledException(target, cleaned)
        }
        return target
    }

    fun addClonedProject(repository: GitHubRepository, target: Path) {
        try {
            val projectManager = projects ?: throw IllegalStateException("Project service is unavailable.")
            val key = RepositoryJsonStore.sshIdentity(repository.sshUrl)
            val tags = repositoryStore.repositories().firstOrNull {
                it.url == repository.sshUrl || (key != null && RepositoryJsonStore.sshIdentity(it.url) == key)
            }?.tags?.toSet().orEmpty()
            projectManager.saveProject(repository.name, target, tags, false)
        } catch (error: Exception) {
            throw IllegalStateException("Repository cloned to $target, but it could not be added to Projects: ${error.message}", error)
        }
    }

    private fun cleanup(target: Path, targetExisted: Boolean): Boolean = runCatching {
        if (Files.isDirectory(target)) {
            Files.list(target).use { children -> children.forEach { child ->
                Files.walk(child).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            } }
            if (!targetExisted) Files.deleteIfExists(target)
        }
        true
    }.getOrDefault(false)
}

class CloneCancelledException(val target: Path, val cleaned: Boolean = false) : RuntimeException()

fun interface GitCommandRunner {
    fun run(arguments: List<String>, workingDirectory: Path, cancelled: AtomicBoolean,
            shouldCancel: () -> Boolean, progress: (String) -> Unit): Int
}

class GitCloneService(private val runner: GitCommandRunner = ProcessGitCommandRunner()) {
    fun clone(url: String, parent: Path, target: Path, config: GitHubConfiguration,
              cancelled: AtomicBoolean, progress: (String) -> Unit, shouldCancel: () -> Boolean = { false }) {
        val args = mutableListOf<String>()
        if (config.proxyEnabled) {
            val proxy = config.socketProxy.takeIf { url.startsWith("git@") || url.startsWith("ssh:") }
            if (proxy != null && proxy.isNotBlank()) {
                val uri = runCatching { java.net.URI(proxy) }.getOrNull()
                if (uri != null && uri.scheme.startsWith("socks")) {
                    val address = "${uri.host}:${if (uri.port > 0) uri.port else 1080}"
                    args += listOf("-c", "core.sshCommand=ssh -o \"ProxyCommand=nc -x $address -X 5 %h %p\"")
                }
            } else if (config.httpProxy.isNotBlank()) args += listOf("-c", "http.proxy=${config.httpProxy}")
        }
        args += listOf("clone", "--", url, target.toString())
        progress("Cloning from GitHub…")
        val code = runner.run(args, parent, cancelled, shouldCancel, progress)
        if (cancelled.get() || shouldCancel()) { cancelled.set(true); throw CloneCancelledException(target) }
        check(code == 0) { "Git clone failed (exit code $code). If a partial directory remains, remove it before retrying." }
    }
}

private class ProcessGitCommandRunner : GitCommandRunner {
    override fun run(arguments: List<String>, workingDirectory: Path, cancelled: AtomicBoolean,
                     shouldCancel: () -> Boolean, progress: (String) -> Unit): Int {
        val process = ProcessBuilder(listOf("git") + arguments).directory(workingDirectory.toFile())
            .redirectErrorStream(true).start()
        val reader = Thread {
            process.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                if (line.contains("Receiving objects") || line.contains("Resolving deltas")) progress(line.take(160))
            } }
        }.apply { isDaemon = true; start() }
        while (process.isAlive) {
            if (cancelled.get() || shouldCancel()) {
                cancelled.set(true)
                process.destroy()
                if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
                reader.join(500)
                throw CloneCancelledException(workingDirectory)
            }
            process.waitFor(100, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        reader.join(1000)
        return process.exitValue()
    }
}
