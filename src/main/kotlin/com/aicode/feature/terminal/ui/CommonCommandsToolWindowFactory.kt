package com.aicode.feature.terminal.ui

import com.aicode.feature.terminal.model.CommonCommand
import com.aicode.feature.terminal.model.CommonCommandDocument
import com.aicode.feature.terminal.service.CommonCommandService
import com.aicode.feature.terminal.service.CommonCommandFileWatcher
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.TransferHandler
import javax.swing.DropMode
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

class CommonCommandsToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = CommonCommandsPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

private data class CommandNode(val index: Int, val command: CommonCommand) {
    override fun toString(): String = buildString {
        append(command.command.replace('\n', ' '))
        if (command.description.isNotBlank()) append("  ·  ${command.description.replace('\n', ' ')}")
        if (command.tags.isNotEmpty()) append("  [${command.tags.joinToString(", ")}]")
    }
}

class CommonCommandsPanel(private val project: Project, private val service: CommonCommandService = CommonCommandService.getInstance()) : JPanel(BorderLayout()), Disposable, DataProvider {
    private val root = DefaultMutableTreeNode("Common Commands")
    private val model = DefaultTreeModel(root)
    private val tree = JTree(model)
    private var snapshot: CommonCommandDocument? = null
    private var watcher: CommonCommandFileWatcher? = null
    private val refreshGeneration = AtomicLong()
    @Volatile private var disposed = false

