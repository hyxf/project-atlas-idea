package com.aicode.feature.github.service

import com.aicode.feature.github.model.GitHubConfiguration
import com.aicode.feature.github.model.GitHubRepository
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.HttpURLConnection

data class GitHubHttpResponse(val status: Int, val headers: Map<String, List<String>>, val body: String)
fun interface GitHubTransport { fun get(url: String, configuration: GitHubConfiguration): GitHubHttpResponse }
interface GitHubCreateTransport {
    fun create(configuration: GitHubConfiguration, name: String): GitHubHttpResponse
}

class GitHubApiClient(
    private val transport: GitHubTransport = JdkGitHubTransport(),
    private val createTransport: GitHubCreateTransport = JdkGitHubTransport(),
) {
    constructor(transport: GitHubTransport) : this(transport, JdkGitHubTransport())

    fun repositoryExists(configuration: GitHubConfiguration, name: String): Boolean {
        val listed = listRepositories(configuration, strict = true).any {
            it.owner.equals(configuration.user, ignoreCase = true) && it.name.equals(name, ignoreCase = true)
        }
        // Fine-grained tokens do not expose classic OAuth scopes and may list only repositories
        // they can access. This lookup prevents obvious conflicts; the create endpoint is the
        // authoritative check when a token cannot see an existing repository.
        return listed
    }

    fun createPrivateRepository(configuration: GitHubConfiguration, name: String): GitHubRepository {
        require(name.isNotBlank()) { "Repository name is empty." }
        // Verify the token still belongs to the selected personal account before the write.
        val identity = transport.get("https://api.github.com/user", configuration)
        requireSuccess(identity)
        val login = parse(identity.body).asJsonObject.get("login")?.asString
            ?: throw IllegalStateException("GitHub returned an unexpected user response.")
        check(login.equals(configuration.user, ignoreCase = true)) {
            "The configured GitHub user does not match the account for this token."
        }
        val response = createTransport.create(configuration, name)
        if (response.status != 201) {
            requireSuccess(response)
            throw IllegalStateException("GitHub returned HTTP ${response.status} instead of 201 for repository creation.")
        }
        val repository = parseRepository(parse(response.body))
            ?: throw IllegalStateException("GitHub created the repository but returned incomplete repository details. Check GitHub before retrying.")
        check(repository.owner.equals(configuration.user, ignoreCase = true) &&
            repository.name.equals(name, ignoreCase = true) && repository.private && repository.sshUrl.isNotBlank()) {
            "GitHub created a repository but returned unexpected owner, name, visibility, or SSH URL. Check GitHub before retrying."
        }
        return repository
    }
    fun repositories(configuration: GitHubConfiguration): List<GitHubRepository> =
        listRepositories(configuration, strict = false)

    private fun listRepositories(configuration: GitHubConfiguration, strict: Boolean): List<GitHubRepository> {
        require(configuration.token.isNotBlank()) { "Set a non-empty token in github.json." }
        require(configuration.user.isNotBlank()) { "Set a non-empty user in github.json." }
        val identity = transport.get("https://api.github.com/user", configuration)
        requireSuccess(identity)
        val login = parse(identity.body).asJsonObject.get("login")?.asString
            ?: throw IllegalStateException("GitHub returned an unexpected user response.")
        if (!login.equals(configuration.user, ignoreCase = true)) {
            throw IllegalStateException("The configured GitHub user does not match the account for this token.")
        }
        val repositories = mutableListOf<GitHubRepository>()
        var next: String? = "https://api.github.com/user/repos?per_page=100"
        val seen = mutableSetOf<String>()
        while (next != null) {
            if (!seen.add(next)) throw IllegalStateException("GitHub pagination returned a repeated page link.")
            val response = transport.get(next, configuration)
            requireSuccess(response)
            val array = parse(response.body).takeIf(JsonElement::isJsonArray)?.asJsonArray
                ?: throw IllegalStateException("GitHub returned an unexpected repository response.")
            array.forEach { entry ->
                val repository = parseRepository(entry)
                if (repository == null && strict) {
                    throw IllegalStateException("GitHub returned incomplete repository data; absence cannot be confirmed.")
                }
                repository?.let(repositories::add)
            }
            next = response.headers.entries.firstOrNull { it.key.equals("Link", true) }?.value?.firstOrNull()
                ?.let(::nextLink)
        }
        return repositories
    }

    private fun requireSuccess(response: GitHubHttpResponse) {
        if (response.status == 401) throw IllegalStateException("GitHub rejected the token. Check token in github.json.")
        if (response.status == 403) throw IllegalStateException("GitHub denied access. Check token permissions and rate limits.")
        if (response.status !in 200..299) {
            val reason = runCatching { parse(response.body).asJsonObject.get("message")?.asString }.getOrNull()
            throw IllegalStateException("GitHub request failed with HTTP ${response.status}${reason?.let { ": $it" } ?: "."}")
        }
    }

    private fun nextLink(header: String): String? {
        val value = Regex("<([^>]+)>\\s*;\\s*rel=\"next\"")
            .findAll(header).lastOrNull()?.groupValues?.get(1) ?: return null
        val uri = runCatching { URI(value) }.getOrNull()
            ?: throw IllegalStateException("GitHub returned an invalid pagination link.")
        val safe = uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("api.github.com", ignoreCase = true) &&
            (uri.port == -1 || uri.port == 443) &&
            uri.rawUserInfo == null && uri.rawFragment == null
        if (!safe) throw IllegalStateException("GitHub returned an unsafe pagination link.")
        return uri.toASCIIString()
    }

    private fun parse(body: String): JsonElement = try { JsonParser.parseString(body) }
        catch (error: Exception) { throw IllegalStateException("GitHub returned invalid JSON.", error) }

    private fun parseRepository(value: JsonElement): GitHubRepository? = runCatching {
        val o = value.asJsonObject
        GitHubRepository(o.get("id").asLong, o.get("name").asString, o.get("full_name").asString,
            o.getAsJsonObject("owner").get("login").asString,
            o.get("description")?.takeUnless(JsonElement::isJsonNull)?.asString,
            o.get("html_url").asString, o.get("ssh_url").asString, o.get("clone_url").asString,
            o.get("private").asBoolean, o.get("archived").asBoolean, o.get("fork").asBoolean,
            o.get("language")?.takeUnless(JsonElement::isJsonNull)?.asString, o.get("updated_at").asString)
    }.getOrNull()
}

