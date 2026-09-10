package com.aicode.feature.git.util

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VirtualFile
import git4idea.remote.hosting.GitHostingUrlUtil
import git4idea.repo.GitRemote
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

object GitRemoteUrlResolver {
    @JvmStatic
    fun findRepository(project: Project, contextFile: VirtualFile?): GitRepository? {
        val manager = GitRepositoryManager.getInstance(project)
        contextFile
            ?.let { manager.getRepositoryForFileQuick(it) }
            ?.let {
                return it
            }
        project.guessProjectDir()
            ?.let { manager.getRepositoryForFileQuick(it) }
            ?.let {
                return it
            }
        return manager.repositories.firstOrNull()
    }

    @JvmStatic
    fun getOriginUrl(repository: GitRepository): String? =
        repository.remotes
            .asSequence()
            .filter { it.name == GitRemote.ORIGIN }
            .mapNotNull { it.firstUrl }
            .firstOrNull { it.isNotBlank() }

    @JvmStatic
    fun getCurrentBranch(repository: GitRepository): String? = repository.currentBranch?.name

    @JvmStatic
    fun toWebUrl(rawUrl: String, branch: String?, relativePath: String?): String? =
        toWebUrl(rawUrl, branch, relativePath, detectHostingPlatform(rawUrl))

    @JvmStatic
    fun toWebUrl(
        rawUrl: String,
        branch: String?,
        relativePath: String?,
        platform: HostingPlatform,
    ): String? = toWebUrl(rawUrl, branch, relativePath, platform, PathType.DIRECTORY)

    @JvmStatic
    fun toWebUrl(
        rawUrl: String,
        branch: String?,
        relativePath: String?,
        platform: HostingPlatform,
        pathType: PathType,
    ): String? {
        val uri = GitHostingUrlUtil.getUriFromRemoteUrl(rawUrl.trim()) ?: return null
        val host = uri.host
        val path = normalizeRepositoryPath(uri.path)
        if (host.isNullOrBlank() || path.isBlank()) return null
        val lower = rawUrl.trim().lowercase(Locale.ROOT)
        val scheme = if (lower.startsWith("http://")) "http" else "https"
        val retainPort = lower.startsWith("http://") || lower.startsWith("https://")
        val authority = if (retainPort && uri.port >= 0) "$host:${uri.port}" else host
        var result = "$scheme://$authority/$path"
        if (!branch.isNullOrBlank()) {
            if (platform == HostingPlatform.UNKNOWN) return null
            result += platform.getRoute(pathType) + encodePath(branch)
            val cleanPath = trimSlashes(relativePath?.replace('\\', '/') ?: "")
            if (cleanPath.isNotBlank()) result += "/" + encodePath(cleanPath)
        }
        return result
    }

    @JvmStatic
    fun detectHostingPlatform(rawUrl: String): HostingPlatform {
        val host =
            GitHostingUrlUtil.getUriFromRemoteUrl(rawUrl.trim())?.host?.lowercase(Locale.ROOT)
                ?: return HostingPlatform.UNKNOWN
        return when {
            host.contains("gitlab") -> HostingPlatform.GITLAB
            host.contains("bitbucket") -> HostingPlatform.BITBUCKET
            host.contains("github") -> HostingPlatform.GITHUB
            host.contains("gitee") -> HostingPlatform.GITEE
            host.contains("codeup") -> HostingPlatform.CODEUP
            else -> HostingPlatform.UNKNOWN
        }
    }

    @JvmStatic
    fun getHost(rawUrl: String): String? =
        GitHostingUrlUtil.getUriFromRemoteUrl(rawUrl.trim())?.host

    private fun encodePath(value: String) =
        URLEncoder.encode(value, StandardCharsets.UTF_8)
            .replace("+", "%20")
            .replace("%2F", "/")
            .replace("%2f", "/")

    private fun trimSlashes(value: String) = value.replace(Regex("^/+|/+$"), "")

    private fun normalizeRepositoryPath(path: String?): String =
        trimSlashes(path ?: "").let { if (it.endsWith(".git")) it.dropLast(4) else it }

    enum class HostingPlatform(private val treeRoute: String, private val blobRoute: String) {
        GITHUB("/tree/", "/blob/"),
        GITLAB("/-/tree/", "/-/blob/"),
        GITEE("/tree/", "/blob/"),
        CODEUP("/tree/", "/blob/"),
        BITBUCKET("/src/", "/src/"),
        UNKNOWN("", "");

        fun getTreeRoute() = treeRoute

        fun getRoute(pathType: PathType) = if (pathType == PathType.FILE) blobRoute else treeRoute
    }

    enum class PathType {
        DIRECTORY,
        FILE,
    }
}
