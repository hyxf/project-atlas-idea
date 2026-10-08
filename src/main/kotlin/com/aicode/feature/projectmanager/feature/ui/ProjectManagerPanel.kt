package com.aicode.feature.projectmanager.feature.ui

import com.aicode.feature.projectmanager.ProjectManagerIcons
import com.aicode.feature.projectmanager.feature.project.ProjectItem
import com.aicode.feature.projectmanager.feature.project.ProjectManagerService
import com.aicode.feature.projectmanager.infrastructure.filesystem.ProjectDirectoryDeletion
import com.aicode.feature.projectmanager.infrastructure.filesystem.ProjectDirectoryDuplicator
import com.aicode.feature.projectmanager.infrastructure.persistence.ProjectJsonStore
import com.aicode.feature.projectmanager.settings.ProjectManagerConfigurable
import com.aicode.feature.projectmanager.settings.ProjectManagerSettings
import com.aicode.feature.projectmanager.settings.ProjectManagerSettingsListener
import com.intellij.icons.AllIcons
import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.PopupHandler
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.JTree

class ProjectManagerPanel(private val project: Project) : SimpleToolWindowPanel(true, true) {
    private val manager = service<ProjectManagerService>()
    private val settings = service<ProjectManagerSettings>()
    private var sortBy = settings.state.sortBy
    private val excludedTagFilters = linkedSetOf<String>()
    private val projectTree = Tree()
    private val status = JBLabel()
    init {
        ApplicationManager.getApplication().messageBus.connect(project).subscribe(
            ProjectManagerSettingsListener.TOPIC,
            ProjectManagerSettingsListener { value ->
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) applySettings(value)
                }
            },
        )
        toolbar = createToolbar()
        setContent(createContent())
        configureProjects()
        refresh()
    }

    private fun createToolbar(): JComponent {
        val actions = DefaultActionGroup(
            object : AnAction("Save Current Project", "Save the current project", AllIcons.Actions.MenuSaveall) {
                override fun actionPerformed(e: AnActionEvent) = saveCurrentProject()

                override fun update(e: AnActionEvent) {
                    val currentPath = currentProjectPath()
                    val alreadySaved = currentPath?.let { manager.findByPath(it) != null } == true
                    e.presentation.isEnabled = currentPath != null && !alreadySaved
                    e.presentation.description = if (alreadySaved) {
                        "The current project is already saved"
                    } else {
                        "Save the current project"
                    }
                }

                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            },
            object : AnAction("Import Local Projects", "Find and import projects from local folders", ProjectManagerIcons.ImportLocalProjects) {
                override fun actionPerformed(e: AnActionEvent) = ProjectImportUi.show(project) { refresh() }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            },
            object : AnAction("Edit project.json", "Open the user-level project configuration", AllIcons.Actions.Edit) {
                override fun actionPerformed(e: AnActionEvent) = editConfiguration()
            },
            ActionManager.getInstance().getAction("com.aicode.projectmanager.SearchProjects"),
            object : AnAction("Refresh", "Reload project.json", AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) = ProjectUiSupport.runInBackground(
                    project, "Refresh project.json", { service<ProjectJsonStore>().forceReload() }, { reloadFromStore() },
                )
            },
            sortActions(),
            manageTagsAction(),
            updateAction(),
            settingsAction(),
        )
        val actionToolbar = ActionManager.getInstance().createActionToolbar("ProjectManager.Toolbar", actions, true).apply {
            targetComponent = this@ProjectManagerPanel
        }.component
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(2, 4)
            add(actionToolbar, BorderLayout.CENTER)
        }
    }

    private fun sortActions() = DefaultActionGroup("Sort", "Sort projects", AllIcons.ObjectBrowser.Sorted).apply {
        isPopup = true
        add(sortAction("Name", ProjectManagerSettings.SortBy.NAME))
        add(sortAction("Path", ProjectManagerSettings.SortBy.PATH))
        add(sortAction("Recent", ProjectManagerSettings.SortBy.RECENT))
    }

    private fun sortAction(text: String, value: ProjectManagerSettings.SortBy) = object : ToggleAction(text) {
        override fun isSelected(e: AnActionEvent) = sortBy == value
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (state) {
                sortBy = value
                ProjectUiSupport.runInBackground(project, "Save sort setting", { settings.updateSortBy(value) })
                refreshProjects()
            }
        }
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private fun settingsAction() = object : AnAction(
        "Project Atlas Settings",
        "Open Project Atlas settings",
        AllIcons.General.Settings,
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, ProjectManagerConfigurable::class.java)
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private fun updateAction() = object : AnAction(
        "Check for Project Atlas Updates",
        "Check for and install a Project Atlas update",
        AllIcons.Actions.Install,
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            ProjectAtlasPluginUpdater.checkForUpdate(project)
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private fun manageTagsAction() = object : ActionGroup(
        "Filter by Tags",
        "Filter projects by tags",
        ProjectManagerIcons.FilterByTag,
    ) {
        init {
            isPopup = true
        }

        override fun getChildren(e: AnActionEvent?): Array<AnAction> {
            val tags = availableTagFilters()
            if (tags.isEmpty()) {
                return arrayOf(object : AnAction("No tags available") {
                    override fun actionPerformed(e: AnActionEvent) = Unit
                    override fun update(e: AnActionEvent) {
                        e.presentation.isEnabled = false
                    }
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                })
            }
            return tags.sortedBy(::tagFilterDisplayName).map { tag ->
                object : ToggleAction(tagFilterDisplayName(tag)) {
                    override fun isSelected(e: AnActionEvent) = tag !in excludedTagFilters

                    override fun setSelected(e: AnActionEvent, state: Boolean) {
                        if (state) excludedTagFilters.remove(tag) else excludedTagFilters.add(tag)
                        refreshProjects(null)
                    }

                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                }
            }.toTypedArray()
        }

        override fun update(e: AnActionEvent) {
            e.presentation.description = if (excludedTagFilters.isEmpty()) {
                "Filter projects by tags"
            } else {
                "Some tags are excluded"
            }
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private fun createContent(): JComponent = JPanel(BorderLayout()).apply {
            add(JBScrollPane(projectTree), BorderLayout.CENTER)
            add(status.apply { border = JBUI.Borders.empty(4, 8) }, BorderLayout.SOUTH)
        }

    private fun configureProjects() {
        projectTree.isRootVisible = false
        projectTree.showsRootHandles = true
        projectTree.rowHeight = 0
        projectTree.cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean,
                                               leaf: Boolean, row: Int, hasFocus: Boolean) {
                val item = (value as? DefaultMutableTreeNode)?.userObject
                icon = null
                border = JBUI.Borders.empty()
                when (item) {
                    is ProjectItem -> {
                        val current = isCurrentProject(item)
                        icon = if (item.favorite) ProjectManagerIcons.FavoriteFolder else AllIcons.Nodes.Folder
                        append(
                            item.name,
                            if (current) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                            else SimpleTextAttributes.REGULAR_ATTRIBUTES,
                        )
                        if (current) {
                            append(" · ✓", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                        }
                        if (ProjectPathStatusCache.isDirectory(item.path) == false) {
                            append("  Missing", SimpleTextAttributes.ERROR_ATTRIBUTES)
                        } else if (ProjectPathStatusCache.isDirectory(item.path) == null) {
                            ProjectPathStatusCache.refresh(item.path) { projectTree.repaint() }
                        }
                    }
                    is TagNode -> {
                        icon = ProjectManagerIcons.TagGroup
                        append(item.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    }
                    else -> append(item?.toString().orEmpty())
                }
            }
        }
        projectTree.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2 && selectedTreeProject() != null) open(selectedTreeProject()!!, defaultNewWindow())
            }
        })
        PopupHandler.installPopupMenu(projectTree, contextActions(), "ProjectManager.ContextMenu")
        bindKey(KeyEvent.VK_ENTER, 0, "open") { openSelected(defaultNewWindow()) }
        bindKey(KeyEvent.VK_ENTER, menuShortcutMask(), "openNew") { openSelected(true) }
        bindKey(KeyEvent.VK_SPACE, 0, "favorite") { toggleFavoriteSelected() }
        bindKey(KeyEvent.VK_DELETE, 0, "remove") { removeSelected() }
        bindKey(KeyEvent.VK_BACK_SPACE, 0, "removeBackspace") { removeSelected() }
    }

    fun refresh(preferredId: String? = selected()?.id) {
        refreshProjects(preferredId)
        service<ProjectJsonStore>().consumeLoadWarning()?.let {
            ProjectUiSupport.notify(project, it, com.intellij.notification.NotificationType.WARNING)
        }
    }

    fun reloadFromStore() {
        applySettings(settings.state)
    }

    private fun applySettings(value: ProjectManagerSettings.Data) {
        sortBy = value.sortBy
        refresh()
    }

    private fun refreshProjects(preferredId: String? = selected()?.id) {
        val allTags = availableTagFilters()
        excludedTagFilters.retainAll(allTags)
        val selectedTags = allTags - excludedTagFilters
        val tagFilteredProjects = when {
            excludedTagFilters.isEmpty() -> manager.projects()
            selectedTags.isEmpty() -> emptyList()
            else -> manager.projects().filter { item ->
                item.tags.any(selectedTags::contains) ||
                    (item.tags.isEmpty() && UNTAGGED_FILTER_KEY in selectedTags)
            }
        }
        tagFilteredProjects.forEach { item ->
            ProjectPathStatusCache.refresh(item.path) {
                projectTree.repaint()
            }
        }
        refreshTagTree(preferredId, tagFilteredProjects, selectedTags)
    }

    private fun refreshTagTree(preferredId: String?, projects: List<ProjectItem>, selectedTags: Set<String>) {
        val root = DefaultMutableTreeNode("Tags")
        var associationCount = 0
        val selectedRealTags = selectedTags - UNTAGGED_FILTER_KEY
        var groupCount = selectedRealTags.size
        selectedRealTags.sorted().forEach { tag ->
            val tagNode = DefaultMutableTreeNode(TagNode(tag))
            manager.sortProjects(projects.filter { tag in it.tags }).forEach {
                tagNode.add(DefaultMutableTreeNode(it)); associationCount++
            }
            root.add(tagNode)
        }
        val untaggedProjects = manager.sortProjects(projects.filter { it.tags.isEmpty() })
        if (UNTAGGED_FILTER_KEY in selectedTags && untaggedProjects.isNotEmpty()) {
            val untaggedNode = DefaultMutableTreeNode(TagNode(UNTAGGED_GROUP_NAME))
            untaggedProjects.forEach {
                untaggedNode.add(DefaultMutableTreeNode(it)); associationCount++
            }
            root.add(untaggedNode)
            groupCount++
        }
        projectTree.model = DefaultTreeModel(root)
        TreeUtil.expandAll(projectTree)
        preferredId?.let { selectProjectInTree(it) }
        projectTree.emptyText.text = "No tags"
        status.text = "$groupCount tag groups  ·  $associationCount project associations"
    }

    private fun availableTagFilters(): Set<String> = buildSet {
        addAll(manager.tags())
        if (manager.projects().any { it.tags.isEmpty() }) add(UNTAGGED_FILTER_KEY)
    }

    private fun tagFilterDisplayName(tag: String) =
        if (tag == UNTAGGED_FILTER_KEY) UNTAGGED_GROUP_NAME else tag

    private fun selectProjectInTree(id: String) {
        val root = projectTree.model.root as? DefaultMutableTreeNode ?: return
        val nodes = root.depthFirstEnumeration()
        while (nodes.hasMoreElements()) {
            val node = nodes.nextElement() as? DefaultMutableTreeNode ?: continue
            if ((node.userObject as? ProjectItem)?.id == id) {
                projectTree.selectionPath = javax.swing.tree.TreePath(node.path)
                return
            }
        }
    }

    private fun saveCurrentProject() {
        val path = currentProjectPath() ?: return
        val existing = manager.findByPath(path)
        val dialog = ProjectEditDialog(project, path, existing, allowPathSelection = false)
        if (dialog.showAndGet()) ProjectUiSupport.runInBackground(project, "Save current project", {
            manager.saveProject(dialog.projectName, path, dialog.tags, dialog.favorite).also {
                manager.updateLastOpened(path)
            }
        }) { refresh(it.id) }
    }

    private fun editConfiguration() {
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { service<ProjectJsonStore>().ensureFile() }
                .onSuccess { path -> ApplicationManager.getApplication().invokeLater {
                    LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)?.let {
                        FileEditorManager.getInstance(project).openFile(it, true)
                    }
                } }
                .onFailure { ProjectUiSupport.report(project, "Open project.json", it) }
        }
    }

    private fun openSelected(newWindow: Boolean) { selected()?.let { open(it, newWindow) } }
    private fun open(item: ProjectItem, newWindow: Boolean) {
        val current = project.basePath?.let(java.nio.file.Path::of)?.toAbsolutePath()?.normalize() == item.path.toAbsolutePath().normalize()
        if (current) return
        if (ProjectUiSupport.open(item, project, newWindow)) {
            ProjectUiSupport.runInBackground(project, "Update recent project", { manager.updateLastOpened(item.path) })
        }
        refreshProjects(item.id)
    }

    private fun contextActions() = DefaultActionGroup().apply {
        add(action("Open") { openSelected(false) }); add(action("Open in New Window") { openSelected(true) }); addSeparator()
        add(action("Edit Project…") { editSelected() })
        add(action("Duplicate Project…") { duplicateSelected() })
        add(action("Edit Tags…") { editTagsSelected() })
        add(object : ContextAction() {
            override fun update(e: AnActionEvent) { super.update(e); e.presentation.text = if (selected()?.favorite == true) "Remove from Favorites" else "Add to Favorites" }
            override fun perform() = toggleFavoriteSelected()
        }); addSeparator()
        add(action("Copy Path") { selected()?.let { CopyPasteManager.getInstance().setContents(StringSelection(it.path.toString())) } })
        add(action("Reveal in Finder / Explorer") { selected()?.let { RevealFileAction.openDirectory(it.path) } })
        add(action("Open in Terminal") { selected()?.let(::openInTerminal) })
        add(action("Locate Missing Project…", {
            selected()?.let { ProjectPathStatusCache.isDirectory(it.path) == false } == true
        }) { locateSelected() }); addSeparator()
        add(action("Delete Project…") { deleteSelected() })
        add(action("Remove from Project Atlas…") { removeSelected() })
    }

    private fun action(text: String, visible: () -> Boolean = { selected() != null }, action: () -> Unit) = object : ContextAction() {
        override fun update(e: AnActionEvent) { super.update(e); e.presentation.text = text; e.presentation.isVisible = visible() }
        override fun perform() = action()
    }
    private abstract inner class ContextAction : AnAction() {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) { e.presentation.isEnabled = selected() != null }
        final override fun actionPerformed(e: AnActionEvent) = perform()
        abstract fun perform()
    }

    private fun selected(): ProjectItem? = selectedTreeProject()
    private fun selectedTreeProject() =
        (projectTree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? ProjectItem

    private fun openInTerminal(item: ProjectItem) {
        val terminalAction = ActionManager.getInstance().getAction("Terminal.OpenInTerminal")
        if (terminalAction == null) {
            ProjectUiSupport.notify(project, "The Terminal plugin is not available", NotificationType.WARNING)
            return
        }
        ProjectUiSupport.runInBackground(project, "Open in Terminal", {
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(item.path)
        }) { directory ->
            if (directory == null || !directory.isDirectory) {
                ProjectUiSupport.notify(project, "Project path is missing or is not a directory: ${item.path}", NotificationType.WARNING)
                return@runInBackground
            }
            val dataContext = DataContext { dataId ->
                when (dataId) {
                    CommonDataKeys.PROJECT.name -> project
                    CommonDataKeys.VIRTUAL_FILE.name -> directory
                    else -> null
                }
            }
            ActionUtil.invokeAction(terminalAction, dataContext, ActionPlaces.UNKNOWN, null, null)
        }
    }

    private fun editSelected() {
        val item = selected() ?: return
        val dialog = ProjectEditDialog(project, item.path, item)
        if (dialog.showAndGet()) ProjectUiSupport.runInBackground(project, "Edit project", {
            if (dialog.projectPath.toAbsolutePath().normalize() != item.path.toAbsolutePath().normalize()) {
                manager.relocateProject(item.id, dialog.projectPath)
            }
            manager.updateProject(item.id, dialog.projectName, dialog.tags, dialog.favorite)
        }) { refresh(it.id) }
    }
    private fun duplicateSelected() {
        val item = selected() ?: return
        ProjectUiSupport.runInBackground(project, "Duplicate project", {
            ProjectDirectoryDuplicator.duplicate(item.path)
        }) { duplicatedPath ->
            val dialog = ProjectEditDialog(
                project,
                duplicatedPath,
                allowPathSelection = false,
                initialTags = item.tags,
                initialFavorite = item.favorite,
            )
            if (dialog.showAndGet()) ProjectUiSupport.runInBackground(project, "Save duplicated project", {
                manager.saveProject(dialog.projectName, duplicatedPath, dialog.tags, dialog.favorite)
            }) { refresh(it.id) }
        }
    }
    private fun editTagsSelected() {
        val item = selected() ?: return
        val dialog = ProjectTagsDialog(project, item.name, manager.tags(), item.tags)
        if (dialog.showAndGet()) ProjectUiSupport.runInBackground(project, "Edit project tags", {
            manager.updateProject(item.id, item.name, dialog.selectedTags, item.favorite)
        }) { refresh(it.id) }
    }
    private fun locateSelected() {
        val item = selected() ?: return
        val path = ProjectUiSupport.chooseDirectory(project) ?: return
        ProjectPathStatusCache.invalidate(item.path)
        ProjectPathStatusCache.invalidate(path)
        ProjectUiSupport.runInBackground(project, "Locate project", { manager.relocateProject(item.id, path) }) {
            refresh(it.id)
        }
    }
    private fun removeSelected() {
        val item = selected() ?: return
        if (Messages.showYesNoDialog(project, "Remove ${item.name} from Project Atlas?\nThe directory will not be deleted.",
                "Remove Project", Messages.getQuestionIcon()) == Messages.YES) {
            ProjectUiSupport.runInBackground(project, "Remove project", { manager.removeProject(item.id) }) { refresh() }
        }
    }
    private fun deleteSelected() {
        val item = selected() ?: return
        if (isCurrentProject(item)) {
            ProjectUiSupport.notify(project, "Close this project before deleting it.", NotificationType.WARNING)
            return
        }
        val dialog = DeleteProjectDialog(project, item)
        if (!dialog.showAndGet()) return
        val deleteDirectly = dialog.deleteDirectly

        ProjectUiSupport.runInBackground(project, "Remove project entry", {
            manager.removeProject(item.id)
        }) {
            refresh()
            deleteProjectDirectory(item, deleteDirectly)
        }
    }
    private fun deleteProjectDirectory(item: ProjectItem, deleteDirectly: Boolean) {
        ProjectUiSupport.runInBackground(project, "Delete project directory", {
            val directoryExisted = java.nio.file.Files.exists(item.path)
            if (directoryExisted) try {
                if (deleteDirectly) ProjectDirectoryDeletion.deleteDirectly(item.path)
                else ProjectDirectoryDeletion.moveToTrash(item.path)
            } catch (error: Exception) {
                val solution = if (deleteDirectly) {
                    "Direct deletion failed and some contents may already be deleted. Close programs using the project, " +
                        "check permissions, then delete the remaining directory manually. " +
                        "The Project Atlas entry has already been removed."
                } else {
                    "Could not move the project to Trash. Close programs using it, check permissions, and try again. " +
                        "Move or delete the directory manually. The Project Atlas entry has already been removed."
                }
                throw IllegalStateException(solution, error)
            }
            directoryExisted
        }) { directoryExisted ->
            val message = when {
                !directoryExisted -> "The directory was already missing."
                deleteDirectly -> "${item.name} was deleted permanently."
                else -> "${item.name} was moved to Trash."
            }
            ProjectUiSupport.notify(project, message, NotificationType.INFORMATION)
        }
    }
    private fun toggleFavoriteSelected() {
        val item = selected() ?: return
        ProjectUiSupport.runInBackground(project, "Update favorite", { manager.toggleFavorite(item.id) }) { refresh(it.id) }
    }
    private fun defaultNewWindow() = service<ProjectManagerSettings>().state.defaultOpenMode == ProjectManagerSettings.OpenMode.NEW_WINDOW
    private fun currentProjectPath() = project.basePath?.let(java.nio.file.Path::of)
    private fun isCurrentProject(item: ProjectItem) =
        currentProjectPath()?.toAbsolutePath()?.normalize() == item.path.toAbsolutePath().normalize()
    private fun bindKey(key: Int, modifiers: Int, name: String, action: () -> Unit) {
        projectTree.getInputMap(JComponent.WHEN_FOCUSED).put(javax.swing.KeyStroke.getKeyStroke(key, modifiers), name)
        projectTree.actionMap.put(name, object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) = action()
        })
    }
    private fun menuShortcutMask() = java.awt.Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
    private data class TagNode(val name: String)
    private companion object {
        const val UNTAGGED_GROUP_NAME = "Untagged"
        const val UNTAGGED_FILTER_KEY = "\u0000untagged"
    }
}
