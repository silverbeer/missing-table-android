package com.missingtable.scorer.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchWeekTest {

    // 2026-08-18 is a Tuesday; its week runs Mon 17th – Sun 23rd.
    private val tuesday = LocalDate.parse("2026-08-18")

    @Test
    fun `midweek resolves to that monday through sunday`() {
        val w = MatchWeek.of(tuesday)
        assertEquals(LocalDate.parse("2026-08-17"), w.start)
        assertEquals(LocalDate.parse("2026-08-23"), w.end)
    }

    @Test
    fun `monday is the start of its own week, not the previous one`() {
        val w = MatchWeek.of(LocalDate.parse("2026-08-17"))
        assertEquals(LocalDate.parse("2026-08-17"), w.start)
    }

    @Test
    fun `sunday belongs to the week that started six days earlier`() {
        // The classic off-by-one: with Sunday = 0 in JS this needs an explicit
        // adjustment, and getting it wrong rolls the whole week forward.
        val w = MatchWeek.of(LocalDate.parse("2026-08-23"))
        assertEquals(LocalDate.parse("2026-08-17"), w.start)
        assertEquals(LocalDate.parse("2026-08-23"), w.end)
    }

    @Test
    fun `previous and next shift by exactly seven days`() {
        assertEquals(LocalDate.parse("2026-08-10"), MatchWeek.of(tuesday, -1).start)
        assertEquals(LocalDate.parse("2026-08-16"), MatchWeek.of(tuesday, -1).end)
        assertEquals(LocalDate.parse("2026-08-24"), MatchWeek.of(tuesday, 1).start)
        assertEquals(LocalDate.parse("2026-08-30"), MatchWeek.of(tuesday, 1).end)
    }

    @Test
    fun `weeks spanning a month boundary stay seven days`() {
        val w = MatchWeek.of(LocalDate.parse("2026-09-01"), 0)
        assertEquals(LocalDate.parse("2026-08-31"), w.start)
        assertEquals(LocalDate.parse("2026-09-06"), w.end)
    }

    @Test
    fun `weeks spanning a year boundary stay seven days`() {
        val w = MatchWeek.of(LocalDate.parse("2027-01-01"), 0)
        assertEquals(LocalDate.parse("2026-12-28"), w.start)
        assertEquals(LocalDate.parse("2027-01-03"), w.end)
    }

    @Test
    fun `contains covers both endpoints and excludes neighbours`() {
        val w = MatchWeek.of(tuesday)
        assertTrue("2026-08-17" in w)
        assertTrue("2026-08-20" in w)
        assertTrue("2026-08-23" in w)
        assertFalse("2026-08-16" in w)
        assertFalse("2026-08-24" in w)
    }

    @Test
    fun `label reads like the web header`() {
        assertEquals("Aug 17 – Aug 23, 2026", MatchWeek.of(tuesday).label())
    }

    @Test
    fun `the match-day weekend falls in one week`() {
        // 8/22 NYRB and 8/23 NYCFC must not straddle a week boundary, or the
        // two fixtures would need different navigation to reach.
        val w = MatchWeek.of(tuesday)
        assertTrue("2026-08-22" in w)
        assertTrue("2026-08-23" in w)
    }
}
