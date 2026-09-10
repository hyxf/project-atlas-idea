package com.aicode.feature.git.util

import com.aicode.feature.git.model.ChangelogData
import com.aicode.feature.git.model.ChangelogData.*
import org.junit.Assert.*
import org.junit.Test

class ChangelogBuilderTest {
    @Test
    fun groupsConventionalCommitsAndKeepsUnknownSubjects() {
        val m =
            ChangelogBuilder.create(
                ChangelogData(
                    listOf(
                        Release(
                            "Unreleased",
                            "",
                            listOf(
                                Commit("1", "feat(ui): add preview"),
                                Commit("2", "fix: avoid overwriting files"),
                                Commit("3", "update documentation"),
                            ),
                            true,
                        )
                    )
                )
            )
        assertTrue(m.contains("### Added\n\n- **ui:** add preview"))
        assertTrue(m.contains("### Fixed\n\n- avoid overwriting files"))
        assertTrue(m.contains("### Other Changes\n\n- update documentation"))
        assertFalse(m.contains("feat(ui)"))
    }

    @Test
    fun rendersReleaseDatesAndOmitsEmptyCategories() {
        val m =
            ChangelogBuilder.create(
                ChangelogData(
                    listOf(
                        Release("Unreleased", "", listOf(), true),
                        Release(
                            "1.6.0",
                            "2026-08-13",
                            listOf(Commit("1", "perf: speed up history loading")),
                            false,
                        ),
                    )
                )
            )
        assertTrue(m.contains("## [Unreleased]"))
        assertTrue(m.contains("## [1.6.0] - 2026-08-13"))
        assertTrue(m.contains("### Changed\n\n- speed up history loading"))
        assertFalse(m.contains("### Added"))
    }

    @Test
    fun updatesOnlyManagedSection() {
        val d =
            ChangelogData(
                listOf(Release("Unreleased", "", listOf(Commit("1", "fix: refreshed")), true))
            )
        val e =
            "# Custom title\n\nManual introduction.\n\n${ChangelogBuilder.START_MARKER}\nold generated content\n${ChangelogBuilder.END_MARKER}\n\nManual footer.\n"
        val u = ChangelogBuilder.update(e, d)
        assertTrue(u.startsWith("# Custom title\n\nManual introduction."))
        assertTrue(u.contains("- refreshed"))
        assertFalse(u.contains("old generated content"))
        assertTrue(u.endsWith("\n\nManual footer.\n"))
    }

    @Test
    fun rejectsFilesWithoutCompleteMarkers() {
        assertFalse(ChangelogBuilder.hasManagedSection("# Changelog"))
        assertThrows(IllegalArgumentException::class.java) {
            ChangelogBuilder.hasManagedSection(ChangelogBuilder.START_MARKER)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChangelogBuilder.hasManagedSection(ChangelogBuilder.END_MARKER)
        }
    }

    @Test
    fun rejectsDuplicateOrReversedMarkers() {
        val x =
            "${ChangelogBuilder.START_MARKER}\n${ChangelogBuilder.START_MARKER}\n${ChangelogBuilder.END_MARKER}"
        assertThrows(IllegalArgumentException::class.java) { ChangelogBuilder.hasManagedSection(x) }
        assertThrows(IllegalArgumentException::class.java) {
            ChangelogBuilder.update(x, ChangelogData(listOf()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChangelogBuilder.hasManagedSection(
                "${ChangelogBuilder.END_MARKER}\n${ChangelogBuilder.START_MARKER}"
            )
        }
    }

    @Test
    fun escapesManagedMarkersFromCommitSubjects() {
        val m =
            ChangelogBuilder.create(
                ChangelogData(
                    listOf(
                        Release(
                            "Unreleased",
                            "",
                            listOf(Commit("1", "fix: handle ${ChangelogBuilder.END_MARKER}")),
                            true,
                        )
                    )
                )
            )
        assertTrue(ChangelogBuilder.hasManagedSection(m))
        assertEquals(1, occurrences(m, ChangelogBuilder.START_MARKER))
        assertEquals(1, occurrences(m, ChangelogBuilder.END_MARKER))
        assertTrue(m.contains("&lt;!-- aicode-changelog:end --&gt;"))
    }

    @Test
    fun mapsBreakingAndMaintenanceCommits() {
        assertEquals("Added", ChangelogBuilder.parse("feat!: breaking feature").category)
        assertEquals("Other Changes", ChangelogBuilder.parse("chore(ci): update runner").category)
        assertEquals(
            "**ci:** update runner",
            ChangelogBuilder.parse("chore(ci): update runner").description,
        )
        assertEquals("Removed", ChangelogBuilder.parse("revert: remove feature").category)
    }

    private fun occurrences(text: String, value: String): Int {
        var count = 0
        var index = 0
        while (text.indexOf(value, index).also { index = it } >= 0) {
            count++
            index += value.length
        }
        return count
    }
}
