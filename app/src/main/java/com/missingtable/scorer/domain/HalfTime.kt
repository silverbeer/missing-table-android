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
