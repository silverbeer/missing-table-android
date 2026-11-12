package com.missingtable.scorer.ui.match

import com.missingtable.scorer.data.api.MatchSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** SB-1645: a Follow on a match follows the squad each side fielded. */
class SquadLinkTest {

    private fun match(homeAg: Int? = null, awayAg: Int? = null) = MatchSummary(
        id = 1,
        matchDate = "2026-11-12",
        homeTeamId = 10,
        awayTeamId = 20,
        ageGroupId = 4,
        ageGroupName = "U14",
        homeAgeGroupId = homeAg,
        awayAgeGroupId = awayAg,
    )

    @Test
    fun `both sides default to the match's age group`() {
        assertEquals(4, squadAgeGroupId(match(), home = true))
        assertEquals(4, squadAgeGroupId(match(), home = false))
    }

    @Test
    fun `a side that played up follows its own squad`() {
        assertEquals(3, squadAgeGroupId(match(awayAg = 3), home = false))
        assertEquals(4, squadAgeGroupId(match(awayAg = 3), home = true))
    }

    @Test
    fun `team link carries the squad and names it only when it is the match's`() {
        val home = teamLink(match(awayAg = 3), home = true, subtitle = null)
        assertEquals(10, home.teamId)
        assertEquals(4, home.ageGroupId)
        assertEquals("U14", home.ageGroupName)

        val away = teamLink(match(awayAg = 3), home = false, subtitle = null)
        assertEquals(3, away.ageGroupId)
        assertNull(away.ageGroupName)
    }
}
