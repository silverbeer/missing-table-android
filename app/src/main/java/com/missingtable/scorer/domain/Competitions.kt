package com.missingtable.scorer.domain

import com.missingtable.scorer.data.api.MatchSummary
import com.missingtable.scorer.data.api.MatchTypeDto

/**
 * The competition filter on the Matches tab (SB-1107), mirroring
 * `matchTypeChips` in MatchesView.vue.
 *
 * Chips are built from `/api/match-types` and the rows on screen, never from a
 * hardcoded list: the five literal ids the web used to carry never learned
 * about Flex, and six fixtures were unreachable except through All (SB-849).
 *
 * Three rules come straight from the web:
 *
 *  1. **A competition with no matches gets no chip.** U13 plays no Flex, and a
 *     chip that can only ever return nothing promises data that is not coming.
 *  2. **League and Flex get a combined chip** — every type flagged
 *     `counts_for_qualification`, which is what qualifies for MLS NEXT Cup. It
 *     is labelled with the names it combines ("League + Flex"), read from the
 *     API, and only appears when it says something the individual chips do not.
 *  3. **All sorts last** and is the default, because a schedule should open
 *     showing the schedule.
 *
 * Counts ride on every chip so a short list is never ambiguous between
 * "filtered" and "broken".
 */
object Competitions {

    const val ALL = "all"
    const val QUALIFYING = "qualifying"

    /**
     * One competition chip.
     *
     * @param typeIds the match types it selects; null is All, which filters
     *   nothing. A set rather than an id because the combined chip is the
     *   whole point of this type.
     */
    data class Chip(
        val key: String,
        val label: String,
        val typeIds: Set<Int>?,
        val count: Int,
    ) {
        val isAll: Boolean get() = typeIds == null
    }

    /**
     * Chips for [rows], in the order they should render.
     *
     * [matchTypes] is `/api/match-types`, already ordered by `display_order`;
     * it is re-sorted here so a client that caches an older payload, or an API
     * that stops ordering, still reads League before Flex. When it is empty —
     * the fetch failed, or an API that predates the endpoint — the chips fall
     * back to the names the rows carry, so the filter degrades to what it was
     * rather than disappearing.
     */
    fun chips(rows: List<MatchSummary>, matchTypes: List<MatchTypeDto>): List<Chip> {
        val all = Chip(ALL, "All", null, rows.size)
        if (rows.isEmpty()) return listOf(all)

        val present = presentTypes(rows, matchTypes)
        if (present.isEmpty()) return listOf(all)

        val chips = present.toMutableList()

        // The combined chip sits immediately after the types it combines, so a
        // reader meets "League, Flex, League + Flex" in that order.
        val qualifyingKeys = matchTypes.filter { it.countsForQualification }.map { it.id.toString() }.toSet()
        val qualifying = present.filter { it.key in qualifyingKeys }
        if (qualifying.size > 1) {
            val after = chips.indexOfLast { it.key in qualifyingKeys }
            chips.add(
                after + 1,
                Chip(
                    key = QUALIFYING,
                    label = qualifying.joinToString(" + ") { it.label },
                    typeIds = qualifying.flatMap { it.typeIds.orEmpty() }.toSet(),
                    count = qualifying.sumOf { it.count },
                ),
            )
        }

        chips.add(all)
        return chips
    }

    /** The chip to show, given what is offered and what the user last chose. */
    fun resolve(chips: List<Chip>, saved: String?, userHasChosen: Boolean): Chip {
        val all = chips.firstOrNull { it.isAll } ?: Chip(ALL, "All", null, 0)
        val match = saved?.let { key -> chips.firstOrNull { it.key == key || it.label == key } }
        // An explicit choice wins — unless it would show nothing, in which case
        // falling back to All beats a blank screen the user cannot explain.
        if (userHasChosen || saved != null) return match ?: all
        // No choice yet: a single competition selects itself, because there is
        // nothing for All to reveal; several means All, so nothing is hidden.
        val types = chips.filterNot { it.isAll || it.key == QUALIFYING }
        return types.singleOrNull() ?: all
    }

    /** True when a saved choice had to be dropped because it matches nothing. */
    fun savedChoiceUnavailable(chips: List<Chip>, saved: String?): Boolean =
        saved != null && chips.none { it.key == saved || it.label == saved }

    /** [rows] narrowed to [chip]. */
    fun filter(rows: List<MatchSummary>, chip: Chip): List<MatchSummary> {
        val wanted = chip.typeIds ?: return rows
        return rows.filter { it.matchTypeId in wanted }
    }

    private fun presentTypes(rows: List<MatchSummary>, matchTypes: List<MatchTypeDto>): List<Chip> {
        val byId = rows.groupingBy { it.matchTypeId }.eachCount()
        if (matchTypes.isNotEmpty()) {
            return matchTypes
                .sortedBy { it.displayOrder ?: Int.MAX_VALUE }
                .mapNotNull { type ->
                    val count = byId[type.id] ?: return@mapNotNull null
                    Chip(type.id.toString(), type.name, setOf(type.id), count)
                }
        }
        // No match-type payload: name the chips from the rows themselves.
        return rows
            .mapNotNull { row -> row.matchTypeId?.let { it to (row.matchTypeName ?: "Other") } }
            .distinct()
            .sortedBy { it.second }
            .map { (id, name) -> Chip(id.toString(), name, setOf(id), byId[id] ?: 0) }
    }
}
