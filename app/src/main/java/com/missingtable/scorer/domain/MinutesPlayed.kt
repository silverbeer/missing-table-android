package com.missingtable.scorer.domain

import com.missingtable.scorer.data.api.MatchEvent

/**
 * Derives per-player minutes from the starting XI and the substitution
 * timeline (SB-282): starters play from 0', subs swap the on-pitch set at
 * their recorded minute, everyone still on at full time plays to matchLength.
 * Card events feed the yellow/red counts. Pure function for testability.
 */
object MinutesPlayed {

    data class PlayerMatch(
        val playerId: Int,
        val started: Boolean,
        val played: Boolean,
        val minutes: Int,
        val yellowCards: Int,
        val redCards: Int,
    )

    fun derive(
        starters: Set<Int>,
        events: List<MatchEvent>,
        teamId: Int,
        matchLength: Int,
    ): List<PlayerMatch> {
        // entry minute for everyone currently on the pitch
        val onPitch = starters.associateWith { 0 }.toMutableMap()
        val minutes = mutableMapOf<Int, Int>()
        val played = mutableSetOf<Int>().apply { addAll(starters) }

        // events arrive newest-first from the API; replay oldest-first
        val subs = events
            .filter { it.eventType == "substitution" && it.teamId == teamId }
            .sortedBy { it.matchMinute ?: 0 }

        subs.forEach { sub ->
            val minute = (sub.matchMinute ?: 0).coerceIn(0, matchLength)
            sub.playerOutId?.let { out ->
                onPitch.remove(out)?.let { entry ->
                    minutes[out] = (minutes[out] ?: 0) + (minute - entry).coerceAtLeast(0)
                }
            }
            sub.playerId?.let { inn ->
                onPitch[inn] = minute
                played.add(inn)
            }
        }

        onPitch.forEach { (id, entry) ->
            minutes[id] = (minutes[id] ?: 0) + (matchLength - entry).coerceAtLeast(0)
        }

        val yellows = events.filter { it.eventType == "yellow_card" && it.teamId == teamId }
            .mapNotNull { it.playerId }
            .groupingBy { it }
            .eachCount()
        val reds = events.filter { it.eventType == "red_card" && it.teamId == teamId }
            .mapNotNull { it.playerId }
            .groupingBy { it }
            .eachCount()

        return played.map { id ->
            PlayerMatch(
                playerId = id,
                started = id in starters,
                played = true,
                minutes = minutes[id] ?: 0,
                yellowCards = (yellows[id] ?: 0).coerceAtMost(2),
                redCards = (reds[id] ?: 0).coerceAtMost(1),
            )
        }.sortedBy { it.playerId }
    }
}
