package com.missingtable.scorer.domain

import com.missingtable.scorer.data.api.SeasonDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeasonsTest {

    @Test
    fun `is_current wins even when older`() {
        val picked = Seasons.pickCurrent(
            listOf(
                SeasonDto(1, "2027-2028", isCurrent = false, startDate = "2027-08-01"),
                SeasonDto(2, "2026-2027", isCurrent = true, startDate = "2026-08-01"),
            )
        )
        assertEquals(2, picked?.id)
    }

    @Test
    fun `no flag falls back to newest by start_date`() {
        val picked = Seasons.pickCurrent(
            listOf(
                SeasonDto(1, "2025-2026", startDate = "2025-08-01"),
                SeasonDto(2, "2026-2027", startDate = "2026-08-01"),
            )
        )
        assertEquals(2, picked?.id)
    }

    @Test
    fun `empty list picks nothing`() {
        assertNull(Seasons.pickCurrent(emptyList()))
    }

    // Saturday 2026-11-14: this week starts Monday 2026-11-09.
    private val today = java.time.LocalDate.parse("2026-11-14")
    private val last = SeasonDto(1, "2025-2026", startDate = "2025-08-01", endDate = "2026-07-31")
    private val now = SeasonDto(2, "2026-2027", isCurrent = true, startDate = "2026-08-01")
    private val next = SeasonDto(3, "2027-2028", startDate = "2027-08-01")

    @Test
    fun `the running season opens on this week`() {
        assertEquals(0, Seasons.weekOffsetFor(now, listOf(last, now, next), today))
    }

    @Test
    fun `a finished season opens on its last week`() {
        val offset = Seasons.weekOffsetFor(last, listOf(last, now), today)
        // 2026-07-31 is a Friday; its week starts Monday 2026-07-27.
        assertEquals(MatchWeek.of(today, offset).start.toString(), "2026-07-27")
    }

    @Test
    fun `a season with no end_date ends the day before the next one starts`() {
        val open = last.copy(endDate = null)
        val offset = Seasons.weekOffsetFor(open, listOf(open, now), today)
        assertEquals("2026-07-27", MatchWeek.of(today, offset).start.toString())
    }

    @Test
    fun `a future season opens on its first week`() {
        val offset = Seasons.weekOffsetFor(next, listOf(last, now, next), today)
        assertEquals("2027-07-26", MatchWeek.of(today, offset).start.toString())
    }
}
