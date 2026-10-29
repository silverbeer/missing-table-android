package com.missingtable.scorer.domain

import com.missingtable.scorer.data.api.CommonOpponent
import com.missingtable.scorer.data.api.MatchPreviewResponse
import com.missingtable.scorer.data.api.PreviewMatch
import com.missingtable.scorer.domain.MatchPreviewModel.Outcome
import com.missingtable.scorer.domain.MatchPreviewModel.Tab
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchPreviewModelTest {

    private val us = 10
    private val them = 20

    private var nextId = 0
    private fun m(
        home: Int,
        away: Int,
        homeScore: Int?,
        awayScore: Int?,
        type: String? = "League",
    ) = PreviewMatch(
        id = ++nextId,
        matchDate = "2026-09-19",
        homeTeamId = home,
        awayTeamId = away,
        homeTeamName = "Team $home",
        awayTeamName = "Team $away",
        homeScore = homeScore,
        awayScore = awayScore,
        matchTypeName = type,
    )

    // ── Outcome and scoreline ────────────────────────────────────────────────

    @Test
    fun `outcome is read from the team's side, home or away`() {
        assertEquals(Outcome.WIN, MatchPreviewModel.outcome(m(us, 99, 3, 1), us))
        assertEquals(Outcome.WIN, MatchPreviewModel.outcome(m(99, us, 1, 3), us))
        assertEquals(Outcome.LOSS, MatchPreviewModel.outcome(m(99, us, 3, 1), us))
        assertEquals(Outcome.DRAW, MatchPreviewModel.outcome(m(99, us, 2, 2), us))
    }

    @Test
    fun `an unreported score is unknown, not a draw`() {
        assertEquals(Outcome.UNKNOWN, MatchPreviewModel.outcome(m(us, 99, null, null), us))
        assertEquals(Outcome.UNKNOWN, MatchPreviewModel.outcome(m(us, 99, 2, null), us))
        assertEquals("?", Outcome.UNKNOWN.label)
    }

    @Test
    fun `scoreline puts the team's own score first (SB-892)`() {
        // An away win must read 3–1, not the raw 1–3 beside a W.
        assertEquals("3–1", MatchPreviewModel.scoreline(m(99, us, 1, 3), us))
        assertEquals("3–1", MatchPreviewModel.scoreline(m(us, 99, 3, 1), us))
        assertNull(MatchPreviewModel.scoreline(m(us, 99, null, 1), us))
    }

    @Test
    fun `venue and opponent follow the team's side`() {
        val away = m(99, us, 0, 0)
        assertEquals("A", MatchPreviewModel.venue(away, us))
        assertEquals("Team 99", MatchPreviewModel.opponentName(away, us))
        val home = m(us, 99, 0, 0)
        assertEquals("H", MatchPreviewModel.venue(home, us))
        assertEquals("Team 99", MatchPreviewModel.opponentName(home, us))
    }

    // ── Competition badges ───────────────────────────────────────────────────

    @Test
    fun `only non-League competitions are called out in common and head-to-head`() {
        assertNull(MatchPreviewModel.exceptionalCompetition("League"))
        assertNull(MatchPreviewModel.exceptionalCompetition(null))
        assertNull(MatchPreviewModel.exceptionalCompetition(""))
        assertEquals("Flex", MatchPreviewModel.exceptionalCompetition("Flex"))
        assertTrue(MatchPreviewModel.isLeague("League"))
        assertFalse(MatchPreviewModel.isLeague("Tournament"))
    }

    // ── QoP rank ─────────────────────────────────────────────────────────────

    @Test
    fun `QoP rank shows only with QoP data and a rank`() {
        assertTrue(MatchPreviewModel.showsQopRank(true, 4))
        assertFalse(MatchPreviewModel.showsQopRank(true, null))
        assertFalse(MatchPreviewModel.showsQopRank(false, 4))
    }

    @Test
    fun `a positive rank change is a climb, negative a fall, zero nothing`() {
        assertEquals("▲3", MatchPreviewModel.rankMove(3)?.label)
        assertEquals(true, MatchPreviewModel.rankMove(3)?.up)
        assertEquals("▼2", MatchPreviewModel.rankMove(-2)?.label)
        assertEquals(false, MatchPreviewModel.rankMove(-2)?.up)
        assertNull(MatchPreviewModel.rankMove(0))
        assertNull(MatchPreviewModel.rankMove(null))
    }

    // ── Tabs ─────────────────────────────────────────────────────────────────

    private fun preview(
        homeRecent: Int = 0,
        awayRecent: Int = 0,
        common: Int = 0,
        h2h: Int = 0,
    ) = MatchPreviewResponse(
        homeTeamRecent = List(homeRecent) { m(us, 99, 1, 0) },
        awayTeamRecent = List(awayRecent) { m(them, 99, 1, 0) },
        commonOpponents = List(common) { CommonOpponent(opponentId = 100 + it, opponentName = "Opp $it") },
        headToHead = List(h2h) { m(us, them, 1, 1) },
    )

    @Test
    fun `Recent Form always shows, without a count`() {
        val tabs = MatchPreviewModel.tabs(preview())
        assertEquals(listOf(MatchPreviewModel.TabSpec(Tab.FORM, null)), tabs)
    }

    @Test
    fun `empty tabs are hidden, the rest carry counts`() {
        val tabs = MatchPreviewModel.tabs(preview(homeRecent = 5, common = 2, h2h = 3))
        assertEquals(
            listOf(
                MatchPreviewModel.TabSpec(Tab.FORM, null),
                MatchPreviewModel.TabSpec(Tab.COMMON, 2),
                MatchPreviewModel.TabSpec(Tab.HEAD_TO_HEAD, 3),
            ),
            tabs,
        )
        assertEquals(
            listOf(Tab.FORM, Tab.HEAD_TO_HEAD),
            MatchPreviewModel.tabs(preview(h2h = 1)).map { it.tab },
        )
    }

    @Test
    fun `opens on the first tab with content`() {
        assertEquals(Tab.FORM, MatchPreviewModel.defaultTab(preview(awayRecent = 1, common = 1, h2h = 1)))
        assertEquals(Tab.COMMON, MatchPreviewModel.defaultTab(preview(common = 1, h2h = 1)))
        assertEquals(Tab.HEAD_TO_HEAD, MatchPreviewModel.defaultTab(preview(h2h = 1)))
        assertEquals(Tab.FORM, MatchPreviewModel.defaultTab(preview()))
    }

    @Test
    fun `a selected tab that disappears falls back to Recent Form`() {
        assertEquals(Tab.FORM, MatchPreviewModel.resolveTab(preview(h2h = 1), Tab.COMMON))
        assertEquals(Tab.HEAD_TO_HEAD, MatchPreviewModel.resolveTab(preview(h2h = 1), Tab.HEAD_TO_HEAD))
    }

    // ── Head-to-head tally ───────────────────────────────────────────────────

    @Test
    fun `head-to-head tally follows the previewed home side across venues`() {
        val meetings = listOf(
            m(us, them, 2, 0), // we won at home
            m(them, us, 0, 1), // we won away
            m(them, us, 3, 1), // they won at home
            m(us, them, 1, 1), // draw
            m(us, them, null, null), // not reported — counts for nothing
        )
        assertEquals(
            MatchPreviewModel.HeadToHeadTally(homeWins = 2, draws = 1, awayWins = 1),
            MatchPreviewModel.headToHeadTally(meetings, us),
        )
    }

    // ── Dates ────────────────────────────────────────────────────────────────

    @Test
    fun `dates format like the web and tolerate junk`() {
        assertEquals("Sep 19", MatchPreviewModel.shortDate("2026-09-19"))
        assertEquals("Sep 19, 2026", MatchPreviewModel.longDate("2026-09-19"))
        assertEquals("Sep 19", MatchPreviewModel.shortDate("2026-09-19T15:00:00"))
        assertEquals("", MatchPreviewModel.shortDate(null))
        assertEquals("", MatchPreviewModel.shortDate("soon"))
    }

    // ── Wire format ──────────────────────────────────────────────────────────

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    @Test
    fun `parses the server's preview payload`() {
        val body = """
            {
              "home_team_id": 10, "away_team_id": 20,
              "home_team_recent": [{
                "id": 1, "match_date": "2026-09-19", "home_team_id": 10, "away_team_id": 99,
                "home_team_name": "Us", "away_team_name": "Rivals", "home_score": 2, "away_score": null,
                "season_name": "2026-2027", "match_type_id": 1, "match_type_name": "League",
                "scoring_mode": "live", "home_penalty_score": null
              }],
              "away_team_recent": [],
              "common_opponents": [{
                "opponent_id": 99, "opponent_name": "Rivals",
                "home_team_matches": [], "away_team_matches": []
              }],
              "head_to_head": [],
              "has_qop_data": true, "home_qop_rank": 4, "home_qop_rank_change": -1,
              "away_qop_rank": null, "away_qop_rank_change": null
            }
        """.trimIndent()
        val parsed = json.decodeFromString(MatchPreviewResponse.serializer(), body)
        assertEquals(1, parsed.homeTeamRecent.size)
        assertNull(parsed.homeTeamRecent[0].awayScore)
        assertEquals("Rivals", parsed.commonOpponents[0].opponentName)
        assertEquals(4, parsed.homeQopRank)
        assertEquals(-1, parsed.homeQopRankChange)
        assertNull(parsed.awayQopRank)
    }

    @Test
    fun `a payload missing its lists parses as empty`() {
        val parsed = json.decodeFromString(MatchPreviewResponse.serializer(), """{"home_team_id": 10}""")
        assertTrue(parsed.homeTeamRecent.isEmpty())
        assertTrue(parsed.headToHead.isEmpty())
        assertFalse(parsed.hasQopData)
    }
}
