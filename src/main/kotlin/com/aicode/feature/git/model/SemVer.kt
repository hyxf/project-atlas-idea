package com.aicode.feature.git.model

class SemVer(private val major: Long, private val minor: Long, private val patch: Long) :
    Comparable<SemVer> {
    init {
        require(major >= 0 && minor >= 0 && patch >= 0) {
            "Version components must be non-negative"
        }
    }

    fun nextMajor() = SemVer(Math.incrementExact(major), 0, 0)

    fun nextMinor() = SemVer(major, Math.incrementExact(minor), 0)

    fun nextPatch() = SemVer(major, minor, Math.incrementExact(patch))

    fun toTag() = "v$major.$minor.$patch"

    override fun compareTo(other: SemVer): Int {
        val majorComparison = major.compareTo(other.major)
        if (majorComparison != 0) return majorComparison
        val minorComparison = minor.compareTo(other.minor)
        return if (minorComparison != 0) minorComparison else patch.compareTo(other.patch)
    }

    override fun equals(other: Any?) =
        other is SemVer && major == other.major && minor == other.minor && patch == other.patch

    override fun hashCode() = java.util.Objects.hash(major, minor, patch)

    override fun toString() = toTag()

    companion object {
        private val TAG_PATTERN = Regex("^v(\\d+)\\.(\\d+)\\.(\\d+)$")

        @JvmStatic
        fun parseTag(tag: String): SemVer? {
            val match = TAG_PATTERN.matchEntire(tag) ?: return null
            return try {
                SemVer(
                    match.groupValues[1].toLong(),
                    match.groupValues[2].toLong(),
                    match.groupValues[3].toLong(),
                )
            } catch (_: NumberFormatException) {
                null
            }
        }
    }
}
