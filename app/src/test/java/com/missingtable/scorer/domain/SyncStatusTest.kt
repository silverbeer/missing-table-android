package com.missingtable.scorer.domain

import com.missingtable.scorer.data.db.PendingAction
import com.missingtable.scorer.data.db.PendingAction.ActionType
import com.missingtable.scorer.data.db.PendingAction.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SB-1592: the app-wide banner sees every match's rows, not just the open one. */
class SyncStatusTest {

    private fun row(
        id: Long,
        matchId: Int = 7,
        type: ActionType = ActionType.GOAL,
        status: Status = Status.PENDING,
        error: String? = null,
        createdAt: Long = 0,
        attempts: Int = 0,
    ) = PendingAction(
        id = id, clientEventId = "c$id", matchId = matchId, actionType = type, payloadJson = "{}",
        status = status, lastError = error, createdAt = createdAt, attemptCount = attempts,
    )

    @Test
    fun `empty queue shows nothing`() {
        assertNull(SyncStatus.from(emptyList(), nowMs = 1_000_000))
    }

    @Test
    fun `a fresh queue draining normally shows nothing`() {
        assertNull(SyncStatus.from(listOf(row(1, createdAt = 990_000)), nowMs = 1_000_000))
    }

    @Test
    fun `rows older than the stale threshold show as waiting`() {
        val s = SyncStatus.from(listOf(row(1, createdAt = 0), row(2)), nowMs = SyncStatus.STALE_MS)!!
        assertEquals(SyncStatus.Kind.WAITING, s.kind)
        assertEquals(2, s.unsent)
        assertFalse(s.canRetry)
        assertFalse(s.canDiscard)
    }

    @Test
    fun `a head with a transient error shows as retrying once stale`() {
        val s = SyncStatus.from(listOf(row(1, error = "HTTP 503", createdAt = 0)), nowMs = SyncStatus.STALE_MS)!!
        assertEquals(SyncStatus.Kind.RETRYING, s.kind)
    }

    @Test
    fun `a brief transient error shows nothing, so the screen doesn't jump`() {
        assertNull(SyncStatus.from(listOf(row(1, error = "timeout", createdAt = 999_000)), nowMs = 1_000_000))
    }

    @Test
    fun `a failed row from another match is the blocker the banner names`() {
        // The 2026-11-07 shape: a rejected action on match 5 holding back match 7.
        val rows = listOf(
            row(1, matchId = 5, status = Status.FAILED, error = "HTTP 422"),
            row(2, matchId = 7),
            row(3, matchId = 7),
        )
        val s = SyncStatus.from(rows, nowMs = 0)!!
        assertEquals(SyncStatus.Kind.BLOCKED, s.kind)
        assertEquals(1L, s.row.id)
        assertEquals(5, s.row.matchId)
        assertEquals(listOf(5, 7), s.matchIds)
        assertEquals(3, s.unsent)
        assertTrue(s.canRetry)
        assertTrue(s.canDiscard)
    }

    @Test
    fun `a failed lineup is reported but not as blocking`() {
        val rows = listOf(
            row(1, type = ActionType.LINEUP_SAVE, status = Status.FAILED, error = "HTTP 400"),
            row(2),
        )
        val s = SyncStatus.from(rows, nowMs = 0)!!
        assertEquals(SyncStatus.Kind.LINEUP_FAILED, s.kind)
        assertTrue(s.canDiscard)
    }

    @Test
    fun `a stuck head behind a failed lineup is reported as stuck, not as lineup only`() {
        val rows = listOf(
            row(1, type = ActionType.LINEUP_SAVE, status = Status.FAILED, error = "HTTP 422"),
            row(2, error = "HTTP 503", createdAt = 0),
        )
        val s = SyncStatus.from(rows, nowMs = SyncStatus.STALE_MS)!!
        assertEquals(SyncStatus.Kind.RETRYING, s.kind)
        assertEquals(2L, s.row.id)
    }

    @Test
    fun `a real blocker outranks a failed lineup ahead of it`() {
        val rows = listOf(
            row(1, type = ActionType.LINEUP_SAVE, status = Status.FAILED),
            row(2, status = Status.FAILED, error = "HTTP 403"),
        )
        val s = SyncStatus.from(rows, nowMs = 0)!!
        assertEquals(SyncStatus.Kind.BLOCKED, s.kind)
        assertEquals(2L, s.row.id)
    }

}
