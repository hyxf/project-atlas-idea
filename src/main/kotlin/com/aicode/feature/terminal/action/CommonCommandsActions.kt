package com.aicode.feature.terminal.action

import com.aicode.feature.terminal.ui.CommonCommandsPanel
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import javax.swing.Icon

abstract class CommonCommandsAction(
    text: String,
    icon: Icon,
    private val needsSelection: Boolean = false,
) : AnAction(text, null, icon), DumbAware {
    final override fun actionPerformed(e: AnActionEvent) {
        e.project?.let(CommonCommandsPanel::forProject)?.let(::perform)
    }
    final override fun update(e: AnActionEvent) {
        val panel = e.project?.let(CommonCommandsPanel::forProject)
        e.presentation.isEnabled = panel != null && (!needsSelection || panel.hasSelection())
    }
    final override fun getActionUpdateThread() = ActionUpdateThread.EDT
    abstract fun perform(panel: CommonCommandsPanel)
}

class AddCommonCommandAction : CommonCommandsAction("Add Command", AllIcons.General.Add) { override fun perform(panel: CommonCommandsPanel) = panel.addSelected() }
class ManageCommonCommandVariablesAction : CommonCommandsAction("Manage Global Variables", AllIcons.General.Settings) { override fun perform(panel: CommonCommandsPanel) = panel.editGlobalVariables() }
class OpenCommonCommandsJsonAction : CommonCommandsAction("Edit commoncmd.json", AllIcons.Actions.Edit) { override fun perform(panel: CommonCommandsPanel) = panel.openRawJson() }
class RefreshCommonCommandsAction : CommonCommandsAction("Refresh", AllIcons.Actions.Refresh) { override fun perform(panel: CommonCommandsPanel) = panel.refresh() }
class ExpandCommonCommandsAction : CommonCommandsAction("Expand All", AllIcons.Actions.Expandall) { override fun perform(panel: CommonCommandsPanel) = panel.expandAll() }
class CollapseCommonCommandsAction : CommonCommandsAction("Collapse All", AllIcons.Actions.Collapseall) { override fun perform(panel: CommonCommandsPanel) = panel.collapseAll() }
class InsertCommonCommandAction : CommonCommandsAction("Insert into Terminal", AllIcons.Actions.Edit, true) { override fun perform(panel: CommonCommandsPanel) = panel.insertSelected() }
class RunCommonCommandAction : CommonCommandsAction("Run Command", AllIcons.Actions.Execute, true) { override fun perform(panel: CommonCommandsPanel) = panel.runSelected() }
class EditCommonCommandAction : CommonCommandsAction("Edit Command", AllIcons.Actions.Edit, true) { override fun perform(panel: CommonCommandsPanel) = panel.editSelected() }
class EditCommonCommandTagsAction : CommonCommandsAction("Edit Tags", AllIcons.General.Settings, true) { override fun perform(panel: CommonCommandsPanel) = panel.editTagsSelected() }
class DeleteCommonCommandAction : CommonCommandsAction("Delete Command", AllIcons.General.Remove, true) { override fun perform(panel: CommonCommandsPanel) = panel.deleteSelected() }
