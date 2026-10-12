package com.aicode.feature.git.util

import com.aicode.feature.git.model.ChangelogData
import com.aicode.feature.git.model.ChangelogData.Commit

object ChangelogBuilder {
    const val START_MARKER = "<!-- aicode-changelog:start -->"
    const val END_MARKER = "<!-- aicode-changelog:end -->"
    private const val HEADER =
        "# Changelog\n\nAll notable changes to this project will be documented in this file.\n\n"
    private val CONVENTIONAL_COMMIT =
        Regex(
            "^(feat|fix|perf|refactor|revert|docs|test|build|ci|chore)(?:\\(([^)]+)\\))?!?:\\s*(.+)$",
            RegexOption.IGNORE_CASE,
        )
    private val CATEGORY_ORDER = listOf("Added", "Fixed", "Changed", "Removed", "Other Changes")

    @JvmStatic fun create(data: ChangelogData): String = HEADER + managedSection(data) + "\n"

    @JvmStatic
    fun hasManagedSection(existing: String): Boolean {
        val start = existing.indexOf(START_MARKER)
        val end = existing.indexOf(END_MARKER)
        if (start < 0 && end < 0) return false
        if (
            start < 0 ||
                end < 0 ||
                end < start ||
                existing.indexOf(START_MARKER, start + START_MARKER.length) >= 0 ||
                existing.indexOf(END_MARKER, end + END_MARKER.length) >= 0
        )
            throw IllegalArgumentException(
                "CHANGELOG.md must contain exactly one ordered pair of AICode changelog markers"
            )
        return true
    }

    @JvmStatic
    fun update(existing: String, data: ChangelogData): String {
        if (!hasManagedSection(existing))
            throw IllegalArgumentException("The changelog does not contain a managed section")
        val start = existing.indexOf(START_MARKER)
        val end = existing.indexOf(END_MARKER)
        return existing.substring(0, start) +
            managedSection(data) +
            existing.substring(end + END_MARKER.length)
    }

    private fun managedSection(data: ChangelogData): String {
        val markdown = StringBuilder(START_MARKER).append('\n')
        for (release in data.releases) {
            markdown.append("\n## [").append(release.version).append(']')
            if (!release.unreleased && release.date.isNotBlank())
                markdown.append(" - ").append(release.date)
            markdown.append('\n')
            appendCommits(markdown, release.commits)
        }
        return markdown.append('\n').append(END_MARKER).toString()
    }

    private fun appendCommits(markdown: StringBuilder, commits: List<Commit>) {
        val categories = linkedMapOf<String, MutableList<String>>()
        CATEGORY_ORDER.forEach { categories[it] = mutableListOf() }
        for (commit in commits) {
            val parsed = parse(commit.subject)
            categories.getValue(parsed.category).add(parsed.description)
        }
        categories.forEach { (category, descriptions) ->
            if (descriptions.isNotEmpty()) {
                markdown.append("\n### ").append(category).append("\n\n")
                descriptions.forEach {
                    markdown.append("- ").append(escapeReservedMarkers(it)).append('\n')
                }
            }
        }
    }

    private fun escapeReservedMarkers(text: String) =
        text
            .replace(START_MARKER, "&lt;!-- aicode-changelog:start --&gt;")
            .replace(END_MARKER, "&lt;!-- aicode-changelog:end --&gt;")

    @JvmStatic
    fun parse(subject: String): ParsedCommit {
        val match =
            CONVENTIONAL_COMMIT.matchEntire(subject.trim())
                ?: return ParsedCommit("Other Changes", subject.trim())
        val type = match.groupValues[1].lowercase()
        val scope = match.groupValues[2]
        var description = match.groupValues[3].trim()
        if (scope.isNotBlank()) description = "**$scope:** $description"
        val category =
            when (type) {
                "feat" -> "Added"
                "fix" -> "Fixed"
                "revert" -> "Removed"
                "perf",
                "refactor" -> "Changed"
                else -> "Other Changes"
            }
        return ParsedCommit(category, description)
    }

    data class ParsedCommit(val category: String, val description: String) {
        fun category() = category

        fun description() = description
    }
}
