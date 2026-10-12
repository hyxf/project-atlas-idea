package com.aicode.feature.git.model

data class ChangelogData(val releases: List<Release>) {
    fun releases(): List<Release> = releases

    data class Release(
        val version: String,
        val date: String,
        val commits: List<Commit>,
        val unreleased: Boolean,
    ) {
        fun version() = version

        fun date() = date

        fun commits() = commits

        fun unreleased() = unreleased
    }

    data class Commit(val hash: String, val subject: String) {
        fun hash() = hash

        fun subject() = subject
    }
}