private class JdkGitHubTransport : GitHubTransport, GitHubCreateTransport {
    override fun get(url: String, configuration: GitHubConfiguration): GitHubHttpResponse {
        return request(url, configuration, "GET")
    }

    override fun create(configuration: GitHubConfiguration, name: String): GitHubHttpResponse {
        val body = com.google.gson.JsonObject().apply {
            addProperty("name", name)
            addProperty("private", true)
            addProperty("auto_init", false)
        }.toString()
        return request("https://api.github.com/user/repos", configuration, "POST", body)
    }

    private fun request(url: String, configuration: GitHubConfiguration, method: String, body: String? = null): GitHubHttpResponse {
        val proxy = if (configuration.proxyEnabled) selectProxy(configuration) else Proxy.NO_PROXY
        val connection = URI(url).toURL().openConnection(proxy) as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("Authorization", "token ${configuration.token}")
        connection.setRequestProperty("User-Agent", "project-atlas-idea")
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        return try {
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val headers = buildMap {
                connection.getHeaderField("Link")?.let { put("Link", listOf(it)) }
                connection.getHeaderField("X-OAuth-Scopes")?.let { put("X-OAuth-Scopes", listOf(it)) }
            }
            GitHubHttpResponse(status, headers, body)
        } catch (error: java.net.SocketTimeoutException) {
            throw IllegalStateException("GitHub request timed out after 30 seconds. Check the network or proxy.", error)
        } catch (error: Exception) {
            throw IllegalStateException("Could not reach GitHub. Check the network and proxy settings in github.json.", error)
        } finally { connection.disconnect() }
    }

    private fun selectProxy(configuration: GitHubConfiguration): Proxy {
        val http = configuration.httpProxy.takeIf(String::isNotBlank)
        if (http != null) {
            val uri = runCatching { URI(http) }.getOrElse { throw IllegalStateException("Invalid HTTP proxy URL in github.json.") }
            val scheme = uri.scheme?.lowercase()
            if (scheme == "http" || scheme == "https") {
                val defaultPort = if (scheme == "https") 443 else 80
                return Proxy(Proxy.Type.HTTP, InetSocketAddress(uri.host, if (uri.port > 0) uri.port else defaultPort))
            }
        }
        val socket = configuration.socketProxy.takeIf(String::isNotBlank)
            ?: throw IllegalStateException("Proxy is enabled but no proxy URL is configured in github.json.")
        val uri = runCatching { URI(socket) }.getOrElse { throw IllegalStateException("Invalid SOCKS proxy URL in github.json.") }
        if (!uri.scheme.startsWith("socks")) throw IllegalStateException("Invalid SOCKS proxy URL in github.json.")
        return Proxy(Proxy.Type.SOCKS, InetSocketAddress(uri.host, if (uri.port > 0) uri.port else 1080))
    }

    companion object { private const val TIMEOUT_MS = 30_000 }
}
