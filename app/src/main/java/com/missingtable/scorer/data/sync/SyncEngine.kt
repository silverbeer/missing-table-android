package com.missingtable.scorer.data.sync

import android.util.Log
import com.missingtable.scorer.data.api.CardRequest
import com.missingtable.scorer.data.api.ClockRequest
import com.missingtable.scorer.data.api.GoalRequest
import com.missingtable.scorer.data.api.LineupSaveRequest
import com.missingtable.scorer.data.api.MessageRequest
import com.missingtable.scorer.data.api.MtApi
import com.missingtable.scorer.data.api.PositionChangeRequest
import com.missingtable.scorer.data.api.SubstitutionRequest
import com.missingtable.scorer.data.db.PendingAction
import com.missingtable.scorer.data.db.PendingActionDao
import com.missingtable.scorer.domain.isSteppedOver
import com.missingtable.scorer.domain.skipsWhenFailed
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
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
 * retries or discards it. One exception (SB-1592): a FAILED lineup save is
 * stepped over, since no scoring event depends on it. Replays are safe — the server dedupes on
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
                // Nothing may end this loop but cancellation: a dead loop
                // means every later action queues and never syncs (SB-1592).
                val result = try {
                    drain()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log(Log.ERROR, "drain failed: ${e.javaClass.simpleName}: ${e.message}")
                    DrainResult.TRANSIENT_FAILURE
                }
                when (result) {
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
            // The head, stepping over a rejected lineup save (SB-1592).
            val head = dao.all().firstOrNull { !it.isSteppedOver() } ?: return DrainResult.EMPTY
            if (head.status == PendingAction.Status.FAILED) return DrainResult.PAUSED
            dao.setStatus(head.id, PendingAction.Status.IN_FLIGHT, head.attemptCount, head.lastError)
            // Send the row as it is now IN_FLIGHT, not as first read: a queued
            // lineup save is rewritten in place (SB-1504), and an edit landing
            // between head() and setStatus would otherwise be sent stale and
            // then deleted. Once IN_FLIGHT, nothing rewrites it.
            val sending = dao.byId(head.id) ?: continue

            val failure = execute(sending)
            // Logged so a release build can be diagnosed over adb (SB-1592):
            // `adb logcat -s MtSync`. No payloads — just what, where and why.
            val what = "${sending.actionType} id=${sending.id} match=${sending.matchId}"
            when {
                failure == null -> {
                    dao.delete(head.id)
                    log(Log.INFO, "synced $what")
                }
                failure.transient -> {
                    log(Log.WARN, "retrying $what: ${failure.message} (attempt ${sending.attemptCount + 1})")
                    dao.setStatus(
                        head.id, PendingAction.Status.PENDING, sending.attemptCount + 1, failure.message
                    )
                    return DrainResult.TRANSIENT_FAILURE
                }
                else -> {
                    dao.setStatus(
                        head.id, PendingAction.Status.FAILED, sending.attemptCount + 1, failure.message
                    )
                    if (sending.skipsWhenFailed()) {
                        log(Log.WARN, "rejected $what: ${failure.message}; skipped, queue continues")
                        continue
                    }
                    log(Log.WARN, "rejected $what: ${failure.message}; queue paused")
                    return DrainResult.PAUSED
                }
            }
        }
        @Suppress("UNREACHABLE_CODE") DrainResult.EMPTY
    }

    /** Un-fail the head so the next drain retries it. */
    suspend fun retryFailed(id: Long) {
        val row = dao.byId(id) ?: return
        // Clear the error: the old rejection is no longer the row's state.
        dao.setStatus(row.id, PendingAction.Status.PENDING, row.attemptCount, null)
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

    private fun log(priority: Int, message: String) {
        runCatching { Log.println(priority, TAG, message) }
    }

    private companion object {
        const val TAG = "MtSync"
    }

    private class Failure(val transient: Boolean, val message: String?)

    private suspend fun execute(action: PendingAction): Failure? {
        // Build the request first. A row this build can't build (bad payload,
        // missing route id) is rejected outright: before SB-1592 this threw out
        // of drain() and ended the engine's loop, so nothing after it synced.
        val send = try {
            prepare(action)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Failure(transient = false, message = "Can't send: ${e.javaClass.simpleName}")
        }
        return send(send)
    }

    private fun prepare(action: PendingAction): suspend () -> Unit = when (action.actionType) {
        PendingAction.ActionType.GOAL -> json.decodeFromString<GoalRequest>(action.payloadJson)
            .let { req -> { api.postGoal(action.matchId, req) } }
        PendingAction.ActionType.CARD -> json.decodeFromString<CardRequest>(action.payloadJson)
            .let { req -> { api.postCard(action.matchId, req) } }
        PendingAction.ActionType.SUB -> json.decodeFromString<SubstitutionRequest>(action.payloadJson)
            .let { req -> { api.postSubstitution(action.matchId, req) } }
        PendingAction.ActionType.POSITION -> json.decodeFromString<PositionChangeRequest>(action.payloadJson)
            .let { req -> { api.postPositionChange(action.matchId, req) } }
        PendingAction.ActionType.CLOCK -> json.decodeFromString<ClockRequest>(action.payloadJson)
            .let { req -> { api.postClock(action.matchId, req) } }
        PendingAction.ActionType.DELETE_EVENT -> requireNotNull(action.serverEventId)
            .let { eventId -> { api.deleteEvent(action.matchId, eventId) } }
        PendingAction.ActionType.REOPEN -> { -> api.reopenMatch(action.matchId) }
        PendingAction.ActionType.MESSAGE -> json.decodeFromString<MessageRequest>(action.payloadJson)
            .let { req -> { api.postMessage(action.matchId, req) } }
        PendingAction.ActionType.LINEUP_SAVE -> {
            val teamId = requireNotNull(action.teamId)
            val req = json.decodeFromString<LineupSaveRequest>(action.payloadJson)
            ({ api.putLineup(action.matchId, teamId, req) })
        }
    }

    private suspend fun send(call: suspend () -> Unit): Failure? = try {
        call()
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
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // The request went out but the reply couldn't be read (a captive
        // portal's 200 HTML page, say). Replays are safe, so retry.
        Failure(transient = true, message = "Bad reply: ${e.javaClass.simpleName}")
    }
}
