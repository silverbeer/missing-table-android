package com.missingtable.scorer.domain

import com.missingtable.scorer.data.api.DivisionDto
import com.missingtable.scorer.data.api.LeagueDto
import com.missingtable.scorer.data.api.MatchSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgramsTest {

    // The prod shape: Flex is a child of Homegrown; Kick Futsal is retired.
    private val homegrown = LeagueDto(id = 1, name = "Homegrown", matchTypeId = 1, displayOrder = 1)
    private val flex = LeagueDto(id = 290, name = "Flex", parentLeagueId = 1, matchTypeId = 5, displayOrder = 2)
    private val academy = LeagueDto(id = 2, name = "Academy", matchTypeId = 1, displayOrder = 3)
    private val futsal = LeagueDto(id = 34, name = "Kick Futsal", isActive = false)
    private val leagues = listOf(academy, flex, homegrown, futsal)

    private val hgNortheast = DivisionDto(id = 10, name = "Northeast", leagueId = 1)
    private val empire = DivisionDto(id = 20, name = "Empire", leagueId = 290)
    private val acNortheast = DivisionDto(id = 30, name = "Northeast", leagueId = 2)
    private val divisions = listOf(hgNortheast, empire, acNortheast)

    private var nextId = 0
    private fun m(division: DivisionDto?, leagueName: String? = null) = MatchSummary(
        id = ++nextId,
        matchDate = "2026-11-14",
        homeTeamId = 1,
        awayTeamId = 2,
        divisionId = division?.id,
        leagueName = leagueName,
    )

    @Test
    fun `Flex fixtures belong to Homegrown`() {
        assertEquals(1, Programs.programOf(m(empire), divisions, leagues))
    }

    @Test
    fun `options are the active top-level leagues, in display order, with counts`() {
        val rows = listOf(m(hgNortheast), m(empire), m(acNortheast))
        val options = Programs.options(rows, divisions, leagues)
        assertEquals(listOf("Homegrown" to 2, "Academy" to 1), options.map { it.name to it.count })
    }

    @Test
    fun `a program with no matches is still offered, at zero`() {
        // Academy U15+ has no fall fixtures in high-school season; the chip
        // must stay so its absence reads as a quiet week, not a bug.
        val options = Programs.options(listOf(m(hgNortheast), m(empire)), divisions, leagues)
        assertEquals(0, options.single { it.name == "Academy" }.count)
    }

    @Test
    fun `filtering to Homegrown keeps League and Flex, drops Academy`() {
        val rows = listOf(m(hgNortheast), m(empire), m(acNortheast))
        assertEquals(listOf(10, 20), Programs.filter(rows, 1, divisions, leagues).map { it.divisionId })
        assertEquals(3, Programs.filter(rows, null, divisions, leagues).size)
    }

    @Test
    fun `a row with no known conference falls back to its league name`() {
        assertEquals(2, Programs.programOf(m(null, leagueName = "Academy"), divisions, leagues))
        assertNull(Programs.programOf(m(null), divisions, leagues))
    }
}
