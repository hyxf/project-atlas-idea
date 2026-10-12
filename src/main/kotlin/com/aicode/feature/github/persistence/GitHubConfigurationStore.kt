package com.aicode.feature.github.persistence

import com.aicode.feature.github.model.GitHubConfiguration
import com.aicode.feature.github.model.GitHubRepository
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.net.URI
import java.util.UUID

/** Reads and atomically updates the VS Code-compatible ~/.project-atlas/github.json. */
class GitHubConfigurationStore(
    val file: Path = defaultFile(),
    private val beforeAtomicReplace: () -> Unit = {},
) {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    @Synchronized
    fun repositories(): List<Pair<GitHubRepository, JsonObject>> = load(missingAllowed = true).array("repositories")
        .mapNotNull { element -> parseRepository(element)?.let { it to element.asJsonObject.deepCopy() } }

    @Synchronized
    fun configuration(): GitHubConfiguration {
        val config = readConfiguration()
        require(config.token.isNotBlank()) { "Set a non-empty token in $file." }
        require(config.user.isNotBlank()) { "Set a non-empty user in $file." }
        return config
    }

    /** Reads editable settings even when credentials have not been configured yet. */
    @Synchronized
    fun settingsConfiguration(): GitHubConfiguration = readConfiguration(validateProxy = false)

    private fun readConfiguration(validateProxy: Boolean = true): GitHubConfiguration {
        val source = load(missingAllowed = true)
        val legacyProxy = source.stringOrNull("proxy")
        val httpProxy = source.stringOrNull("httpProxy") ?: legacyProxy ?: DEFAULT_HTTP_PROXY
        val socketProxy = source.stringOrNull("socketProxy") ?: DEFAULT_SOCKET_PROXY
        if (validateProxy) {
            validateProxyUrl(httpProxy, setOf("http", "https"), "httpProxy")
            validateProxyUrl(socketProxy, setOf("socks", "socks4", "socks4a", "socks5", "socks5h"), "socketProxy")
        }
        val proxyEnabled = source.get("proxyEnabled")?.takeIf(JsonElement::isJsonPrimitive)?.asBoolean ?: false
        if (proxyEnabled && httpProxy.isBlank() && socketProxy.isBlank()) {
            throw IllegalStateException("Set a non-empty httpProxy or socketProxy in github.json, or disable the proxy.")
        }
        return GitHubConfiguration(
            token = source.stringOrNull("token").orEmpty().trim(),
            user = source.stringOrNull("user").orEmpty().trim(),
            proxyEnabled = proxyEnabled,
            httpProxy = if (validateProxy) normalizeProxy(httpProxy) else httpProxy.trim(),
            socketProxy = if (validateProxy) normalizeProxy(socketProxy) else socketProxy.trim(),
        )
    }

    @Synchronized
    fun ensureFile(): Path {
        Files.createDirectories(file.parent)
        if (!Files.exists(file)) {
            val initial = JsonObject().apply {
                addProperty("token", "")
                addProperty("user", "")
                addProperty("httpProxy", DEFAULT_HTTP_PROXY)
                addProperty("socketProxy", DEFAULT_SOCKET_PROXY)
                addProperty("proxyEnabled", false)
                add("repositories", JsonArray())
            }
            try {
                createPrivateFile(file)
                Files.writeString(file, gson.toJson(initial) + "\n", StandardCharsets.UTF_8)
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                // Another IDE created the shared file first; validate it below.
            } catch (_: UnsupportedOperationException) {
                Files.writeString(file, gson.toJson(initial) + "\n", StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE_NEW)
            }
        } else {
            val original = readBytesOrNull() ?: throw IllegalStateException("github.json disappeared; refresh and retry.")
            val root = parseRoot(original)
            var changed = false
            if (root.get("proxyEnabled") == null) { root.addProperty("proxyEnabled", false); changed = true }
            if (root.get("httpProxy") == null) {
                root.addProperty("httpProxy", root.stringOrNull("proxy") ?: DEFAULT_HTTP_PROXY)
                root.remove("proxy")
                changed = true
            }
            if (root.get("socketProxy") == null) { root.addProperty("socketProxy", DEFAULT_SOCKET_PROXY); changed = true }
            if (root.get("repositories") == null) { root.add("repositories", JsonArray()); changed = true }
            if (changed) save(root, original)
        }
        load(missingAllowed = false)
        return file
    }

    @Synchronized
    fun replaceRepositories(repositories: List<GitHubRepository>) {
        val sourceBytes = readBytesOrNull() ?: throw IllegalStateException("github.json disappeared; refresh the configuration file and retry.")
        val root = parseRoot(sourceBytes)
        val oldById = root.array("repositories").mapNotNull { element ->
            element.takeIf(JsonElement::isJsonObject)?.asJsonObject?.get("id")?.takeIf(JsonElement::isJsonPrimitive)
                ?.let { runCatching { it.asLong to element.asJsonObject }.getOrNull() }
        }.toMap()
        val array = JsonArray()
        repositories.forEach { repository ->
            val entry = oldById[repository.id]?.deepCopy() ?: JsonObject()
            entry.addProperty("id", repository.id)
            entry.addProperty("name", repository.name)
            entry.addProperty("fullName", repository.fullName)
            entry.addProperty("owner", repository.owner)
            entry.putNullable("description", repository.description)
            entry.addProperty("htmlUrl", repository.htmlUrl)
            entry.addProperty("sshUrl", repository.sshUrl)
            entry.addProperty("cloneUrl", repository.cloneUrl)
            entry.addProperty("private", repository.private)
            entry.addProperty("archived", repository.archived)
            entry.addProperty("fork", repository.fork)
            entry.putNullable("language", repository.language)
            entry.addProperty("updatedAt", repository.updatedAt)
            array.add(entry)
        }
        root.add("repositories", array)
        save(root, sourceBytes)
    }

    @Synchronized
    fun updateSettings(token: String? = null, user: String? = null, proxyEnabled: Boolean? = null,
                       httpProxy: String? = null, socketProxy: String? = null) {
        val existing = readBytesOrNull()
        val sourceBytes = existing ?: initialBytes()
        val root = parseRoot(sourceBytes)
        token?.let { root.addProperty("token", it) }
        user?.let { root.addProperty("user", it) }
        proxyEnabled?.let { root.addProperty("proxyEnabled", it) }
        httpProxy?.let { root.addProperty("httpProxy", it); root.remove("proxy") }
        socketProxy?.let { root.addProperty("socketProxy", it) }
        save(root, existing)
    }

    private fun load(missingAllowed: Boolean): JsonObject {
        val bytes = readBytesOrNull() ?: if (missingAllowed) return JsonObject() else throw IllegalStateException("github.json does not exist: $file")
        return parseRoot(bytes)
    }

    private fun parseRoot(bytes: ByteArray): JsonObject = try {
        JsonParser.parseString(String(bytes, StandardCharsets.UTF_8)).takeIf(JsonElement::isJsonObject)?.asJsonObject
            ?: throw IllegalArgumentException("root must be an object")
    } catch (error: Exception) {
        throw IllegalStateException("Could not parse github.json; fix the JSON before refreshing or changing settings.", error)
    }

    private fun save(root: JsonObject, expected: ByteArray?) {
        Files.createDirectories(file.parent)
        val lock = file.resolveSibling(file.fileName.toString() + ".lock")
        FileChannel.open(lock, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE).use { channel ->
            val processLock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                ?: throw IllegalStateException("github.json is being updated by another Project Atlas process. Retry after it finishes.")
            try {
                val current = readBytesOrNull()
                if (expected != null && !expected.contentEqualsNullable(current)) {
                    throw IllegalStateException("github.json changed in another editor. Reload it before retrying to avoid overwriting changes.")
                }
                if (expected == null && current != null) {
                    throw IllegalStateException("github.json was created by another process. Reload it before retrying.")
                }
                val temporary = createPrivateTemporary(file)
                try {
                    Files.writeString(temporary, gson.toJson(root) + "\n", StandardCharsets.UTF_8)
                    beforeAtomicReplace()
                    val recheck = readBytesOrNull()
                    if (!expected.contentEqualsNullable(recheck)) {
                        throw IllegalStateException("github.json changed during the update. Reload it before retrying.")
                    }
                    try {
                        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
                    }
                } finally {
                    Files.deleteIfExists(temporary)
                }
            } finally {
                processLock.release()
            }
        }
    }

    private fun initialBytes(): ByteArray = "{}".toByteArray(StandardCharsets.UTF_8)
    private fun readBytesOrNull(): ByteArray? = if (Files.exists(file)) Files.readAllBytes(file) else null

    companion object {
        const val DEFAULT_HTTP_PROXY = "http://127.0.0.1:1087"
        const val DEFAULT_SOCKET_PROXY = "socks5://127.0.0.1:1086"
        fun defaultFile(): Path = Path.of(System.getProperty("user.home"), ".project-atlas", "github.json")
    }
}

