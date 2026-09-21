package com.missingtable.scorer.domain

import com.missingtable.scorer.data.api.LeagueDto

/**
 * Which leagues the Table tab offers as chips (SB-1106).
 *
 * MLS NEXT is Division → Competition → Conference. `/api/leagues` flattens all
 * three into one list, so two of its four rows are not divisions at all:
 *
 *  - **Flex** (`parent_league_id = Homegrown`) is a *competition* of Homegrown,
 *    not a peer of it. Its conferences are reached through Homegrown plus the
 *    Flex competition, which is why the web drops any row with a parent
 *    (`leagues.filter(l => !l.parent_league_id)` in LeagueTable.vue). Offering
 *    it as a division chip asks `/api/table` for a conference that does not
 *    belong to the league being shown.
 *  - **Kick Futsal** is `is_active: false` — a retired league whose chip can
 *    only ever produce an empty table.
 *
 * `/api/leagues` does not come back sorted, so the display order the rows
 * carry has to be applied here; the web gets it for free from
 * `/api/leagues/available`.
 */
object Leagues {

    /**
     * Top-level, active leagues in display order — the division chips.
     *
     * Rows with no `display_order` sort last, in the order the API sent them,
     * rather than jumping to the front on a null.
     */
    fun divisionChips(leagues: List<LeagueDto>): List<LeagueDto> =
        leagues
            .filter { it.parentLeagueId == null && it.isActive }
            .sortedBy { it.displayOrder ?: Int.MAX_VALUE }

    /** The chip to open on: Homegrown when offered, else the first. */
    fun defaultChip(leagues: List<LeagueDto>): LeagueDto? {
        val chips = divisionChips(leagues)
        return chips.firstOrNull { it.name == "Homegrown" } ?: chips.firstOrNull()
    }
}
