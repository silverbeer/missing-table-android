package com.missingtable.scorer.domain

import com.missingtable.scorer.data.api.TournamentMatch

/**
 * Group-stage standings computed client-side from a tournament's matches —
 * same 3/1/0 scheme the web's TournamentStandings uses. Pure function for
 * testability.
 */
object TournamentStandings {

    data class Row(
        val teamId: Int,
        val teamName: String,
        val played: Int,
        val wins: Int,
        val draws: Int,
        val losses: Int,
        val goalsFor: Int,
        val goalsAgainst: Int,
    ) {
        val goalDifference: Int get() = goalsFor - goalsAgainst
        val points: Int get() = wins * 3 + draws
    }

    /** Standings for one group's matches (completed only), sorted for display. */
    fun compute(matches: List<TournamentMatch>): List<Row> {
        data class Acc(
            var played: Int = 0, var wins: Int = 0, var draws: Int = 0, var losses: Int = 0,
            var gf: Int = 0, var ga: Int = 0, var name: String = "",
        )

        val acc = mutableMapOf<Int, Acc>()
        matches
            .filter { it.matchStatus in setOf("completed", "forfeit") }
            .forEach { m ->
                val home = m.homeTeam ?: return@forEach
                val away = m.awayTeam ?: return@forEach
                val hs = m.homeScore ?: return@forEach
                val as_ = m.awayScore ?: return@forEach
                val h = acc.getOrPut(home.id) { Acc(name = home.name) }
                val a = acc.getOrPut(away.id) { Acc(name = away.name) }
                h.played++; a.played++
                h.gf += hs; h.ga += as_
                a.gf += as_; a.ga += hs
                when {
                    hs > as_ -> { h.wins++; a.losses++ }
                    hs < as_ -> { a.wins++; h.losses++ }
                    else -> { h.draws++; a.draws++ }
                }
            }

        return acc.map { (id, v) ->
            Row(id, v.name, v.played, v.wins, v.draws, v.losses, v.gf, v.ga)
        }.sortedWith(
            compareByDescending<Row> { it.points }
                .thenByDescending { it.goalDifference }
                .thenByDescending { it.goalsFor }
                .thenBy { it.teamName },
        )
    }
}
