package com.missingtable.scorer.domain

import com.missingtable.scorer.data.api.MatchSummary
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The words on the Match of the Week hero (SB-1108), mirroring MotwHero.vue.
 *
 * Pure, because every line here is a claim about a match and each one has a
 * way of going wrong that a screenshot would not catch: a countdown on a
 * match played two days ago, "Unknown" printed as if it were a division, or
 * 0–0 shown for a fixture nobody has played. The Vue component pins the same
 * rules in its own spec; this is the Android half.
 */
object MotwCopy {

    enum class State { LIVE, FINAL, UPCOMING }

    private val meta = DateTimeFormatter.ofPattern("EEE, MMM d, h:mm a")

    fun state(match: MatchSummary): State = when (match.matchStatus) {
        "live" -> State.LIVE
        "completed", "forfeit" -> State.FINAL
        else -> State.UPCOMING
    }

    /**
     * True when there is a real result to show.
     *
     * Asks the status, not the numbers: a scheduled match carrying scores is
     * a data problem, and rendering an unrecorded score as 0–0 is the same
     * pun as calling a missing value zero. Both scores must be present —
     * one-sided is a half-entered result.
     */
    fun hasScore(match: MatchSummary): Boolean =
        state(match) != State.UPCOMING && match.homeScore != null && match.awayScore != null

    /** "home", "away", "draw", or null while there is no real result. */
    fun winner(match: MatchSummary): String? {
        if (!hasScore(match)) return null
        val home = match.homeScore!!
        val away = match.awayScore!!
        return when {
            home == away -> "draw"
            home > away -> "home"
            else -> "away"
        }
    }

    /** "IFA won 3–1" / "Drew 2–2", or null with no result. */
    fun resultLine(match: MatchSummary): String? {
        val winner = winner(match) ?: return null
        val home = match.homeScore!!
        val away = match.awayScore!!
        if (winner == "draw") return "Drew $home–$away"
        val name = if (winner == "home") match.homeTeamName else match.awayTeamName
        return "$name won ${maxOf(home, away)}–${minOf(home, away)}"
    }

    /**
     * The one status line, in three voices. Never a countdown for a match
     * already played — that is what makes a featured card look abandoned by
     * Monday.
     */
    fun statusLabel(match: MatchSummary, now: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
        when (state(match)) {
            State.LIVE -> return "Live now"
            State.FINAL -> return "Full time"
            State.UPCOMING -> Unit
        }
        val kickoff = kickoff(match, zone) ?: return "Time TBC"
        val delta = Duration.between(now, kickoff)
        if (delta.isZero || delta.isNegative) return "Kicking off"
        if (delta >= Duration.ofDays(1)) {
            val days = Math.round(delta.toMinutes() / (60.0 * 24))
            return "Kicks off in $days day${if (days == 1L) "" else "s"}"
        }
        val hours = maxOf(1L, Math.round(delta.toMinutes() / 60.0))
        return "Kicks off in $hours hour${if (hours == 1L) "" else "s"}"
    }

    /**
     * "U15 · Turnpike · Sat, Sep 26, 2:00 PM".
     *
     * Every part is skipped when absent rather than printed as "Unknown" — a
     * featured card is the wrong place to advertise a gap in the data.
     */
    fun metaLine(match: MatchSummary, zone: ZoneId = ZoneId.systemDefault()): String {
        val parts = listOfNotNull(match.ageGroupName, match.divisionName)
            .filter { it.isNotBlank() && it != "Unknown" }
            .toMutableList()
        val kickoff = kickoff(match, zone)
        if (kickoff != null) parts += kickoff.format(meta) else parts += match.matchDate
        return parts.joinToString(" · ")
    }

    /**
     * Closed-state copy: enough to be worth a tap — that there *is* a pick,
     * and roughly when — without giving the fixture away.
     */
    fun teaser(match: MatchSummary): String = when (state(match)) {
        State.LIVE -> "Being played right now — reveal"
        State.FINAL -> "Played — reveal the result"
        State.UPCOMING -> "Reveal this week's pick"
    }

    /** The age group, or null when it is absent or unknown. */
    fun ageGroupLabel(match: MatchSummary): String? =
        match.ageGroupName?.takeIf { it.isNotBlank() && it != "Unknown" }

    private fun kickoff(match: MatchSummary, zone: ZoneId) =
        match.scheduledKickoff
            ?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
            ?.atZoneSameInstant(zone)
}
