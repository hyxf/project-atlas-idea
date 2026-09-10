package com.aicode.feature.terminal.util

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import javax.swing.JPanel

class TerminalTextInserterTest : BasePlatformTestCase() {
    fun testExplicitTargetTakesPriorityOverSelectedContent() {
        val target = createContent("target")
        val selected = createContent("selected")

        assertSame(target, TerminalTextInserter.selectContent(target, selected) { true })
    }

    fun testFallsBackToSelectedContentWithoutExplicitTarget() {
        val selected = createContent("selected")

        assertSame(selected, TerminalTextInserter.selectContent(null, selected) { true })
    }

    fun testDoesNotFallBackWhenExplicitTargetIsNoLongerAvailable() {
        val staleTarget = createContent("stale")
        val selected = createContent("selected")

        assertNull(TerminalTextInserter.selectContent(staleTarget, selected) { false })
    }

    fun testWritesTextIntoExplicitTargetContent() {
        val target = createContent("target")
        var writtenContent: Content? = null
        var writtenText: String? = null

        val inserted = TerminalTextInserter.insertInto(target, "git status") { content, text ->
            writtenContent = content
            writtenText = text
            true
        }

        assertTrue(inserted)
        assertSame(target, writtenContent)
        assertEquals("git status", writtenText)
    }

    fun testPropagatesWriterFailure() {
        val target = createContent("target")

        assertFalse(TerminalTextInserter.insertInto(target, "git status") { _, _ -> false })
    }

    private fun createContent(name: String): Content =
        ContentFactory.getInstance().createContent(JPanel(), name, false)
}
