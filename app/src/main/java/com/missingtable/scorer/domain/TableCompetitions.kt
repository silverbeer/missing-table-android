package com.missingtable.scorer.domain

import com.missingtable.scorer.data.api.MatchTypeDto
import com.missingtable.scorer.data.api.TableCoverage

/**
 * The Table tab's Competition row (SB-1596), mirroring LeagueTable.vue
 * (SB-1037, SB-1039, SB-1043, SB-1027).
 *
 * MLS NEXT is Division (Homegrown, Academy) → Competition (League, Flex) →
 * Conference. The table is a conference's table *of a competition*, so the
 * request carries `match_type` explicitly — the API's default is League, and
 * a Flex conference asked for League matches renders an empty table.
 *
 * Chips are read from `/api/match-types/available` for the division, never
 * from a hardcoded list:
 *
 *  - Only competitions that produce a table (`has_standings`). Friendlies and
 *    tournaments are played, but a table of them is not a standing. Hidden
 *    only on an explicit false, so an older API that omits it hides nothing.
 *  - A combined chip ("League + Flex") when more than one qualifying
 *    competition is played, labelled from the API's names. It is a record,
 *    not a standing, and the caption says so.
 *  - The division opens on its own competition (`in_division > 0`): League
 *    for Homegrown and Academy.
 */
object TableCompetitions {

    /** The combined chip's value, as `/api/table` takes it. */
    const val QUALIFYING = "qualifying"

    /** One chip; [value] is what `/api/table?match_type=` gets. */
    data class Chip(val value: String, val label: String) {
        val isCombined: Boolean get() = value == QUALIFYING
    }

    fun chips(available: List<MatchTypeDto>): List<Chip> {
        val present = available.filter { it.hasStandings != false }
        val chips = present.map { Chip(it.name, it.name) }.toMutableList()
        val qualifying = present.filter { it.countsForQualification }
        if (qualifying.size > 1) {
            val names = qualifying.map { it.name }
            val after = chips.indexOfLast { it.value in names }
            chips.add(after + 1, Chip(QUALIFYING, names.joinToString(" + ")))
        }
        return chips
    }

    /** The competition a division opens on: its own, else the first, else League. */
    fun default(available: List<MatchTypeDto>): String =
        available.firstOrNull { it.inDivision > 0 }?.name
            ?: available.firstOrNull()?.name
            ?: "League"

    /**
     * Keep [current] while this division still offers it; otherwise its own
     * competition. A Flex choice carried to U13 (no Flex) would otherwise ask
     * for a table that cannot exist.
     */
    fun resolve(available: List<MatchTypeDto>, current: String?): String {
        if (current != null && chips(available).any { it.value == current }) return current
        return default(available)
    }

    /**
     * `match_type` for `/api/divisions/available`: the combined view keeps
     * the division's own conferences, so it sends none.
     */
    fun conferencesMatchType(selected: String?): String? = selected?.takeUnless { it == QUALIFYING }

    /**
     * Keep the current conference when still offered; otherwise Northeast,
     * else the first. Null when there is none.
     */
    fun resolveConference(options: List<Pair<Int, String>>, current: Int?): Int? {
        if (options.any { it.first == current }) return current
        return (options.firstOrNull { it.second == "Northeast" } ?: options.firstOrNull())?.first
    }

    /**
     * "Flex: a match level after regulation goes to penalties…" (SB-1027).
     * Which competitions score shootouts comes from the API, so a 1W 2D = 7
     * line can say why. Empty when none do.
     */
    fun shootoutNote(coverage: TableCoverage?): String {
        val names = coverage?.shootoutPoints.orEmpty().joinToString(" + ")
        if (names.isEmpty()) return ""
        return "$names: a match level after regulation goes to penalties. " +
            "Shootout winner 2 pts, loser 1. Counted as a draw in D."
    }

    /**
     * Caption for a combined view (SB-1043): how much each competition
     * contributed, zero included — "League 3 played · Flex 0 played" is the
     * difference between "Flex is missing" and "none played yet". Also notes
     * matches against teams outside the table. Empty when there is nothing
     * to say.
     */
    fun coverageNote(coverage: TableCoverage?): String {
        val c = coverage ?: return ""
        val competitions = c.competitions
        val combined = competitions.size != 1
        val outside = c.matchesVsOutsideTable
        if (!combined && outside == 0) return ""

        val names = competitions.joinToString(" + ").ifEmpty { "all competitions" }
        val parts = mutableListOf<String>()
        val contributions = competitions
            .filter { it in c.countedByCompetition }
            .map { "$it ${c.countedByCompetition.getValue(it)} played" }
        if (combined && contributions.isNotEmpty()) parts += contributions.joinToString(" · ")
        if (outside > 0) {
            val teams = c.teamsOutsideTable
            parts += "includes $outside ${if (outside == 1) "match" else "matches"} against " +
                "$teams ${if (teams == 1) "team" else "teams"} outside this table"
        }
        val suffix = if (combined) " A record, not a standing." else ""
        return "$names combined — ${parts.joinToString("; ")}.$suffix"
    }

    /** Instead of an empty table: fixtures exist and none are played, or there are none. */
    fun emptyNote(coverage: TableCoverage?): String {
        val fixtures = coverage?.fixtures ?: 0
        return if (fixtures > 0) {
            "$fixtures ${if (fixtures == 1) "fixture" else "fixtures"} scheduled, none played yet."
        } else {
            "No completed matches yet"
        }
    }
}
