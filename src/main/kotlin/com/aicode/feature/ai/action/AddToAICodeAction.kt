package com.aicode.feature.ai.action

import com.aicode.feature.ai.service.AICodeFileService
import com.aicode.feature.ai.settings.AICodeIgnoreSettings
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor

class AddToAICodeAction : AnAction(), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) ?: return
        if (files.isEmpty()) return
        val service = AICodeFileService.getInstance(project)
        val baseDir = project.baseDir
        val currentPaths = ArrayList(service.readFilePaths())
        val existingSet = HashSet(currentPaths)
        val newPaths = ArrayList<String>()
        for (file in files) {
            if (file == baseDir) continue
            if (file.isDirectory) {
                VfsUtilCore.visitChildrenRecursively(
                    file,
                    object : VirtualFileVisitor<Void>() {
                        override fun visitFile(child: VirtualFile): Boolean {
                            if (AICodeIgnoreSettings.isIgnored(child.name)) return false
                            if (
                                !child.isDirectory &&
                                    !child.fileType.isBinary &&
                                    child.name != ".aicode.json"
                            ) {
                                val path = service.getRelativePath(child)
                                if (path != null && !existingSet.contains(path)) newPaths.add(path)
                            }
                            return true
                        }
                    },
                )
            } else if (
                !AICodeIgnoreSettings.isIgnored(file.name) &&
                    !file.fileType.isBinary &&
                    file.name != ".aicode.json"
            ) {
                val path = service.getRelativePath(file)
                if (path != null && !existingSet.contains(path)) newPaths.add(path)
            }
        }
        if (newPaths.isNotEmpty()) {
            currentPaths.addAll(newPaths)
            service.writeFilePaths(currentPaths)
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        var visible = false
        if (project != null && files != null && files.isNotEmpty()) {
            val service = AICodeFileService.getInstance(project)
            visible =
                files.any { file ->
                    val valid =
                        file != project.baseDir &&
                            file.name != ".aicode.json" &&
                            !AICodeIgnoreSettings.isIgnored(file.name) &&
                            (file.isDirectory || !file.fileType.isBinary)
                    valid && (file.isDirectory || !service.containsFile(file))
                }
        }
        e.presentation.isEnabledAndVisible = visible
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
