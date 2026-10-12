package com.aicode.feature.git.model

data class GitMessageConfig(
    val messages: MutableList<CommitMessageTemplate> = mutableListOf(),
)

data class CommitMessageTemplate(
    val type: String = "",
    val scope: String? = null,
    val subject: String = "",
) {
    fun formatted(): String {
        if (type.isBlank()) return subject
        val prefix = if (scope.isNullOrBlank()) type else "$type($scope)"
        return "$prefix: $subject"
    }
}
