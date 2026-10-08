package com.missingtable.scorer.data.sync

import com.missingtable.scorer.data.api.CardRequest
import com.missingtable.scorer.data.api.ClockRequest
import com.missingtable.scorer.data.api.GoalRequest
import com.missingtable.scorer.data.api.LineupSaveRequest
import com.missingtable.scorer.data.api.MessageRequest
import com.missingtable.scorer.data.api.MtApi
import com.missingtable.scorer.data.api.SubstitutionRequest
import com.missingtable.scorer.data.db.PendingAction
import com.missingtable.scorer.data.db.PendingActionDao
import java.io.IOException
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import retrofit2.HttpException

/**
 * Drains the pending-action queue in strict FIFO order, one action in flight
 * at a time. Ordering is the correctness guarantee (a sub must land before
 * the goal scored by the player it brought on), so the engine never skips:
 * transient failures (offline, 5xx) back off and retry the same action;
 * a 4xx marks the head FAILED and pauses the whole queue until the user
 * retries or discards it. Replays are safe — the server dedupes on
 * client_event_id and clock actions are naturally idempotent.
 */
class SyncEngine(
    private val dao: PendingActionDao,
    private val api: MtApi,
    private val json: Json,
    scope: CoroutineScope,
    private val backoffBaseMs: Long = 2_000,
    private val backoffCapMs: Long = 60_000,
    // Tests drive drain() by hand instead of racing the background loop.
    autoStart: Boolean = true,
) {
    private val kicks = Channel<Unit>(Channel.CONFLATED)
    private val drainLock = Mutex()

    /** Wake the engine — call on enqueue, connectivity regained, app foreground. */
    fun kick() {
        kicks.trySend(Unit)
    }

    init {
        if (autoStart) scope.launch {
            dao.resetInFlight()
            var backoffMs = backoffBaseMs
            while (true) {
                when (drain()) {
                    DrainResult.EMPTY, DrainResult.PAUSED -> {
                        // Nothing runnable — sleep until someone kicks.
                        kicks.receive()
                        backoffMs = backoffBaseMs
                    }
                    DrainResult.TRANSIENT_FAILURE -> {
                        // Same action retries; an early kick resets the backoff.
                        val kicked = withTimeoutOrNull(backoffMs) { kicks.receive() } != null
                        backoffMs = if (kicked) backoffBaseMs else min(backoffMs * 2, backoffCapMs)
                    }
                }
            }
        }
    }

    enum class DrainResult { EMPTY, PAUSED, TRANSIENT_FAILURE }

    /** One pass from the head: runs until empty, a FAILED head, or a transient error. */
    suspend fun drain(): DrainResult = drainLock.withLock {
        while (true) {
            val head = dao.head() ?: return DrainResult.EMPTY
            if (head.status == PendingAction.Status.FAILED) return DrainResult.PAUSED
            dao.setStatus(head.id, PendingAction.Status.IN_FLIGHT, head.attemptCount, head.lastError)

            val failure = execute(head)
            when {
                failure == null -> dao.delete(head.id)
                failure.transient -> {
                    dao.setStatus(
                        head.id, PendingAction.Status.PENDING, head.attemptCount + 1, failure.message
                    )
                    return DrainResult.TRANSIENT_FAILURE
                }
                else -> {
                    dao.setStatus(
                        head.id, PendingAction.Status.FAILED, head.attemptCount + 1, failure.message
                    )
                    return DrainResult.PAUSED
                }
            }
        }
        @Suppress("UNREACHABLE_CODE") DrainResult.EMPTY
    }

    /** Un-fail the head so the next drain retries it. */
    suspend fun retryFailed(id: Long) {
        val row = dao.byId(id) ?: return
        dao.setStatus(row.id, PendingAction.Status.PENDING, row.attemptCount, row.lastError)
        kick()
    }

    /** Drop a FAILED action and let the rest of the queue flow. */
    suspend fun discardFailed(id: Long) {
        val row = dao.byId(id) ?: return
        if (row.status == PendingAction.Status.FAILED) {
            dao.delete(row.id)
            kick()
        }
    }

    private class Failure(val transient: Boolean, val message: String?)

    private suspend fun execute(action: PendingAction): Failure? = try {
        when (action.actionType) {
            PendingAction.ActionType.GOAL ->
                api.postGoal(action.matchId, json.decodeFromString<GoalRequest>(action.payloadJson))
            PendingAction.ActionType.CARD ->
                api.postCard(action.matchId, json.decodeFromString<CardRequest>(action.payloadJson))
            PendingAction.ActionType.SUB ->
                api.postSubstitution(
                    action.matchId, json.decodeFromString<SubstitutionRequest>(action.payloadJson)
                )
            PendingAction.ActionType.CLOCK ->
                api.postClock(action.matchId, json.decodeFromString<ClockRequest>(action.payloadJson))
            PendingAction.ActionType.DELETE_EVENT ->
                api.deleteEvent(action.matchId, requireNotNull(action.serverEventId))
            PendingAction.ActionType.REOPEN -> api.reopenMatch(action.matchId)
            PendingAction.ActionType.MESSAGE ->
                api.postMessage(action.matchId, json.decodeFromString<MessageRequest>(action.payloadJson))
            PendingAction.ActionType.LINEUP_SAVE ->
                api.putLineup(
                    action.matchId,
                    requireNotNull(action.teamId),
                    json.decodeFromString<LineupSaveRequest>(action.payloadJson),
                )
        }
        null
    } catch (e: IOException) {
        Failure(transient = true, message = e.message ?: "offline")
    } catch (e: HttpException) {
        // 401 is TRANSIENT (SB-779). It means the token needed refreshing, not
        // that this action is invalid. TokenAuthenticator refreshes and retries
        // once, but when the refresh itself fails transiently (the backend
        // returns 503 on transient failures, SB-123) the original 401 reaches
        // here. Treating it as terminal marked the action FAILED and paused the
        // whole queue in strict FIFO — permanently, since nothing retries a
        // FAILED head. One momentary auth hiccup then silently stopped every
        // subsequent event in a match.
        //
        // Retrying is safe: every write carries a client_event_id the server
        // dedupes on, and clock actions are idempotent. If the refresh token is
        // genuinely dead, TokenAuthenticator clears tokens and the UI drops to
        // login; the queue retries harmlessly until sign-in, then drains.
        //
        // 403 stays terminal — that is a real answer about this action, not a
        // recoverable auth state.
        val transient = e.code() >= 500 || e.code() == 408 || e.code() == 429 || e.code() == 401
        Failure(transient = transient, message = "HTTP ${e.code()}")
    }
}
