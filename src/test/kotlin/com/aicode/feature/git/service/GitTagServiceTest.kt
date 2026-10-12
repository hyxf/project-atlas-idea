package com.aicode.feature.git.service

import org.junit.Assert.*
import org.junit.Test

class GitTagServiceTest {
    @Test
    fun matchesOnlyTheExactRemoteTagRef() {
        assertTrue(GitTagService.hasRef("0123456789abcdef\trefs/tags/v1.6.4", "refs/tags/v1.6.4"))
        assertFalse(GitTagService.hasRef("0123456789abcdef\trefs/tags/v1.6.40", "refs/tags/v1.6.4"))
        assertFalse(
            GitTagService.hasRef("0123456789abcdef\trefs/tags/v1.6.4^{}", "refs/tags/v1.6.4")
        )
    }

    @Test
    fun extractsOnlyDirectTagRefs() {
        assertEquals(
            "v1.6.4",
            GitTagService.parseTagRef("0123456789abcdef\trefs/tags/v1.6.4"),
        )
        assertNull(GitTagService.parseTagRef("0123456789abcdef\trefs/tags/v1.6.4^{}"))
        assertNull(GitTagService.parseTagRef("0123456789abcdef\trefs/heads/main"))
    }

    @Test
    fun parsesAheadAndBehindCounts() {
        assertEquals(3 to 2, GitTagService.parseDivergence("3\t2"))
        assertNull(GitTagService.parseDivergence("invalid"))
    }

    @Test
    fun pushesTheConfirmedCommitDirectlyToTheRemoteTagRef() {
        assertEquals(
            "0123456789abcdef:refs/tags/v1.9.10",
            GitTagService.tagPushRefspec("0123456789abcdef", "v1.9.10"),
        )
    }
}
