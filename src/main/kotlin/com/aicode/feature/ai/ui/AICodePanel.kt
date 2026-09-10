package com.aicode.feature.ai.ui

import com.aicode.common.util.ClipboardService
import com.aicode.feature.ai.icons.AICodeIcons
import com.aicode.feature.ai.service.AICodeFileService
import com.aicode.feature.ai.settings.AICodeIgnoreSettings
import com.aicode.feature.ai.util.MarkdownBuilder
import com.aicode.feature.git.service.GitContextDiffService
import com.aicode.feature.git.ui.GitContextDiffDialog
import com.intellij.icons.AllIcons
import com.intellij.ide.CommonActionsManager
import com.intellij.ide.TreeExpander
import com.intellij.notification.*
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.*
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import git4idea.repo.GitRepositoryManager
import java.awt.BorderLayout
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.beans.PropertyChangeEvent
import javax.swing.*
import javax.swing.tree.*

class AICodePanel(private val project: Project) : JPanel(), Disposable {
    private val rootNode = DefaultMutableTreeNode(AICodeNodeData("Project", null, true, false))
    private val treeModel = DefaultTreeModel(rootNode)
    private val tree = Tree(treeModel)

    init {
        layout = BorderLayout()
        setupUI()
        setupListeners()
        refreshTree()
        project.messageBus
            .connect(this)
            .subscribe(
                AICodeFileService.AICODE_TOPIC,
                AICodeFileService.AICodeStateListener { refreshTree() },
            )
    }

    private fun setupUI() {
        tree.isRootVisible = true
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.cellRenderer = AICodeTreeCellRenderer()
        tree.emptyText.text = "No files in this context group."
        add(
            JBScrollPane(tree).apply { border = BorderFactory.createEmptyBorder() },
            BorderLayout.CENTER,
        )
        add(createToolbar(), BorderLayout.NORTH)
    }

