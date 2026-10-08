package com.aicode.feature.projectmanager.feature.ui

import com.aicode.feature.projectmanager.feature.project.ProjectImportOutcome
import com.aicode.feature.projectmanager.feature.project.ProjectImportRequest
import com.aicode.feature.projectmanager.feature.project.ProjectImportSummary
import com.aicode.feature.projectmanager.feature.project.ProjectManagerService
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

object ProjectImportUi {
    fun show(project: Project, onImported: () -> Unit) {
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
            .withTitle("Import Local Project")
            .withDescription("Choose a project folder to add to Project Atlas")
        val selected = FileChooser.chooseFile(descriptor, project, null) ?: return
        val path = selected.toNioPath()
        val manager = service<ProjectManagerService>()
        var summary: ProjectImportSummary? = null
        var importError: Throwable? = null
        ProgressManager.getInstance().run(object : Task.Modal(project, "Importing Local Project", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                runCatching {
                    manager.importProjects(
                        listOf(
                            ProjectImportRequest(
                                path = path,
                                name = path.fileName?.toString().orEmpty().ifBlank { path.toString() },
                                tags = emptySet(),
                                favorite = false,
                            ),
                        ),
                        updateExisting = false,
                    )
                }
                    .onSuccess { summary = it }
                    .onFailure { importError = it }
            }
        })
        importError?.let {
            ProjectUiSupport.report(project, "Import projects", it)
            return
        }
        val completed = summary ?: return
        if (completed.count(ProjectImportOutcome.ADDED) > 0) onImported()
        val failed = completed.results.filter { it.outcome == ProjectImportOutcome.FAILED }
        if (failed.isNotEmpty()) {
            val details = failed.joinToString("\n") { result ->
                "${result.path}: ${result.message.ifBlank { "Unknown error" }}"
            }
            Messages.showWarningDialog(
                project,
                "Some projects couldn't be imported:\n\n$details",
                "Import Completed with Errors",
            )
        }
        when {
            completed.count(ProjectImportOutcome.ADDED) > 0 ->
                ProjectUiSupport.notify(project, "Project added to Project Atlas", NotificationType.INFORMATION)
            completed.count(ProjectImportOutcome.SKIPPED) > 0 ->
                ProjectUiSupport.notify(project, "This project is already in Project Atlas", NotificationType.INFORMATION)
        }
    }

}
