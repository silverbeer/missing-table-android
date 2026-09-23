package com.missingtable.scorer.domain

import com.missingtable.scorer.data.api.DivisionDto
import com.missingtable.scorer.data.api.LeagueDto
import com.missingtable.scorer.data.api.MatchSummary
import com.missingtable.scorer.data.api.MatchTypeDto

/**
 * The conference filter on the Matches tab (SB-1110), mirroring
 * `visibleDivisions` / `conferenceGroups` in MatchesView.vue.
 *
 * Two things make this unlike every other filter on the tab:
 *
 *  1. **It is multi-select.** Conferences are neighbours, not alternatives: a
 *     club near a border plays Turnpike, New England and Northeast in the same
 *     season, and one-at-a-time turned that schedule into three lookups
 *     (SB-1007). "All conferences" is the *empty* selection rather than a
 *     value of its own, so it clears instead of adding.
 *  2. **It has to be grouped by competition.** Florida, Frontier, Northwest
 *     and Southeast exist under both League and Flex as different rows, and
 *     two chips both reading "Florida" under one heading told nobody which was
 *     which (SB-1040).
 */
object Conferences {

    data class Conference(val id: Int, val name: String, val leagueId: Int?)

    data class Group(val key: String, val label: String, val conferences: List<Conference>)

    /**
     * The conferences worth reaching first, per competition (SB-1117).
     *
     * The chip row scrolls horizontally on a phone, so alphabetical order
     * buried the ones actually being watched behind Central, Florida, Frontier
     * and Mid-America — every matchday opened with a scroll. Order inside each
     * list is the order the chips appear in.
     *
     * Keyed by competition, so promoting a name under League leaves a
     * same-named conference under Flex where it was. Edit this when the
     * watched conferences change; a name here that has no matches still gets
     * no chip, because priority decides order and never presence.
     */
    val PRIORITY: Map<String, List<String>> = mapOf(
        // Pro Player Pathway brackets are Homegrown conferences, so they group
        // under League beside the regional Northeast.
        "League" to listOf("Northeast", "Northeast (Pro Player Pathway)"),
        "Flex" to listOf("Empire", "New England"),
    )

    /**
     * The conferences present in [rows], named from [divisions], sorted by
     * name.
     *
     * Only what is actually on screen gets a chip — the same rule the age
     * group and competition rows follow, for the same reason: a chip that can
     * only ever return nothing promises data that is not coming.
     */
    fun visible(rows: List<MatchSummary>, divisions: List<DivisionDto>): List<Conference> {
        val byId = divisions.associateBy { it.id }
        return rows
            .mapNotNull { it.divisionId }
            .distinct()
            .mapNotNull { id ->
                val division = byId[id]
                val name = division?.name ?: rows.firstOrNull { it.divisionId == id }?.divisionName
                name?.takeIf { it.isNotBlank() && it != "Unknown" }
                    ?.let { Conference(id, it, division?.leagueId) }
            }
            .sortedBy { it.name }
    }

    /**
     * [conferences] grouped by the competition their tables belong to —
     * League conferences, then Flex conferences — in `display_order`.
     *
     * A league that does not say which competition it is (an API predating
     * `leagues.match_type_id`) groups as "Other" and sorts last. With a single
     * group the caller shows no headings; the grouping still decides order.
     *
     * Within a group the watched conferences come first, in [PRIORITY] order,
     * and everything else keeps the alphabetical order [visible] gave it.
     */
    fun groups(
        conferences: List<Conference>,
        leagues: List<LeagueDto>,
        matchTypes: List<MatchTypeDto>,
    ): List<Group> {
        val order = matchTypes.withIndex().associate { (i, t) -> t.name to (t.displayOrder ?: i) }
        val grouped = LinkedHashMap<String, MutableList<Conference>>()
        val labels = mutableMapOf<String, String>()
        for (conference in conferences) {
            val competition = competitionOfLeague(leagues, matchTypes, conference.leagueId)
            val key = competition ?: "other"
            labels[key] = competition ?: "Other"
            grouped.getOrPut(key) { mutableListOf() } += conference
        }
        return grouped
            .map { (key, list) ->
                val label = labels.getValue(key)
                // Stable sort, so conferences off the priority list keep the
                // alphabetical order they arrived in.
                val priority = PRIORITY[label].orEmpty()
                Group(
                    key = key.lowercase().replace(Regex("[^a-z0-9]+"), "-"),
                    label = label,
                    conferences = list.sortedBy { conference ->
                        priority.indexOf(conference.name).takeIf { it >= 0 } ?: Int.MAX_VALUE
                    },
                ) to if (key == "other") 999 else (order[label] ?: 99)
            }
            .sortedBy { it.second }
            .map { it.first }
    }

    /**
     * The competition whose tables a league's conferences are — "League" for
     * Homegrown and Academy, "Flex" for the Flex league — or null when the
     * league does not say.
     */
    fun competitionOfLeague(
        leagues: List<LeagueDto>,
        matchTypes: List<MatchTypeDto>,
        leagueId: Int?,
    ): String? {
        val typeId = leagues.firstOrNull { it.id == leagueId }?.matchTypeId ?: return null
        return matchTypes.firstOrNull { it.id == typeId }?.name
    }

    /** [rows] narrowed to [selected]; an empty selection filters nothing. */
    fun filter(rows: List<MatchSummary>, selected: Set<Int>): List<MatchSummary> =
        if (selected.isEmpty()) rows else rows.filter { it.divisionId in selected }

    /**
     * Drops selected conferences the current view does not play.
     *
     * Without this a selection carried over from U15 leaves U13 showing an
     * empty list with no visibly-selected chip to explain why. Only prunes
     * once conferences are known — matches load async, and pruning against an
     * empty list during that window would wipe the selection outright.
     */
    fun prune(selected: Set<Int>, visible: List<Conference>): Set<Int> {
        if (visible.isEmpty() || selected.isEmpty()) return selected
        val ids = visible.map { it.id }.toSet()
        return selected.filter { it in ids }.toSet()
    }
}
