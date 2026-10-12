package com.aicode.feature.terminal.action

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import javax.swing.JPanel

class SelectCommonCommandActionTest : BasePlatformTestCase() {
    fun testCommonCommandsToolWindowActionsAreRegistered() {
        val manager = ActionManager.getInstance()
        for (name in listOf("Add", "Variables", "Json", "Refresh", "Expand", "Collapse", "Insert", "Run", "Edit", "Tags", "Delete")) {
            assertNotNull(manager.getAction("com.aicode.commoncommands.$name"))
        }
        for (name in listOf("Add", "Variables", "Json", "Refresh", "Expand", "Collapse")) {
            assertNotNull(manager.getAction("com.aicode.commoncommands.$name").templatePresentation.icon)
        }
        assertNotNull(manager.getAction("com.aicode.action.OpenCommonCommandsAction"))
    }
    fun testResolvesContentFromClickedToolWindowTabComponent() {
        val content = createContent("clicked")
        val tab = ContentComponent(content)
        val child = JPanel()
        tab.add(child)

        assertSame(content, SelectCommonCommandAction().resolveTargetContent(child))
    }

    fun testReturnsNullOutsideToolWindowTab() {
        assertNull(SelectCommonCommandAction().resolveTargetContent(JPanel()))
    }

    private fun createContent(name: String): Content =
        ContentFactory.getInstance().createContent(JPanel(), name, false)

    private class ContentComponent(private val storedContent: Content) : JPanel() {
        fun getContent(): Content = storedContent
    }
}
