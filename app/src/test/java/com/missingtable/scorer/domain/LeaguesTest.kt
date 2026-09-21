package com.missingtable.scorer.domain

import com.missingtable.scorer.data.api.LeagueDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The real `/api/leagues` payload (2026-09-21), in the order the API sends it —
 * which is not display order. Flex is a child of Homegrown; Kick Futsal is
 * retired.
 */
private val PROD_LEAGUES = listOf(
    LeagueDto(id = 2, name = "Academy", parentLeagueId = null, matchTypeId = 1, isActive = true, displayOrder = 3),
    LeagueDto(id = 290, name = "Flex", parentLeagueId = 1, matchTypeId = 5, isActive = true, displayOrder = 2),
    LeagueDto(id = 1, name = "Homegrown", parentLeagueId = null, matchTypeId = 1, isActive = true, displayOrder = 1),
    LeagueDto(id = 34, name = "Kick Futsal", parentLeagueId = null, matchTypeId = null, isActive = false, displayOrder = null),
)

class LeaguesTest {

    @Test
    fun `Flex is not a division chip`() {
        val names = Leagues.divisionChips(PROD_LEAGUES).map { it.name }
        assertTrue("Flex is a competition of Homegrown, not a division: $names", "Flex" !in names)
    }

    @Test
    fun `retired leagues are not division chips`() {
        val names = Leagues.divisionChips(PROD_LEAGUES).map { it.name }
        assertTrue("inactive league offered as a chip: $names", "Kick Futsal" !in names)
    }

    @Test
    fun `chips are the top-level active leagues in display order`() {
        assertEquals(
            listOf("Homegrown", "Academy"),
            Leagues.divisionChips(PROD_LEAGUES).map { it.name },
        )
    }

    @Test
    fun `rows without a display order sort last, keeping API order`() {
        val leagues = listOf(
            LeagueDto(id = 9, name = "Zeta", displayOrder = null),
            LeagueDto(id = 8, name = "Yankee", displayOrder = null),
            LeagueDto(id = 1, name = "Homegrown", displayOrder = 1),
        )
        assertEquals(
            listOf("Homegrown", "Zeta", "Yankee"),
            Leagues.divisionChips(leagues).map { it.name },
        )
    }

    @Test
    fun `an API that predates the columns offers every league`() {
        // parent_league_id, is_active and display_order all default so an older
        // API keeps working rather than rendering an empty chip row.
        val leagues = listOf(LeagueDto(id = 1, name = "Homegrown"), LeagueDto(id = 2, name = "Academy"))
        assertEquals(listOf("Homegrown", "Academy"), Leagues.divisionChips(leagues).map { it.name })
    }

    @Test
    fun `default chip is Homegrown`() {
        assertEquals(1, Leagues.defaultChip(PROD_LEAGUES)?.id)
    }

    @Test
    fun `default chip falls back to the first offered league`() {
        val leagues = listOf(
            LeagueDto(id = 290, name = "Flex", parentLeagueId = 1, displayOrder = 2),
            LeagueDto(id = 2, name = "Academy", displayOrder = 3),
        )
        assertEquals(2, Leagues.defaultChip(leagues)?.id)
    }

    @Test
    fun `no offered league means no default`() {
        assertNull(Leagues.defaultChip(listOf(LeagueDto(id = 34, name = "Kick Futsal", isActive = false))))
    }
}
