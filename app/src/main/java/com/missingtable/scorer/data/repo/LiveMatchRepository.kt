package com.missingtable.scorer.data.repo

import android.content.Context
import com.missingtable.scorer.data.api.CardRequest
import com.missingtable.scorer.data.api.ClockRequest
import com.missingtable.scorer.data.api.GoalRequest
import com.missingtable.scorer.data.api.LineupSaveRequest
import com.missingtable.scorer.data.api.SubstitutionRequest
import com.missingtable.scorer.data.db.PendingAction
import com.missingtable.scorer.data.db.PendingActionDao
import com.missingtable.scorer.data.sync.SyncEngine
import com.missingtable.scorer.data.sync.SyncWorker
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

/**
 * Enqueue-only write path for live scoring. Every mutation lands in Room
 * first (so it survives offline and process death) and the SyncEngine ships
 * it FIFO. Minute/extra_time must already be baked into the request DTOs by
 * the caller — they are captured at tap time, not at sync time.
 */
class LiveMatchRepository(
    private val dao: PendingActionDao,
    private val engine: SyncEngine,
    private val json: Json,
    private val appContext: Context,
) {

    fun pendingForMatch(matchId: Int): Flow<List<PendingAction>> = dao.observeForMatch(matchId)

    fun pendingCount(): Flow<Int> = dao.observeCount()

    suspend fun enqueueGoal(matchId: Int, req: GoalRequest) = enqueue(
        matchId, PendingAction.ActionType.GOAL, json.encodeToString(req), req.clientEventId
    )

    suspend fun enqueueCard(matchId: Int, req: CardRequest) = enqueue(
        matchId, PendingAction.ActionType.CARD, json.encodeToString(req), req.clientEventId
    )

    suspend fun enqueueSubstitution(matchId: Int, req: SubstitutionRequest) = enqueue(
        matchId, PendingAction.ActionType.SUB, json.encodeToString(req), req.clientEventId
    )

    // Clock actions carry no client_event_id (they're naturally idempotent
    // server-side); the row still needs a unique key for the queue.
    suspend fun enqueueClock(matchId: Int, req: ClockRequest) = enqueue(
        matchId, PendingAction.ActionType.CLOCK, json.encodeToString(req), UUID.randomUUID().toString()
    )

    suspend fun enqueueDeleteEvent(matchId: Int, serverEventId: Int) = enqueue(
        matchId, PendingAction.ActionType.DELETE_EVENT, "{}", UUID.randomUUID().toString(),
        serverEventId = serverEventId,
    )

    suspend fun enqueueReopen(matchId: Int) = enqueue(
        matchId, PendingAction.ActionType.REOPEN, "{}", UUID.randomUUID().toString()
    )

    suspend fun enqueueLineupSave(matchId: Int, teamId: Int, req: LineupSaveRequest) = enqueue(
        matchId, PendingAction.ActionType.LINEUP_SAVE, json.encodeToString(req),
        UUID.randomUUID().toString(), teamId = teamId,
    )

    /**
     * Delete a not-yet-synced action locally — never touches the API.
     * Returns false if the action is already in flight (or already synced),
     * in which case the caller should delete the server event instead.
     */
    suspend fun deletePendingRow(id: Long): Boolean = dao.deleteIfNotInFlight(id) > 0

    suspend fun retryFailed(id: Long) = engine.retryFailed(id)

    suspend fun discardFailed(id: Long) = engine.discardFailed(id)

    private suspend fun enqueue(
        matchId: Int,
        type: PendingAction.ActionType,
        payloadJson: String,
        clientEventId: String,
        teamId: Int? = null,
        serverEventId: Int? = null,
    ) {
        dao.insert(
            PendingAction(
                clientEventId = clientEventId,
                matchId = matchId,
                actionType = type,
                payloadJson = payloadJson,
                teamId = teamId,
                serverEventId = serverEventId,
                createdAt = System.currentTimeMillis(),
            )
        )
        engine.kick()
        SyncWorker.schedule(appContext)
    }
}