    init {
        panels[project] = this
        val manager = ActionManager.getInstance()
        add(toolbar(), BorderLayout.NORTH)
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(
                tree: JTree,
                value: Any?,
                selected: Boolean,
                expanded: Boolean,
                leaf: Boolean,
                row: Int,
                hasFocus: Boolean,
            ) {
                val node = value as? DefaultMutableTreeNode
                when (val item = node?.userObject) {
                    is CommandNode -> {
                        append(item.command.command.replace('\n', ' '), SimpleTextAttributes.REGULAR_ATTRIBUTES)
                        item.command.description.takeIf(String::isNotBlank)?.let {
                            append("  ·  ${it.replace('\n', ' ')}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        }
                        item.command.tags.takeIf { it.isNotEmpty() }?.let {
                            append("  [${it.joinToString(", ")}]", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        }
                    }
                    else -> append(
                        item?.toString().orEmpty(),
                        if (node?.level == 1) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                        else SimpleTextAttributes.REGULAR_ATTRIBUTES,
                    )
                }
            }
        }
        tree.dragEnabled = true
        tree.dropMode = DropMode.ON
        tree.transferHandler = object : TransferHandler() {
            override fun getSourceActions(c: JComponent) = MOVE
            override fun createTransferable(c: JComponent): java.awt.datatransfer.Transferable? =
                selectedNode()?.index?.toString()?.let(::StringSelection)
            override fun canImport(support: TransferSupport): Boolean = support.isDrop && support.isDataFlavorSupported(DataFlavor.stringFlavor)
            override fun importData(support: TransferSupport): Boolean {
                if (!canImport(support)) return false
                val from = (support.transferable.getTransferData(DataFlavor.stringFlavor) as String).toIntOrNull() ?: return false
                val path = (support.dropLocation as? JTree.DropLocation)?.path ?: return false
                val to = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? CommandNode ?: return false
                val expected = snapshot?.contents ?: return false
                change { service.moveCommand(from, to.index, expected) }
                return true
            }
        }
        tree.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mousePressed(e: java.awt.event.MouseEvent) = popup(e)
            override fun mouseReleased(e: java.awt.event.MouseEvent) = popup(e)
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)) selectedNode()?.let { run(it, false) }
            }
            private fun popup(e: java.awt.event.MouseEvent) {
                if (!e.isPopupTrigger) return
                tree.getPathForLocation(e.x, e.y)?.let { tree.selectionPath = it }
                selectedNode() ?: return
                val actions = DefaultActionGroup().apply {
                    listOf("Insert", "Run", "Edit", "Tags", "Delete").forEach { name ->
                        add(manager.getAction("com.aicode.commoncommands.$name"))
                    }
                }
                manager.createActionPopupMenu("CommonCommands", actions).component.show(tree, e.x, e.y)
            }
        })
        add(JScrollPane(tree), BorderLayout.CENTER)
        refresh()
        startWatcher()
    }

    private fun toolbar(): JComponent {
        val actions = DefaultActionGroup().apply {
            listOf("Add", "Variables", "Json", "Refresh", "Expand", "Collapse").forEach { name ->
                val id = "com.aicode.commoncommands.$name"
                add(requireNotNull(ActionManager.getInstance().getAction(id)) { "Missing action: $id" })
            }
        }
        val actionToolbar = ActionManager.getInstance()
            .createActionToolbar("CommonCommands.Toolbar", actions, true)
            .apply { targetComponent = this@CommonCommandsPanel }
            .component
        return JPanel(BorderLayout(8, 0)).apply {
            border = JBUI.Borders.empty(2, 4)
            add(actionToolbar, BorderLayout.CENTER)
        }
    }

    private fun selectedNode(): CommandNode? = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? CommandNode
    override fun getData(dataId: String): Any? = if (dataId == CommonDataKeys.PROJECT.name) project else null

    fun hasSelection(): Boolean = selectedNode() != null
    fun addSelected() = addCommand()
    fun editSelected() { selectedNode()?.let(::edit) }
    fun editTagsSelected() { selectedNode()?.let(::editTags) }
    fun deleteSelected() { selectedNode()?.let(::delete) }
    fun insertSelected() { selectedNode()?.let { run(it, false) } }
    fun runSelected() { selectedNode()?.let { run(it, true) } }
    fun editGlobalVariables() = editGlobals()
    fun openRawJson() = openJson()
    fun expandAll() = expandAllTreeRows(tree)
    fun collapseAll() = collapseAllTreeRows(tree)

    fun refresh() {
        val generation = refreshGeneration.incrementAndGet()
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { service.read() }
            SwingUtilities.invokeLater {
                if (disposed || project.isDisposed || generation != refreshGeneration.get()) return@invokeLater
                if (result.isSuccess) showDocument(result.getOrThrow()) else showError(result.exceptionOrNull()?.message ?: "Failed to read commoncmd.json")
            }
        }
    }

    private fun showDocument(document: CommonCommandDocument) {
        snapshot = document
        root.removeAllChildren()
        val groups = linkedMapOf<String, DefaultMutableTreeNode>()
        document.commands.forEachIndexed { index, command ->
            (command.tags.ifEmpty { listOf("Untagged") }).forEach { tag ->
                groups.getOrPut(tag) { DefaultMutableTreeNode(tag) }.add(DefaultMutableTreeNode(CommandNode(index, command)))
            }
        }
        groups.values.forEach(root::add)
        if (groups.isEmpty()) root.add(DefaultMutableTreeNode("No common commands. Use Add or edit commoncmd.json."))
        model.reload()
        for (row in 0 until tree.rowCount) tree.expandRow(row)
    }

    private fun showError(message: String) {
        snapshot = null
        root.removeAllChildren()
        root.add(DefaultMutableTreeNode("Error: $message — Refresh after fixing commoncmd.json."))
        model.reload()
        tree.expandRow(0)
    }

    private fun change(operation: () -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching(operation)
            SwingUtilities.invokeLater {
                if (disposed || project.isDisposed) return@invokeLater
                result.exceptionOrNull()?.let { Messages.showErrorDialog(project, it.message ?: "Failed to update commoncmd.json", "Common Commands") }
                refresh()
            }
        }
    }

    private fun addCommand() {
        val command = CommonCommandDialog.showAdd() ?: return
        val expected = snapshot?.contents ?: return
        change { if (!service.addCommand(command, expected)) error("The command already exists.") }
    }

    private fun edit(node: CommandNode) {
        val command = CommonCommandDialog.showEdit(node.command) ?: return
        val expected = snapshot?.contents ?: return
        change { service.updateCommand(node.index, command, expected) }
    }

    private fun editTags(node: CommandNode) {
        val input = Messages.showInputDialog(project, "Comma separated tags", "Edit Tags", null,
            node.command.tags.joinToString(", "), null) ?: return
        val expected = snapshot?.contents ?: return
        change { service.updateCommand(node.index, node.command.copy(tags = input.split(',').map(String::trim).filter(String::isNotEmpty).distinct()), expected) }
    }

    private fun delete(node: CommandNode) {
        if (Messages.showYesNoDialog(project, "Delete this JSON record?\n\n${node.command.command}", "Delete Common Command", null) != Messages.YES) return
        val expected = snapshot?.contents ?: return
        change { service.deleteCommand(node.index, expected) }
    }

    private fun editGlobals() {
        val document = snapshot ?: return
        val variables = CommonCommandVariablesUi.edit(document.variables, "Global Command Variables") ?: return
        change { service.updateGlobalVariables(variables, document.contents) }
    }

    private fun openJson() {
        val file = CommonCommandService.defaultConfigPath().toFile()
        val virtual = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file) ?: return
        FileEditorManager.getInstance(project).openFile(virtual, true)
    }

    private fun run(node: CommandNode, execute: Boolean) {
        try { CommonCommandRunner.run(project, node.command, execute) }
        catch (ex: Exception) { Messages.showErrorDialog(project, ex.message ?: "Could not use command", "Common Commands") }
    }

    private fun startWatcher() {
        val path = CommonCommandService.defaultConfigPath()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val created = CommonCommandFileWatcher(path) { if (!disposed && !project.isDisposed) refresh() }
                watcher = created
                if (disposed) created.close()
            }
            catch (ex: Exception) { SwingUtilities.invokeLater {
                if (!disposed && !project.isDisposed) showError("File watcher failed: ${ex.message}")
            } }
        }
    }

    override fun dispose() { disposed = true; watcher?.close(); panels.remove(project, this) }

    companion object {
        private val panels = java.util.concurrent.ConcurrentHashMap<Project, CommonCommandsPanel>()
        fun forProject(project: Project): CommonCommandsPanel? = panels[project]
    }
}

internal fun expandAllTreeRows(tree: JTree) {
    var row = 0
    while (row < tree.rowCount) {
        tree.expandRow(row)
        row++
    }
}

internal fun collapseAllTreeRows(tree: JTree) {
    for (row in tree.rowCount - 1 downTo 0) tree.collapseRow(row)
}
