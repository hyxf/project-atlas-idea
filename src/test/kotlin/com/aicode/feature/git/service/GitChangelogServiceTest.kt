package com.aicode.feature.git.service

import org.junit.Assert.*
import org.junit.Test

class GitChangelogServiceTest {
    @Test
    fun parsesSemanticTagAndCreationDate() {
        val tag = GitChangelogService.parseTag("v1.6.7\t2026-08-13")!!
        assertEquals("v1.6.7", tag.name)
        assertEquals("2026-08-13", tag.date)
        assertEquals("v1.6.7", tag.version.toTag())
    }

    @Test
    fun ignoresTagsOutsideSupportedSemanticVersionFormat() {
        assertNull(GitChangelogService.parseTag("release-1.6.7\t2026-08-13"))
        assertNull(GitChangelogService.parseTag("v1.6.7-beta\t2026-08-13"))
        assertNull(GitChangelogService.parseTag("v1.6\t2026-08-13"))
    }

    @Test
    fun parsesCommitHashAndPreservesTabsInSubject() {
        val c = GitChangelogService.parseCommit("abc123\tfeat: support\ttabs")!!
        assertEquals("abc123", c.hash)
        assertEquals("feat: support\ttabs", c.subject)
    }

    @Test
    fun ignoresMalformedCommitOutput() {
        assertNull(GitChangelogService.parseCommit("missing separator"))
        assertNull(GitChangelogService.parseCommit("abc123\t"))
    }
}
