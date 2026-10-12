package com.aicode.feature.git.service

import com.aicode.feature.git.model.SemVer

object GitTagVersionService {
    private val INITIAL_BASELINE = SemVer(0, 0, 0)

    @JvmStatic
    fun calculateCandidates(tags: Collection<String>): VersionCandidates {
        val latest = tags.mapNotNull(SemVer::parseTag).maxOrNull() ?: INITIAL_BASELINE
        return VersionCandidates(latest, latest.nextMajor(), latest.nextMinor(), latest.nextPatch())
    }

    data class VersionCandidates(
        val current: SemVer,
        val major: SemVer,
        val minor: SemVer,
        val patch: SemVer,
    ) {
        fun current() = current

        fun major() = major

        fun minor() = minor

        fun patch() = patch
    }
}
