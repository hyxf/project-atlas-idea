package com.aicode.feature.ai.provider

import com.aicode.feature.ai.icons.AICodeIcons
import com.aicode.feature.ai.service.AICodeFileService
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import java.util.function.Function
import javax.swing.JComponent

class AICodeEditorNotificationProvider : EditorNotificationProvider {
    override fun collectNotificationData(
        project: Project,
        file: VirtualFile,
    ): Function<in FileEditor, out JComponent?>? {
        val service = AICodeFileService.getInstance(project)
        if (file.isDirectory || file.name == ".aicode.json" || !service.isBannerEnabled())
            return null
        if (!service.containsFile(file)) return null
        return Function { fileEditor ->
            EditorNotificationPanel(fileEditor, EditorNotificationPanel.Status.Info).apply {
                text = "This file is in the active AICode context."
                icon(AICodeIcons.LOGO)
                createActionLabel("Remove from Context") { service.removeFile(file) }
            }
        }
    }
}