private fun createPrivateFile(file: Path) {
    try {
        Files.createFile(file, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
            java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")))
    } catch (_: UnsupportedOperationException) {
        Files.createFile(file)
    }
}

private fun createPrivateTemporary(file: Path): Path = try {
    Files.createTempFile(file.parent, "${file.fileName}.", ".tmp",
        java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
            java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")))
} catch (_: UnsupportedOperationException) {
    Files.createTempFile(file.parent, "${file.fileName}.", ".tmp")
}

private fun validateProxyUrl(value: String, schemes: Set<String>, field: String) {
    if (value.isBlank()) return
    val uri = runCatching { URI(value.trim()) }.getOrElse {
        throw IllegalStateException("Set $field in github.json to a valid proxy URL.")
    }
    if (uri.scheme?.lowercase() !in schemes || uri.host.isNullOrBlank()) {
        val expected = if (field == "httpProxy") "HTTP or HTTPS" else "SOCKS"
        throw IllegalStateException("Set $field in github.json to a valid $expected proxy URL.")
    }
}

private fun normalizeProxy(value: String): String {
    if (value.isBlank()) return ""
    val uri = URI(value.trim())
    val normalized = uri.toASCIIString().replaceFirst(Regex("^[^:]+:"), "${uri.scheme.lowercase()}:")
    return if (uri.path == "/" && uri.rawQuery == null && uri.rawFragment == null) normalized.dropLast(1) else normalized
}

private fun JsonObject.array(name: String): List<JsonElement> = get(name)?.takeIf(JsonElement::isJsonArray)
    ?.asJsonArray?.toList().orEmpty()
private fun JsonObject.stringOrNull(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive && !it.asJsonPrimitive.isBoolean && !it.asJsonPrimitive.isNumber }?.asString
private fun JsonObject.putNullable(name: String, value: String?) { if (value == null) add(name, com.google.gson.JsonNull.INSTANCE) else addProperty(name, value) }
private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean = when { this == null -> other == null; other == null -> false; else -> contentEquals(other) }

private fun parseRepository(element: JsonElement): GitHubRepository? = runCatching {
    val o = element.asJsonObject
    GitHubRepository(o.get("id").asLong, o.get("name").asString, o.get("fullName").asString,
        o.get("owner").asString, o.get("description")?.takeUnless(JsonElement::isJsonNull)?.asString,
        o.get("htmlUrl").asString, o.get("sshUrl").asString, o.get("cloneUrl").asString,
        o.get("private").asBoolean, o.get("archived").asBoolean, o.get("fork").asBoolean,
        o.get("language")?.takeUnless(JsonElement::isJsonNull)?.asString, o.get("updatedAt").asString)
}.getOrNull()
