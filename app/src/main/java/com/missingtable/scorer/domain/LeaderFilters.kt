package com.missingtable.scorer.domain

import com.missingtable.scorer.data.api.MatchTypeDto

/**
 * Which competition the Leaders tab ranks (SB-1119).
 *
 * Unlike the Matches tab this filter is a query param, not a pass over a list
 * already in memory:
 *
 *  - There is **no All**. A leaderboard is always a leaderboard *of*
 *    something; pooling Friendlies with League produces a number that is not
 *    a standing and does not say so. One competition is always selected.
 *  - There **is** a "League + Flex" chip (SB-1594): every type flagged
 *    `counts_for_qualification`, totalled server-side by sending
 *    `match_type_id` once per type. Like the Matches tab it sits right after
 *    the types it combines, is labelled from the API's names, and appears
 *    only when this age group plays more than one of them.
 *
 * The default is League — asked for directly, and the right answer for a
 * scoring table.
 */
object LeaderFilters {

    const val DEFAULT = "League"

    /**
     * Saved-choice id of the combined chip. Real match-type ids are positive,
     * so this fits the existing Int preference without a migration.
     */
    const val COMBINED_ID = -1

    /** One chip; [typeIds] is what the leaderboard request filters on. */
    data class Chip(val id: Int, val name: String, val typeIds: List<Int>) {
        val isCombined: Boolean get() = id == COMBINED_ID
    }

    /**
     * Chips in the order they should render — `display_order`, so League comes
     * before Flex rather than alphabetically — with the combined chip right
     * after the last type it combines.
     */
    fun chips(available: List<MatchTypeDto>): List<Chip> {
        val sorted = available.sortedBy { it.displayOrder ?: Int.MAX_VALUE }
        val chips = sorted.map { Chip(it.id, it.name, listOf(it.id)) }.toMutableList()
        val qualifying = sorted.filter { it.countsForQualification }
        if (qualifying.size > 1) {
            val ids = qualifying.map { it.id }
            val after = chips.indexOfLast { it.id in ids }
            chips.add(after + 1, Chip(COMBINED_ID, qualifying.joinToString(" + ") { it.name }, ids))
        }
        return chips
    }

    /**
     * The chip to rank, given what this season and age group play and what the
     * viewer last chose.
     *
     * A saved choice the current age group does not play falls back to the
     * default rather than to an empty list the viewer cannot explain — U13
     * plays no Flex, and a Flex (or League + Flex) choice carried over from
     * U17 would otherwise rank nobody, or rank League under a combined label.
     *
     * @return null only when nothing is available at all, which is the
     *   caller's cue to render the empty state rather than a chip row.
     */
    fun resolve(chips: List<Chip>, saved: Int?): Chip? {
        if (chips.isEmpty()) return null
        return chips.firstOrNull { it.id == saved }
            ?: chips.firstOrNull { it.name == DEFAULT }
            ?: chips.first()
    }

    /** True when a saved choice had to be dropped because it is not played here. */
    fun savedChoiceUnavailable(chips: List<Chip>, saved: Int?): Boolean =
        saved != null && chips.isNotEmpty() && chips.none { it.id == saved }
}
