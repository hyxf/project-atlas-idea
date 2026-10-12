package com.aicode.feature.ai.action

import com.aicode.feature.ai.service.AICodeFileService
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.project.DumbAware

class RemoveFromAICodeAction : AnAction(), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) ?: return
        if (files.isEmpty()) return
        val service = AICodeFileService.getInstance(project)
        val current = service.readFilePaths()
        val remove = hashSetOf<String>()
        for (file in files) {
            val relative = service.getRelativePath(file) ?: continue
            if (file.isDirectory) {
                val prefix = if (relative.endsWith('/')) relative else "$relative/"
                current.filterTo(remove) { it.startsWith(prefix) || it == relative }
            } else remove.add(relative)
        }
        if (remove.isNotEmpty()) {
            current.removeAll(remove)
            service.writeFilePaths(current)
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
                    if (file.isDirectory) {
                        if (file == project.baseDir) false
                        else
                            service.getRelativePath(file)?.let { dir ->
                                service.readFilePaths().any {
                                    it.startsWith(if (dir.endsWith('/')) dir else "$dir/")
                                }
                            } ?: false
                    } else service.containsFile(file)
                }
        }
        e.presentation.isEnabledAndVisible = visible
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
