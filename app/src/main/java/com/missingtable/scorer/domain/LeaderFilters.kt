package com.missingtable.scorer.domain

import com.missingtable.scorer.data.api.MatchTypeDto

/**
 * Which competition the Leaders tab ranks (SB-1119).
 *
 * Unlike the Matches tab this filter is a query param, not a pass over a list
 * already in memory, which changes the shape of the problem twice:
 *
 *  - There is **no All**. A leaderboard is always a leaderboard *of*
 *    something; pooling Friendlies with League produces a number that is not
 *    a standing and does not say so. One competition is always selected.
 *  - There is **no "League + Flex"**. `match_type_id` is singular server-side,
 *    so the combined record would need two requests merged by player. Worth
 *    doing, not worth pretending: the chip is simply absent rather than
 *    present and wrong.
 *
 * The default is League — asked for directly, and the right answer for a
 * scoring table.
 */
object LeaderFilters {

    const val DEFAULT = "League"

    /**
     * The competition to rank, given what this season and age group play and
     * what the viewer last chose.
     *
     * A saved choice the current age group does not play falls back to the
     * default rather than to an empty list the viewer cannot explain — U13
     * plays no Flex, and a Flex choice carried over from U17 would otherwise
     * rank nobody.
     *
     * @return null only when nothing is available at all, which is the
     *   caller's cue to render the empty state rather than a chip row.
     */
    fun resolve(available: List<MatchTypeDto>, saved: Int?): MatchTypeDto? {
        if (available.isEmpty()) return null
        return available.firstOrNull { it.id == saved }
            ?: available.firstOrNull { it.name == DEFAULT }
            ?: available.first()
    }

    /** True when a saved choice had to be dropped because it is not played here. */
    fun savedChoiceUnavailable(available: List<MatchTypeDto>, saved: Int?): Boolean =
        saved != null && available.isNotEmpty() && available.none { it.id == saved }

    /**
     * Chips in the order they should render — `display_order`, so League comes
     * before Flex rather than alphabetically.
     */
    fun chips(available: List<MatchTypeDto>): List<MatchTypeDto> =
        available.sortedBy { it.displayOrder ?: Int.MAX_VALUE }
}
