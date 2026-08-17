package com.missingtable.scorer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockStageTest {

    private val kickoff = "2026-09-05T10:00:00+00:00"
    private val ht = "2026-09-05T10:47:00+00:00"
    private val sh = "2026-09-05T11:00:00+00:00"
    private val ft = "2026-09-05T11:50:00+00:00"

    private fun stage(k: String? = null, h: String? = null, s: String? = null, e: String? = null) =
        MatchClock.stage(k, h, s, e)

    @Test
    fun `no kickoff means the match has not started`() {
        assertEquals(ClockStage.NOT_STARTED, stage())
        assertEquals("START MATCH", stage().label)
    }

    @Test
    fun `kickoff alone means first half`() {
        assertEquals(ClockStage.FIRST_HALF, stage(k = kickoff))
        assertEquals("HALFTIME", stage(k = kickoff).label)
    }

    @Test
    fun `halftime set means the interval`() {
        assertEquals(ClockStage.HALFTIME, stage(k = kickoff, h = ht))
        assertEquals("START 2ND HALF", stage(k = kickoff, h = ht).label)
    }

    @Test
    fun `second half outranks the halftime that preceded it`() {
        assertEquals(ClockStage.SECOND_HALF, stage(k = kickoff, h = ht, s = sh))
        assertEquals("END MATCH", stage(k = kickoff, h = ht, s = sh).label)
    }

    @Test
    fun `an ended match is ended whatever else is set`() {
        assertEquals(ClockStage.ENDED, stage(k = kickoff, h = ht, s = sh, e = ft))
        assertEquals(ClockStage.ENDED, stage(k = kickoff, e = ft))
        assertEquals(ClockStage.ENDED, stage(e = ft))
    }

    @Test
    fun `cancelling halftime returns to the first half`() {
        // cancel_halftime nulls halftime_start, so the stage derives back.
        assertEquals(ClockStage.FIRST_HALF, stage(k = kickoff, h = null))
    }

    @Test
    fun `only a finished match has no primary action`() {
        assertFalse(ClockStage.ENDED.hasPrimaryAction)
        listOf(
            ClockStage.NOT_STARTED,
            ClockStage.FIRST_HALF,
            ClockStage.HALFTIME,
            ClockStage.SECOND_HALF,
        ).forEach { assertTrue("$it should offer an action", it.hasPrimaryAction) }
    }

    @Test
    fun `every actionable stage has a non-empty label`() {
        ClockStage.entries.filter { it.hasPrimaryAction }.forEach {
            assertTrue("$it has no label", it.label.isNotEmpty())
        }
        assertEquals("", ClockStage.ENDED.label)
    }

    @Test
    fun `primary actions use the names the backend accepts`() {
        // backend valid_actions: start_first_half, start_halftime,
        // cancel_halftime, start_second_half, end_match
        assertEquals("start_halftime", MatchClock.primaryAction(ClockStage.FIRST_HALF))
        assertEquals("start_second_half", MatchClock.primaryAction(ClockStage.HALFTIME))
        assertEquals("end_match", MatchClock.primaryAction(ClockStage.SECOND_HALF))
    }

    @Test
    fun `starting and ending are handled by the UI, not a direct post`() {
        // NOT_STARTED opens the half-length dialog (SB-645); SECOND_HALF's
        // END MATCH goes through the existing confirmation.
        assertNull(MatchClock.primaryAction(ClockStage.NOT_STARTED))
        assertNull(MatchClock.primaryAction(ClockStage.ENDED))
    }

    @Test
    fun `the stage never offers an action the backend would reject for it`() {
        // Walking the normal run of play must never repeat or skip a step.
        val walk = listOf(
            stage() to null,
            stage(k = kickoff) to "start_halftime",
            stage(k = kickoff, h = ht) to "start_second_half",
            stage(k = kickoff, h = ht, s = sh) to "end_match",
            stage(k = kickoff, h = ht, s = sh, e = ft) to null,
        )
        walk.forEach { (st, expected) ->
            assertEquals("wrong action for $st", expected, MatchClock.primaryAction(st))
        }
        assertEquals(walk.size, walk.map { it.first }.distinct().size)
    }
}
