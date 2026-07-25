package com.missingtable.scorer.ui.live

import com.missingtable.scorer.data.api.CardRequest
import com.missingtable.scorer.data.api.ClockRequest
import com.missingtable.scorer.data.api.GoalRequest
import com.missingtable.scorer.data.api.LiveMatchState
import com.missingtable.scorer.data.api.MatchEvent
import com.missingtable.scorer.data.api.RosterPlayer
import com.missingtable.scorer.data.api.SubstitutionRequest
import com.missingtable.scorer.data.db.PendingAction
import kotlinx.serialization.json.Json

/**
 * Folds the pending-action queue into the last known server state so the UI
 * renders what the match will look like once everything syncs. Pending events
 * are injected into recentEvents with negative ids (-row.id), which keeps the
 * existing timeline, delete plumbing, and on-pitch derivation working
 * unchanged — the UI can tell pending from server rows by the sign.
 */
object OptimisticLive {

    fun merge(
        server: LiveMatchState,
        pending: List<PendingAction>,
        json: Json,
        rosters: Map<Int, List<RosterPlayer>>,
    ): LiveMatchState {
        var s = server
        var events = server.recentEvents

        // Queue order = chronological; recentEvents is newest-first, so each
        // pending event is prepended as we walk oldest→newest.
        pending.forEach { p ->
            when (p.actionType) {
                PendingAction.ActionType.GOAL -> {
                    val req = json.decodeFromString<GoalRequest>(p.payloadJson)
                    s = when (req.teamId) {
                        s.homeTeamId -> s.copy(homeScore = (s.homeScore ?: 0) + 1)
                        s.awayTeamId -> s.copy(awayScore = (s.awayScore ?: 0) + 1)
                        else -> s
                    }
                    val who = name(rosters, req.teamId, req.playerId, req.playerName)
                    val assist = req.assistPlayerId?.let { name(rosters, req.teamId, it, null) }
                    events = listOf(
                        pendingEvent(
                            p, "goal", req.teamId,
                            playerId = req.playerId,
                            minute = req.matchMinute, extra = req.extraTime,
                            message = "Goal — $who" + (assist?.let { " (assist: $it)" } ?: ""),
                        )
                    ) + events
                }

                PendingAction.ActionType.CARD -> {
                    val req = json.decodeFromString<CardRequest>(p.payloadJson)
                    val who = name(rosters, req.teamId, req.playerId, req.playerName)
                    val label = if (req.cardType == "red_card") "Red card" else "Yellow card"
                    events = listOf(
                        pendingEvent(
                            p, req.cardType, req.teamId,
                            playerId = req.playerId,
                            minute = req.matchMinute, extra = req.extraTime,
                            message = "$label — $who",
                        )
                    ) + events
                }

                PendingAction.ActionType.SUB -> {
                    val req = json.decodeFromString<SubstitutionRequest>(p.payloadJson)
                    val on = name(rosters, req.teamId, req.playerInId, null)
                    val off = name(rosters, req.teamId, req.playerOutId, null)
                    events = listOf(
                        pendingEvent(
                            p, "substitution", req.teamId,
                            playerId = req.playerInId, playerOutId = req.playerOutId,
                            minute = req.matchMinute, extra = req.extraTime,
                            message = "Sub — $on on for $off",
                        )
                    ) + events
                }

                PendingAction.ActionType.CLOCK -> {
                    val req = json.decodeFromString<ClockRequest>(p.payloadJson)
                    s = when (req.action) {
                        "start_first_half" -> s.copy(
                            kickoffTime = req.occurredAt ?: s.kickoffTime,
                            halfDuration = req.halfDuration ?: s.halfDuration,
                            matchStatus = "live",
                        )
                        "start_halftime" -> s.copy(halftimeStart = req.occurredAt)
                        "cancel_halftime" -> s.copy(halftimeStart = null)
                        "start_second_half" -> s.copy(secondHalfStart = req.occurredAt)
                        "end_match" -> s.copy(matchEndTime = req.occurredAt)
                        else -> s
                    }
                }

                PendingAction.ActionType.DELETE_EVENT ->
                    events = events.filterNot { it.id == p.serverEventId }

                PendingAction.ActionType.REOPEN ->
                    s = s.copy(matchEndTime = null, matchStatus = "live")

                // Lineups are loaded separately; nothing to fold in here.
                PendingAction.ActionType.LINEUP_SAVE -> {}
            }
        }
        return s.copy(recentEvents = events)
    }

    /** Timeline rows with a negative id are pending (not yet on the server). */
    fun isPending(event: MatchEvent): Boolean = event.id < 0

    /** Recover the queue row id a pending timeline entry came from. */
    fun pendingRowId(event: MatchEvent): Long = -event.id.toLong()

    private fun pendingEvent(
        p: PendingAction,
        type: String,
        teamId: Int,
        playerId: Int? = null,
        playerOutId: Int? = null,
        minute: Int? = null,
        extra: Int? = null,
        message: String,
    ) = MatchEvent(
        id = -p.id.toInt(),
        matchId = p.matchId,
        eventType = type,
        teamId = teamId,
        playerId = playerId,
        playerOutId = playerOutId,
        matchMinute = minute,
        extraTime = extra,
        message = message,
    )

    private fun name(
        rosters: Map<Int, List<RosterPlayer>>,
        teamId: Int,
        playerId: Int?,
        freeText: String?,
    ): String =
        freeText
            ?: playerId?.let { id -> rosters[teamId]?.firstOrNull { it.id == id }?.label }
            ?: "?"
}
