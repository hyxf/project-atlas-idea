package com.aicode.feature.git.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class BranchSelectionTest {
    @Test
    fun `prefers master then main then test`() {
        assertEquals("master", BranchSelection.preferredBranch(listOf("test", "main", "master")))
        assertEquals("main", BranchSelection.preferredBranch(listOf("test", "main")))
        assertEquals("test", BranchSelection.preferredBranch(listOf("feature/demo", "test")))
    }

    @Test
    fun `matches preferred names without changing their original case`() {
        assertEquals("Main", BranchSelection.preferredBranch(listOf("feature/demo", "Main")))
    }

    @Test
    fun `returns empty when no preferred branch exists`() {
        assertEquals("", BranchSelection.preferredBranch(listOf("develop", "feature/demo")))
    }
}
