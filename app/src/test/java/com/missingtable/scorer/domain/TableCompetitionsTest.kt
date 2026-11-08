package com.missingtable.scorer.domain

import com.missingtable.scorer.data.api.MatchTypeDto
import com.missingtable.scorer.data.api.TableCoverage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SB-1596: the Table tab's Competition row, mirroring LeagueTable.vue. */
class TableCompetitionsTest {

    private val league = MatchTypeDto(1, "League", 1, countsForQualification = true, hasStandings = true, inDivision = 40)
    private val flex = MatchTypeDto(5, "Flex", 2, countsForQualification = true, hasStandings = true, inDivision = 0)
    private val friendly = MatchTypeDto(4, "Friendly", 4, hasStandings = false)

    @Test
    fun `homegrown U15 offers League, Flex and League + Flex, without Friendly`() {
        val chips = TableCompetitions.chips(listOf(league, flex, friendly))
        assertEquals(listOf("League", "Flex", "League + Flex"), chips.map { it.label })
        assertEquals(listOf("League", "Flex", "qualifying"), chips.map { it.value })
        assertTrue(chips.last().isCombined)
    }

    @Test
    fun `an API without has_standings hides nothing`() {
        val old = MatchTypeDto(4, "Friendly", 4, hasStandings = null)
        assertEquals(listOf("Friendly"), TableCompetitions.chips(listOf(old)).map { it.value })
    }

    @Test
    fun `U13 plays League alone, so no combined chip`() {
        assertEquals(listOf("League"), TableCompetitions.chips(listOf(league)).map { it.value })
    }

    @Test
    fun `the division opens on its own competition`() {
        assertEquals("League", TableCompetitions.default(listOf(flex, league)))
        assertEquals("Flex", TableCompetitions.default(listOf(flex)))
        assertEquals("League", TableCompetitions.default(emptyList()))
    }

    @Test
    fun `a choice still offered is kept, including the combined one`() {
        val available = listOf(league, flex)
        assertEquals("Flex", TableCompetitions.resolve(available, "Flex"))
        assertEquals("qualifying", TableCompetitions.resolve(available, "qualifying"))
    }

    @Test
    fun `a choice not played here falls back to the division's own`() {
        assertEquals("League", TableCompetitions.resolve(listOf(league), "Flex"))
        assertEquals("League", TableCompetitions.resolve(listOf(league), "qualifying"))
        assertEquals("League", TableCompetitions.resolve(listOf(league, flex), null))
    }

    @Test
    fun `conferences are asked for per competition, except the combined view`() {
        assertEquals("Flex", TableCompetitions.conferencesMatchType("Flex"))
        assertNull(TableCompetitions.conferencesMatchType("qualifying"))
    }

    @Test
    fun `conference keeps current, else Northeast, else first`() {
        val options = listOf(10 to "Mid-Atlantic", 11 to "Northeast", 12 to "New England")
        assertEquals(12, TableCompetitions.resolveConference(options, 12))
        assertEquals(11, TableCompetitions.resolveConference(options, 99))
        assertEquals(10, TableCompetitions.resolveConference(listOf(10 to "Mid-Atlantic"), null))
        assertNull(TableCompetitions.resolveConference(emptyList(), 3))
    }

    @Test
    fun `flex explains its shootout points`() {
        assertEquals(
            "Flex: a match level after regulation goes to penalties. " +
                "Shootout winner 2 pts, loser 1. Counted as a draw in D.",
            TableCompetitions.shootoutNote(TableCoverage(shootoutPoints = listOf("Flex"))),
        )
        assertEquals("", TableCompetitions.shootoutNote(TableCoverage()))
        assertEquals("", TableCompetitions.shootoutNote(null))
    }

    @Test
    fun `combined view says what each competition contributed, zero included`() {
        val c = TableCoverage(
            competitions = listOf("Flex", "League"),
            countedByCompetition = mapOf("Flex" to 0, "League" to 3),
        )
        assertEquals(
            "Flex + League combined — Flex 0 played · League 3 played. A record, not a standing.",
            TableCompetitions.coverageNote(c),
        )
    }

    @Test
    fun `single competition with outside matches notes them, otherwise nothing`() {
        assertEquals("", TableCompetitions.coverageNote(TableCoverage(competitions = listOf("League"))))
        assertEquals(
            "League combined — includes 2 matches against 1 team outside this table.",
            TableCompetitions.coverageNote(
                TableCoverage(competitions = listOf("League"), matchesVsOutsideTable = 2, teamsOutsideTable = 1),
            ),
        )
    }

    @Test
    fun `empty table says whether fixtures exist`() {
        assertEquals("3 fixtures scheduled, none played yet.", TableCompetitions.emptyNote(TableCoverage(fixtures = 3)))
        assertEquals("No completed matches yet", TableCompetitions.emptyNote(null))
    }
}
