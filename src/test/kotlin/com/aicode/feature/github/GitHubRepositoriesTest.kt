package com.aicode.feature.github

import com.aicode.feature.github.model.GitHubConfiguration
import com.aicode.feature.github.model.GitHubRepository
import com.aicode.feature.github.persistence.GitHubConfigurationStore
import com.aicode.feature.github.persistence.RepositoryJsonStore
import com.aicode.feature.github.persistence.SavedGitRepository
import com.aicode.feature.github.service.CloneCancelledException
import com.aicode.feature.github.service.GitHubApiClient
import com.aicode.feature.github.service.GitHubHttpResponse
import com.aicode.feature.github.service.GitHubRepositoriesService
import com.aicode.feature.github.service.GitCloneService
import com.aicode.feature.github.service.GitCommandRunner
import com.aicode.feature.github.service.groupRepositories
import com.aicode.feature.github.ui.shouldOpenGithubOnClick
import com.aicode.feature.projectmanager.feature.project.ProjectItem
import com.aicode.feature.projectmanager.feature.project.ProjectManagerService
import com.aicode.feature.projectmanager.feature.project.ProjectRepository
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GitHubRepositoriesTest {
    @Rule @JvmField val temporaryFolder = TemporaryFolder()

    @Test
    fun `reads legacy proxy field defaults and leaves an existing config untouched`() {
        val dir = temporaryFolder.newFolder("github-config").toPath()
        val file = dir.resolve("github.json")
        Files.writeString(file, """{"token":"secret","user":"atlas","proxy":"http://127.0.0.1:9000","extra":7}""")
        val store = GitHubConfigurationStore(file)
        val config = store.configuration()
        assertEquals("secret", config.token)
        assertEquals("atlas", config.user)
        assertEquals("http://127.0.0.1:9000", config.httpProxy)
        assertEquals(GitHubConfigurationStore.DEFAULT_SOCKET_PROXY, config.socketProxy)
        assertFalse(config.proxyEnabled)
        store.ensureFile()
        assertTrue(Files.readString(file).contains("\"extra\""))
        assertTrue(Files.readString(file).contains("\"httpProxy\": \"http://127.0.0.1:9000\""))
        assertFalse(Files.readString(file).contains("\"proxy\":"))
    }

    @Test
    fun `rejects unsupported proxy protocols without including proxy credentials`() {
        val file = temporaryFolder.newFolder("github-invalid-proxy").toPath().resolve("github.json")
        Files.writeString(file, """{"token":"t","user":"u","proxyEnabled":false,"httpProxy":"ftp://user:password@proxy.invalid","socketProxy":"socks5://proxy.invalid"}""")
        val error = assertThrows(IllegalStateException::class.java) { GitHubConfigurationStore(file).configuration() }
        assertFalse(error.message.orEmpty().contains("password"))
        assertFalse(error.message.orEmpty().contains("proxy.invalid"))
    }

    @Test
    fun `requires configured token and user for network operations but permits opening settings`() {
        val file = temporaryFolder.newFolder("github-empty-credentials").toPath().resolve("github.json")
        val store = GitHubConfigurationStore(file)
        store.ensureFile()
        assertEquals("", store.settingsConfiguration().token)
        assertThrows(IllegalArgumentException::class.java) { store.configuration() }
    }

    @Test
    fun `settings can load and repair invalid proxy urls`() {
        val file = temporaryFolder.newFolder("github-repair-proxy").toPath().resolve("github.json")
        Files.writeString(file, """{"token":"t","user":"u","proxyEnabled":false,"httpProxy":"ftp://invalid.proxy","socketProxy":"socks5://localhost:1080"}""")
        val store = GitHubConfigurationStore(file)
        assertEquals("ftp://invalid.proxy", store.settingsConfiguration().httpProxy)
        assertThrows(IllegalStateException::class.java) { store.configuration() }

        store.updateSettings(httpProxy = "http://127.0.0.1:8080")
        assertEquals("http://127.0.0.1:8080", store.configuration().httpProxy)
    }

    @Test
    fun `merges unknown repository and top level fields on refresh`() {
        val dir = temporaryFolder.newFolder("github-merge").toPath()
        val file = dir.resolve("github.json")
        Files.writeString(file, """{"token":"t","user":"u","futureSetting":{"v":3},"repositories":[{"id":9,"name":"old","fullName":"org/old","owner":"org","htmlUrl":"https://github.com/org/old","sshUrl":"git@github.com:org/old.git","cloneUrl":"https://github.com/org/old.git","private":false,"archived":false,"fork":false,"updatedAt":"now","futureRepositoryField":"keep"}]}""")
        val store = GitHubConfigurationStore(file)
        store.replaceRepositories(listOf(repository(9, "org/new")))
        val text = Files.readString(file)
        assertTrue(text.contains("futureSetting"))
        assertTrue(text.contains("futureRepositoryField"))
        assertTrue(text.contains("org/new"))
    }

    @Test
    fun `does not overwrite invalid json and protects external edits during atomic write`() {
        val dir = temporaryFolder.newFolder("github-corrupt").toPath()
        val file = dir.resolve("github.json")
        Files.writeString(file, "[]")
        val invalid = GitHubConfigurationStore(file)
        assertThrows(IllegalStateException::class.java) { invalid.ensureFile() }
        assertEquals("[]", Files.readString(file))

        Files.writeString(file, "{}")
        val store = GitHubConfigurationStore(file) {
            Files.writeString(file, "{\"changedExternally\":true}")
        }
        assertThrows(IllegalStateException::class.java) { store.replaceRepositories(listOf(repository(1, "o/r"))) }
        assertTrue(Files.readString(file).contains("changedExternally"))
    }

    @Test
    fun `loads cache without calling api and groups public private unknown language and sorted full names`() {
        val dir = temporaryFolder.newFolder("github-cache").toPath()
        val file = dir.resolve("github.json")
        val store = GitHubConfigurationStore(file)
        store.ensureFile()
        store.replaceRepositories(listOf(repository(1, "zeta/X"), repository(2, "Alpha/b", language = null),
            repository(3, "private/a", private = true)))
        var calls = 0
        val client = GitHubApiClient { _, _ -> calls++; error("cache view must not request the API") }
        val service = GitHubRepositoriesService(store, RepositoryJsonStore(dir.resolve("repos.json")), client)
        assertEquals(3, service.cached().size)
        assertEquals(0, calls)
        val groups = groupRepositories(service.cached())
        assertEquals("Public", groups[0].title)
        assertEquals(listOf("Alpha/b", "zeta/X"), groups[0].repositories.map { it.fullName })
        assertEquals("Unknown", groups[0].languages.first { it.repositories.any { repo -> repo.id == 2L } }.language)
        assertEquals("Private", groups[1].title)
    }

    @Test
    fun `groups case-distinct languages and sorts repositories`() {
        val repositories = listOf(
            repository(1, "org/alpha", language = "Java"),
            repository(2, "org/beta", language = "Java"),
            repository(3, "org/gamma", language = "java"),
        )
        val groups = groupRepositories(repositories)
        val public = groups.first { it.title == "Public" }
        assertEquals(3, public.count)
        assertEquals(listOf("org/alpha", "org/beta", "org/gamma"), public.repositories.map { it.fullName })
        assertEquals(2, public.languages.size)
        assertEquals(2, public.languages.first { it.language == "Java" }.count)
        assertEquals(1, public.languages.first { it.language == "java" }.count)
    }

    @Test
    fun `only opens a repository webpage after a primary-button double click`() {
        assertFalse(shouldOpenGithubOnClick(1, java.awt.event.MouseEvent.BUTTON1))
        assertFalse(shouldOpenGithubOnClick(2, java.awt.event.MouseEvent.BUTTON3))
        assertTrue(shouldOpenGithubOnClick(2, java.awt.event.MouseEvent.BUTTON1))
    }

    @Test
    fun `validates authenticated user then follows every next page and passes proxy config`() {
        val calls = mutableListOf<String>()
        val observed = mutableListOf<GitHubConfiguration>()
        val client = GitHubApiClient { url, configuration ->
            calls += url; observed += configuration
            when {
                url.endsWith("/user") -> response(200, """{"login":"Atlas"}""")
                url.endsWith("per_page=100") -> GitHubHttpResponse(200, mapOf("Link" to listOf("<https://api.github.com/user/repos?page=2>; rel=\"next\"")), "[]")
                else -> response(200, "[]")
            }
        }
        val config = GitHubConfiguration("not-printed", "atlas", true, "http://localhost:8080", "socks5://localhost:1080")
        assertTrue(client.repositories(config).isEmpty())
        assertEquals(3, calls.size)
        assertTrue(observed.all { it == config })
        val mismatch = GitHubApiClient { _, _ -> response(200, """{"login":"someone-else"}""") }
        assertThrows(IllegalStateException::class.java) { mismatch.repositories(config) }
    }

    @Test
    fun `refuses cross-origin pagination links before sending the token`() {
        val requestedUrls = mutableListOf<String>()
        val client = GitHubApiClient { url, _ ->
            requestedUrls += url
            when {
                url.endsWith("/user") -> response(200, """{"login":"atlas"}""")
                else -> GitHubHttpResponse(
                    200,
                    mapOf("Link" to listOf("<https://attacker.example/collect>; rel=\"next\"")),
                    "[]",
                )
            }
        }

        val error = assertThrows(IllegalStateException::class.java) {
            client.repositories(GitHubConfiguration("test-token", "atlas", false, "", ""))
        }

        assertTrue(error.message.orEmpty().contains("unsafe pagination link"))
        assertEquals(listOf("https://api.github.com/user", "https://api.github.com/user/repos?per_page=100"), requestedUrls)
    }

    @Test
    fun `reports authentication and permission failures without echoing response body`() {
        for (status in listOf(401, 403)) {
            val client = GitHubApiClient { _, _ -> response(status, "token should never appear") }
            val error = assertThrows(IllegalStateException::class.java) {
                client.repositories(GitHubConfiguration("private-token", "atlas", false, "", ""))
            }
            assertFalse(error.message.orEmpty().contains("private-token"))
            assertFalse(error.message.orEmpty().contains("token should never appear"))
        }
    }

    @Test
    fun `rejects malformed api payloads and surfaces timeout as a network failure`() {
        val malformed = GitHubApiClient { url, _ ->
            if (url.endsWith("/user")) response(200, """{"login":"atlas"}""") else response(200, "{}")
        }
        assertThrows(IllegalStateException::class.java) {
            malformed.repositories(GitHubConfiguration("t", "atlas", false, "", ""))
        }
        val timeout = GitHubApiClient { _, _ -> throw java.net.SocketTimeoutException("timeout") }
        assertThrows(java.net.SocketTimeoutException::class.java) {
            timeout.repositories(GitHubConfiguration("t", "atlas", true, "http://127.0.0.1:1", ""))
        }
    }

    @Test
    fun `reports unavailable configured proxy without disclosing its address`() {
        val error = assertThrows(IllegalStateException::class.java) {
            GitHubApiClient().repositories(
                GitHubConfiguration("token-not-for-output", "atlas", true, "http://127.0.0.1:1", ""),
            )
        }
        assertTrue(error.message.orEmpty().contains("Check the network and proxy settings"))
        assertFalse(error.message.orEmpty().contains("127.0.0.1"))
        assertFalse(error.message.orEmpty().contains("token-not-for-output"))
    }

    @Test
    fun `deduplicates SSH urls and carries saved tags into cloned Projects`() {
        val dir = temporaryFolder.newFolder("github-project-tags").toPath()
        val repoStore = RepositoryJsonStore(dir.resolve("repos.json"))
        assertTrue(repoStore.addIfMissing(SavedGitRepository("org", "demo", "git@github.com:Org/Demo.git", listOf("work", "kotlin"))))
        assertFalse(repoStore.addIfMissing(SavedGitRepository("org", "demo", "git@GITHUB.com:Org/Demo", emptyList())))
        assertTrue(repoStore.addIfMissing(SavedGitRepository("org", "demo", "git@github.com:org/demo", emptyList())))
        val memory = MemoryProjects()
        val projectService = ProjectManagerService(memory, Clock.systemUTC())
        val configStore = GitHubConfigurationStore(dir.resolve("github.json"))
        configStore.ensureFile()
        val service = GitHubRepositoriesService(configStore, repoStore, projects = projectService)
        val target = dir.resolve("demo")
        Files.createDirectory(target)
        service.addClonedProject(repository(3, "Org/Demo"), target)
        assertEquals(setOf("work", "kotlin"), memory.items.single().tags)
    }

    @Test
    fun `explains where a successful clone remains when Projects persistence fails`() {
        val dir = temporaryFolder.newFolder("github-project-failure").toPath()
        val memory = MemoryProjects(failOnAdd = true)
        val projectService = ProjectManagerService(memory, Clock.systemUTC())
        val configStore = GitHubConfigurationStore(dir.resolve("github.json"))
        configStore.ensureFile()
        val service = GitHubRepositoriesService(configStore, RepositoryJsonStore(dir.resolve("repos.json")), projects = projectService)
        val target = dir.resolve("cloned-demo")
        val error = assertThrows(IllegalStateException::class.java) { service.addClonedProject(repository(8, "org/demo"), target) }
        assertTrue(error.message.orEmpty().contains(target.toString()))
        assertTrue(error.message.orEmpty().contains("could not be added to Projects"))
    }

    @Test
    fun `uses SOCKS proxy for SSH clone and cleans a partially cloned target after cancel`() {
        val dir = temporaryFolder.newFolder("github-clone-cancel").toPath()
        val configFile = dir.resolve("github.json")
        Files.writeString(configFile, """{"token":"t","user":"u","proxyEnabled":true,"httpProxy":"","socketProxy":"socks5://127.0.0.1:1086","repositories":[]}""")
        val config = GitHubConfigurationStore(configFile)
        var args = emptyList<String>()
        val target = dir.resolve("demo")
        val runner = GitCommandRunner { arguments, _, _, _, _ ->
            args = arguments
            Files.createDirectory(target)
            Files.writeString(target.resolve("partial"), "x")
            1
        }
        val service = GitHubRepositoriesService(config, RepositoryJsonStore(dir.resolve("repos.json")),
            clone = GitCloneService(runner))
        val error = assertThrows(CloneCancelledException::class.java) {
            service.cloneRepository(repository(4, "org/demo"), dir, AtomicBoolean(), {}, { true })
        }
        assertTrue(args.any { it.contains("core.sshCommand") })
        assertTrue(error.cleaned)
        assertFalse(Files.exists(target))
    }

    @Test
    fun `creates default clone parent and validates target directory before confirmation`() {
        val base = temporaryFolder.newFolder("github-clone-parent").toPath()
        val dir = base.resolve("ProjectAtlas")
        val store = GitHubConfigurationStore(base.resolve("github.json"))
        store.ensureFile()
        val service = GitHubRepositoriesService(store, RepositoryJsonStore(base.resolve("repos.json")))
        assertEquals(dir, service.defaultCloneParent(dir))
        assertEquals(dir.resolve("demo"), service.resolveCloneTarget(repository(1, "org/demo"), dir))
        val target = Files.createDirectory(dir.resolve("demo"))
        Files.writeString(target.resolve("existing"), "x")
        assertThrows(IllegalArgumentException::class.java) { service.resolveCloneTarget(repository(1, "org/demo"), dir) }
        assertThrows(IllegalArgumentException::class.java) { service.resolveCloneTarget(repository(1, "org/../unsafe"), dir) }
    }

    @Test
    fun `uses configured HTTP proxy for HTTPS clones`() {
        val dir = temporaryFolder.newFolder("github-http-proxy-clone").toPath()
        val config = GitHubConfiguration("t", "u", true, "http://proxy.example:3128", "socks5://proxy.example:1080")
        var args = emptyList<String>()
        val clone = GitCloneService(GitCommandRunner { arguments, _, _, _, _ -> args = arguments; 0 })
        clone.clone("https://github.com/org/demo.git", dir, dir.resolve("demo"), config, AtomicBoolean(), {})
        assertTrue(args.contains("http.proxy=http://proxy.example:3128"))
    }

    private fun repository(id: Long, fullName: String, private: Boolean = false, language: String? = "Kotlin") =
        GitHubRepository(id, fullName.substringAfter('/'), fullName, fullName.substringBefore('/'), null,
            "https://github.com/$fullName", "git@github.com:$fullName.git", "https://github.com/$fullName.git",
            private, false, false, language, "2026-01-01T00:00:00Z")

    private fun response(status: Int, body: String) = GitHubHttpResponse(status, emptyMap(), body)

    private class MemoryProjects(private val failOnAdd: Boolean = false) : ProjectRepository {
        val items = mutableListOf<ProjectItem>()
        override fun getAll() = items.toList()
        override fun findById(id: String) = items.firstOrNull { it.id == id }
        override fun findByPath(path: Path) = items.firstOrNull { it.path == path.toAbsolutePath().normalize() }
        override fun add(project: ProjectItem): ProjectItem { if (failOnAdd) error("storage unavailable"); items += project; return project }
        override fun update(project: ProjectItem): ProjectItem { items[items.indexOfFirst { it.id == project.id }] = project; return project }
        override fun remove(id: String): Boolean = items.removeIf { it.id == id }
        override fun replaceAll(projects: List<ProjectItem>) { items.clear(); items += projects }
    }
}
