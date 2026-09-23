package com.missingtable.scorer.domain

import com.missingtable.scorer.data.api.MatchTypeDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LeaderFiltersTest {

    private val league = MatchTypeDto(id = 1, name = "League", displayOrder = 1, countsForQualification = true)
    private val flex = MatchTypeDto(id = 5, name = "Flex", displayOrder = 2, countsForQualification = true)
    private val tournament = MatchTypeDto(id = 3, name = "Tournament", displayOrder = 3)
    private val friendly = MatchTypeDto(id = 4, name = "Friendly", displayOrder = 4)

    @Test
    fun `the default is League`() {
        val available = listOf(friendly, flex, league)
        assertEquals(1, LeaderFilters.resolve(available, saved = null)?.id)
    }

    @Test
    fun `a saved choice wins over the default`() {
        val available = listOf(league, flex)
        assertEquals(5, LeaderFilters.resolve(available, saved = 5)?.id)
    }

    @Test
    fun `a saved choice this age group does not play falls back to League`() {
        // U13 plays no Flex; a Flex choice carried over from U17 would
        // otherwise rank nobody, with no way to tell why.
        val available = listOf(league, friendly)
        assertEquals(1, LeaderFilters.resolve(available, saved = 5)?.id)
        assertTrue(LeaderFilters.savedChoiceUnavailable(available, saved = 5))
    }

    @Test
    fun `a saved choice that is offered is not reported as dropped`() {
        assertFalse(LeaderFilters.savedChoiceUnavailable(listOf(league, flex), saved = 5))
    }

    @Test
    fun `nothing is dropped when nothing is available yet`() {
        // Still loading — reporting a dropped choice here would flash a
        // message and then take it back.
        assertFalse(LeaderFilters.savedChoiceUnavailable(emptyList(), saved = 5))
    }

    @Test
    fun `with no League the first competition is ranked rather than nothing`() {
        val available = listOf(tournament, friendly)
        assertEquals(3, LeaderFilters.resolve(available, saved = null)?.id)
    }

    @Test
    fun `nothing available means no selection, and the caller renders empty`() {
        assertNull(LeaderFilters.resolve(emptyList(), saved = null))
        assertNull(LeaderFilters.resolve(emptyList(), saved = 1))
    }

    @Test
    fun `chips follow display order, not the order the API happened to return`() {
        val available = listOf(friendly, tournament, flex, league)
        assertEquals(
            listOf("League", "Flex", "Tournament", "Friendly"),
            LeaderFilters.chips(available).map { it.name },
        )
    }

    @Test
    fun `a competition with no display order sorts last rather than first`() {
        val unordered = MatchTypeDto(id = 9, name = "Showcase", displayOrder = null)
        assertEquals(
            listOf("League", "Showcase"),
            LeaderFilters.chips(listOf(unordered, league)).map { it.name },
        )
    }
}
