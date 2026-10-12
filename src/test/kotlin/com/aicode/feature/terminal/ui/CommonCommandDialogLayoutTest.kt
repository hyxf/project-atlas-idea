package com.aicode.feature.terminal.ui

import com.aicode.feature.terminal.model.CommonCommand
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBTextArea
import java.awt.Component
import java.awt.Container
import javax.swing.JComponent
import javax.swing.JViewport

class CommonCommandDialogLayoutTest : BasePlatformTestCase() {
    fun testCommandEditorKeepsCommandAndDetailsVisible() {
        val dialog = construct("CommandEditorDialog", arrayOf(String::class.java, CommonCommand::class.java),
            "Edit Command", CommonCommand("git status"))
        val panel = centerPanel(dialog)
        layout(panel)

        val commandEditor = field(dialog, "commandEditor") as JBTextArea
        val commandViewport = commandEditor.parent as JViewport
        val descriptionEditor = field(dialog, "descriptionEditor") as JBTextArea
        val descriptionViewport = descriptionEditor.parent as JViewport
        val tagsEditor = field(dialog, "tagsEditor") as JBTextArea
        val tagsViewport = tagsEditor.parent as JViewport
        assertEquals(4, commandEditor.rows)
        assertEquals(4, descriptionEditor.rows)
        assertEquals(3, tagsEditor.rows)
        assertTrue(commandViewport.height >= commandEditor.getFontMetrics(commandEditor.font).height * 4)
        assertTrue(descriptionViewport.height >= descriptionEditor.getFontMetrics(descriptionEditor.font).height * 4)
        assertTrue(tagsViewport.height >= tagsEditor.getFontMetrics(tagsEditor.font).height * 3)
    }

    private fun construct(name: String, types: Array<Class<*>>, vararg args: Any): Any {
        val type = Class.forName("com.aicode.feature.terminal.ui.$name")
        return type.getDeclaredConstructor(*types).apply { isAccessible = true }.newInstance(*args)
    }

    private fun centerPanel(dialog: Any): JComponent = dialog.javaClass.getDeclaredMethod("createCenterPanel")
        .apply { isAccessible = true }.invoke(dialog) as JComponent

    private fun field(dialog: Any, name: String): Any = dialog.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(dialog)

    private fun layout(component: JComponent) {
        component.size = component.preferredSize
        layoutChildren(component)
    }

    private fun layoutChildren(component: Component) {
        if (component is Container) {
            component.doLayout()
            component.components.forEach(::layoutChildren)
        }
    }
}
