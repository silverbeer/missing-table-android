package com.missingtable.scorer.domain

import com.missingtable.scorer.data.api.DivisionDto
import com.missingtable.scorer.data.api.LeagueDto
import com.missingtable.scorer.data.api.MatchSummary
import com.missingtable.scorer.data.api.MatchTypeDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConferencesTest {

    // Homegrown's conferences are League tables; Flex is a child league whose
    // conferences are the Flex tables.
    private val homegrown = LeagueDto(id = 1, name = "Homegrown", matchTypeId = 1)
    private val flex = LeagueDto(id = 290, name = "Flex", parentLeagueId = 1, matchTypeId = 5)
    private val leagues = listOf(homegrown, flex)

    private val leagueType = MatchTypeDto(id = 1, name = "League", displayOrder = 1, countsForQualification = true)
    private val flexType = MatchTypeDto(id = 5, name = "Flex", displayOrder = 2, countsForQualification = true)
    private val matchTypes = listOf(leagueType, flexType)

    // Florida exists in both competitions, as two different rows.
    private val northeast = DivisionDto(id = 10, name = "Northeast", leagueId = 1)
    private val floridaLeague = DivisionDto(id = 11, name = "Florida", leagueId = 1)
    private val turnpike = DivisionDto(id = 20, name = "Turnpike", leagueId = 290)
    private val floridaFlex = DivisionDto(id = 21, name = "Florida", leagueId = 290)
    private val divisions = listOf(northeast, floridaLeague, turnpike, floridaFlex)

    private var nextId = 0
    private fun m(division: DivisionDto, type: MatchTypeDto = leagueType) = MatchSummary(
        id = ++nextId,
        matchDate = "2026-09-19",
        homeTeamId = 1,
        awayTeamId = 2,
        matchTypeId = type.id,
        matchTypeName = type.name,
        divisionId = division.id,
        divisionName = division.name,
    )

    @Test
    fun `only conferences with matches get a chip`() {
        val rows = listOf(m(northeast), m(turnpike, flexType))
        assertEquals(
            listOf("Northeast", "Turnpike"),
            Conferences.visible(rows, divisions).map { it.name },
        )
    }

    @Test
    fun `conferences are sorted by name`() {
        val rows = listOf(m(turnpike, flexType), m(northeast), m(floridaLeague))
        assertEquals(
            listOf("Florida", "Northeast", "Turnpike"),
            Conferences.visible(rows, divisions).map { it.name },
        )
    }

    @Test
    fun `a conference the divisions list does not know still gets its name from the row`() {
        val rows = listOf(m(DivisionDto(id = 99, name = "Frontier", leagueId = 1)))
        val visible = Conferences.visible(rows, emptyList())
        assertEquals(listOf("Frontier"), visible.map { it.name })
        assertNull("no league is known for it", visible.single().leagueId)
    }

    @Test
    fun `Unknown is not a conference`() {
        val rows = listOf(
            MatchSummary(
                id = 1,
                matchDate = "2026-09-19",
                homeTeamId = 1,
                awayTeamId = 2,
                divisionId = 77,
                divisionName = "Unknown",
            ),
        )
        assertTrue(Conferences.visible(rows, emptyList()).isEmpty())
    }

    @Test
    fun `groups are League then Flex, in display order`() {
        val rows = listOf(m(turnpike, flexType), m(northeast))
        val groups = Conferences.groups(Conferences.visible(rows, divisions), leagues, matchTypes)
        assertEquals(listOf("League", "Flex"), groups.map { it.label })
    }

    @Test
    fun `same-named conferences in different competitions stay separate chips`() {
        // Two chips both reading "Florida" under one heading told nobody which
        // was which — the grouping is what disambiguates them.
        val rows = listOf(m(floridaLeague), m(floridaFlex, flexType))
        val groups = Conferences.groups(Conferences.visible(rows, divisions), leagues, matchTypes)
        assertEquals(2, groups.size)
        assertEquals(listOf(11), groups.first { it.label == "League" }.conferences.map { it.id })
        assertEquals(listOf(21), groups.first { it.label == "Flex" }.conferences.map { it.id })
    }

    @Test
    fun `a league that does not say its competition groups as Other, last`() {
        val unknownLeague = LeagueDto(id = 7, name = "Mystery")
        val mystery = DivisionDto(id = 70, name = "Mystery Conference", leagueId = 7)
        val rows = listOf(m(mystery), m(northeast))
        val groups = Conferences.groups(
            Conferences.visible(rows, divisions + mystery),
            leagues + unknownLeague,
            matchTypes,
        )
        assertEquals(listOf("League", "Other"), groups.map { it.label })
    }

    @Test
    fun `an empty selection filters nothing`() {
        val rows = listOf(m(northeast), m(turnpike, flexType))
        assertEquals(rows.size, Conferences.filter(rows, emptySet()).size)
    }

    @Test
    fun `a selection keeps every conference in it`() {
        // Conferences are neighbours, not alternatives: a border club plays
        // several in one season and wants them in one list.
        val rows = listOf(m(northeast), m(turnpike, flexType), m(floridaLeague))
        val kept = Conferences.filter(rows, setOf(10, 20))
        assertEquals(2, kept.size)
        assertTrue(kept.none { it.divisionId == 11 })
    }

    @Test
    fun `pruning drops conferences the current view does not play`() {
        val visible = Conferences.visible(listOf(m(northeast)), divisions)
        assertEquals(setOf(10), Conferences.prune(setOf(10, 20), visible))
    }

    @Test
    fun `pruning leaves the selection alone while conferences are still loading`() {
        // Matches load async; pruning against an empty list would wipe it.
        assertEquals(setOf(10, 20), Conferences.prune(setOf(10, 20), emptyList()))
    }

    @Test
    fun `competitionOfLeague resolves a league to the competition its tables are`() {
        assertEquals("League", Conferences.competitionOfLeague(leagues, matchTypes, 1))
        assertEquals("Flex", Conferences.competitionOfLeague(leagues, matchTypes, 290))
        assertNull(Conferences.competitionOfLeague(leagues, matchTypes, null))
        assertNull(Conferences.competitionOfLeague(leagues, matchTypes, 404))
    }

    @Test
    fun `conference filtering runs before the competition counts`() {
        // The web narrows by conference first, so the chip counts describe the
        // list underneath. Selecting Northeast must not leave "Flex 1" on a
        // list with no Flex fixtures in it.
        val rows = listOf(m(northeast), m(northeast), m(turnpike, flexType))
        val chips = Competitions.chips(Conferences.filter(rows, setOf(10)), matchTypes)
        assertEquals(listOf("League", "All"), chips.map { it.label })
        assertEquals(2, chips.first { it.label == "League" }.count)
    }
}
