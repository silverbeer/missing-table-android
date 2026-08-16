package com.missingtable.scorer.domain

import com.missingtable.scorer.data.api.MatchSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchBucketsTest {

    private val today = "2026-08-16"

    private fun match(
        id: Int,
        date: String,
        status: String?,
    ) = MatchSummary(
        id = id,
        matchDate = date,
        homeTeamId = 1,
        awayTeamId = 2,
        matchStatus = status,
    )

    @Test
    fun `past scheduled match lands in needsScoring`() {
        // SB-641: this row used to match no filter at all and vanished.
        val m = match(1, "2026-08-12", "scheduled")
        val b = MatchBucketing.bucket(listOf(m), today)
        assertEquals(listOf(m), b.needsScoring)
        assertTrue(b.todays.isEmpty())
        assertTrue(b.upcoming.isEmpty())
        assertTrue(b.recent.isEmpty())
    }

    @Test
    fun `tbd is awaiting a score whatever its date`() {
        // tbd means "played, score pending" server-side — actionable even if
        // the date is today or in the future.
        val past = match(1, "2026-08-01", "tbd")
        val future = match(2, "2026-09-01", "tbd")
        val b = MatchBucketing.bucket(listOf(past, future), today)
        assertEquals(listOf(past, future), b.needsScoring)
        assertTrue(b.upcoming.isEmpty())
    }

    @Test
    fun `today scheduled match lands in todays`() {
        val m = match(1, today, "scheduled")
        assertEquals(listOf(m), MatchBucketing.bucket(listOf(m), today).todays)
    }

    @Test
    fun `future scheduled match lands in upcoming`() {
        val m = match(1, "2026-08-23", "scheduled")
        assertEquals(listOf(m), MatchBucketing.bucket(listOf(m), today).upcoming)
    }

    @Test
    fun `live match lands in live regardless of date`() {
        val m = match(1, "2026-08-10", "live")
        val b = MatchBucketing.bucket(listOf(m), today)
        assertEquals(listOf(m), b.live)
        assertTrue(b.needsScoring.isEmpty())
    }

    @Test
    fun `finished statuses all land in recent`() {
        val completed = match(1, "2026-08-15", "completed")
        val played = match(2, "2026-08-14", "played")
        val forfeit = match(3, "2026-08-13", "forfeit")
        val b = MatchBucketing.bucket(listOf(completed, played, forfeit), today)
        assertEquals(listOf(completed, played, forfeit), b.recent)
    }

    @Test
    fun `postponed cancelled and null statuses are visible, not dropped`() {
        val postponed = match(1, "2026-08-10", "postponed")
        val cancelled = match(2, "2026-08-09", "cancelled")
        val unknown = match(3, "2026-08-08", null)
        val b = MatchBucketing.bucket(listOf(postponed, cancelled, unknown), today)
        assertEquals(3, b.other.size)
        assertEquals(3, b.size)
    }

    @Test
    fun `every row lands in exactly one bucket`() {
        // The real invariant. SB-641 was not a row in the wrong bucket, it was
        // a row in no bucket — so totality is what guards against a repeat.
        val rows = listOf(
            match(1, "2026-08-12", "scheduled"),
            match(2, today, "scheduled"),
            match(3, "2026-08-23", "scheduled"),
            match(4, "2026-08-16", "live"),
            match(5, "2026-08-15", "completed"),
            match(6, "2026-08-11", "tbd"),
            match(7, "2026-08-10", "postponed"),
            match(8, "2026-08-09", "cancelled"),
            match(9, "2026-08-08", "forfeit"),
            match(10, "2026-08-07", "played"),
            match(11, "2026-08-06", null),
            match(12, "2026-08-05", "something-new"),
        )
        val b = MatchBucketing.bucket(rows, today)
        assertEquals(rows.size, b.size)

        val placed = b.live + b.needsScoring + b.todays + b.upcoming + b.recent + b.other
        assertEquals(rows.size, placed.map { it.id }.distinct().size)
        assertEquals(rows.map { it.id }.toSet(), placed.map { it.id }.toSet())
    }

    @Test
    fun `needsScoring is oldest first so the most overdue is at the top`() {
        val newer = match(1, "2026-08-14", "scheduled")
        val older = match(2, "2026-08-11", "scheduled")
        val b = MatchBucketing.bucket(listOf(newer, older), today)
        assertEquals(listOf(older, newer), b.needsScoring)
    }

    @Test
    fun `recent is newest first and capped`() {
        val rows = (1..15).map { match(it, "2026-08-%02d".format(it), "completed") }
        val b = MatchBucketing.bucket(rows, today)
        assertEquals(MatchBucketing.RECENT_LIMIT, b.recent.size)
        assertEquals(15, b.recent.first().id)
        assertEquals(6, b.recent.last().id)
    }

    @Test
    fun `the observed prod payload renders all eight rows`() {
        // Exactly what /api/matches returned for season 184 on 2026-08-16.
        // Four TSC fixtures were invisible before this fix.
        val rows = listOf(
            match(3772, "2026-08-11", "scheduled"),
            match(3774, "2026-08-12", "scheduled"),
            match(3773, "2026-08-12", "scheduled"),
            match(3775, "2026-08-14", "scheduled"),
            match(3693, "2026-08-15", "completed"),
            match(3694, "2026-08-16", "completed"),
            match(3695, "2026-08-23", "scheduled"),
            match(3696, "2026-08-23", "scheduled"),
        )
        val b = MatchBucketing.bucket(rows, today)
        assertEquals(8, b.size)
        assertEquals(listOf(3772, 3773, 3774, 3775), b.needsScoring.map { it.id }.sorted())
        assertEquals(listOf(3693, 3694), b.recent.map { it.id }.sorted())
        assertEquals(listOf(3695, 3696), b.upcoming.map { it.id }.sorted())
    }

    @Test
    fun `empty input yields empty buckets`() {
        val b = MatchBucketing.bucket(emptyList(), today)
        assertEquals(0, b.size)
    }
}
