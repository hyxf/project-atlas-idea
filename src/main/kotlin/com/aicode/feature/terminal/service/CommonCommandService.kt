package com.aicode.feature.terminal.service

import com.aicode.feature.terminal.data.DefaultCommonCommands
import com.aicode.feature.terminal.model.CommonCommand
import com.aicode.feature.terminal.model.CommonCommandConfig
import com.google.gson.GsonBuilder
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class CommonCommandService(
    private val configPath: Path = defaultConfigPath(),
) {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    @Synchronized
    fun getCommands(): List<CommonCommand> {
        if (!Files.exists(configPath)) {
            saveCommands(DefaultCommonCommands.commands)
            return DefaultCommonCommands.commands
        }
        try {
            val content = Files.readString(configPath, StandardCharsets.UTF_8)
            if (content.isBlank()) return emptyList()
            val root = JsonParser.parseString(content)
            if (!root.isJsonObject) throw IllegalStateException("Common command configuration must be a JSON object.")
            val commands = root.asJsonObject.get("commands") ?: return emptyList()
            if (!commands.isJsonArray) {
                throw IllegalStateException("Common command configuration field 'commands' must be an array.")
            }
            return normalize(commands.asJsonArray.mapNotNull { element ->
                when {
                    element.isJsonPrimitive && element.asJsonPrimitive.isString ->
                        CommonCommand(element.asString)
                    element.isJsonObject -> {
                        val command = element.asJsonObject.get("command")
                            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                            ?: return@mapNotNull null
                        val description = element.asJsonObject.get("description")
                            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()
                        CommonCommand(command, description)
                    }
                    else -> null
                }
            })
        } catch (ex: JsonParseException) {
            throw IllegalStateException("Invalid JSON in $configPath: ${ex.message}", ex)
        } catch (ex: Exception) {
            if (ex is IllegalStateException) throw ex
            throw IllegalStateException("Failed to read $configPath: ${ex.message}", ex)
        }
    }

    @Synchronized
    fun saveCommands(commands: List<CommonCommand>) {
        val parent = configPath.toAbsolutePath().parent
        try {
            if (parent != null) Files.createDirectories(parent)
            val temporaryFile = Files.createTempFile(parent, "commoncmd", ".tmp")
            try {
                Files.writeString(
                    temporaryFile,
                    gson.toJson(CommonCommandConfig(normalize(commands).toMutableList())),
                    StandardCharsets.UTF_8,
                )
                try {
                    Files.move(temporaryFile, configPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
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

    @Synchronized
    fun addCommand(command: CommonCommand): Boolean {
        val normalized = normalize(command) ?: return false
        val commands = getCommands()
        if (commands.any { it.command == normalized.command }) return false
        saveCommands(commands + normalized)
        return true
    }

    private fun normalize(commands: List<CommonCommand>): List<CommonCommand> =
        commands.mapNotNull(::normalize).distinctBy(CommonCommand::command)

    private fun normalize(command: CommonCommand): CommonCommand? =
        command.command.trim().takeIf(String::isNotEmpty)?.let { CommonCommand(it, command.description.trim()) }

    companion object {
        fun getInstance(): CommonCommandService =
            ApplicationManager.getApplication().getService(CommonCommandService::class.java)

        fun defaultConfigPath(): Path = Path.of(System.getProperty("user.home"), ".aicode", "commoncmd.json")
    }
}
