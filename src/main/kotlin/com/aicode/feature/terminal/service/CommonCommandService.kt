package com.aicode.feature.terminal.service

import com.aicode.feature.terminal.model.CommonCommand
import com.aicode.feature.terminal.model.CommonCommandDocument
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

class CommonCommandService(
    private val configPath: Path = defaultConfigPath(),
    private val hasUnsavedEditor: () -> Boolean = {
        val app = ApplicationManager.getApplication()
        if (app == null || app.isUnitTestMode) false else {
            val file = LocalFileSystem.getInstance().findFileByIoFile(configPath.toFile())
            file != null && FileDocumentManager.getInstance().getDocument(file)?.let {
                FileDocumentManager.getInstance().isDocumentUnsaved(it)
            } == true
        }
    },
) {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val lockPath = configPath.resolveSibling("${configPath.fileName}.lock")

    fun getCommands(): List<CommonCommand> = read().commands

    fun read(): CommonCommandDocument {
        ensureFile()
        val contents = Files.readString(configPath, StandardCharsets.UTF_8)
        return parse(contents)
    }

    fun addCommand(command: CommonCommand, expected: String? = null): Boolean {
        return try {
            mutate(expected) { root ->
                val entries = root.getAsJsonArray("commands")
                require(entries.none { it.isJsonObject && it.asJsonObject.get("command")?.asString?.trim() == command.command.trim() }) {
                    "duplicate command in commoncmd.json"
                }
                entries.add(toJson(command))
            }
            true
        } catch (ex: IllegalArgumentException) {
            if (ex.message?.contains("duplicate command") == true) false else throw ex
        }
    }

    fun updateCommand(index: Int, command: CommonCommand, expected: String) = mutate(expected) { root ->
        val entries = root.getAsJsonArray("commands")
        require(index in 0 until entries.size()) { "Command no longer exists. Refresh the view." }
        require(entries.withIndex().none { (position, element) ->
            position != index && element.isJsonObject && element.asJsonObject.get("command")?.asString?.trim() == command.command.trim()
        }) { "duplicate command in commoncmd.json" }
        val raw = entries[index].asJsonObject.deepCopy()
        putCommand(raw, command)
        entries.set(index, raw)
    }

    fun removeCommand(index: Int, expected: String) = mutate(expected) { root ->
        val entries = root.getAsJsonArray("commands")
        require(index in 0 until entries.size()) { "Command no longer exists. Refresh the view." }
        entries.remove(index)
    }

    fun moveCommand(from: Int, to: Int, expected: String) = mutate(expected) { root ->
        val entries = root.getAsJsonArray("commands")
        require(from in 0 until entries.size() && to in 0 until entries.size()) { "Command no longer exists. Refresh the view." }
        val item = entries.remove(from)
        val reordered = JsonArray()
        for (index in 0..entries.size()) {
            if (index == to) reordered.add(item)
            if (index < entries.size()) reordered.add(entries[index])
        }
        root.add("commands", reordered)
    }

    fun saveCommands(commands: List<CommonCommand>, expected: String? = null) {
        val snapshot = read()
        mutate(expected ?: snapshot.contents) { root ->
            val normalized = commands.filter { it.command.isNotBlank() }
            require(normalized.distinctBy { it.command.trim() }.size == normalized.size) { "duplicate command in commoncmd.json" }
            val old = root.getAsJsonArray("commands")
            val entries = JsonArray()
            normalized.forEach { item ->
                val matching = old.firstOrNull {
                    it.isJsonObject && it.asJsonObject.get("command")?.asString?.trim() == item.command.trim()
                }
                val raw = (item.raw.takeUnless { it.size() == 0 } ?: matching?.asJsonObject ?: JsonObject()).deepCopy()
                putCommand(raw, item)
                entries.add(raw)
            }
            root.add("commands", entries)
        }
    }

    private fun mutate(expected: String?, change: (JsonObject) -> Unit) {
        ensureFile()
        withLock {
            val hasUnsavedChanges = ApplicationManager.getApplication().runReadAction<Boolean> {
                hasUnsavedEditor()
            }
            if (hasUnsavedChanges) error("Save or discard unsaved commoncmd.json changes before continuing.")
            val before = Files.readString(configPath, StandardCharsets.UTF_8)
            if (expected != null && before != expected) error("commoncmd.json changed externally. Refresh the view and try again.")
            val root = parseRoot(before)
            change(root)
            parse(gson.toJson(root))
            val parent = configPath.toAbsolutePath().parent
            val temp = Files.createTempFile(parent, "commoncmd-", ".tmp")
            try {
                Files.writeString(temp, gson.toJson(root) + "\n", StandardCharsets.UTF_8)
                if (Files.readString(configPath, StandardCharsets.UTF_8) != before) {
                    error("commoncmd.json changed externally. Refresh the view and try again.")
                }
                Files.move(temp, configPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(temp)
            }
        }
    }

    private fun ensureFile() {
        if (Files.exists(configPath)) return
        Files.createDirectories(configPath.toAbsolutePath().parent)
        withLock {
            try {
                Files.writeString(configPath, gson.toJson(JsonObject().apply {
                    add("commands", JsonArray().apply {
                        add(toJson(CommonCommand("git status", "Show working tree status")))
                        add(toJson(CommonCommand("git diff", "Show unstaged changes")))
                        add(toJson(CommonCommand("git log --oneline -10", "Show the latest 10 commits")))
                    })
                }) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)
            } catch (_: FileAlreadyExistsException) {
                // Another writer initialized the shared file.
            }
        }
    }

    private fun withLock(block: () -> Unit) {
        val lock = try {
            Files.newOutputStream(lockPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        } catch (_: FileAlreadyExistsException) {
            error("Another writer owns $lockPath. Retry after it finishes.")
        }
        try {
            lock.use { block() }
        } finally {
            Files.deleteIfExists(lockPath)
        }
    }

    private fun parse(contents: String): CommonCommandDocument {
        val root = parseRoot(contents)
        val commands = root.getAsJsonArray("commands").mapIndexed { index, element ->
            require(element.isJsonObject) { "Common command ${index + 1} must be an object." }
            val item = element.asJsonObject
            val text = string(item, "command") ?: error("Common command ${index + 1} needs a command.")
            require(text.isNotBlank()) { "Common command ${index + 1} needs a command." }
            val description = string(item, "description") ?: ""
            val tags = item.get("tags")?.let { value ->
                require(value.isJsonArray) { "Common command ${index + 1} has invalid tags." }
                value.asJsonArray.map { require(it.isJsonPrimitive && it.asJsonPrimitive.isString); it.asString.trim() }
                    .filter(String::isNotBlank).distinct()
            } ?: emptyList()
            CommonCommand(text.trim(), description.trim(), tags, item.deepCopy())
        }
        return CommonCommandDocument(commands, contents)
    }

    private fun parseRoot(contents: String): JsonObject {
        val root = try { JsonParser.parseString(contents) } catch (ex: Exception) { throw IllegalStateException("Invalid JSON in $configPath", ex) }
        require(root.isJsonObject && root.asJsonObject.get("commands")?.isJsonArray == true) {
            "commoncmd.json must contain a commands array."
        }
        return root.asJsonObject
    }

    private fun string(root: JsonObject, key: String): String? = root.get(key)?.let {
        require(it.isJsonPrimitive && it.asJsonPrimitive.isString) { "$key must be a string." }
        it.asString
    }

    private fun toJson(command: CommonCommand) = JsonObject().also { putCommand(it, command) }

    private fun putCommand(raw: JsonObject, command: CommonCommand) {
        require(command.command.isNotBlank()) { "Command is required." }
        raw.addProperty("command", command.command.trim())
        if (command.description.isBlank()) raw.remove("description") else raw.addProperty("description", command.description.trim())
        if (command.tags.isEmpty()) raw.remove("tags") else raw.add("tags", JsonArray().apply { command.tags.map(String::trim).filter(String::isNotBlank).distinct().forEach(::add) })
    }

    companion object {
        fun getInstance(): CommonCommandService = ApplicationManager.getApplication().getService(CommonCommandService::class.java)
        fun defaultConfigPath(): Path = Path.of(System.getProperty("user.home"), ".project-atlas", "commoncmd.json")
    }
}
