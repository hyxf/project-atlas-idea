package com.aicode.feature.git.util

import com.aicode.feature.git.util.GitRemoteUrlResolver.HostingPlatform
import com.aicode.feature.git.util.GitRemoteUrlResolver.PathType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitRemoteUrlResolverTest {
    @Test
    fun selectsGitLabRoutesForDirectoryAndFileTargets() {
        val remote = "git@gitlab.example.com:team/project.git"

        assertEquals(
            "https://gitlab.example.com/team/project/-/tree/feature/test/src/main",
            GitRemoteUrlResolver.toWebUrl(
                remote,
                "feature/test",
                "src/main",
                HostingPlatform.GITLAB,
                PathType.DIRECTORY,
            ),
        )
        assertEquals(
            "https://gitlab.example.com/team/project/-/blob/feature/test/src/App.kt",
            GitRemoteUrlResolver.toWebUrl(
                remote,
                "feature/test",
                "src/App.kt",
                HostingPlatform.GITLAB,
                PathType.FILE,
            ),
        )
    }

    @Test
    fun refusesBranchUrlsWhenHostingPlatformIsUnknown() {
        assertNull(
            GitRemoteUrlResolver.toWebUrl(
                "ssh://git@example.internal/team/project.git",
                "main",
                null,
                HostingPlatform.UNKNOWN,
                PathType.DIRECTORY,
            )
        )
    }
}
