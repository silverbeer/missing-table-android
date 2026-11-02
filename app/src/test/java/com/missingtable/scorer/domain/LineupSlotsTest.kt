package com.missingtable.scorer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LineupSlotsTest {

    @Test
    fun `load maps position codes onto the preset's slots`() {
        val loaded = LineupSlots.load("4-3-3", listOf("GK" to 1, "ST" to 9, "LB" to 3))!!
        assertEquals("4-3-3", loaded.formation)
        // GK is slot 0, LB slot 1, ST slot 9 in the 4-3-3 preset.
        assertEquals(mapOf(0 to 1, 1 to 3, 9 to 9), loaded.assignments)
    }

    @Test
    fun `a formation this app does not draw loads as null`() {
        assertNull(LineupSlots.load("5-4-1", listOf("GK" to 1)))
    }

    @Test
    fun `positions round-trips load`() {
        val pairs = listOf("GK" to 1, "LB" to 3, "ST" to 9)
        val loaded = LineupSlots.load("4-3-3", pairs)!!
        assertEquals(pairs, LineupSlots.positions(loaded.formation, loaded.assignments))
    }

    @Test
    fun `current pitch replays subs onto the half-built XI and skips placeholder numbers`() {
        // GK #1, ST #9 assigned; LB is a typed number with no roster row yet.
        val assignments = mapOf(0 to 1, 1 to -14, 9 to 9)
        val pitch = LineupSlots.currentPitch(
            "4-3-3",
            assignments,
            listOf(OnPitch.Sub(outId = 9, inId = 11)),
        )
        assertEquals(mapOf("GK" to 1, "ST" to 11), pitch)
    }

    @Test
    fun `fixing the starting XI drops a sub of the player taken out of it`() {
        // Kickoff lineup had #7 at ST by mistake; #7 was subbed for #11. The
        // fix puts #9 at ST: the sub names #7, who is no longer on the pitch,
        // so it is ignored rather than guessed at (OnPitch.current).
        val pitch = LineupSlots.currentPitch(
            "4-3-3",
            mapOf(0 to 1, 9 to 9),
            listOf(OnPitch.Sub(outId = 7, inId = 11)),
        )
        assertEquals(mapOf("GK" to 1, "ST" to 9), pitch)
    }
}
