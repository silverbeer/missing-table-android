package com.missingtable.scorer.domain

import com.missingtable.scorer.data.api.MatchSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Matches tab's age-group filter (SB-642). The filter itself is a one-line
 * predicate; what is worth pinning is that it composes correctly with the
 * bucketing — a filtered list must still place every row it keeps.
 */
class AgeGroupFilterTest {

    private val today = "2026-08-17"

    private fun match(
        id: Int,
        date: String,
        status: String?,
        ageGroupId: Int?,
        ageGroupName: String? = null,
    ) = MatchSummary(
        id = id,
        matchDate = date,
        homeTeamId = 1,
        awayTeamId = 2,
        ageGroupId = ageGroupId,
        ageGroupName = ageGroupName,
        matchStatus = status,
    )

    /** Mirrors the screen: null age group means "All". */
    private fun filter(rows: List<MatchSummary>, ageGroupId: Int?) =
        ageGroupId?.let { id -> rows.filter { it.ageGroupId == id } } ?: rows

    private val squad = listOf(
        match(1, "2026-08-22", "scheduled", 3, "U15"),
        match(2, "2026-08-22", "scheduled", 2, "U14"),
        match(3, "2026-08-22", "scheduled", 1, "U13"),
        match(4, "2026-08-16", "completed", 3, "U15"),
        match(5, "2026-08-10", "scheduled", 2, "U14"),
        match(6, "2026-08-22", "scheduled", null, null),
    )

    @Test
    fun `no selection shows everything`() {
        assertEquals(squad.size, filter(squad, null).size)
    }

    @Test
    fun `selecting an age group keeps only that group`() {
        val u15 = filter(squad, 3)
        assertEquals(listOf(1, 4), u15.map { it.id })
    }

    @Test
    fun `a match with no age group is hidden by any selection but shown under All`() {
        // Real data has these — TSC fixtures and ad-hoc friendlies both.
        assertTrue(filter(squad, 3).none { it.id == 6 })
        assertTrue(filter(squad, null).any { it.id == 6 })
    }

    @Test
    fun `filtering then bucketing still places every remaining row`() {
        // The SB-641 totality invariant must survive the filter.
        val u14 = filter(squad, 2)
        val buckets = MatchBucketing.bucket(u14, today)
        assertEquals(u14.size, buckets.size)
    }

    @Test
    fun `filtering does not change which bucket a row lands in`() {
        val all = MatchBucketing.bucket(squad, today)
        val u15 = MatchBucketing.bucket(filter(squad, 3), today)
        // Match 4 is completed -> RECENT either way; match 1 is future -> UPCOMING.
        assertTrue(all.recent.any { it.id == 4 })
        assertTrue(u15.recent.any { it.id == 4 })
        assertTrue(all.upcoming.any { it.id == 1 })
        assertTrue(u15.upcoming.any { it.id == 1 })
    }

    @Test
    fun `an overdue match stays overdue after filtering`() {
        // Match 5 is past-dated + scheduled -> NEEDS SCORING (SB-641).
        val u14 = MatchBucketing.bucket(filter(squad, 2), today)
        assertEquals(listOf(5), u14.needsScoring.map { it.id })
    }

    @Test
    fun `chip options come from the rows present and exclude the unlabelled`() {
        val options = squad
            .mapNotNull { m -> m.ageGroupId?.let { it to (m.ageGroupName ?: "U?") } }
            .distinct()
            .sortedBy { it.second }
        assertEquals(listOf(1 to "U13", 2 to "U14", 3 to "U15"), options)
    }

    @Test
    fun `a selection matching nothing yields an empty list, not everything`() {
        // The screen shows an explicit "no matches for this age group" rather
        // than silently falling back — falling back would be worse.
        assertTrue(filter(squad, 99).isEmpty())
    }
}
