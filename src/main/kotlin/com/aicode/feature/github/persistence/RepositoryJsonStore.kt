package com.aicode.feature.github.persistence

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

data class SavedGitRepository(val group: String, val name: String, val url: String, val tags: List<String>, val description: String? = null)

class RepositoryJsonStore(private val file: Path = defaultFile()) {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    @Synchronized fun repositories(): List<SavedGitRepository> = read().get("repos")
        ?.takeIf(JsonElement::isJsonArray)?.asJsonArray?.mapNotNull(::parse).orEmpty()

    @Synchronized fun addIfMissing(repo: SavedGitRepository): Boolean {
        val root = read()
        val repos = root.get("repos")?.takeIf(JsonElement::isJsonArray)?.asJsonArray ?: JsonArray()
        val identity = sshIdentity(repo.url)
        if (repos.mapNotNull(::parse).any { it.url == repo.url || (identity != null && sshIdentity(it.url) == identity) }) return false
        repos.add(JsonObject().apply {
            addProperty("group", repo.group); addProperty("name", repo.name); addProperty("url", repo.url)
            add("tags", JsonArray().also { array -> repo.tags.forEach(array::add) })
            repo.description?.let { addProperty("description", it) }
        })
        root.addProperty("version", 1)
        root.add("repos", repos)
        write(root)
        return true
    }

    private fun read(): JsonObject {
        if (!Files.exists(file)) return JsonObject().apply { addProperty("version", 1); add("repos", JsonArray()) }
        return try { JsonParser.parseString(Files.readString(file)).takeIf(JsonElement::isJsonObject)?.asJsonObject
            ?: throw IllegalArgumentException("root must be an object") }
        catch (e: Exception) { throw IllegalStateException("Could not read repos.json; fix the JSON before changing repositories.", e) }
    }

    private fun write(root: JsonObject) {
        Files.createDirectories(file.parent)
        val tmp = file.resolveSibling("${file.fileName}.${UUID.randomUUID()}.tmp")
        try {
            Files.writeString(tmp, gson.toJson(root) + "\n", StandardCharsets.UTF_8)
            try { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(tmp) }
    }

    companion object {
        fun defaultFile(): Path = Path.of(System.getProperty("user.home"), ".project-atlas", "repos.json")
        fun sshIdentity(remote: String): String? {
            val value = remote.trim()
            val scp = Regex("^([^@/:\\s]+)@([^/:\\s]+):(.+)$").matchEntire(value)
            val user: String
            val host: String
            var port = "22"
            var path: String
            if (scp != null) { user = scp.groupValues[1]; host = scp.groupValues[2]; path = scp.groupValues[3] }
            else {
                val uri = runCatching { java.net.URI(value) }.getOrNull() ?: return null
                if (uri.scheme != "ssh" || uri.host.isNullOrBlank() || uri.host.contains(':') || uri.userInfo?.contains(':') == true ||
                    uri.query != null || uri.fragment != null) return null
                user = uri.userInfo.orEmpty(); host = uri.host; path = uri.path
                if (uri.port > 0) port = uri.port.toString()
            }
            path = path.trim('/').replace(Regex("\\.git$", RegexOption.IGNORE_CASE), "")
            val parts = path.split('/').filter(String::isNotBlank)
            if (parts.size < 2) return null
            return "$user@${host.lowercase()}:$port/${parts.dropLast(1).joinToString("/")}/${parts.last()}"
        }
    }
}

private fun parse(value: JsonElement): SavedGitRepository? = runCatching {
    val o = value.asJsonObject
    SavedGitRepository(o.get("group").asString, o.get("name").asString, o.get("url").asString,
        o.getAsJsonArray("tags").map { it.asString }, o.get("description")?.takeUnless(JsonElement::isJsonNull)?.asString)
}.getOrNull()
