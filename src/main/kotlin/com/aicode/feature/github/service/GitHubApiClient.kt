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

class GitHubApiClient(private val transport: GitHubTransport = JdkGitHubTransport()) {
    fun repositories(configuration: GitHubConfiguration): List<GitHubRepository> {
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
            array.forEach { parseRepository(it)?.let(repositories::add) }
            next = response.headers.entries.firstOrNull { it.key.equals("Link", true) }?.value?.firstOrNull()
                ?.let(::nextLink)
        }
        return repositories
    }

    private fun requireSuccess(response: GitHubHttpResponse) {
        if (response.status == 401) throw IllegalStateException("GitHub rejected the token. Check token in github.json.")
        if (response.status == 403) throw IllegalStateException("GitHub denied access. Check token permissions and rate limits.")
        if (response.status !in 200..299) throw IllegalStateException("GitHub request failed with HTTP ${response.status}.")
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

private class JdkGitHubTransport : GitHubTransport {
    override fun get(url: String, configuration: GitHubConfiguration): GitHubHttpResponse {
        val proxy = if (configuration.proxyEnabled) selectProxy(configuration) else Proxy.NO_PROXY
        val connection = URI(url).toURL().openConnection(proxy) as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("Authorization", "token ${configuration.token}")
        connection.setRequestProperty("User-Agent", "project-atlas-idea")
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val link = connection.getHeaderField("Link")
            GitHubHttpResponse(status, if (link == null) emptyMap() else mapOf("Link" to listOf(link)), body)
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
