package com.aicode.feature.ai.action

import com.aicode.feature.ai.service.AICodeFileService
import com.intellij.ide.DataManager
import com.intellij.notification.*
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.terminal.ui.TerminalWidget
import com.intellij.ui.content.Content
import java.io.UncheckedIOException
import java.lang.reflect.InvocationTargetException
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

class AddToTerminalAction : AnAction(), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) ?: return
        if (files.isEmpty()) return
        val paths = getRelativePaths(project, files)
        if (paths.size != files.size) {
            show(
                project,
                "Only files inside the project root can be added to Terminal",
                NotificationType.WARNING,
            )
            return
        }
        val toolWindow = TerminalToolWindowManager.getInstance(project).toolWindow
        val content = toolWindow?.contentManager?.selectedContent
        if (content == null) {
            show(project, NO_TERMINAL_MESSAGE, NotificationType.WARNING)
            return
        }
        val widget = TerminalToolWindowManager.findWidgetByContent(content)
        try {
            if (
                !sendToSelectedTerminal(
                    project,
                    content,
                    widget,
                    paths.joinToString(" ") { "@$it" },
                )
            ) {
                show(project, NO_TERMINAL_MESSAGE, NotificationType.WARNING)
                return
            }
        } catch (ex: ReflectiveOperationException) {
            failure(project, ex)
            return
        } catch (ex: UncheckedIOException) {
            failure(project, ex)
            return
        }
        toolWindow.activate {
            if (widget != null) widget.requestFocus() else content.component.requestFocusInWindow()
        }
    }

    @Throws(ReflectiveOperationException::class)
    private fun sendToSelectedTerminal(
        project: Project,
        content: Content,
        widget: TerminalWidget?,
        text: String,
    ): Boolean {
        if (sendToReworkedTerminal(project, content, text)) return true
        if (widget == null) return false
        widget.ttyConnectorAccessor.executeWithTtyConnector { connector ->
            try {
                connector.write(text)
            } catch (ex: java.io.IOException) {
                throw UncheckedIOException(ex)
            }
        }
        return true
    }

    @Throws(ReflectiveOperationException::class)
    private fun sendToReworkedTerminal(project: Project, content: Content, text: String): Boolean {
        try {
            val clazz = Class.forName(TERMINAL_VIEW_CLASS)
            val view = findReworkedTerminalView(project, content) ?: return false
            clazz.getMethod("sendText", String::class.java).invoke(view, text)
            return true
        } catch (_: ClassNotFoundException) {
            return false
        } catch (ex: InvocationTargetException) {
            val cause = ex.cause
            if (cause is RuntimeException) throw cause
            throw ex
        }
    }

    @Throws(ReflectiveOperationException::class)
    private fun findReworkedTerminalView(project: Project, content: Content): Any? {
        try {
            val managerClass = Class.forName(TERMINAL_TABS_MANAGER_CLASS)
            val tabClass = Class.forName(TERMINAL_TAB_CLASS)
            val manager =
                managerClass.getMethod("getInstance", Project::class.java).invoke(null, project)
            val tabs = managerClass.getMethod("getTabs").invoke(manager)
            val getContent = tabClass.getMethod("getContent")
            val getView = tabClass.getMethod("getView")
            if (tabs is Iterable<*>)
                for (tab in tabs) if (getContent.invoke(tab) === content) return getView.invoke(tab)
        } catch (_: ClassNotFoundException) {} catch (_: NoSuchMethodException) {}
        return DataManager.getInstance()
            .getDataContext(content.component)
            .getData(TERMINAL_VIEW_KEY)
    }

    private fun failure(project: Project, ex: Exception) {
        LOG.warn("Failed to insert paths into the selected terminal", ex)
        show(project, getFailureMessage(ex), NotificationType.ERROR)
    }

    private fun getFailureMessage(exception: Exception): String {
        var cause: Throwable = exception
        while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
        return cause.message
            ?.takeUnless { it.isBlank() }
            ?.let { "Failed to add the file path to Terminal: $it" }
            ?: "Failed to add the file path to Terminal"
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        e.presentation.isEnabledAndVisible =
            project != null &&
                files != null &&
                files.isNotEmpty() &&
                getRelativePaths(project, files).size == files.size
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    private fun getRelativePaths(project: Project, files: Array<VirtualFile>): List<String> {
        val service = AICodeFileService.getInstance(project)
        val result = ArrayList<String>(files.size)
        for (file in files) {
            val path = service.getRelativePath(file)
            if (path.isNullOrEmpty()) break
            result.add(path)
        }
        return result
    }

    private fun show(project: Project, content: String, type: NotificationType) =
        Notifications.Bus.notify(
            Notification("AICode", "Project Atlas", content, type),
            project,
        )

    companion object {
        private val LOG = Logger.getInstance(AddToTerminalAction::class.java)
        private const val TERMINAL_VIEW_CLASS = "com.intellij.terminal.frontend.view.TerminalView"
        private const val TERMINAL_TAB_CLASS =
            "com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab"
        private const val TERMINAL_TABS_MANAGER_CLASS =
            "com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager"
        private val TERMINAL_VIEW_KEY = DataKey.create<Any>("TerminalView")
        private const val NO_TERMINAL_MESSAGE = "Open a terminal session before adding a file path"
    }
}
