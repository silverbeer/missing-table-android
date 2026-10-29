package com.missingtable.scorer.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HalfTimeTest {

    private val ht = "2026-10-18T10:30:00+00:00"
    private fun at(secondsAfterHt: Long): Instant =
        Instant.parse("2026-10-18T10:30:00Z").plusSeconds(secondsAfterHt)

    @Test
    fun `no half time means no countdown`() {
        assertNull(HalfTime.remainingSeconds(null, at(0)))
        assertNull(HalfTime.remainingSeconds("garbage", at(0)))
        assertNull(HalfTime.expectedSecondHalfStart(null, at(0)))
    }

    @Test
    fun `counts down from the break length`() {
        assertEquals(600L, HalfTime.remainingSeconds(ht, at(0)))
        assertEquals(138L, HalfTime.remainingSeconds(ht, at(462)))
        assertEquals("2:18", HalfTime.countdown(138))
    }

    @Test
    fun `goes negative once overdue`() {
        assertEquals(-135L, HalfTime.remainingSeconds(ht, at(735)))
        assertEquals("+2:15", HalfTime.countdown(-135))
        assertEquals("0:00", HalfTime.countdown(0))
    }

    private val kickoff = "2026-10-18T10:00:00+00:00"
    private fun played(minutes: Long, seconds: Long = 0): Instant =
        Instant.parse("2026-10-18T10:00:00Z").plusSeconds(minutes * 60 + seconds)

    @Test
    fun `halftime 14 minutes into a 45 minute half is early`() {
        assertEquals(14L, HalfTime.earlyHalftimeMinute(kickoff, 45, played(14, 3)))
    }

    @Test
    fun `halftime within the margin of the half length is not early`() {
        assertEquals(39L, HalfTime.earlyHalftimeMinute(kickoff, 45, played(39, 59)))
        assertNull(HalfTime.earlyHalftimeMinute(kickoff, 45, played(40)))
        assertNull(HalfTime.earlyHalftimeMinute(kickoff, 45, played(45)))
        assertNull(HalfTime.earlyHalftimeMinute(kickoff, 45, played(48)))
    }

    @Test
    fun `threshold follows the half length`() {
        assertEquals(20L, HalfTime.earlyHalftimeMinute(kickoff, 30, played(20)))
        assertNull(HalfTime.earlyHalftimeMinute(kickoff, 30, played(25)))
    }

    @Test
    fun `no kickoff means no warning`() {
        assertNull(HalfTime.earlyHalftimeMinute(null, 45, played(10)))
        assertNull(HalfTime.earlyHalftimeMinute("garbage", 45, played(10)))
    }

    @Test
    fun `second half 34 seconds into the break is early`() {
        assertEquals(34L, HalfTime.earlySecondHalfSeconds(ht, at(34)))
        assertEquals(179L, HalfTime.earlySecondHalfSeconds(ht, at(179)))
    }

    @Test
    fun `second half after the early window is not early`() {
        assertNull(HalfTime.earlySecondHalfSeconds(ht, at(180)))
        assertNull(HalfTime.earlySecondHalfSeconds(ht, at(900)))
        assertNull(HalfTime.earlySecondHalfSeconds(null, at(10)))
    }

    @Test
    fun `late start is backdated to the end of the break`() {
        assertEquals(at(600), HalfTime.expectedSecondHalfStart(ht, at(900)))
    }

    @Test
    fun `early start is never in the future`() {
        assertEquals(at(300), HalfTime.expectedSecondHalfStart(ht, at(300)))
    }

    @Test
    fun `backdated start gives events a second-half minute`() {
        // 30m halves, kicked off 10:00, HT 10:30, break over 10:40; goal
        // entered at 10:47 while the clock still read HT.
        val start = HalfTime.expectedSecondHalfStart(ht, at(17 * 60))!!
        val c = LiveClock.derive(
            "2026-10-18T10:00:00+00:00", ht, start.toString(), null, 30, now = at(17 * 60),
        )
        assertEquals(37, c.minute)
    }
}
