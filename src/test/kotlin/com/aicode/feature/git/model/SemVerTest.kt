package com.aicode.feature.git.model

import org.junit.Assert.*
import org.junit.Test

class SemVerTest {
    @Test
    fun parsesOnlyCompleteVersionTags() {
        assertEquals(SemVer(1, 6, 2), SemVer.parseTag("v1.6.2"))
        assertNull(SemVer.parseTag("v1.6"))
        assertNull(SemVer.parseTag("v1.6.2-beta"))
        assertNull(SemVer.parseTag("release-1.6.2"))
    }

    @Test
    fun incrementsEachVersionLevel() {
        val v = SemVer(1, 6, 3)
        assertEquals("v2.0.0", v.nextMajor().toTag())
        assertEquals("v1.7.0", v.nextMinor().toTag())
        assertEquals("v1.6.4", v.nextPatch().toTag())
    }

    @Test
    fun normalizesLeadingZeroes() {
        assertEquals("v1.2.3", SemVer.parseTag("v01.002.0003")!!.toTag())
    }

    @Test
    fun rejectsComponentsThatExceedLongRange() {
        assertNull(SemVer.parseTag("v9223372036854775808.0.0"))
    }

    @Test
    fun failsInsteadOfWrappingOnIncrementOverflow() {
        assertThrows(ArithmeticException::class.java) { SemVer(Long.MAX_VALUE, 0, 0).nextMajor() }
    }
}
