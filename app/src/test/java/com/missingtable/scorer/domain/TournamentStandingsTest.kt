package com.missingtable.scorer.domain

import com.missingtable.scorer.data.api.TeamRef
import com.missingtable.scorer.data.api.TournamentMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TournamentStandingsTest {

    private fun match(
        homeId: Int, awayId: Int, hs: Int?, as_: Int?, status: String = "completed",
    ) = TournamentMatch(
        id = homeId * 100 + awayId,
        matchStatus = status,
        homeScore = hs,
        awayScore = as_,
        homeTeam = TeamRef(homeId, "Team $homeId"),
        awayTeam = TeamRef(awayId, "Team $awayId"),
    )

    @Test
    fun `three points for a win one for a draw`() {
        val rows = TournamentStandings.compute(
            listOf(
                match(1, 2, 2, 0), // 1 beats 2
                match(2, 3, 1, 1), // draw
            )
        )
        val byId = rows.associateBy { it.teamId }
        assertEquals(3, byId[1]!!.points)
        assertEquals(1, byId[2]!!.points)
        assertEquals(1, byId[3]!!.points)
        assertEquals(2, byId[2]!!.played)
    }

    @Test
    fun `sorted by points then goal difference then goals for`() {
        val rows = TournamentStandings.compute(
            listOf(
                match(1, 2, 3, 0),
                match(3, 4, 1, 0),
            )
        )
        // Both 1 and 3 have 3 pts; 1 has better GD
        assertEquals(1, rows[0].teamId)
        assertEquals(3, rows[1].teamId)
    }

    @Test
    fun `scheduled and scoreless matches are ignored`() {
        val rows = TournamentStandings.compute(
            listOf(
                match(1, 2, null, null, status = "scheduled"),
                match(1, 2, null, null, status = "completed"), // no score yet
            )
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `forfeit counts like a completed match`() {
        val rows = TournamentStandings.compute(listOf(match(1, 2, 3, 0, status = "forfeit")))
        assertEquals(3, rows.first { it.teamId == 1 }.points)
    }
}
