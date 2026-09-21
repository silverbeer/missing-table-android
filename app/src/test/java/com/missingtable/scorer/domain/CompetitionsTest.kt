package com.missingtable.scorer.domain

import com.missingtable.scorer.data.api.MatchSummary
import com.missingtable.scorer.data.api.MatchTypeDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompetitionsTest {

    private val league = MatchTypeDto(id = 1, name = "League", displayOrder = 1, countsForQualification = true)
    private val flex = MatchTypeDto(id = 5, name = "Flex", displayOrder = 2, countsForQualification = true)
    private val tournament = MatchTypeDto(id = 3, name = "Tournament", displayOrder = 3)
    private val friendly = MatchTypeDto(id = 4, name = "Friendly", displayOrder = 4)
    private val types = listOf(league, flex, tournament, friendly)

    private var nextId = 0
    private fun m(type: MatchTypeDto) = MatchSummary(
        id = ++nextId,
        matchDate = "2026-09-19",
        homeTeamId = 1,
        awayTeamId = 2,
        matchTypeId = type.id,
        matchTypeName = type.name,
    )

    private fun rows(vararg spec: Pair<MatchTypeDto, Int>) =
        spec.flatMap { (type, count) -> List(count) { m(type) } }

    private fun labels(chips: List<Competitions.Chip>) = chips.map { it.label }

    @Test
    fun `chips are League, Flex, the combined chip, then All`() {
        val chips = Competitions.chips(rows(league to 3, flex to 2), types)
        assertEquals(listOf("League", "Flex", "League + Flex", "All"), labels(chips))
    }

    @Test
    fun `chips follow display order, not the order matches arrive in`() {
        val chips = Competitions.chips(rows(friendly to 1, flex to 1, league to 1), types)
        assertEquals(listOf("League", "Flex", "League + Flex", "Friendly", "All"), labels(chips))
    }

    @Test
    fun `a competition with no matches gets no chip`() {
        // U13 plays no Flex: a chip that can only return nothing promises
        // data that is not coming.
        val chips = Competitions.chips(rows(league to 4), types)
        assertEquals(listOf("League", "All"), labels(chips))
    }

    @Test
    fun `the combined chip needs more than one qualifying competition`() {
        val chips = Competitions.chips(rows(league to 4, friendly to 1), types)
        assertEquals(listOf("League", "Friendly", "All"), labels(chips))
    }

    @Test
    fun `the combined label is read from the API names`() {
        val renamed = listOf(
            league.copy(name = "Regular Season"),
            flex.copy(name = "Flex"),
        )
        val chips = Competitions.chips(rows(league to 1, flex to 1), renamed)
        assertTrue("Regular Season + Flex" in labels(chips))
    }

    @Test
    fun `counts are per competition and All counts everything`() {
        val chips = Competitions.chips(rows(league to 3, flex to 2, friendly to 1), types)
        val byLabel = chips.associate { it.label to it.count }
        assertEquals(3, byLabel["League"])
        assertEquals(2, byLabel["Flex"])
        assertEquals(5, byLabel["League + Flex"])
        assertEquals(6, byLabel["All"])
    }

    @Test
    fun `the combined chip selects the union of League and Flex`() {
        val all = rows(league to 3, flex to 2, friendly to 1)
        val combined = Competitions.chips(all, types).first { it.key == Competitions.QUALIFYING }
        val filtered = Competitions.filter(all, combined)
        assertEquals(5, filtered.size)
        assertTrue(filtered.none { it.matchTypeName == "Friendly" })
    }

    @Test
    fun `All filters nothing`() {
        val all = rows(league to 3, friendly to 1)
        val chip = Competitions.chips(all, types).first { it.isAll }
        assertEquals(all.size, Competitions.filter(all, chip).size)
    }

    @Test
    fun `the default is All when several competitions are present`() {
        // It used to default to League, which is why six Flex fixtures looked
        // missing (SB-849) — a schedule should open showing the schedule.
        val chips = Competitions.chips(rows(league to 3, flex to 2), types)
        assertTrue(Competitions.resolve(chips, saved = null, userHasChosen = false).isAll)
    }

    @Test
    fun `a lone competition selects itself`() {
        val chips = Competitions.chips(rows(friendly to 4), types)
        assertEquals("Friendly", Competitions.resolve(chips, saved = null, userHasChosen = false).label)
    }

    @Test
    fun `an explicit choice is honoured`() {
        val chips = Competitions.chips(rows(league to 3, flex to 2), types)
        assertEquals("Flex", Competitions.resolve(chips, saved = "5", userHasChosen = true).label)
    }

    @Test
    fun `an explicit All is honoured over the lone-competition default`() {
        val chips = Competitions.chips(rows(friendly to 4), types)
        assertTrue(Competitions.resolve(chips, saved = null, userHasChosen = true).isAll)
    }

    @Test
    fun `a saved choice that matches nothing falls back to All`() {
        val chips = Competitions.chips(rows(friendly to 4), types)
        assertTrue(Competitions.resolve(chips, saved = "5", userHasChosen = true).isAll)
        assertTrue(Competitions.savedChoiceUnavailable(chips, "5"))
    }

    @Test
    fun `a choice saved before chips were id-based still resolves`() {
        // The old pref stored the competition's name.
        val chips = Competitions.chips(rows(league to 3, flex to 2), types)
        assertEquals("Flex", Competitions.resolve(chips, saved = "Flex", userHasChosen = true).label)
        assertFalse(Competitions.savedChoiceUnavailable(chips, "Flex"))
    }

    @Test
    fun `no rows means only the All chip`() {
        assertEquals(listOf("All"), labels(Competitions.chips(emptyList(), types)))
    }

    @Test
    fun `chips still work when the match-type payload is missing`() {
        // The fetch failed, or an API that predates /api/match-types: name the
        // chips from the rows rather than losing the filter.
        val chips = Competitions.chips(rows(league to 3, flex to 2), emptyList())
        assertEquals(listOf("Flex", "League", "All"), labels(chips))
        assertEquals(3, chips.first { it.label == "League" }.count)
    }
}
