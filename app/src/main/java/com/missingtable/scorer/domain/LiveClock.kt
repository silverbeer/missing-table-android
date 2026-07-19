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
            val elapsed = ((now.epochSecond - secondHalf.epochSecond) / 60).toInt() + 1
            val total = halfDuration + elapsed
            val fullTime = halfDuration * 2
            return if (total > fullTime) {
                ClockText("$fullTime+${total - fullTime}'", fullTime, total - fullTime)
            } else {
                ClockText("$total'", total, null)
            }
        }

        if (halftimeStart != null) return ClockText("HT", halfDuration, null)

        val elapsed = ((now.epochSecond - kickoff.epochSecond) / 60).toInt() + 1
        return if (elapsed > halfDuration) {
            ClockText("$halfDuration+${elapsed - halfDuration}'", halfDuration, elapsed - halfDuration)
        } else {
            ClockText("$elapsed'", elapsed, null)
        }
    }
}
