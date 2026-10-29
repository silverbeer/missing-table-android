package com.missingtable.scorer.ui.live

import com.missingtable.scorer.data.api.ClockRequest
import com.missingtable.scorer.data.api.LiveMatchState
import com.missingtable.scorer.data.api.MatchEvent
import com.missingtable.scorer.data.api.MessageRequest
import com.missingtable.scorer.data.api.PositionChangeRequest
import com.missingtable.scorer.data.db.PendingAction
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OptimisticLiveTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun messageRow(id: Long, text: String) = PendingAction(
        id = id,
        clientEventId = "m$id",
        matchId = 7,
        actionType = PendingAction.ActionType.MESSAGE,
        payloadJson = json.encodeToString(MessageRequest(text, "m$id")),
        createdAt = id,
    )

    @Test
    fun `queued chat message shows newest-first as a pending message by me`() {
        val server = LiveMatchState(
            matchId = 7, homeScore = 1, awayScore = 0,
            recentEvents = listOf(MatchEvent(id = 40, matchId = 7, eventType = "goal", message = "Goal")),
        )

        val merged = OptimisticLive.merge(
            server, listOf(messageRow(3, "Great save")), json, emptyMap(),
            me = OptimisticLive.Author("u-1", "tom"),
        )

        val top = merged.recentEvents.first()
        assertEquals("message", top.eventType)
        assertEquals("Great save", top.message)
        assertEquals("u-1", top.createdBy)
        assertEquals("tom", top.createdByUsername)
        assertNull(top.teamId)
        assertTrue(OptimisticLive.isPending(top))
        assertEquals(3L, OptimisticLive.pendingRowId(top))
        // A comment never moves the score.
        assertEquals(1, merged.homeScore)
        assertEquals(0, merged.awayScore)
        assertEquals(2, merged.recentEvents.size)
    }

    @Test
    fun `queued position change shows as a pending position_change carrying its position`() {
        val row = PendingAction(
            id = 5,
            clientEventId = "p5",
            matchId = 7,
            actionType = PendingAction.ActionType.POSITION,
            payloadJson = json.encodeToString(
                PositionChangeRequest(teamId = 1, playerId = 20, position = "RW", matchMinute = 31, clientEventId = "p5")
            ),
            createdAt = 5,
        )

        val merged = OptimisticLive.merge(LiveMatchState(matchId = 7), listOf(row), json, emptyMap())

        val top = merged.recentEvents.single()
        assertEquals("position_change", top.eventType)
        assertEquals(20, top.playerId)
        assertEquals("RW", top.position)
        assertEquals(31, top.matchMinute)
        assertTrue(OptimisticLive.isPending(top))
    }

    @Test
    fun `pending pitch changes append to the full pitch list, deletes drop from it`() {
        val sub = MatchEvent(id = 10, matchId = 7, eventType = "substitution", teamId = 1, playerId = 20, playerOutId = 9)
        val gone = MatchEvent(id = 11, matchId = 7, eventType = "position_change", teamId = 1, playerId = 7, position = "ST")
        val server = LiveMatchState(matchId = 7, pitchEvents = listOf(sub, gone))
        val delete = PendingAction(
            id = 4, clientEventId = "d4", matchId = 7,
            actionType = PendingAction.ActionType.DELETE_EVENT, payloadJson = "{}", createdAt = 4,
            serverEventId = 11,
        )
        val move = PendingAction(
            id = 5, clientEventId = "p5", matchId = 7,
            actionType = PendingAction.ActionType.POSITION,
            payloadJson = json.encodeToString(PositionChangeRequest(1, 20, "RW", clientEventId = "p5")),
            createdAt = 5,
        )

        val pitch = OptimisticLive.merge(server, listOf(delete, move), json, emptyMap()).pitchEvents!!

        assertEquals(listOf(10, -5), pitch.map { it.id })
        assertEquals("RW", pitch.last().position)
    }

    @Test
    fun `no pitch list from the server stays null`() {
        assertNull(OptimisticLive.merge(LiveMatchState(matchId = 7), emptyList(), json, emptyMap()).pitchEvents)
    }

    private fun clockRow(id: Long, action: String) = PendingAction(
        id = id, clientEventId = "c$id", matchId = 7,
        actionType = PendingAction.ActionType.CLOCK,
        payloadJson = json.encodeToString(ClockRequest(action)),
        createdAt = id,
    )

    private val inSecondHalf = LiveMatchState(
        matchId = 7,
        kickoffTime = "2026-10-28T22:30:56+00:00",
        halftimeStart = "2026-10-28T22:44:59+00:00",
        secondHalfStart = "2026-10-28T22:45:33+00:00",
    )

    @Test
    fun `queued back to 1st half clears half time and the second half`() {
        val merged = OptimisticLive.merge(inSecondHalf, listOf(clockRow(1, "cancel_halftime")), json, emptyMap())
        assertNull(merged.halftimeStart)
        assertNull(merged.secondHalfStart)
        assertEquals(inSecondHalf.kickoffTime, merged.kickoffTime)
    }

    @Test
    fun `queued back to half time clears only the second half`() {
        val merged = OptimisticLive.merge(inSecondHalf, listOf(clockRow(1, "cancel_second_half")), json, emptyMap())
        assertEquals(inSecondHalf.halftimeStart, merged.halftimeStart)
        assertNull(merged.secondHalfStart)
    }
}
