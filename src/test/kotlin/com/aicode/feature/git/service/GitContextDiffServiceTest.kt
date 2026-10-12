package com.aicode.feature.git.service

import org.junit.Assert.assertEquals
import org.junit.Test

class GitContextDiffServiceTest {
    @Test
    fun `finds the remote owning a remote tracking branch`() {
        assertEquals(
            "company/origin",
            GitContextDiffService.findRemoteName(
                "company/origin/main",
                listOf("origin", "company/origin"),
            ),
        )
        assertEquals(null, GitContextDiffService.findRemoteName("main", listOf("origin")))
    }

    @Test
    fun `marks only context paths returned by git as changed`() {
        val results =
            GitContextDiffService.buildResults(
                listOf("src/Main.kt", "README.md", "docs/guide.md"),
                listOf("src/Main.kt", "untracked.txt", "docs\\guide.md"),
            )

        assertEquals(listOf(true, false, true), results.map { it.changed })
    }

    @Test
    fun `keeps input order and handles dot prefix`() {
        val results =
            GitContextDiffService.buildResults(
                listOf("b.txt", "a.txt"),
                listOf("./a.txt"),
            )

        assertEquals(listOf("b.txt", "a.txt"), results.map { it.path })
        assertEquals(listOf(false, true), results.map { it.changed })
    }

    @Test
    fun `matches project paths when project is below repository root`() {
        val results =
            GitContextDiffService.buildResults(
                listOf("src/Main.kt", "README.md"),
                listOf("apps/plugin/src/Main.kt"),
                "apps/plugin",
            )

        assertEquals(listOf(true, false), results.map { it.changed })
    }

    @Test
    fun `parses zero terminated paths without git quoting`() {
        val output = "src/中文 文件.kt\u0000path/with\nnewline.txt\u0000".toByteArray()

        assertEquals(
            listOf("src/中文 文件.kt", "path/with\nnewline.txt"),
            GitContextDiffService.parseZeroTerminatedPaths(output),
        )
    }
}
