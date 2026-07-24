package com.missingtable.scorer.domain

import java.time.Instant
import java.time.OffsetDateTime

/**
 * Derives the current match minute from clock timestamps — same math as the
 * backend's calculate_match_minute (app.py). Pure function of the timestamps
 * and `now`, so nothing needs to run in the background.
 */
object LiveClock {

    data class ClockText(val display: String, val minute: Int?, val extraTime: Int?)

    private fun parse(ts: String?): Instant? =
        ts?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }

    /** Elapsed seconds -> a running MM:SS clock (counts up past the half, soccer-style). */
    private fun mmss(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        return "%02d:%02d".format(s / 60, s % 60)
    }

    fun derive(
        kickoffTime: String?,
        halftimeStart: String?,
        secondHalfStart: String?,
        matchEndTime: String?,
        halfDuration: Int,
        now: Instant = Instant.now(),
    ): ClockText {
        val kickoff = parse(kickoffTime) ?: return ClockText("—", null, null)
        if (matchEndTime != null) return ClockText("FT", null, null)

        val secondHalf = parse(secondHalfStart)
        if (secondHalf != null) {
            // Second half continues counting from halfDuration (e.g. 45:00).
            val secs = (now.epochSecond - secondHalf.epochSecond) + halfDuration * 60L
            val minute = (secs / 60).toInt()
            val fullTime = halfDuration * 2
            val extra = if (minute > fullTime) minute - fullTime else null
            return ClockText(mmss(secs), minute.coerceAtMost(fullTime), extra)
        }

        if (halftimeStart != null) return ClockText("HT", halfDuration, null)

        val secs = now.epochSecond - kickoff.epochSecond
        val minute = (secs / 60).toInt()
        val extra = if (minute > halfDuration) minute - halfDuration else null
        return ClockText(mmss(secs), minute.coerceAtMost(halfDuration), extra)
    }
}
