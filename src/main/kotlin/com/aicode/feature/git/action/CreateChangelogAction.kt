package com.aicode.feature.git.action

import com.aicode.feature.git.model.ChangelogData
import com.aicode.feature.git.service.GitChangelogService
import com.aicode.feature.git.ui.ChangelogPreviewDialog
import com.aicode.feature.git.util.ChangelogBuilder
import com.intellij.notification.*
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.*
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vfs.VirtualFile
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import java.util.concurrent.atomic.AtomicReference

class CreateChangelogAction : AnAction(), DumbAware {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        chooseRepository(project, event.getData(CommonDataKeys.VIRTUAL_FILE))?.let {
            loadHistory(project, it)
        }
    }

    override fun update(event: AnActionEvent) {
        val project = event.project
        val hasRepository =
            project != null && GitRepositoryManager.getInstance(project).repositories.isNotEmpty()
        event.presentation.isVisible = project != null
        event.presentation.isEnabled = hasRepository
        event.presentation.description =
            if (hasRepository) "Create or update CHANGELOG.md from local Git tags"
            else "No Git repository was detected for this project"
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    companion object {
        private val LOG = Logger.getInstance(CreateChangelogAction::class.java)
        private const val FILE_NAME = "CHANGELOG.md"

        private fun loadHistory(project: Project, repository: GitRepository) {
            object : Task.Backgroundable(project, "Reading Git History", true) {
                    private var data: ChangelogData? = null
                    private var error: String? = null

                    override fun run(indicator: ProgressIndicator) {
                        try {
                            data = GitChangelogService().read(project, repository)
                        } catch (ex: ProcessCanceledException) {
                            throw ex
                        } catch (ex: VcsException) {
                            failed(ex)
                        } catch (ex: RuntimeException) {
                            failed(ex)
                        }
                    }

                    private fun failed(ex: Exception) {
                        LOG.warn("Failed to read Git history for changelog generation", ex)
                        error = safeMessage(ex)
                    }

                    override fun onSuccess() {
                        if (project.isDisposed) return
                        error?.let {
                            notify(
                                project,
                                "Failed to read Git history: $it",
                                NotificationType.ERROR,
                            )
                            return
                        }
                        preview(project, repository, data!!)
                    }
                }
                .queue()
        }

        private fun preview(project: Project, repository: GitRepository, data: ChangelogData) {
            val existingFile = repository.root.findChild(FILE_NAME)
            val existingDocument =
                existingFile?.let { FileDocumentManager.getInstance().getDocument(it) }
            if (existingFile != null && existingDocument == null) {
                notify(
                    project,
                    "CHANGELOG.md could not be opened as a text document.",
                    NotificationType.ERROR,
                )
                return
            }
            val existingContent = existingDocument?.text
            val replacing =
                try {
                    existingContent != null && !ChangelogBuilder.hasManagedSection(existingContent)
                } catch (ex: IllegalArgumentException) {
                    notify(
                        project,
                        ex.message + ". Fix the markers and generate it again.",
                        NotificationType.ERROR,
                    )
                    return
                }
            val content =
                if (existingFile == null || replacing) ChangelogBuilder.create(data)
                else ChangelogBuilder.update(existingContent!!, data)
            val dialog = ChangelogPreviewDialog(project, content, replacing)
            if (dialog.showAndGet())
                write(
                    project,
                    repository.root,
                    existingFile,
                    existingDocument,
                    existingContent,
                    existingFile?.modificationStamp ?: -1,
                    existingDocument?.modificationStamp ?: -1,
                    dialog.getContent(),
                )
        }

        private fun write(
            project: Project,
            root: VirtualFile,
            existingFile: VirtualFile?,
            existingDocument: Document?,
            expectedContent: String?,
            expectedFileStamp: Long,
            expectedDocumentStamp: Long,
            content: String,
        ) {
            val writtenFile = AtomicReference<VirtualFile>()
            val writtenDocument = AtomicReference<Document>()
            val failure = AtomicReference<Exception>()
            val conflict = AtomicReference<String>()
            WriteCommandAction.runWriteCommandAction(
                project,
                "Create or Update CHANGELOG.md",
                null,
                {
                    try {
                        val file: VirtualFile
                        val document: Document?
                        if (existingFile == null) {
                            if (root.findChild(FILE_NAME) != null) {
                                conflict.set(
                                    "CHANGELOG.md was created while the preview was open. Generate it again."
                                )
                                return@runWriteCommandAction
                            }
                            file =
                                root.createChildData(CreateChangelogAction::class.java, FILE_NAME)
                            document = FileDocumentManager.getInstance().getDocument(file)
                        } else {
                            file = existingFile
                            document = existingDocument
                            if (
                                !file.isValid ||
                                    document == null ||
                                    file.modificationStamp != expectedFileStamp ||
                                    document.modificationStamp != expectedDocumentStamp ||
                                    document.text != expectedContent
                            ) {
                                conflict.set(
                                    "CHANGELOG.md changed while the preview was open. Generate it again."
                                )
                                return@runWriteCommandAction
                            }
                        }
                        if (document == null)
                            throw IllegalStateException(
                                "CHANGELOG.md could not be opened as a text document"
                            )
                        document.setText(content)
                        writtenFile.set(file)
                        writtenDocument.set(document)
                    } catch (ex: Exception) {
                        failure.set(ex)
                    }
                },
            )
            conflict.get()?.let {
                notify(project, it, NotificationType.WARNING)
                return
            }
            failure.get()?.let {
                LOG.warn("Failed to write CHANGELOG.md", it)
                notify(
                    project,
                    "Failed to write CHANGELOG.md: " + safeMessage(it),
                    NotificationType.ERROR,
                )
                return
            }
            FileDocumentManager.getInstance().saveDocument(writtenDocument.get())
            FileEditorManager.getInstance(project).openFile(writtenFile.get(), true)
            notify(
                project,
                if (existingFile == null) "Created CHANGELOG.md." else "Updated CHANGELOG.md.",
                NotificationType.INFORMATION,
            )
        }

        private fun chooseRepository(project: Project, context: VirtualFile?): GitRepository? {
            val manager = GitRepositoryManager.getInstance(project)
            context
                ?.let { manager.getRepositoryForFileQuick(it) }
                ?.let {
                    return it
                }
            val repositories = manager.repositories.sortedBy { it.root.presentableUrl }
            if (repositories.isEmpty()) {
                notify(
                    project,
                    "No Git repository was detected for this project.",
                    NotificationType.WARNING,
                )
                return null
            }
            if (repositories.size == 1) return repositories[0]
            val options = repositories.map { it.root.presentableUrl }.toTypedArray()
            val selected =
                Messages.showChooseDialog(
                    project,
                    "Select the repository whose history will be used.",
                    "Select Git Repository",
                    Messages.getQuestionIcon(),
                    options,
                    options[0],
                )
            return if (selected < 0) null else repositories[selected]
        }

        private fun safeMessage(ex: Exception) =
            ex.message?.takeUnless { it.isBlank() } ?: "Unexpected error"

        private fun notify(project: Project, message: String, type: NotificationType) =
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed)
                    Notifications.Bus.notify(
                        Notification("AICode", "CHANGELOG.md", message, type),
                        project,
                    )
            }
    }
}
