package com.missingtable.scorer.domain

import com.missingtable.scorer.data.api.MatchSummary

/**
 * Splits the match list into the sections the Matches tab renders.
 *
 * Extracted from MatchListScreen and made total (SB-641). The original
 * in-line filters had no case for a `scheduled` match whose date had passed,
 * so those rows arrived from the API and were silently dropped — which hid
 * exactly the fixtures that still needed scoring. The guarantee here is that
 * every input row lands in exactly one bucket; `other` is the catch-all that
 * makes that true for statuses nobody has thought about yet.
 *
 * Dates are compared as ISO `yyyy-MM-dd` strings, which sort correctly.
 */
data class MatchBuckets(
    val live: List<MatchSummary> = emptyList(),
    val needsScoring: List<MatchSummary> = emptyList(),
    val todays: List<MatchSummary> = emptyList(),
    val upcoming: List<MatchSummary> = emptyList(),
    val recent: List<MatchSummary> = emptyList(),
    val other: List<MatchSummary> = emptyList(),
) {
    /** Total rows placed — compare against the input size to assert nothing was dropped. */
    val size: Int
        get() = live.size + needsScoring.size + todays.size + upcoming.size + recent.size + other.size
}

object MatchBucketing {

    /** RECENT is a glance backwards, not an archive. */
    const val RECENT_LIMIT = 10

    // A finished match, whatever flavour of finished. `played` is legacy.
    private val FINISHED = setOf("completed", "played", "forfeit")

    // Not yet resolved. `tbd` means "match played, score pending" server-side
    // (backend models/match_data.py) — it is awaiting a score by definition,
    // regardless of its date.
    private const val SCHEDULED = "scheduled"
    private const val TBD = "tbd"

    /**
     * @param today ISO `yyyy-MM-dd` for the device's current date.
     */
    fun bucket(matches: List<MatchSummary>, today: String): MatchBuckets {
        val live = mutableListOf<MatchSummary>()
        val needsScoring = mutableListOf<MatchSummary>()
        val todays = mutableListOf<MatchSummary>()
        val upcoming = mutableListOf<MatchSummary>()
        val recent = mutableListOf<MatchSummary>()
        val other = mutableListOf<MatchSummary>()

        matches.forEach { m ->
            val status = m.matchStatus
            when {
                status == "live" -> live += m
                // Played, no score yet — actionable no matter when it was.
                status == TBD -> needsScoring += m
                status == SCHEDULED && m.matchDate < today -> needsScoring += m
                status == SCHEDULED && m.matchDate == today -> todays += m
                status == SCHEDULED -> upcoming += m
                status in FINISHED -> recent += m
                // postponed / cancelled / null / anything new. Visible, not lost.
                else -> other += m
            }
        }

        return MatchBuckets(
            live = live,
            // Oldest first: the longest-overdue match is the most urgent.
            needsScoring = needsScoring.sortedBy { it.matchDate },
            todays = todays,
            upcoming = upcoming.sortedBy { it.matchDate },
            recent = recent.sortedByDescending { it.matchDate }.take(RECENT_LIMIT),
            other = other.sortedByDescending { it.matchDate },
        )
    }
}
