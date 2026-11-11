package com.missingtable.scorer.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** Per-match standings cells (SB-1623). */
class StandingsFormatTest {

    @Test
    fun `per-match values show exactly two decimals`() {
        assertEquals("2.25", StandingsFormat.perMatch(2.25))
        assertEquals("2.20", StandingsFormat.perMatch(2.2))
        assertEquals("3.00", StandingsFormat.perMatch(3.0))
        assertEquals("0.00", StandingsFormat.perMatch(0.0))
        assertEquals("1.67", StandingsFormat.perMatch(1.6666))
    }

    @Test
    fun `null renders as an en dash, never zero`() {
        assertEquals("–", StandingsFormat.perMatch(null))
        assertEquals("–", StandingsFormat.signedPerMatch(null))
        assertEquals("–", StandingsFormat.perMatch(Double.NaN))
    }

    @Test
    fun `GD per match carries a sign when positive`() {
        assertEquals("+0.50", StandingsFormat.signedPerMatch(0.5))
        assertEquals("+2.00", StandingsFormat.signedPerMatch(2.0))
        assertEquals("-0.75", StandingsFormat.signedPerMatch(-0.75))
    }

    @Test
    fun `zero GD per match is plain 0_00`() {
        assertEquals("0.00", StandingsFormat.signedPerMatch(0.0))
        assertEquals("0.00", StandingsFormat.signedPerMatch(-0.0))
        assertEquals("0.00", StandingsFormat.signedPerMatch(0.001))
        assertEquals("0.00", StandingsFormat.signedPerMatch(-0.001))
    }

    @Test
    fun `whole goal difference matches the existing column`() {
        assertEquals("+3", StandingsFormat.goalDifference(3))
        assertEquals("0", StandingsFormat.goalDifference(0))
        assertEquals("-2", StandingsFormat.goalDifference(-2))
    }

    @Test
    fun `decimal separator ignores the device locale`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("2.25", StandingsFormat.perMatch(2.25))
            assertEquals("+0.50", StandingsFormat.signedPerMatch(0.5))
        } finally {
            Locale.setDefault(saved)
        }
    }
}
