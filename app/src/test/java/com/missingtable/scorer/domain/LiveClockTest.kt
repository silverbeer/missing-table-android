package com.missingtable.scorer.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveClockTest {

    private val kickoff = "2026-09-05T10:00:00+00:00"
    private fun at(secondsAfterKickoff: Long): Instant =
        Instant.parse("2026-09-05T10:00:00Z").plusSeconds(secondsAfterKickoff)

    @Test
    fun `no kickoff yet shows dash`() {
        val c = LiveClock.derive(null, null, null, null, halfDuration = 45)
        assertEquals("—", c.display)
        assertNull(c.minute)
        assertNull(c.extraTime)
    }

    @Test
    fun `unparseable kickoff shows dash`() {
        val c = LiveClock.derive("not-a-timestamp", null, null, null, halfDuration = 45)
        assertEquals("—", c.display)
    }

    @Test
    fun `first half counts up from kickoff`() {
        val c = LiveClock.derive(kickoff, null, null, null, 45, now = at(12 * 60 + 30))
        assertEquals("12:30", c.display)
        assertEquals(12, c.minute)
        assertNull(c.extraTime)
    }

    @Test
    fun `first half extra time caps minute and reports extra`() {
        val c = LiveClock.derive(kickoff, null, null, null, 45, now = at(47 * 60))
        assertEquals("47:00", c.display)
        assertEquals(45, c.minute)
        assertEquals(2, c.extraTime)
    }

    @Test
    fun `halftime shows HT at halfDuration`() {
        val c = LiveClock.derive(kickoff, "2026-09-05T10:47:00+00:00", null, null, 45, now = at(50 * 60))
        assertEquals("HT", c.display)
        assertEquals(45, c.minute)
        assertNull(c.extraTime)
    }

    @Test
    fun `cancelled halftime resumes first-half clock`() {
        // cancel_halftime nulls halftime_start server-side; clock derives from kickoff again
        val c = LiveClock.derive(kickoff, null, null, null, 45, now = at(30 * 60))
        assertEquals("30:00", c.display)
        assertEquals(30, c.minute)
    }

    @Test
    fun `second half continues from halfDuration`() {
        val secondHalf = "2026-09-05T11:00:00+00:00"
        val now = Instant.parse("2026-09-05T11:10:00Z")
        val c = LiveClock.derive(kickoff, "2026-09-05T10:47:00+00:00", secondHalf, null, 45, now = now)
        assertEquals("55:00", c.display)
        assertEquals(55, c.minute)
        assertNull(c.extraTime)
    }

    @Test
    fun `second half extra time caps at full time`() {
        val secondHalf = "2026-09-05T11:00:00+00:00"
        val now = Instant.parse("2026-09-05T11:48:00Z") // 48 min into 2nd half of a 45 half
        val c = LiveClock.derive(kickoff, "2026-09-05T10:47:00+00:00", secondHalf, null, 45, now = now)
        assertEquals("93:00", c.display)
        assertEquals(90, c.minute)
        assertEquals(3, c.extraTime)
    }

    @Test
    fun `shorter halves respect halfDuration`() {
        val secondHalf = "2026-09-05T10:45:00+00:00"
        val now = Instant.parse("2026-09-05T10:50:00Z")
        val c = LiveClock.derive(kickoff, "2026-09-05T10:36:00+00:00", secondHalf, null, 35, now = now)
        assertEquals("40:00", c.display)
        assertEquals(40, c.minute)
    }

    @Test
    fun `match end shows FT`() {
        val c = LiveClock.derive(kickoff, null, "2026-09-05T11:00:00+00:00", "2026-09-05T11:50:00+00:00", 45)
        assertEquals("FT", c.display)
        assertNull(c.minute)
    }
}
