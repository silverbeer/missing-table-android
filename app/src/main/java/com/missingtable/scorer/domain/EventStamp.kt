package com.missingtable.scorer.domain

/**
 * The match minute an event is recorded against (SB-652).
 *
 * The minute must be read when the user *starts* entering an event, not when
 * they finish. A goal takes several taps to enter — team, scorer, assist —
 * and reading the clock at the end stamps however long that took onto the
 * event. Under match pressure the entry flow is slowest exactly when the
 * drift matters most.
 *
 * Held for the life of one entry flow and cleared when the flow ends, so an
 * abandoned-and-restarted flow re-stamps rather than reusing a stale minute.
 */
data class EventStamp(val minute: Int?, val extraTime: Int?) {

    /** "23'", "45+2'", or null when the clock isn't running yet. */
    fun label(): String? {
        val m = minute ?: return null
        val extra = extraTime?.takeIf { it > 0 }
        return if (extra != null) "$m+$extra'" else "$m'"
    }

    companion object {
        val UNKNOWN = EventStamp(null, null)

        /**
         * The most stoppage time the backend accepts on an event (`le=30`).
         * A clock running fast — #10106's read 90+30' — must not stamp more:
         * a rejected event pauses the whole sync queue (SB-1451).
         */
        const val MAX_EXTRA_TIME = 30

        fun from(clock: LiveClock.ClockText) =
            EventStamp(clock.minute, clock.extraTime?.coerceAtMost(MAX_EXTRA_TIME))
    }
}
