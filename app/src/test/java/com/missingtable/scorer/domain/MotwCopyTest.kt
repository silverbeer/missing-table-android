package com.missingtable.scorer.domain

import com.missingtable.scorer.data.api.MatchSummary
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotwCopyTest {

    private val utc = ZoneId.of("UTC")
    private val now = Instant.parse("2026-09-24T12:00:00Z")

    private fun match(
        status: String? = "scheduled",
        home: Int? = null,
        away: Int? = null,
        kickoff: String? = null,
        ageGroup: String? = "U15",
        division: String? = "Northeast",
        date: String = "2026-09-26",
    ) = MatchSummary(
        id = 1,
        matchDate = date,
        scheduledKickoff = kickoff,
        homeTeamId = 1,
        awayTeamId = 2,
        homeTeamName = "IFA",
        awayTeamName = "NEFC",
        homeScore = home,
        awayScore = away,
        ageGroupName = ageGroup,
        divisionName = division,
        matchStatus = status,
    )

    @Test
    fun `a scheduled match with scores on it still shows no score`() {
        // Asks the status, not the numbers — a scheduled match carrying
        // scores is a data problem, not a result.
        assertFalse(MotwCopy.hasScore(match(status = "scheduled", home = 1, away = 0)))
    }

    @Test
    fun `a half-entered result is not a result`() {
        assertFalse(MotwCopy.hasScore(match(status = "completed", home = 2, away = null)))
        assertNull(MotwCopy.winner(match(status = "completed", home = 2, away = null)))
    }

    @Test
    fun `a completed match with both scores has a result`() {
        assertTrue(MotwCopy.hasScore(match(status = "completed", home = 3, away = 1)))
    }

    @Test
    fun `the result line names the winner and puts the bigger number first`() {
        assertEquals("IFA won 3–1", MotwCopy.resultLine(match(status = "completed", home = 3, away = 1)))
        assertEquals("NEFC won 4–0", MotwCopy.resultLine(match(status = "completed", home = 0, away = 4)))
        assertEquals("Drew 2–2", MotwCopy.resultLine(match(status = "completed", home = 2, away = 2)))
    }

    @Test
    fun `a played match never shows a countdown`() {
        // The mistake that makes a featured card look abandoned by Monday.
        assertEquals(
            "Full time",
            MotwCopy.statusLabel(match(status = "completed", home = 1, away = 0, kickoff = "2026-09-20T18:00:00Z"), now, utc),
        )
    }

    @Test
    fun `a live match says so`() {
        assertEquals("Live now", MotwCopy.statusLabel(match(status = "live"), now, utc))
    }

    @Test
    fun `an upcoming match counts down in days then hours`() {
        assertEquals(
            "Kicks off in 2 days",
            MotwCopy.statusLabel(match(kickoff = "2026-09-26T12:00:00Z"), now, utc),
        )
        assertEquals(
            "Kicks off in 1 day",
            MotwCopy.statusLabel(match(kickoff = "2026-09-25T12:00:00Z"), now, utc),
        )
        assertEquals(
            "Kicks off in 3 hours",
            MotwCopy.statusLabel(match(kickoff = "2026-09-24T15:00:00Z"), now, utc),
        )
        assertEquals(
            "Kicks off in 1 hour",
            MotwCopy.statusLabel(match(kickoff = "2026-09-24T13:00:00Z"), now, utc),
        )
    }

    @Test
    fun `a kickoff that has passed but is not under way says so`() {
        assertEquals(
            "Kicking off",
            MotwCopy.statusLabel(match(kickoff = "2026-09-24T11:00:00Z"), now, utc),
        )
    }

    @Test
    fun `no kickoff time is TBC, not a countdown to nothing`() {
        assertEquals("Time TBC", MotwCopy.statusLabel(match(kickoff = null), now, utc))
    }

    @Test
    fun `an unparseable kickoff falls back to TBC rather than throwing`() {
        assertEquals("Time TBC", MotwCopy.statusLabel(match(kickoff = "not a date"), now, utc))
    }

    @Test
    fun `the meta line skips Unknown rather than printing it`() {
        assertEquals(
            "U15 · 2026-09-26",
            MotwCopy.metaLine(match(division = "Unknown", kickoff = null), utc),
        )
        assertEquals(
            "2026-09-26",
            MotwCopy.metaLine(match(ageGroup = null, division = null, kickoff = null), utc),
        )
    }

    @Test
    fun `the meta line prefers the kickoff time over the bare date`() {
        assertEquals(
            "U15 · Northeast · Sat, Sep 26, 2:00 PM",
            MotwCopy.metaLine(match(kickoff = "2026-09-26T14:00:00Z"), utc),
        )
    }

    @Test
    fun `the teaser says there is a pick without naming it`() {
        val teaser = MotwCopy.teaser(match())
        assertEquals("Reveal this week's pick", teaser)
        assertFalse("the teaser must not give the fixture away", teaser.contains("IFA"))
        assertEquals("Being played right now — reveal", MotwCopy.teaser(match(status = "live")))
        assertEquals("Played — reveal the result", MotwCopy.teaser(match(status = "completed")))
    }

    @Test
    fun `the age group label drops Unknown`() {
        assertEquals("U15", MotwCopy.ageGroupLabel(match()))
        assertNull(MotwCopy.ageGroupLabel(match(ageGroup = "Unknown")))
        assertNull(MotwCopy.ageGroupLabel(match(ageGroup = null)))
    }
}
