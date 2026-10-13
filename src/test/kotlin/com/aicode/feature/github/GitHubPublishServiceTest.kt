package com.aicode.feature.github

import com.aicode.feature.github.service.GitHubPublishService
import com.aicode.feature.github.service.PublishFailure
import com.aicode.feature.github.model.GitHubConfiguration
import com.aicode.feature.github.service.GitHubApiClient
import com.aicode.feature.github.service.GitHubCreateTransport
import com.aicode.feature.github.service.GitHubHttpResponse
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import git4idea.commands.Git
import git4idea.checkin.GitCheckinEnvironment
import git4idea.checkin.GitCommitOptions
import git4idea.changes.GitChangeUtils
import git4idea.repo.GitRepositoryImpl
import git4idea.util.GitFileUtils
import java.nio.file.Files
import java.nio.file.Path

class GitHubPublishServiceTest : BasePlatformTestCase() {
    private val temporaryRoots = mutableListOf<Path>()
    override fun runInDispatchThread() = false

    override fun tearDown() {
        try {
            temporaryRoots.forEach { root ->
                Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            }
        } finally {
            super.tearDown()
        }
    }

    fun testNonGitProjectIsPreparedForInitialCommit() {
        val root = projectRoot()
        Files.writeString(Path.of(root.path, ".env"), "example")

        val prepared = GitHubPublishService(project).prepare(Path.of(root.path))

        assertFalse(prepared.existingGit)
        assertNull(prepared.head)
        assertTrue(prepared.sensitivePaths.any { it.startsWith(".env:") })
    }

    fun testExistingGitWithoutCommitStopsBeforeRemoteCreation() {
        val root = projectRoot()
        Git.getInstance().init(project, root).throwOnError()
        root.refresh(false, true)

        val failure = org.junit.Assert.assertThrows(PublishFailure::class.java) {
            GitHubPublishService(project).prepare(Path.of(root.path))
        }

        assertEquals("Commit and branch check", failure.stage)
    }

    fun testAnyExistingRemoteBlocksPublication() {
        val root = projectRoot()
        Git.getInstance().init(project, root).throwOnError()
        root.refresh(false, true)
        val repository = GitRepositoryImpl.getInstance(root, project, false)
        Git.getInstance().addRemote(repository, "backup", "git@github.com:atlas/backup.git").throwOnError()

        val failure = org.junit.Assert.assertThrows(PublishFailure::class.java) {
            GitHubPublishService(project).prepare(Path.of(root.path))
        }

        assertEquals("Remote check", failure.stage)
    }

    fun testExistingGitWithCommitAndNoRemoteCanProceed() {
        val root = committedRoot()

        val prepared = GitHubPublishService(project).prepare(Path.of(root.path))

        assertTrue(prepared.existingGit)
        assertNotNull(prepared.head)
        assertNotNull(prepared.branch)
        assertFalse(prepared.hasUncommittedChanges)
    }

    fun testRemoteAppearingAfterGitHubCreationIsPreservedAndStopsPush() {
        val root = committedRoot()
        val repository = GitRepositoryImpl.getInstance(root, project, false)
        val config = GitHubConfiguration("token", "atlas", false, "", "")
        val client = GitHubApiClient(
            { url, _ ->
                if (url.endsWith("/user")) GitHubHttpResponse(200, mapOf("X-OAuth-Scopes" to listOf("repo")), """{"login":"atlas"}""")
                else GitHubHttpResponse(200, emptyMap(), "[]")
            },
            object : GitHubCreateTransport {
                override fun create(configuration: GitHubConfiguration, name: String): GitHubHttpResponse {
                    Git.getInstance().addRemote(repository, "backup", "git@github.com:atlas/backup.git").throwOnError()
                    return GitHubHttpResponse(201, emptyMap(),
                        """{"id":9,"name":"$name","full_name":"atlas/$name","owner":{"login":"atlas"},"html_url":"https://github.com/atlas/$name","ssh_url":"git@github.com:atlas/$name.git","clone_url":"https://github.com/atlas/$name.git","private":true,"archived":false,"fork":false,"updated_at":"now"}""")
                }
            },
        )
        val service = GitHubPublishService(project, client)
        val prepared = service.prepare(Path.of(root.path))

        val failure = org.junit.Assert.assertThrows(PublishFailure::class.java) {
            service.publish(prepared, config) { }
        }

        repository.update()
        assertEquals("Remote check", failure.stage)
        assertEquals("https://github.com/atlas/${root.name}", failure.repository?.htmlUrl)
        assertEquals(listOf("backup"), repository.remotes.map { it.name })
    }

    fun testExistingGitHubRepositoryStopsBeforeInitializingLocalGit() {
        val root = projectRoot()
        val config = GitHubConfiguration("token", "atlas", false, "", "")
        val client = GitHubApiClient { url, _ ->
            val body = if (url.endsWith("/user")) """{"login":"atlas"}""" else
                """[{"id":1,"name":"${root.name}","full_name":"atlas/${root.name}","owner":{"login":"atlas"},"html_url":"https://github.com/atlas/${root.name}","ssh_url":"git@github.com:atlas/${root.name}.git","clone_url":"https://github.com/atlas/${root.name}.git","private":true,"archived":false,"fork":false,"updated_at":"now"}]"""
            GitHubHttpResponse(200, emptyMap(), body)
        }
        val service = GitHubPublishService(project, client)
        val prepared = service.prepare(Path.of(root.path))

        val failure = org.junit.Assert.assertThrows(PublishFailure::class.java) {
            service.publish(prepared, config) { }
        }

        assertEquals("Repository lookup", failure.stage)
        assertNull(git4idea.GitUtil.findGitRootFor(Path.of(root.path)))
    }

    private fun projectRoot() = Files.createTempDirectory("atlas-publish-test-").let { path ->
        temporaryRoots.add(path)
        Files.writeString(path.resolve("README.md"), "project")
        requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
    }

    private fun committedRoot() = projectRoot().also { root ->
        Git.getInstance().init(project, root).throwOnError()
        root.refresh(false, true)
        val repository = GitRepositoryImpl.getInstance(root, project, false)
        Git.getInstance().config(repository, "user.name", "Atlas Test").throwOnError()
        Git.getInstance().config(repository, "user.email", "atlas@example.test").throwOnError()
        GitFileUtils.addFiles(project, root, listOf(requireNotNull(root.findChild("README.md"))))
        val staged = GitChangeUtils.getStagedChanges(project, root)
        assertTrue("README.md must be staged", staged.isNotEmpty())
        val message = GitCheckinEnvironment.createCommitMessageFile(project, root, "Test commit")
        try {
            val errors = GitCheckinEnvironment.commitUsingIndex(
                project, repository,
                staged.map { GitCheckinEnvironment.ChangedPath(it.beforePath, it.afterPath) },
                emptySet(), message, GitCommitOptions(),
            )
            assertTrue(errors.joinToString("; ") { it.message.orEmpty() }, errors.isEmpty())
        } finally {
            Files.deleteIfExists(message.toPath())
        }
    }
}
