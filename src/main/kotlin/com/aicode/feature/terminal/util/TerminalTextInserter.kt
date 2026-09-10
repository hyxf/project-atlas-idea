package com.aicode.feature.terminal.util

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.project.Project
import com.intellij.terminal.ui.TerminalWidget
import com.intellij.ui.content.Content
import java.io.UncheckedIOException
import java.lang.reflect.InvocationTargetException
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

object TerminalTextInserter {
    fun insert(project: Project, text: String, targetContent: Content? = null): Boolean {
        val manager = TerminalToolWindowManager.getInstance(project)
        val contentManager = manager.toolWindow?.contentManager ?: return false
        val content = selectContent(targetContent, contentManager.selectedContent) { it.manager === contentManager }
            ?: return false
        return insertInto(content, text) { selectedContent, selectedText ->
            sendToTerminal(project, selectedContent, selectedText)
        }
    }

    internal fun insertInto(
        content: Content,
        text: String,
        sender: (Content, String) -> Boolean,
    ): Boolean = sender(content, text)

    internal fun selectContent(
        targetContent: Content?,
        selectedContent: Content?,
        belongsToTerminal: (Content) -> Boolean,
    ): Content? =
        if (targetContent != null) targetContent.takeIf(belongsToTerminal) else selectedContent

    private fun sendToTerminal(project: Project, content: Content, text: String): Boolean {
        val widget = TerminalToolWindowManager.findWidgetByContent(content)
        if (!sendToReworkedTerminal(project, content, text)) {
            if (widget == null) return false
            widget.ttyConnectorAccessor.executeWithTtyConnector { connector ->
                try {
                    connector.write(text)
                } catch (ex: java.io.IOException) {
                    throw UncheckedIOException(ex)
                }
            }
        }
        if (widget != null) widget.requestFocus() else content.component.requestFocusInWindow()
        return true
    }

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

    private fun findReworkedTerminalView(project: Project, content: Content): Any? {
        try {
            val managerClass = Class.forName(TERMINAL_TABS_MANAGER_CLASS)
            val tabClass = Class.forName(TERMINAL_TAB_CLASS)
            val manager = managerClass.getMethod("getInstance", Project::class.java).invoke(null, project)
            val tabs = managerClass.getMethod("getTabs").invoke(manager)
            val getContent = tabClass.getMethod("getContent")
            val getView = tabClass.getMethod("getView")
            if (tabs is Iterable<*>) {
                for (tab in tabs) if (getContent.invoke(tab) === content) return getView.invoke(tab)
            }
        } catch (_: ClassNotFoundException) {
        } catch (_: NoSuchMethodException) {
        }
        return DataManager.getInstance().getDataContext(content.component).getData(TERMINAL_VIEW_KEY)
    }

    private const val TERMINAL_VIEW_CLASS = "com.intellij.terminal.frontend.view.TerminalView"
    private const val TERMINAL_TAB_CLASS = "com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab"
    private const val TERMINAL_TABS_MANAGER_CLASS =
        "com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager"
    private val TERMINAL_VIEW_KEY = DataKey.create<Any>("TerminalView")
}
