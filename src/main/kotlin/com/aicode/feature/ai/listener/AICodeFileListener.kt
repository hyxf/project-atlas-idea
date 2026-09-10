package com.aicode.feature.ai.listener

import com.aicode.feature.ai.service.AICodeFileService
import com.aicode.feature.ai.settings.AICodeIgnoreSettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.*

class AICodeFileListener : BulkFileListener {
    override fun after(events: List<VFileEvent>) = events.forEach(::handleEvent)

    private fun handleEvent(event: VFileEvent) {
        val file = event.file ?: return
        if (AICodeIgnoreSettings.isIgnored(file.name)) return
        val project = findProject(file) ?: return
        val service = AICodeFileService.getInstance(project)
        when (event) {
            is VFileCreateEvent -> service.notifyChange()
            is VFileDeleteEvent -> handleDelete(service, file)
            is VFileMoveEvent -> handleMove(service, event, project)
            is VFilePropertyChangeEvent -> handlePropertyChange(service, event, project)
            is VFileContentChangeEvent -> if (file.name == ".aicode.json") service.notifyChange()
        }
    }

    private fun handleDelete(service: AICodeFileService, file: VirtualFile) {
        val path = service.getRelativePath(file)
        if (path != null && service.readFilePaths().contains(path)) service.removeFilePath(path)
        else service.notifyChange()
    }

    private fun handleMove(service: AICodeFileService, event: VFileMoveEvent, project: Project) {
        val file = event.file
        val baseDir = project.baseDir ?: return
        val oldParent = event.oldParent ?: return
        val oldPath = oldParent.path + "/" + file.name
        if (oldPath.startsWith(baseDir.path)) {
            val oldRelativePath = oldPath.substring(baseDir.path.length + 1)
            val newPath = service.getRelativePath(file)
            if (newPath != null && service.readFilePaths().contains(oldRelativePath))
                service.updateFilePath(oldRelativePath, newPath)
            else service.notifyChange()
        }
    }

    private fun handlePropertyChange(
        service: AICodeFileService,
        event: VFilePropertyChangeEvent,
        project: Project,
    ) {
        if (event.propertyName != VirtualFile.PROP_NAME) return
        val oldName = event.oldValue as? String ?: return
        val newName = event.newValue as? String ?: return
        if (oldName == newName) return
        val file = event.file
        val parent = file.parent ?: return
        val baseDir = project.baseDir ?: return
        if (parent.path.startsWith(baseDir.path)) {
            var relativePath = parent.path.substring(baseDir.path.length)
            if (relativePath.startsWith("/")) relativePath = relativePath.substring(1)
            val oldPath = if (relativePath.isEmpty()) oldName else "$relativePath/$oldName"
            val newPath = service.getRelativePath(file)
            if (newPath != null && service.readFilePaths().contains(oldPath))
                service.updateFilePath(oldPath, newPath)
            else service.notifyChange()
        }
    }

    private fun findProject(file: VirtualFile): Project? =
        ProjectManager.getInstance().openProjects.firstOrNull {
            val baseDir = it.baseDir
            baseDir != null && file.path.startsWith(baseDir.path)
        }
}
