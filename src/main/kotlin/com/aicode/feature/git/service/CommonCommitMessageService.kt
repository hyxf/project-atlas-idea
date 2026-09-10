package com.aicode.feature.git.service

import com.aicode.feature.git.data.DefaultCommitMessages
import com.aicode.feature.git.model.CommitMessageTemplate
import com.aicode.feature.git.model.GitMessageConfig
import com.google.gson.GsonBuilder
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class CommonCommitMessageService(
    private val configPath: Path = defaultConfigPath(),
) {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    @Synchronized
    fun getMessages(): List<String> = getTemplates().map(CommitMessageTemplate::formatted)

    @Synchronized
    fun getTemplates(): List<CommitMessageTemplate> {
        if (!Files.exists(configPath)) {
            saveTemplates(DefaultCommitMessages.templates)
            return DefaultCommitMessages.templates
        }
        try {
            val content = Files.readString(configPath, StandardCharsets.UTF_8)
            if (content.isBlank()) return emptyList()
            val root = JsonParser.parseString(content)
            if (!root.isJsonObject) throw IllegalStateException("Commit message configuration must be a JSON object.")
            val messages = root.asJsonObject.get("messages") ?: return emptyList()
            if (!messages.isJsonArray) throw IllegalStateException("Commit message configuration field 'messages' must be an array.")
            return normalizeTemplates(
                messages.asJsonArray.mapNotNull { element ->
                    when {
                        element.isJsonPrimitive && element.asJsonPrimitive.isString -> parseLegacyMessage(element.asString)
                        element.isJsonObject -> parseTemplateObject(element.asJsonObject)
                        else -> null
                    }
                }
            )
        } catch (ex: JsonParseException) {
            throw IllegalStateException("Invalid JSON in $configPath: ${ex.message}", ex)
        } catch (ex: Exception) {
            if (ex is IllegalStateException) throw ex
            throw IllegalStateException("Failed to read $configPath: ${ex.message}", ex)
        }
    }

    @Synchronized
    fun saveMessages(messages: List<String>) {
        saveTemplates(messages.map(::parseLegacyMessage))
    }

    @Synchronized
    fun saveTemplates(messages: List<CommitMessageTemplate>) {
        val normalized = normalizeTemplates(messages)
        val parent = configPath.toAbsolutePath().parent
        try {
            if (parent != null) Files.createDirectories(parent)
            val temporaryFile = Files.createTempFile(parent, "gitmessage", ".tmp")
            try {
                Files.writeString(
                    temporaryFile,
                    gson.toJson(GitMessageConfig(normalized.toMutableList())),
                    StandardCharsets.UTF_8,
                )
                try {
                    Files.move(
                        temporaryFile,
                        configPath,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporaryFile, configPath, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temporaryFile)
            }
        } catch (ex: Exception) {
            throw IllegalStateException("Failed to write $configPath: ${ex.message}", ex)
        }
    }

    private fun normalizeTemplates(messages: List<CommitMessageTemplate>?): List<CommitMessageTemplate> =
        messages.orEmpty()
            .map {
                CommitMessageTemplate(
                    type = it.type.trim().lowercase(),
                    scope = it.scope?.trim()?.takeIf(String::isNotEmpty),
                    subject = it.subject.trim(),
                )
            }
            .filter { it.subject.isNotEmpty() }
            .distinctBy(CommitMessageTemplate::formatted)

    private fun parseLegacyMessage(message: String): CommitMessageTemplate {
        val normalized = message.trim()
        val match = LEGACY_MESSAGE_PATTERN.matchEntire(normalized)
        return if (match == null) {
            CommitMessageTemplate(subject = normalized)
        } else {
            CommitMessageTemplate(
                type = match.groupValues[1],
                scope = match.groupValues[2].takeIf(String::isNotEmpty),
                subject = match.groupValues[3],
            )
        }
    }

    private fun parseTemplateObject(element: com.google.gson.JsonObject): CommitMessageTemplate? {
        val subject = element.get("subject")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            ?: return null
        val type = element.get("type")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()
        val scope = element.get("scope")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        return CommitMessageTemplate(type = type, scope = scope, subject = subject)
    }

    companion object {
        private val LEGACY_MESSAGE_PATTERN =
            Regex("^([A-Za-z][A-Za-z0-9-]*)(?:\\(([^)]+)\\))?:\\s*(.+)$", RegexOption.DOT_MATCHES_ALL)

        fun getInstance(): CommonCommitMessageService =
            ApplicationManager.getApplication().getService(CommonCommitMessageService::class.java)

        fun defaultConfigPath(): Path =
            Path.of(System.getProperty("user.home"), ".aicode", "gitmessage.json")
    }
}
