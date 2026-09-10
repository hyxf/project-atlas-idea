package com.aicode.feature.ai.service

import com.aicode.feature.ai.model.AICodeConfig
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotifications
import com.intellij.util.messages.Topic
import java.nio.charset.StandardCharsets

class AICodeFileService(private val project: Project) {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    @Volatile private var bannerEnabled = false
    @Volatile private var cachedPaths: Set<String>? = null

    fun isBannerEnabled() = bannerEnabled

    fun setBannerEnabled(enabled: Boolean) {
        bannerEnabled = enabled
        notifyChange()
    }

    fun getOrCreateAICodeFile(): VirtualFile? {
        val baseDir = project.baseDir ?: return null
        baseDir.findChild(AICODE_FILE_NAME)?.let {
            return it
        }
        return try {
            WriteCommandAction.writeCommandAction(project).compute<VirtualFile?, RuntimeException> {
                try {
                    baseDir.createChildData(this, AICODE_FILE_NAME).also {
                        it.setBinaryContent(
                            gson.toJson(AICodeConfig()).toByteArray(StandardCharsets.UTF_8)
                        )
                    }
                } catch (_: Exception) {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun readConfig(): AICodeConfig {
        val file = getOrCreateAICodeFile() ?: return AICodeConfig()
        return try {
            val content = String(file.contentsToByteArray(), StandardCharsets.UTF_8)
            if (content.trim().isEmpty()) return AICodeConfig()
            try {
                val config = gson.fromJson(content, AICodeConfig::class.java)
                if (config != null && config.getGroups().isNotEmpty()) return config
            } catch (_: JsonSyntaxException) {}
            try {
                val type = object : TypeToken<ArrayList<String>>() {}.type
                val oldPaths: MutableList<String>? = gson.fromJson(content, type)
                if (oldPaths != null)
                    return AICodeConfig().apply {
                        setActiveGroup(AICodeConfig.DEFAULT_GROUP)
                        getGroups()[AICodeConfig.DEFAULT_GROUP] = oldPaths
                    }
            } catch (_: JsonSyntaxException) {}
            AICodeConfig()
        } catch (_: Exception) {
            AICodeConfig()
        }
    }

    fun saveConfig(config: AICodeConfig) {
        val file = getOrCreateAICodeFile() ?: return
        WriteCommandAction.runWriteCommandAction(project) {
            try {
                file.setBinaryContent(gson.toJson(config).toByteArray(StandardCharsets.UTF_8))
                notifyChange()
            } catch (_: java.io.IOException) {}
        }
    }

    fun getActiveGroupName() = readConfig().getActiveGroup()

    fun getGroupNames(): Set<String> = readConfig().getGroups().keys

    fun setActiveGroup(groupName: String) {
        readConfig().also {
            if (it.getGroups().containsKey(groupName)) {
                it.setActiveGroup(groupName)
                saveConfig(it)
            }
        }
    }

    fun addGroup(groupName: String) {
        readConfig().also {
            if (!it.getGroups().containsKey(groupName)) {
                it.getGroups()[groupName] = ArrayList()
                it.setActiveGroup(groupName)
                saveConfig(it)
            }
        }
    }

    fun duplicateGroup(sourceGroupName: String?, newGroupName: String?) {
        if (sourceGroupName == null || newGroupName == null || sourceGroupName == newGroupName)
            return
        val config = readConfig()
        val groups = config.getGroups()
        if (groups.containsKey(sourceGroupName) && !groups.containsKey(newGroupName)) {
            groups[newGroupName] = ArrayList(groups.getValue(sourceGroupName))
            config.setActiveGroup(newGroupName)
            saveConfig(config)
        }
    }

    fun renameGroup(oldName: String?, newName: String?) {
        if (oldName == null || newName == null || oldName == newName) return
        val config = readConfig()
        val groups = config.getGroups()
        if (groups.containsKey(oldName) && !groups.containsKey(newName)) {
            val paths = groups.remove(oldName)!!
            groups[newName] = paths
            if (oldName == config.getActiveGroup()) config.setActiveGroup(newName)
            saveConfig(config)
        }
    }

    fun removeGroup(groupName: String) {
        val config = readConfig()
        val groups = config.getGroups()
        if (groups.size <= 1 && groups.containsKey(groupName)) {
            groups.remove(groupName)
            groups[AICodeConfig.DEFAULT_GROUP] = ArrayList()
            config.setActiveGroup(AICodeConfig.DEFAULT_GROUP)
        } else {
            groups.remove(groupName)
            if (groupName == config.getActiveGroup())
                config.setActiveGroup(groups.keys.iterator().next())
        }
        saveConfig(config)
    }

    fun readFilePaths(): MutableList<String> {
        cachedPaths?.let {
            return ArrayList(it)
        }
        val paths = readConfig().getActivePaths()
        cachedPaths = HashSet(paths)
        return paths
    }

    fun writeFilePaths(paths: MutableList<String>) {
        readConfig().also {
            it.setActivePaths(paths)
            saveConfig(it)
        }
    }

    fun addFile(file: VirtualFile) {
        val path = getRelativePath(file) ?: return
        WriteCommandAction.runWriteCommandAction(
            project,
            "Add to AICode",
            null,
            {
                val config = readConfig()
                val paths = config.getActivePaths()
                if (!paths.contains(path)) {
                    paths.add(path)
                    config.setActivePaths(paths)
                    saveConfig(config)
                }
            },
        )
    }

    fun removeFile(file: VirtualFile) {
        val path = getRelativePath(file) ?: return
        WriteCommandAction.runWriteCommandAction(
            project,
            "Remove from AICode",
            null,
            {
                val config = readConfig()
                val paths = config.getActivePaths()
                if (paths.remove(path)) {
                    config.setActivePaths(paths)
                    saveConfig(config)
                }
            },
        )
    }

    fun removeFilePath(path: String) =
        WriteCommandAction.runWriteCommandAction(project) {
            val config = readConfig()
            val paths = config.getActivePaths()
            if (paths.remove(path)) {
                config.setActivePaths(paths)
                saveConfig(config)
            }
        }

    fun updateFilePath(oldPath: String, newPath: String) =
        WriteCommandAction.runWriteCommandAction(project) {
            val config = readConfig()
            val paths = config.getActivePaths()
            val index = paths.indexOf(oldPath)
            if (index >= 0) {
                paths[index] = newPath
                config.setActivePaths(paths)
                saveConfig(config)
            }
        }

    fun notifyChange() {
        if (project.isDisposed) return
        cachedPaths = null
        project.messageBus.syncPublisher(AICODE_TOPIC).onContextChanged()
        EditorNotifications.getInstance(project).updateAllNotifications()
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) ProjectView.getInstance(project).refresh()
        }
    }

    fun containsFile(file: VirtualFile): Boolean {
        val path = getRelativePath(file) ?: return false
        return cachedPaths?.contains(path) ?: readFilePaths().contains(path)
    }

    fun getRelativePath(file: VirtualFile): String? =
        project.baseDir?.let { VfsUtilCore.getRelativePath(file, it, '/') }

    fun getFileFromPath(relativePath: String): VirtualFile? =
        project.baseDir?.findFileByRelativePath(relativePath)

    fun interface AICodeStateListener {
        fun onContextChanged()
    }

    companion object {
        private const val AICODE_FILE_NAME = ".aicode.json"
        @JvmField
        val AICODE_TOPIC: Topic<AICodeStateListener> =
            Topic.create("AICode Context Changed", AICodeStateListener::class.java)

        @JvmStatic
        fun getInstance(project: Project): AICodeFileService =
            project.getService(AICodeFileService::class.java)
    }
}
