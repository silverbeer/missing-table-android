package com.missingtable.scorer.domain

import com.missingtable.scorer.data.api.MatchSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchTypeFilterTest {

    private fun m(id: Int, type: String?) = MatchSummary(
        id = id,
        matchDate = "2026-08-18",
        homeTeamId = 1,
        awayTeamId = 2,
        matchTypeName = type,
    )

    private val friendliesOnly = listOf(m(1, "Friendly"), m(2, "Friendly"))
    private val mixed = listOf(m(1, "League"), m(2, "Friendly"), m(3, "Tournament"))

    @Test
    fun `options come from the rows and are deduped`() {
        assertEquals(listOf("Friendly", "League", "Tournament"), MatchTypeFilter.options(mixed))
        assertEquals(listOf("Friendly"), MatchTypeFilter.options(friendliesOnly))
    }

    @Test
    fun `a single type selects itself`() {
        // The preseason case: everything is a Friendly, so showing Friendly is
        // the only sensible default. A hardcoded League would show nothing.
        assertEquals("Friendly", MatchTypeFilter.resolve(friendliesOnly, null, false))
    }

    @Test
    fun `several types default to All so nothing is hidden`() {
        assertNull(MatchTypeFilter.resolve(mixed, null, false))
    }

    @Test
    fun `an explicit choice wins over the dynamic default`() {
        assertEquals("Friendly", MatchTypeFilter.resolve(mixed, "Friendly", true))
        assertEquals("Tournament", MatchTypeFilter.resolve(mixed, "Tournament", true))
    }

    @Test
    fun `explicitly choosing All is respected, not re-defaulted`() {
        // "chose All" and "never chose" must not collapse — otherwise picking
        // All in a single-type week would silently snap back to that type.
        assertNull(MatchTypeFilter.resolve(friendliesOnly, null, true))
    }

    @Test
    fun `a stale choice that matches nothing falls back to All`() {
        // A sticky "League" from mid-season must not blank the screen in a
        // preseason of friendlies — a blank list at a pitch is unexplainable.
        assertNull(MatchTypeFilter.resolve(friendliesOnly, "League", true))
        assertTrue(MatchTypeFilter.savedChoiceUnavailable(friendliesOnly, "League"))
    }

    @Test
    fun `a usable saved choice is not reported as dropped`() {
        assertFalse(MatchTypeFilter.savedChoiceUnavailable(mixed, "League"))
        assertFalse(MatchTypeFilter.savedChoiceUnavailable(friendliesOnly, "Friendly"))
    }

    @Test
    fun `an empty list never claims a choice was dropped`() {
        // Nothing loaded yet is not the same as "your filter matched nothing".
        assertFalse(MatchTypeFilter.savedChoiceUnavailable(emptyList(), "League"))
        assertNull(MatchTypeFilter.resolve(emptyList(), null, false))
    }

    @Test
    fun `rows with no type do not create a phantom chip`() {
        val rows = listOf(m(1, "League"), m(2, null))
        assertEquals(listOf("League"), MatchTypeFilter.options(rows))
    }

    @Test
    fun `the real season resolves to Friendly with no user input`() {
        // Every fixture in season 184 is a Friendly, including both match-day
        // games — this is the case a hardcoded League default would break.
        val season = listOf(m(3695, "Friendly"), m(3696, "Friendly"), m(3801, "Friendly"))
        assertEquals("Friendly", MatchTypeFilter.resolve(season, null, false))
    }
}
