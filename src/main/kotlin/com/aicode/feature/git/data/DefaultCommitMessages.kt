package com.aicode.feature.git.data

import com.aicode.feature.git.model.CommitMessageTemplate

object DefaultCommitMessages {
    val templates: List<CommitMessageTemplate> =
        listOf(
            CommitMessageTemplate(type = "docs", scope = "readme", subject = "Update README.md"),
            CommitMessageTemplate(type = "style", subject = "Format code"),
            CommitMessageTemplate(type = "refactor", subject = "Optimize code structure"),
            CommitMessageTemplate(type = "fix", subject = "Fix known issues"),
            CommitMessageTemplate(type = "feat", subject = "Add new feature"),
            CommitMessageTemplate(type = "perf", subject = "Improve performance"),
            CommitMessageTemplate(type = "test", subject = "Add test cases"),
            CommitMessageTemplate(type = "build", subject = "Update build configuration and dependencies"),
            CommitMessageTemplate(type = "chore", subject = "Update project configuration"),
        )

    fun missingFrom(messages: Collection<CommitMessageTemplate>): List<CommitMessageTemplate> =
        templates.filterNot(messages::contains)
}
