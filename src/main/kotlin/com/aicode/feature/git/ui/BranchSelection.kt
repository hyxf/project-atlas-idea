package com.aicode.feature.git.ui

object BranchSelection {
    private val preferredNames = listOf("master", "main", "test")

    @JvmStatic
    fun preferredBranch(branches: List<String>): String =
        preferredNames.firstNotNullOfOrNull { preferred ->
            branches.firstOrNull { it.equals(preferred, ignoreCase = true) }
        }.orEmpty()
}
