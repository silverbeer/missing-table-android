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
