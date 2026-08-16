package com.missingtable.scorer.domain

/**
 * Half length in minutes, chosen at kickoff (SB-645).
 *
 * `half_duration` is written once when the match starts and then drives the
 * displayed minute, the halftime prompt and extra time for the rest of the
 * match — so a wrong value at kickoff is wrong for the whole 90 and never
 * announces itself. Hence a sensible default per age group, and an override
 * for the matches that don't follow it.
 *
 * Mirrors the web app's Start Match modal
 * (missing-table frontend/src/components/live/LiveAdminControls.vue).
 */
object HalfDuration {

    /** Offered as one-tap buttons, labelled by the age groups that use them. */
    val PRESETS = listOf(35, 40, 45)

    /** Accepted range for a custom value — same bounds as the web input. */
    const val MIN = 20
    const val MAX = 60

    /** System-wide fallback, matching the backend and the LiveMatchState DTO. */
    const val FALLBACK = 45

    /**
     * Default half length for an age group.
     *
     * U15 and up play 45s. Matched as a substring of a display name because
     * that is what the API returns ("U15", "U15 Boys"), and tolerant of the
     * hyphenated spelling for the same reason.
     */
    fun defaultFor(ageGroupName: String?): Int {
        val ag = ageGroupName?.lowercase() ?: return FALLBACK
        return when {
            ag.contains("u13") || ag.contains("u-13") -> 35
            ag.contains("u14") || ag.contains("u-14") -> 40
            // U15, U16, U17, U19 and anything unrecognised.
            else -> FALLBACK
        }
    }

    /** Which age groups a preset is the default for — the button's sublabel. */
    fun presetLabel(minutes: Int): String = when (minutes) {
        35 -> "U13"
        40 -> "U14"
        45 -> "U15+"
        else -> ""
    }

    fun isValid(minutes: Int?): Boolean = minutes != null && minutes in MIN..MAX
}
