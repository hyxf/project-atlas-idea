package com.aicode.feature.git.ui

import com.aicode.feature.git.service.GitContextDiffService
import com.aicode.feature.git.service.GitContextDiffService.FileDiffResult
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.codeInsight.completion.CodeCompletionHandlerBase
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.ComponentWithBrowseButton
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.TextFieldWithAutoCompletion
import com.intellij.ui.JBColor
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import git4idea.repo.GitRepository
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.ListSelectionModel
import javax.swing.JPanel
import javax.swing.RowSorter
import javax.swing.SortOrder
import javax.swing.JTable
import javax.swing.SwingUtilities
import javax.swing.RowFilter
import javax.swing.event.DocumentEvent as SwingDocumentEvent
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableRowSorter

class GitContextDiffDialog(
    private val project: Project,
    private val repository: GitRepository,
    private val currentBranch: String,
    branches: List<String>,
    private val paths: List<String>,
    private val diffService: GitContextDiffService = GitContextDiffService(),
) : DialogWrapper(project, true) {
    private val comparisonBranches = branches.filter { it != currentBranch }.distinct()
    private val defaultComparisonBranch = BranchSelection.preferredBranch(comparisonBranches)
    private val branchTextField =
        TextFieldWithAutoCompletion(
            project,
            CaseInsensitiveBranchCompletionProvider(comparisonBranches),
            true,
            defaultComparisonBranch,
        ).apply {
            setPlaceholder("Select or enter a comparison branch")
            toolTipText = "Type to complete a branch name, or use the arrow to browse all branches."
        }
    private val compareBranchBox =
        ComponentWithBrowseButton(branchTextField) {
            branchTextField.requestFocusInWindow()
            SwingUtilities.invokeLater { showBranchCompletions() }
        }.apply {
            setButtonIcon(AllIcons.General.ArrowDown)
        }
    private val fetchBeforeCompareCheckBox =
        JBCheckBox("Fetch before compare", true).apply {
            toolTipText = "Fetch the selected remote before comparing."
        }
    private val fetchOptionPanel = JPanel(BorderLayout()).apply {
        add(fetchBeforeCompareCheckBox, BorderLayout.CENTER)
        preferredSize = fetchBeforeCompareCheckBox.preferredSize
    }
    private val compareBranchControls = JPanel(BorderLayout(JBUI.scale(10), 0)).apply {
        add(compareBranchBox, BorderLayout.CENTER)
        add(fetchOptionPanel, BorderLayout.EAST)
    }
    private val tableModel = DiffTableModel()
    private val table = JBTable(tableModel)
    private val tableSorter = TableRowSorter(tableModel)
    private val searchField = JBTextField().apply { emptyText.text = "Search files or paths" }
    private val fileFilterBox = ComboBox(FileFilter.values()).apply {
        selectedItem = FileFilter.CHANGED
    }
    private val statusLabel = JBLabel("Choose a branch and click Execute.")
    private var comparedBranch: String? = null

    init {
        title = "Compare AICode Context"
        setOKButtonText("Execute")
        branchTextField.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) = updateFetchOption()
            }
        )
        searchField.document.addDocumentListener(
            object : DocumentAdapter() {
                override fun textChanged(event: SwingDocumentEvent) = applyTableFilter()
            }
        )
        fileFilterBox.addActionListener { applyTableFilter() }
        updateFetchOption()
        init()
    }

    override fun createActions(): Array<Action> = arrayOf(okAction, cancelAction)

    override fun doOKAction() {
        val compareBranch = selectedBranch()
        if (compareBranch.isEmpty() || compareBranch == currentBranch) return
        val remoteName =
            diffService.getRemoteName(repository, compareBranch)
                ?.takeIf { fetchBeforeCompareCheckBox.isSelected }
        saveContextDocuments()
        isOKActionEnabled = false
        statusLabel.text = if (remoteName == null) "Comparing..." else "Fetching $remoteName..."
        object : Task.Backgroundable(project, "Comparing AICode context files", false) {
            private var results: List<FileDiffResult> = emptyList()
            private var error: String? = null
            private var operation = if (remoteName == null) "Comparison" else "Fetch"

            override fun run(indicator: ProgressIndicator) {
                try {
                    if (remoteName != null) {
                        diffService.fetch(project, repository, remoteName)
                        operation = "Comparison"
                    }
                    results =
                        diffService.compare(
                            project,
                            repository,
                            compareBranch,
                            paths,
                        )
                } catch (ex: ProcessCanceledException) {
                    throw ex
                } catch (ex: Exception) {
                    error = "$operation failed: ${ex.message?.takeIf { it.isNotBlank() } ?: "Unexpected Git error"}"
                }
            }

            override fun onFinished() {
                if (project.isDisposed || this@GitContextDiffDialog.isDisposed) return
                ApplicationManager.getApplication().assertIsDispatchThread()
                isOKActionEnabled = true
                error?.let {
                    statusLabel.text = "Comparison failed: $it"
                    return
                }
                tableModel.setResults(results)
                comparedBranch = compareBranch
                val changedCount = results.count { it.changed }
                applyTableFilter()
                if (table.rowCount > 0) table.setRowSelectionInterval(0, 0)
                statusLabel.text =
                    "$changedCount changed · ${results.size - changedCount} unchanged · ${results.size} total"
            }
        }.queue()
    }

    override fun doValidate(): ValidationInfo? {
        val branch = selectedBranch()
        return when {
            branch.isEmpty() -> ValidationInfo("Select or enter a comparison branch.", compareBranchBox)
            branch == currentBranch ->
                ValidationInfo("The comparison branch must differ from the current branch.", compareBranchBox)
            else -> null
        }
    }

    override fun createCenterPanel(): JComponent {
        table.emptyText.text = "No files match the current filter."
        table.setShowGrid(false)
        table.rowHeight = JBUI.scale(24)
        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        table.fillsViewportHeight = true
        table.rowSorter = tableSorter
        tableSorter.sortKeys = listOf(RowSorter.SortKey(0, SortOrder.DESCENDING))
        table.columnModel.getColumn(0).cellRenderer = StatusCellRenderer()
        table.columnModel.getColumn(1).cellRenderer = FileCellRenderer(tableModel)
        table.columnModel.getColumn(2).cellRenderer = LocationCellRenderer(tableModel)
        table.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(event: MouseEvent) {
                    if (event.clickCount != 2) return
                    val viewRow = table.rowAtPoint(event.point)
                    val viewColumn = table.columnAtPoint(event.point)
                    if (viewRow < 0 || viewColumn < 0) return
                    val modelRow = table.convertRowIndexToModel(viewRow)
                    if (table.convertColumnIndexToModel(viewColumn) == LOCATION_COLUMN) {
                        openFileInProject(modelRow)
                    } else {
                        openFileDiff(modelRow)
                    }
                }
            }
        )
        table.addKeyListener(
            object : KeyAdapter() {
                override fun keyPressed(event: KeyEvent) {
                    if (event.keyCode != KeyEvent.VK_ENTER) return
                    openSelectedFileDiff()
                    event.consume()
                }
            }
        )
        table.columnModel.getColumn(0).preferredWidth = 100
        table.columnModel.getColumn(0).maxWidth = 120
        table.columnModel.getColumn(1).preferredWidth = 210
        val branchForm =
            FormBuilder.createFormBuilder()
                .setVerticalGap(JBUI.scale(6))
                .setHorizontalGap(JBUI.scale(10))
                .addLabeledComponent("Current branch:", JBLabel(currentBranch))
                .addLabeledComponent("Compare branch:", compareBranchControls)
                .panel
        val filters = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            border = JBUI.Borders.emptyTop(2)
            add(searchField, BorderLayout.CENTER)
            add(
                JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                    add(JBLabel("Show: "))
                    add(fileFilterBox)
                },
                BorderLayout.EAST,
            )
        }
        val header = JPanel(BorderLayout(0, JBUI.scale(10))).apply {
            add(branchForm, BorderLayout.NORTH)
            add(filters, BorderLayout.SOUTH)
        }
        return JPanel(BorderLayout(0, JBUI.scale(10))).apply {
            border = JBUI.Borders.empty(10, 12, 8, 12)
            add(header, BorderLayout.NORTH)
            add(JBScrollPane(table), BorderLayout.CENTER)
            add(statusLabel, BorderLayout.SOUTH)
            preferredSize = Dimension(JBUI.scale(720), JBUI.scale(460))
        }
    }

    private fun selectedBranch() = branchTextField.text.trim()

    private fun updateFetchOption() {
        val isRemoteBranch = diffService.getRemoteName(repository, selectedBranch()) != null
        fetchBeforeCompareCheckBox.isSelected = isRemoteBranch
        fetchBeforeCompareCheckBox.isEnabled = isRemoteBranch
        fetchBeforeCompareCheckBox.toolTipText =
            if (isRemoteBranch) "Fetch the selected remote before comparing."
            else "Fetch is available only for remote branches."
    }

    private fun applyTableFilter() {
        val query = searchField.text.trim()
        val filter = fileFilterBox.selectedItem as? FileFilter ?: FileFilter.ALL
        tableSorter.rowFilter =
            object : RowFilter<DiffTableModel, Int>() {
                override fun include(entry: Entry<out DiffTableModel, out Int>): Boolean {
                    val result = tableModel.getResult(entry.identifier) ?: return false
                    val statusMatches =
                        when (filter) {
                            FileFilter.CHANGED -> result.changed
                            FileFilter.UNCHANGED -> !result.changed
                            FileFilter.ALL -> true
                        }
                    return statusMatches && result.path.contains(query, ignoreCase = true)
                }
            }
    }

    private fun openSelectedFileDiff() {
        val viewRow = table.selectedRow
        if (viewRow >= 0) openFileDiff(table.convertRowIndexToModel(viewRow))
    }

    private fun openFileInProject(modelRow: Int) {
        val path = tableModel.getResult(modelRow)?.path ?: return
        val root = projectRoot() ?: return
        val file = root.findFileByRelativePath(path)?.takeUnless { it.isDirectory } ?: return
        ProjectView.getInstance(project).select(null, file, true)
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    private fun showBranchCompletions() {
        if (project.isDisposed || isDisposed) return
        val editor = branchTextField.editor ?: return
        CodeCompletionHandlerBase(CompletionType.BASIC).invokeCompletion(project, editor)
    }

    private fun saveContextDocuments() {
        val projectRoot = projectRoot() ?: return
        val documentManager = FileDocumentManager.getInstance()
        paths.forEach { path ->
            val file = projectRoot.findFileByRelativePath(path) ?: return@forEach
            val document = documentManager.getCachedDocument(file) ?: return@forEach
            if (documentManager.isDocumentUnsaved(document)) documentManager.saveDocument(document)
        }
    }

    private fun openFileDiff(modelRow: Int) {
        val branch = comparedBranch ?: return
        val path = tableModel.getResult(modelRow)?.path ?: return
        object : Task.Backgroundable(project, "Loading file comparison", false) {
            private var branchContent: ByteArray? = null
            private var error: String? = null

            override fun run(indicator: ProgressIndicator) {
                try {
                    branchContent = diffService.readFileAtBranch(project, repository, branch, path)
                } catch (ex: ProcessCanceledException) {
                    throw ex
                } catch (ex: Exception) {
                    error = ex.message?.takeIf { it.isNotBlank() } ?: "Unexpected Git error"
                }
            }

            override fun onSuccess() {
                if (project.isDisposed || this@GitContextDiffDialog.isDisposed) return
                error?.let {
                    statusLabel.text = "Failed to load file comparison: $it"
                    return
                }
                val currentFile = projectRoot()?.findFileByRelativePath(path)
                val contentFactory = DiffContentFactory.getInstance()
                val branchDiffContent =
                    branchContent?.let {
                        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(path)
                        contentFactory.createFromBytes(project, it, fileType, path.substringAfterLast('/'))
                    } ?: contentFactory.createEmpty()
                val currentDiffContent =
                    currentFile?.let { contentFactory.create(project, it) }
                        ?: contentFactory.createEmpty()
                DiffManager.getInstance()
                    .showDiff(
                        project,
                        SimpleDiffRequest(
                            path,
                            branchDiffContent,
                            currentDiffContent,
                            branch,
                            "$currentBranch (Working Tree)",
                        ),
                    )
            }
        }.queue()
    }

    private fun projectRoot() =
        project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }

    private companion object {
        const val LOCATION_COLUMN = 2
    }

    private class DiffTableModel : AbstractTableModel() {
        private var results: List<FileDiffResult> = emptyList()

        fun setResults(newResults: List<FileDiffResult>) {
            results = newResults
            fireTableDataChanged()
        }

        fun getResult(row: Int) = results.getOrNull(row)

        override fun getRowCount() = results.size

        override fun getColumnCount() = 3

        override fun getColumnName(column: Int) =
            when (column) {
                0 -> "Status"
                1 -> "File"
                else -> "Location"
            }

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any =
            when (columnIndex) {
                0 -> results[rowIndex].changed
                1 -> results[rowIndex].path.substringAfterLast('/')
                else -> results[rowIndex].path.substringBeforeLast('/', "")
            }

        override fun getColumnClass(columnIndex: Int): Class<*> =
            if (columnIndex == 0) Boolean::class.java else String::class.java
    }

    private class CaseInsensitiveBranchCompletionProvider(branches: List<String>) :
        TextFieldWithAutoCompletion.StringsCompletionProvider(branches, null) {
        override fun createPrefixMatcher(prefix: String): PrefixMatcher =
            CaseInsensitiveContainsMatcher(prefix)
    }

    private class CaseInsensitiveContainsMatcher(prefix: String) : PrefixMatcher(prefix) {
        override fun prefixMatches(name: String): Boolean = name.contains(prefix, ignoreCase = true)

        override fun cloneWithPrefix(prefix: String): PrefixMatcher =
            CaseInsensitiveContainsMatcher(prefix)
    }

    private enum class FileFilter(private val label: String) {
        CHANGED("Changed"),
        UNCHANGED("Unchanged"),
        ALL("All");

        override fun toString() = label
    }

    private class StatusCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            val changed = value == true
            text = if (changed) "●  Changed" else "—  Unchanged"
            if (!isSelected) foreground = if (changed) JBColor(0xCC7832, 0xCC7832) else JBColor.GRAY
            return this
        }
    }

    private class FileCellRenderer(private val model: DiffTableModel) : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            val result = model.getResult(table.convertRowIndexToModel(row))
            toolTipText = result?.path
            font = font.deriveFont(if (result?.changed == true) Font.BOLD else Font.PLAIN)
            return this
        }
    }

    private class LocationCellRenderer(private val model: DiffTableModel) : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            super.getTableCellRendererComponent(
                table,
                value,
                isSelected,
                hasFocus,
                row,
                column,
            )
            val result = model.getResult(table.convertRowIndexToModel(row))
            val location = value?.toString().orEmpty().ifEmpty { "Project root" }
            toolTipText = result?.path
            if (!isSelected) foreground = JBColor.GRAY
            text = shortenPath(location, table.columnModel.getColumn(column).width - JBUI.scale(12))
            return this
        }

        private fun shortenPath(path: String, availableWidth: Int): String {
            val metrics = getFontMetrics(font)
            if (path.isEmpty() || availableWidth <= 0 || metrics.stringWidth(path) <= availableWidth)
                return path
            val separators = path.indices.filter { path[it] == '/' }
            val tailStart =
                when {
                    separators.size >= 2 -> separators[separators.lastIndex - 1] + 1
                    separators.isNotEmpty() -> separators.last() + 1
                    else -> 0
                }
            var tail = path.substring(tailStart)
            var candidate = "…/$tail"
            if (metrics.stringWidth(candidate) > availableWidth) {
                tail = path.substringAfterLast('/')
                candidate = "…/$tail"
            }
            if (metrics.stringWidth(candidate) > availableWidth) return candidate

            val prefixSource = path.substring(0, tailStart)
            var prefixLength = 0
            while (prefixLength < prefixSource.length) {
                val next = prefixSource.substring(0, prefixLength + 1) + candidate
                if (metrics.stringWidth(next) > availableWidth) break
                prefixLength++
            }
            return prefixSource.substring(0, prefixLength) + candidate
        }
    }
}
