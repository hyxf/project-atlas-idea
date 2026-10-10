package com.aicode.feature.github.service

import com.aicode.feature.github.model.GitHubRepository

data class GitHubLanguageGroup(val language: String, val repositories: List<GitHubRepository>, val count: Int = repositories.size)
data class GitHubVisibilityGroup(
    val title: String,
    val repositories: List<GitHubRepository>,
    val languages: List<GitHubLanguageGroup>,
    val count: Int = repositories.size,
)

fun groupRepositories(repositories: List<GitHubRepository>): List<GitHubVisibilityGroup> {
    val comparator = java.text.Collator.getInstance(java.util.Locale.ROOT).apply {
        strength = java.text.Collator.PRIMARY
    }
    return listOf(false to "Public", true to "Private").map { (private, title) ->
        val allValues = repositories.filter { it.private == private }
        val values = allValues.sortedWith(compareBy(comparator) { it.fullName })
        val allLanguages = allValues.groupBy { it.language ?: "Unknown" }
        val languages = allLanguages.entries
            .map { (language, repositoriesInLanguage) ->
                GitHubLanguageGroup(language, repositoriesInLanguage.sortedWith(compareBy(comparator) { it.fullName }))
            }
            .sortedWith(compareBy(comparator) { it.language })
        GitHubVisibilityGroup(title, values, languages, allValues.size)
    }
}
