package com.missingtable.scorer.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventStampTest {

    private val kickoff = "2026-09-05T10:00:00+00:00"
    private fun at(secondsAfterKickoff: Long): Instant =
        Instant.parse("2026-09-05T10:00:00Z").plusSeconds(secondsAfterKickoff)

    private fun clockAt(seconds: Long) =
        LiveClock.derive(kickoff, null, null, null, halfDuration = 45, now = at(seconds))

    @Test
    fun `label renders a plain minute`() {
        assertEquals("23'", EventStamp(23, null).label())
    }

    @Test
    fun `label renders extra time`() {
        assertEquals("45+2'", EventStamp(45, 2).label())
    }

    @Test
    fun `zero extra time is not shown`() {
        assertEquals("45'", EventStamp(45, 0).label())
    }

    @Test
    fun `no minute means no label`() {
        assertNull(EventStamp.UNKNOWN.label())
        assertNull(EventStamp(null, null).label())
    }

    @Test
    fun `stamp is taken from the clock at that instant`() {
        val stamp = EventStamp.from(clockAt(23 * 60 + 40))
        assertEquals(23, stamp.minute)
        assertEquals("23'", stamp.label())
    }

    @Test
    fun `a stamp does not move while the clock runs on`() {
        // SB-652: the whole point — the minute is fixed when entry starts, so
        // however long the pickers take cannot change it.
        val atGoal = EventStamp.from(clockAt(23 * 60))
        val fortySecondsLater = EventStamp.from(clockAt(23 * 60 + 40))
        val aMinuteLater = EventStamp.from(clockAt(24 * 60 + 5))

        assertEquals(23, atGoal.minute)
        assertEquals(23, fortySecondsLater.minute)
        // The clock genuinely has moved on — proving the drift this prevents.
        assertEquals(24, aMinuteLater.minute)
        // The stamp taken at the goal is unaffected by either.
        assertEquals(23, atGoal.minute)
    }

    @Test
    fun `entry spanning a minute boundary keeps the earlier minute`() {
        // Goal at 23:55, scorer picked at 24:10. Recording 24' would be wrong.
        val stamp = EventStamp.from(clockAt(23 * 60 + 55))
        val whenEntryFinished = EventStamp.from(clockAt(24 * 60 + 10))
        assertEquals(23, stamp.minute)
        assertEquals(24, whenEntryFinished.minute)
    }

    @Test
    fun `extra time carries through the stamp`() {
        val stamp = EventStamp.from(clockAt(47 * 60))
        assertEquals(45, stamp.minute)
        assertEquals(2, stamp.extraTime)
        assertEquals("45+2'", stamp.label())
    }

    @Test
    fun `before kickoff there is nothing to stamp`() {
        val noClock = LiveClock.derive(null, null, null, null, halfDuration = 45)
        val stamp = EventStamp.from(noClock)
        assertNull(stamp.minute)
        assertNull(stamp.label())
        assertEquals(EventStamp.UNKNOWN, stamp)
    }

    @Test
    fun `a full-time clock stamps nothing`() {
        val ft = LiveClock.derive(kickoff, null, null, "2026-09-05T11:50:00+00:00", 45)
        assertNull(EventStamp.from(ft).minute)
    }
}
