package com.aicode.feature.terminal.action

import org.junit.Assert.assertEquals
import org.junit.Test

class AddCurrentCommandActionTest {
    private val action = AddCurrentCommandAction()

    @Test
    fun `uses clipboard text as initial value`() {
        assertEquals("git status", action.initialValue("git status"))
    }

    @Test
    fun `uses empty initial value without clipboard text`() {
        assertEquals("", action.initialValue(null))
        assertEquals("", action.initialValue("  "))
    }
}
