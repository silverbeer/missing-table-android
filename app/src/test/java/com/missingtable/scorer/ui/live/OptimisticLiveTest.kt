package com.missingtable.scorer.ui.live

import com.missingtable.scorer.data.api.LiveMatchState
import com.missingtable.scorer.data.api.MatchEvent
import com.missingtable.scorer.data.api.MessageRequest
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
}