    private fun createToolbar(): JComponent {
        val group = DefaultActionGroup()
        group.add(GroupSelectorAction())
        group.addSeparator()
        group.add(
            simple(
                "Open Configuration",
                "Open .aicode.json configuration file",
                AllIcons.General.Settings,
            ) {
                openAICodeFile()
            }
        )
        group.add(
            simple(
                "Copy as Markdown",
                "Export current context group as Markdown",
                AllIcons.Actions.Copy,
            ) {
                copyMarkdownToClipboard()
            }
        )
        group.add(
            simple(
                "Copy File List",
                "Copy current context group's file list",
                AllIcons.Actions.ListFiles,
            ) {
                copyFileListToClipboard()
            }
        )
        group.addSeparator()
        val expander =
            object : TreeExpander {
                override fun expandAll() = TreeUtil.expandAll(tree)

                override fun canExpand() = true

                override fun collapseAll() = TreeUtil.collapseAll(tree, 1)

                override fun canCollapse() = true
            }
        CommonActionsManager.getInstance().let {
            group.add(it.createExpandAllAction(expander, tree))
            group.add(it.createCollapseAllAction(expander, tree))
        }
        group.addSeparator()
        group.add(simple("Refresh", "Refresh tree", AllIcons.Actions.Refresh) { refreshTree() })
        group.add(
            object : AnAction() {
                override fun actionPerformed(e: AnActionEvent) {
                    val s = service()
                    s.setBannerEnabled(!s.isBannerEnabled())
                }

                override fun update(e: AnActionEvent) {
                    val enabled = service().isBannerEnabled()
                    e.presentation.icon =
                        if (enabled) AICodeIcons.EYE_OPEN else AICodeIcons.EYE_CLOSE
                    e.presentation.text =
                        if (enabled) "Hide Editor Banner" else "Show Editor Banner"
                    e.presentation.description =
                        if (enabled) "Click to hide editor banner"
                        else "Click to show editor banner"
                }

                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            }
        )
        group.add(
            object : AnAction(
                "Compare Context Branches",
                "Compare context files between Git branches",
                AllIcons.Actions.Diff,
            ) {
                override fun actionPerformed(e: AnActionEvent) {
                    showGitContextDiff()
                }

                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabledAndVisible = findGitRepository() != null
                }

                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            }
        )
        return ActionManager.getInstance()
            .createActionToolbar("AICodeToolbar", group, true)
            .apply { targetComponent = this@AICodePanel }
            .component
    }

    private fun showGitContextDiff() {
        val repository = findGitRepository() ?: return
        val paths = service().readFilePaths()
        object : Task.Backgroundable(project, "Loading Git branches", false) {
            private var currentBranch: String? = null
            private var branches: List<String> = emptyList()
            private var error: String? = null

            override fun run(indicator: ProgressIndicator) {
                try {
                    repository.update()
                    currentBranch = repository.currentBranchName
                    branches = GitContextDiffService().getBranchNames(repository)
                } catch (ex: ProcessCanceledException) {
                    throw ex
                } catch (ex: Exception) {
                    error = ex.message?.takeIf { it.isNotBlank() } ?: "Unexpected Git error"
                }
            }

            override fun onSuccess() {
                if (project.isDisposed) return
                error?.let {
                    show("Failed to load Git branches: $it", NotificationType.ERROR)
                    return
                }
                val branch = currentBranch
                if (branch.isNullOrBlank()) {
                    show(
                        "Cannot compare branches while Git HEAD is detached.",
                        NotificationType.WARNING,
                    )
                    return
                }
                GitContextDiffDialog(project, repository, branch, branches, paths).show()
            }
        }.queue()
    }

    private fun findGitRepository() =
        project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }?.let {
            GitRepositoryManager.getInstance(project).getRepositoryForFileQuick(it)
        }

    private fun simple(text: String, description: String, icon: Icon?, run: () -> Unit) =
        object : AnAction(text, description, icon) {
            override fun actionPerformed(e: AnActionEvent) = run()
        }

    private inner class GroupSelectorAction : ComboBoxAction() {
        override fun createComboBoxButton(presentation: Presentation): ComboBoxButton =
            EndEllipsisComboBoxButton(presentation)

        override fun update(e: AnActionEvent) {
            e.project?.let {
                val active = AICodeFileService.getInstance(it).getActiveGroupName()
                e.presentation.text = active
                e.presentation.description = "Current Context Group: $active"
                e.presentation.icon = AllIcons.Nodes.ModuleGroup
            }
        }

        override fun createPopupActionGroup(button: JComponent): DefaultActionGroup {
            val group = DefaultActionGroup()
            val s = service()
            val active = s.getActiveGroupName()
            s.getGroupNames().sorted().forEach { name ->
                group.add(
                    simple(
                        name,
                        "Switch to $name",
                        if (name == active) AllIcons.Actions.Checked else null,
                    ) {
                        s.setActiveGroup(name)
                    }
                )
            }
            group.addSeparator()
            group.add(
                simple("New Group...", "Create a new empty context group", AllIcons.General.Add) {
                    val name =
                        Messages.showInputDialog(
                            project,
                            "Enter name for new context group:",
                            "New Group",
                            null,
                        )
                    if (!name.isNullOrBlank()) {
                        val n = name.trim()
                        if (n in s.getGroupNames())
                            show("Group '$n' already exists.", NotificationType.ERROR)
                        else s.addGroup(n)
                    }
                }
            )
            group.add(
                simple(
                    "Rename Current Group...",
                    "Rename the currently active group",
                    AllIcons.Actions.Edit,
                ) {
                    val current = s.getActiveGroupName()
                    val name =
                        Messages.showInputDialog(
                            project,
                            "Rename group '$current' to:",
                            "Rename Group",
                            null,
                            current,
                            null,
                        )
                    if (!name.isNullOrBlank() && name != current) {
                        val n = name.trim()
                        if (n in s.getGroupNames())
                            show("Group '$n' already exists.", NotificationType.ERROR)
                        else s.renameGroup(current, n)
                    }
                }
            )
            group.add(
                simple(
                    "Duplicate Current Group...",
                    "Create a copy of the current group",
                    AllIcons.Actions.Copy,
                ) {
                    val current = s.getActiveGroupName()
                    val name =
                        Messages.showInputDialog(
                            project,
                            "Enter name for the new group copy:",
                            "Duplicate Group",
                            null,
                            "$current Copy",
                            null,
                        )
                    if (!name.isNullOrBlank()) {
                        val n = name.trim()
                        if (n in s.getGroupNames())
                            show("Group '$n' already exists.", NotificationType.ERROR)
                        else s.duplicateGroup(current, n)
                    }
                }
            )
            group.add(
                object :
                    AnAction(
                        "Delete Current Group",
                        "Delete the currently active group",
                        AllIcons.General.Remove,
                    ) {
                    override fun actionPerformed(e: AnActionEvent) {
                        val current = s.getActiveGroupName()
                        if (
                            Messages.showYesNoDialog(
                                project,
                                "Are you sure you want to delete context group '$current'?",
                                "Delete Group",
                                Messages.getWarningIcon(),
                            ) == Messages.YES
                        )
                            s.removeGroup(current)
                    }

                    override fun update(e: AnActionEvent) {
                        e.presentation.isEnabled = s.getGroupNames().size > 1
                    }
                }
            )
            return group
        }

        private inner class EndEllipsisComboBoxButton(presentation: Presentation) :
            ComboBoxButton(presentation) {
            private var fullText = presentation.text.orEmpty()

            override fun presentationChanged(event: PropertyChangeEvent) {
                if (event.propertyName == Presentation.PROP_TEXT) {
                    fullText = event.newValue as? String ?: ""
                }
                super.presentationChanged(event)
            }

            override fun paintComponent(graphics: Graphics) {
                val clippedText = clipAtEnd(fullText, graphics.fontMetrics, availableTextWidth())
                if (text != clippedText) text = clippedText
                super.paintComponent(graphics)
            }

            private fun availableTextWidth(): Int {
                val iconWidth = icon?.iconWidth ?: 0
                val iconGap = if (iconWidth == 0 || fullText.isEmpty()) 0 else iconTextGap
                val arrowWidth = if (isArrowVisible) JBUI.scale(16) else 0
                return (width -
                        insets.left - insets.right -
                        margin.left - margin.right -
                        iconWidth - iconGap - arrowWidth)
                    .coerceAtLeast(0)
            }
        }
    }

    private fun clipAtEnd(text: String, fontMetrics: FontMetrics, availableWidth: Int): String {
        if (fontMetrics.stringWidth(text) <= availableWidth) return text
        val ellipsis = "..."
        if (fontMetrics.stringWidth(ellipsis) > availableWidth) return ""

        val codePoints = text.codePoints().toArray()
        var low = 0
        var high = codePoints.size
        while (low < high) {
            val length = (low + high + 1) / 2
            val candidate = String(codePoints, 0, length) + ellipsis
            if (fontMetrics.stringWidth(candidate) <= availableWidth) low = length else high = length - 1
        }
        return String(codePoints, 0, low) + ellipsis
    }

    private fun copyMarkdownToClipboard() {
        val s = service()
        val paths = s.readFilePaths()
        val group = s.getActiveGroupName()
        if (paths.isEmpty()) {
            show("Group '$group' is empty.", NotificationType.WARNING)
            return
        }
        try {
            ClipboardService.copyToClipboard(
                MarkdownBuilder.buildMarkdown(project, paths, s::getFileFromPath)
            )
            show(
                "Copied group '$group' (${paths.size} files) to clipboard.",
                NotificationType.INFORMATION,
            )
        } catch (ex: Exception) {
            show("Failed to export: " + ex.message, NotificationType.ERROR)
        }
    }

    private fun copyFileListToClipboard() {
        val s = service()
        val paths = s.readFilePaths()
        val group = s.getActiveGroupName()
        if (paths.isEmpty()) {
            show("Group '$group' is empty.", NotificationType.WARNING)
            return
        }
        try {
            ClipboardService.copyToClipboard(paths.joinToString("\n") { "@$it" })
            show(
                "Copied file list for group '$group' (${paths.size} files) to clipboard.",
                NotificationType.INFORMATION,
            )
        } catch (ex: Exception) {
            show("Failed to copy file list: " + ex.message, NotificationType.ERROR)
        }
    }

    private fun show(content: String, type: NotificationType) =
        Notifications.Bus.notify(Notification("AICode", "AICode Context", content, type), project)

    private fun setupListeners() {
        tree.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2)
                        tree.getPathForLocation(e.x, e.y)?.let {
                            openFileFromNode(it.lastPathComponent as DefaultMutableTreeNode)
                        }
                }
            }
        )
        tree.addMouseListener(
            object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    if (SwingUtilities.isRightMouseButton(e))
                        tree.getPathForLocation(e.x, e.y)?.let {
                            tree.selectionPath = it
                            showContextMenu(e, it.lastPathComponent as DefaultMutableTreeNode)
                        }
                }
            }
        )
    }

    private fun showContextMenu(e: MouseEvent, node: DefaultMutableTreeNode) {
        val data = node.userObject as? AICodeNodeData ?: return
        val menu = JPopupMenu()
        if (data.isDirectory && data.hasMissingFiles) {
            data.virtualFile?.let { directory ->
                menu.add(
                    JMenuItem("Add Missing Files").apply {
                        icon = AllIcons.General.Add
                        addActionListener { addMissingFiles(directory) }
                    }
                )
                menu.addSeparator()
            }
        }
        menu.add(
            JMenuItem(
                    if (data.isDirectory) "Remove Directory from Context"
                    else "Remove File from Context"
                )
                .apply {
                    icon = AllIcons.Actions.Cancel
                    addActionListener { removeNodeContext(node) }
                }
        )
        if (!data.isDirectory)
            data.fullRelativePath?.let { relativePath ->
                menu.add(
                    JMenuItem("Copy Relative Path").apply {
                        icon = AllIcons.Actions.Copy
                        addActionListener { copyRelativePath(relativePath) }
                    }
                )
            }
        menu.show(tree, e.x, e.y)
    }

    private fun copyRelativePath(path: String) {
        try {
            ClipboardService.copyToClipboard(path)
            show("Copied relative path: $path", NotificationType.INFORMATION)
        } catch (ex: Exception) {
            show("Failed to copy relative path: " + ex.message, NotificationType.ERROR)
        }
    }

    private fun addMissingFiles(dir: VirtualFile) {
        val s = service()
        val current = HashSet(s.readFilePaths())
        val additions = ArrayList<String>()
        VfsUtilCore.visitChildrenRecursively(
            dir,
            object : VirtualFileVisitor<Void>() {
                override fun visitFile(file: VirtualFile): Boolean {
                    if (AICodeIgnoreSettings.isIgnored(file.name)) return false
                    if (file.fileType.isBinary) return true
                    if (!file.isDirectory && file.name != ".aicode.json")
                        s.getRelativePath(file)?.let { if (it !in current) additions.add(it) }
                    return true
                }
            },
        )
        if (additions.isNotEmpty()) {
            val all = ArrayList(current)
            all.addAll(additions)
            s.writeFilePaths(all)
            show("Added ${additions.size} missing files.", NotificationType.INFORMATION)
        }
    }

    private fun removeNodeContext(node: DefaultMutableTreeNode) {
        val paths = ArrayList<String>()
        collectPaths(node, paths)
        paths.forEach(service()::removeFilePath)
    }

    private fun collectPaths(node: DefaultMutableTreeNode, collector: MutableList<String>) {
        (node.userObject as? AICodeNodeData)?.let { data ->
            if (!data.isDirectory) data.fullRelativePath?.let(collector::add)
        }
        for (i in 0 until node.childCount) collectPaths(
            node.getChildAt(i) as DefaultMutableTreeNode,
            collector,
        )
    }

    private fun openFileFromNode(node: DefaultMutableTreeNode) {
        (node.userObject as? AICodeNodeData)?.let { data ->
            if (!data.isDirectory)
                data.virtualFile?.let { FileEditorManager.getInstance(project).openFile(it, true) }
        }
    }

    fun refreshTree() {
        SwingUtilities.invokeLater {
            rootNode.removeAllChildren()
            val s = service()
            val root = rootNode.userObject as AICodeNodeData
            root.displayName = "Group: ${s.getActiveGroupName()}"
            root.virtualFile = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
            root.hasMissingFiles = false
            val paths = s.readFilePaths()
            paths.sort()
            buildTreeStructure(paths, HashSet(paths), s)
            treeModel.reload()
            TreeUtil.expandAll(tree)
        }
    }

    private fun buildTreeStructure(
        paths: List<String>,
        pathSet: Set<String>,
        s: AICodeFileService,
    ) {
        val dirs = HashMap<String, DefaultMutableTreeNode>()
        for (path in paths) {
            val parts = path.split('/')
            var current = rootNode
            var accumulator = ""
            for (i in parts.indices) {
                val part = parts[i]
                if (accumulator.isNotEmpty()) accumulator += "/"
                accumulator += part
                if (i == parts.lastIndex) {
                    val data = AICodeNodeData(part, path, false, false)
                    data.virtualFile = s.getFileFromPath(path)
                    current.add(DefaultMutableTreeNode(data))
                } else {
                    val dirPath = accumulator
                    current =
                        dirs[dirPath]
                            ?: run {
                                val file = s.getFileFromPath(dirPath)
                                val data =
                                    AICodeNodeData(
                                        part,
                                        dirPath,
                                        true,
                                        checkHasMissingFiles(file, pathSet, s),
                                    )
                                data.virtualFile = file
                                DefaultMutableTreeNode(data).also {
                                    dirs[dirPath] = it
                                    current.add(it)
                                }
                            }
                }
            }
        }
    }

    private fun checkHasMissingFiles(
        dir: VirtualFile?,
        tracked: Set<String>,
        s: AICodeFileService,
    ): Boolean {
        if (dir == null || !dir.isValid) return false
        var missing = false
        VfsUtilCore.visitChildrenRecursively(
            dir,
            object : VirtualFileVisitor<Void>() {
                override fun visitFile(file: VirtualFile): Boolean {
                    if (missing) return false
                    if (AICodeIgnoreSettings.isIgnored(file.name)) return false
                    if (file.fileType.isBinary) return true
                    if (!file.isDirectory && file.name != ".aicode.json") {
                        val path = s.getRelativePath(file)
                        if (path != null && path !in tracked) {
                            missing = true
                            return false
                        }
                    }
                    return true
                }
            },
        )
        return missing
    }

    private fun openAICodeFile() {
        service().getOrCreateAICodeFile()?.let {
            FileEditorManager.getInstance(project).openFile(it, true)
        }
    }

    private fun service() = AICodeFileService.getInstance(project)

    override fun dispose() {}

    private data class AICodeNodeData(
        var displayName: String,
        val fullRelativePath: String?,
        val isDirectory: Boolean,
        var hasMissingFiles: Boolean,
        var virtualFile: VirtualFile? = null,
    ) {
        override fun toString() = displayName
    }

    private class AICodeTreeCellRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree,
            value: Any,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ) {
            val data = (value as? DefaultMutableTreeNode)?.userObject as? AICodeNodeData ?: return
            val file = data.virtualFile
            icon =
                if (data.displayName.startsWith("Group: ")) AllIcons.Nodes.ModuleGroup
                else if (file != null)
                    if (data.isDirectory) AllIcons.Nodes.Folder else file.fileType.icon
                else if (data.isDirectory) AllIcons.Nodes.Folder else AllIcons.FileTypes.Unknown
            if (file == null && !data.isDirectory && !data.displayName.startsWith("Group: ")) {
                append(data.displayName, SimpleTextAttributes.ERROR_ATTRIBUTES)
                append(" (missing)", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            } else {
                append(data.displayName, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                if (data.isDirectory && data.hasMissingFiles)
                    append(" (*)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
    }
}
