package com.aicode.feature.terminal.ui

import com.aicode.feature.terminal.model.CommonCommand
import com.aicode.feature.terminal.model.CommonCommandVariable
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Component
import java.awt.Container
import javax.swing.JComponent
import javax.swing.JViewport

class CommonCommandDialogLayoutTest : BasePlatformTestCase() {
    fun testCommandEditorKeepsCommandAndVariablesVisible() {
        val dialog = construct("CommandEditorDialog", arrayOf(String::class.java, CommonCommand::class.java),
            "Edit Command", CommonCommand("git status", variables = listOf(CommonCommandVariable("branch", "text", "Branch"))))
        val panel = centerPanel(dialog)
        layout(panel)

        val variableList = field(dialog, "variablesList") as JComponent
        val commandEditor = field(dialog, "commandEditor") as JComponent
        val commandViewport = commandEditor.parent as JViewport
        assertTrue(commandViewport.height >= 70)
        assertTrue(commandViewport.height <= 130)
        assertTrue(variableList.height >= 90)
        assertTrue(variableList.width >= 250)
    }

    fun testVariableEditorKeepsListAndFieldsVisible() {
        val dialog = construct("VariablesDialog", arrayOf(List::class.java, String::class.java),
            listOf(CommonCommandVariable("branch", "select", "Branch", options = listOf("main", "dev"))), "Command Variables")
        val panel = centerPanel(dialog)
        layout(panel)

        val list = field(dialog, "list") as JComponent
        val name = field(dialog, "nameField") as JComponent
        val options = field(dialog, "optionsField") as JComponent
        assertTrue(list.width >= 150)
        assertTrue(name.width >= 200)
        assertTrue(options.isVisible)
        assertTrue(options.height >= 30)
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
