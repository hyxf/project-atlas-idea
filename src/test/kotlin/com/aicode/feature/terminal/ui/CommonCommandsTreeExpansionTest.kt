package com.aicode.feature.terminal.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

class CommonCommandsTreeExpansionTest {
    @Test
    fun `expand and collapse process every group and command row`() {
        val root = DefaultMutableTreeNode("root")
        val groups = listOf("Git", "Build").map { name ->
            DefaultMutableTreeNode(name).apply { add(DefaultMutableTreeNode("command")) }
        }
        groups.forEach(root::add)
        val tree = JTree(root).apply { isRootVisible = false }

        expandAllTreeRows(tree)

        assertEquals(4, tree.rowCount)
        groups.forEach { assertTrue(tree.isExpanded(TreePath(arrayOf(root, it)))) }

        collapseAllTreeRows(tree)

        assertEquals(2, tree.rowCount)
        groups.forEach { assertFalse(tree.isExpanded(TreePath(arrayOf(root, it)))) }
    }
}
