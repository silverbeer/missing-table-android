package com.missingtable.scorer.domain

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/**
 * The interval between halves (SB-1350).
 *
 * Forgetting to start the second half used to be silent: the clock sat on
 * "HT" while play went on, and a goal entered then was stamped at the
 * half-time minute. The interval is a fixed length, so the app knows when the
 * second half should have kicked off — it counts down to it, and can backdate
 * a late start to that moment (the backend accepts `occurred_at`).
 */
object HalfTime {

    /** Length of the interval. Fixed for now; every age group plays 10. */
    const val BREAK_MINUTES = 10L

    private fun parse(ts: String?): Instant? =
        ts?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }

    /**
     * Seconds until the interval is up; negative once it's overdue. Null when
     * half time hasn't started (or its timestamp is unreadable).
     */
    fun remainingSeconds(halftimeStart: String?, now: Instant = Instant.now()): Long? {
        val start = parse(halftimeStart) ?: return null
        return Duration.between(now, start.plus(Duration.ofMinutes(BREAK_MINUTES))).seconds
    }

    /**
     * How close to the end of the half a HALFTIME tap goes straight through.
     * Referees end a half a minute or two either side of time; anything
     * earlier than this is far more likely a stray tap (SB-1448).
     */
    const val EARLY_HALFTIME_MARGIN_MINUTES = 5L

    /**
     * Whole minutes played in the first half when HALFTIME is tapped this
     * early, or null when the tap is close enough to the half length to need
     * no warning. Match #10106 went to half time 14' into a 45' half, then to
     * the second half 34s later, and the clock ran 30 minutes fast after.
     */
    fun earlyHalftimeMinute(kickoffTime: String?, halfDuration: Int, now: Instant = Instant.now()): Long? {
        val kickoff = parse(kickoffTime) ?: return null
        val played = Duration.between(kickoff, now)
        val threshold = Duration.ofMinutes(halfDuration - EARLY_HALFTIME_MARGIN_MINUTES)
        return if (played < threshold) played.toMinutes().coerceAtLeast(0) else null
    }

    /**
     * How far into the break START 2ND HALF goes straight through. Sooner
     * than this is a double tap or a misread, not a restart (SB-1450).
     */
    const val EARLY_SECOND_HALF_MINUTES = 3L

    /**
     * Seconds into the break when START 2ND HALF is tapped this early, or
     * null when the break has run long enough to need no warning.
     */
    fun earlySecondHalfSeconds(halftimeStart: String?, now: Instant = Instant.now()): Long? {
        val start = parse(halftimeStart) ?: return null
        val into = Duration.between(start, now)
        return if (into < Duration.ofMinutes(EARLY_SECOND_HALF_MINUTES)) into.seconds.coerceAtLeast(0) else null
    }

    /** "7:42", or "+2:15" once overdue. */
    fun countdown(remaining: Long): String {
        val s = kotlin.math.abs(remaining)
        val text = "%d:%02d".format(s / 60, s % 60)
        return if (remaining < 0) "+$text" else text
    }

    /**
     * When a late-started second half most likely kicked off: the end of the
     * interval, but never in the future — tapping early means it's starting
     * now. Null when half time hasn't started.
     */
    fun expectedSecondHalfStart(halftimeStart: String?, now: Instant = Instant.now()): Instant? {
        val start = parse(halftimeStart) ?: return null
        val expected = start.plus(Duration.ofMinutes(BREAK_MINUTES))
        return if (expected.isAfter(now)) now else expected
    }
}
