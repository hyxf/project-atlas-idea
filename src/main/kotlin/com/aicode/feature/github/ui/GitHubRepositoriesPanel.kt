package com.aicode.feature.github.ui

import com.aicode.feature.github.model.GitHubRepository
import com.aicode.feature.github.icons.GitHubIcons
import com.aicode.feature.github.persistence.GitHubConfigurationStore
import com.aicode.feature.github.persistence.SavedGitRepository
import com.aicode.feature.github.service.CloneCancelledException
import com.aicode.feature.github.service.GitHubRepositoriesService
import com.aicode.feature.github.service.groupRepositories
import com.aicode.feature.projectmanager.feature.ui.ProjectUiSupport
import com.aicode.feature.projectmanager.feature.ui.ProjectUiSupport.notify
import com.aicode.feature.projectmanager.feature.project.ProjectDataChangedListener
import com.intellij.ide.BrowserUtil
import com.intellij.ide.actions.RevealFileAction
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class GitHubRepositoriesPanel(private val project: Project) : JPanel(BorderLayout()) {
    private val store = GitHubConfigurationStore()
    private val service = GitHubRepositoriesService(projects = com.intellij.openapi.components.service())
    private val tree = Tree()
    private val status = JBLabel("Cached repositories are shown here. Refresh to contact GitHub.")
    private val refreshing = AtomicBoolean(false)
    private var collapseLanguageGroups = false
    private var repositoryByNode = mutableMapOf<DefaultMutableTreeNode, GitHubRepository>()
    @Volatile private var cachedRepositories: List<GitHubRepository> = emptyList()
    @Volatile private var savedRepositories: List<SavedGitRepository> = emptyList()

    init {
        border = JBUI.Borders.empty(4)
        add(toolbar(), BorderLayout.NORTH)
        add(JBScrollPane(tree), BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(t: javax.swing.JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
                val node = value as? DefaultMutableTreeNode
                val repo = node?.let(repositoryByNode::get)
                icon = null
                when {
                    repo != null -> {
                        icon = AllIcons.Nodes.Folder
                        append(repo.fullName, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                        repo.description?.takeIf(String::isNotBlank)?.let {
                            append(" · $it", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        }
                        if (repo.archived) append(" · Archived", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        if (isSaved(repo)) append(" · Saved in Git Repositories", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                    node?.userObject is Group -> {
                        val group = node.userObject as Group
                        if (group.isLanguage) icon = GitHubIcons.LANGUAGE_GROUP
                        append("${group.title} (${group.count})", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    }
                    else -> append(node?.userObject?.toString().orEmpty())
                }
            }
        }
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                val path = tree.getPathForLocation(event.x, event.y) ?: return
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val repo = repositoryByNode[node] ?: return
                if (shouldOpenGithubOnClick(event.clickCount, event.button)) BrowserUtil.browse(repo.htmlUrl)
            }
            override fun mousePressed(event: MouseEvent) { if (event.isPopupTrigger) showPopup(event) }
            override fun mouseReleased(event: MouseEvent) { if (event.isPopupTrigger) showPopup(event) }
            private fun showPopup(e: MouseEvent) { tree.getPathForLocation(e.x, e.y)?.lastPathComponent?.let { node ->
                (node as? DefaultMutableTreeNode)?.let(repositoryByNode::get)?.let { popup(it).show(tree, e.x, e.y) }
            } }
        })
        refreshCache()
    }

    fun refreshCache() {
        object : Task.Backgroundable(project, "Load Cached GitHub Repositories", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    store.ensureFile()
                    val cache = service.cached()
                    val saved = service.savedRepositories()
                    ApplicationManager.getApplication().invokeLater {
                        cachedRepositories = cache
                        savedRepositories = saved
                        rebuild()
                    }
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater { status.text = e.message ?: "Could not read GitHub configuration." }
                }
            }
        }.queue()
    }

    private fun toolbar(): JComponent {
        val actions = DefaultActionGroup().apply {
            listOf(
                "com.aicode.github.RefreshRepositories",
                "com.aicode.github.ExpandRepositories",
                "com.aicode.github.CollapseRepositories",
                "com.aicode.github.OpenConfiguration",
                "com.aicode.github.OpenSettings",
            ).forEach { id -> add(requireNotNull(ActionManager.getInstance().getAction(id)) { "Missing action: $id" }) }
        }
        val actionToolbar = ActionManager.getInstance()
            .createActionToolbar("GitHubRepositories.Toolbar", actions, true)
            .apply { targetComponent = this@GitHubRepositoriesPanel }
            .component
        return JPanel(BorderLayout(8, 0)).apply {
            border = JBUI.Borders.empty(2, 4)
            add(actionToolbar, BorderLayout.CENTER)
        }
    }

    private fun rebuild() {
        if (!ApplicationManager.getApplication().isDispatchThread) {
            ApplicationManager.getApplication().invokeLater { rebuild() }; return
        }
        val root = DefaultMutableTreeNode("GitHub")
        repositoryByNode = mutableMapOf()
        val groups = groupRepositories(cachedRepositories)
        groups.forEach { visibility ->
            val groupNode = DefaultMutableTreeNode(Group(visibility.title, visibility.count, isLanguage = false))
            root.add(groupNode)
            visibility.languages.forEach { language ->
                val languageNode = DefaultMutableTreeNode(Group(language.language, language.count, isLanguage = true))
                groupNode.add(languageNode)
                language.repositories.forEach { repo ->
                    val item = DefaultMutableTreeNode(repo.fullName)
                    languageNode.add(item)
                    repositoryByNode[item] = repo
                }
            }
        }
        tree.model = DefaultTreeModel(root)
        expandVisibilityGroups()
        if (status.text.startsWith("Cached") || status.text.startsWith("Synchronized")) {
            status.text = "${cachedRepositories.size} cached repositories. Double-click a repository to open GitHub."
        }
    }

    private fun refreshFromGitHub() {
        if (!refreshing.compareAndSet(false, true)) return
        status.text = "Synchronizing GitHub repositories…"
        object : Task.Backgroundable(project, "Synchronize GitHub Repositories", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.isIndeterminate = true
                    indicator.text = "Verifying configured GitHub user…"
                    val count = service.refresh()
                    val cache = service.cached()
                    val saved = service.savedRepositories()
                    ApplicationManager.getApplication().invokeLater {
                        cachedRepositories = cache; savedRepositories = saved
                        refreshing.set(false); rebuild(); status.text = "Synchronized $count GitHub repositories."
                    }
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        refreshing.set(false); status.text = e.message ?: "GitHub refresh failed. Check github.json and network settings."
                        com.intellij.openapi.ui.Messages.showErrorDialog(project, status.text, "GitHub Refresh Failed")
                    }
                }
            }
        }.queue()
    }

    fun isRefreshing(): Boolean = refreshing.get()
    fun refreshFromAction() = refreshFromGitHub()
    fun expandFromAction() = expandAll()
    fun collapseFromAction() = collapseAll()
    fun openConfigurationFromAction() = openConfig()
    fun openSettingsFromAction() = editSettings()

    private fun popup(repo: GitHubRepository) = JPopupMenu().apply {
        add(JMenuItem("Open on GitHub").apply { addActionListener { BrowserUtil.browse(repo.htmlUrl) } })
        add(JMenuItem("Copy SSH URL").apply { addActionListener {
            java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(repo.sshUrl), null)
            status.text = "Copied SSH URL for ${repo.fullName}."
        } })
        add(JMenuItem("Clone Repository…").apply { addActionListener { cloneRepository(repo) } })
    }

    private fun cloneRepository(repo: GitHubRepository) {
        object : Task.Backgroundable(project, "Prepare Clone Location", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val defaultParent = service.defaultCloneParent()
                    val initial = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(defaultParent)
                    ApplicationManager.getApplication().invokeLater {
                        val selected = FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFolderDescriptor(), project, initial) ?: return@invokeLater
                        validateAndClone(repo, selected.toNioPath())
                    }
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater { showError("Could not prepare clone location", e) }
                }
            }
        }.queue()
    }

    private fun validateAndClone(repo: GitHubRepository, parent: Path) {
        object : Task.Backgroundable(project, "Validate Clone Destination", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    service.resolveCloneTarget(repo, parent)
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed) runClone(repo, parent)
                    }
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed) {
                            notify(project, "Invalid clone destination: ${e.message ?: "Choose a different parent directory."}", NotificationType.ERROR)
                        }
                    }
                }
            }
        }.queue()
    }

    private fun runClone(repo: GitHubRepository, parent: Path) {
        val cancelled = AtomicBoolean(false)
        object : Task.Backgroundable(project, "Clone ${repo.fullName}", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    val cloned = service.cloneRepository(repo, parent, cancelled, { indicator.text2 = it }, { indicator.isCanceled })
                    service.addClonedProject(repo, cloned)
                    ApplicationManager.getApplication().messageBus
                        .syncPublisher(ProjectDataChangedListener.TOPIC)
                        .projectsChanged()
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed) return@invokeLater
                        val notification = NotificationGroupManager.getInstance()
                            .getNotificationGroup("AICode.ProjectManager")
                            .createNotification(
                                "Clone Complete",
                                "Cloned ${repo.fullName} to $cloned and added it to Projects.",
                                NotificationType.INFORMATION,
                            )
                            .addAction(NotificationAction.createSimpleExpiring("Open in File Manager") {
                                RevealFileAction.openDirectory(cloned.toFile())
                            })
                            .addAction(NotificationAction.createSimpleExpiring("Open in New Window") {
                                ProjectUiSupport.open(
                                    com.aicode.feature.projectmanager.feature.project.ProjectItem("github-${repo.id}", repo.name, cloned),
                                    project,
                                    true,
                                )
                            })
                        notification.notify(project)
                    }
                } catch (e: CloneCancelledException) {
                    ApplicationManager.getApplication().invokeLater {
                        val message = if (e.cleaned) "Clone cancelled; partial data was removed from ${e.target}." else "Clone cancelled; partial data could not be removed from ${e.target}. Remove it before retrying."
                        notify(project, message, if (e.cleaned) com.intellij.notification.NotificationType.INFORMATION else com.intellij.notification.NotificationType.WARNING)
                    }
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater { com.intellij.openapi.ui.Messages.showErrorDialog(project, e.message ?: "Clone failed.", "Clone Repository") }
                }
            }
        }.queue()
    }

    private fun openConfig() {
        try {
            object : Task.Backgroundable(project, "Open GitHub Configuration", false) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        val file = store.ensureFile()
                        ApplicationManager.getApplication().invokeLater {
                            val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file)
                            if (vf == null) showError("Could not open github.json", IllegalStateException("File is not accessible: $file"))
                            else FileEditorManager.getInstance(project).openFile(vf, true)
                        }
                    } catch (e: Exception) { ApplicationManager.getApplication().invokeLater { showError("Could not open github.json", e) } }
                }
            }.queue()
        } catch (e: Exception) { showError("Could not open github.json", e) }
    }

    private fun editSettings() {
        try {
            object : Task.Backgroundable(project, "Load GitHub Settings", false) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        val current = store.settingsConfiguration()
                        ApplicationManager.getApplication().invokeLater {
                            val dialog = GitHubSettingsDialog(project, current)
                            if (dialog.showAndGet()) {
                                val draft = dialog.settings()
                                object : Task.Backgroundable(project, "Save GitHub Settings", false) {
                                    override fun run(indicator: ProgressIndicator) {
                                        try {
                                            store.ensureFile()
                                            store.updateSettings(
                                                token = draft.token.takeIf { it != current.token },
                                                user = draft.user.takeIf { it != current.user },
                                                proxyEnabled = draft.proxyEnabled.takeIf { it != current.proxyEnabled },
                                                httpProxy = draft.httpProxy.takeIf { it != current.httpProxy },
                                                socketProxy = draft.socketProxy.takeIf { it != current.socketProxy },
                                            )
                                            ApplicationManager.getApplication().invokeLater { status.text = "GitHub settings saved. The next refresh will use these settings." }
                                        } catch (e: Exception) { ApplicationManager.getApplication().invokeLater { showError("Could not update GitHub settings", e) } }
                                    }
                                }.queue()
                            }
                        }
                    } catch (e: Exception) { ApplicationManager.getApplication().invokeLater { showError("Could not load GitHub settings", e) } }
                }
            }.queue()
        } catch (e: Exception) { showError("Could not update GitHub settings", e) }
    }

    private fun isSaved(repo: GitHubRepository): Boolean {
        val identity = com.aicode.feature.github.persistence.RepositoryJsonStore.sshIdentity(repo.sshUrl)
        return savedRepositories.any { it.url == repo.sshUrl || (identity != null && com.aicode.feature.github.persistence.RepositoryJsonStore.sshIdentity(it.url) == identity) }
    }

    private fun expandAll() {
        collapseLanguageGroups = false
        var row = 0
        while (row < tree.rowCount) { tree.expandRow(row); row++ }
    }

    private fun collapseAll() {
        collapseLanguageGroups = true
        for (row in tree.rowCount - 1 downTo 0) {
            val path = tree.getPathForRow(row) ?: continue
            if (path.pathCount >= 3) tree.collapsePath(path)
        }
    }

    private fun expandVisibilityGroups() {
        var row = 0
        while (row < tree.rowCount) {
            val path = tree.getPathForRow(row) ?: break
            if (!collapseLanguageGroups || path.pathCount <= 2) tree.expandPath(path)
            row++
        }
    }
    private fun showError(title: String, error: Throwable) = com.intellij.openapi.ui.Messages.showErrorDialog(project, error.message ?: "Unexpected error", title)
    private data class Group(val title: String, val count: Int, val isLanguage: Boolean)
}

fun shouldOpenGithubOnClick(clickCount: Int, button: Int): Boolean =
    clickCount >= 2 && button == MouseEvent.BUTTON1
