package com.aicode.feature.git.service

import org.junit.Assert.assertEquals
import org.junit.Test

class GitTagVersionServiceTest {
    @Test
    fun choosesLatestTagByNumericVersion() {
        val c =
            GitTagVersionService.calculateCandidates(
                listOf("test", "v1.9.9", "v1.10.0", "v1.10.0-beta")
            )
        assertEquals("v1.10.0", c.current.toTag())
        assertEquals("v2.0.0", c.major.toTag())
        assertEquals("v1.11.0", c.minor.toTag())
        assertEquals("v1.10.1", c.patch.toTag())
    }

    @Test
    fun usesZeroBaselineWhenNoReleaseTagExists() {
        val c = GitTagVersionService.calculateCandidates(listOf("test", "v1.0", "release-1.0.0"))
        assertEquals("v0.0.0", c.current.toTag())
        assertEquals("v1.0.0", c.major.toTag())
        assertEquals("v0.1.0", c.minor.toTag())
        assertEquals("v0.0.1", c.patch.toTag())
    }
}
