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
}
