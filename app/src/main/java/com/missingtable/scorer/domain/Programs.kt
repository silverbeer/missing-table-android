package com.missingtable.scorer.domain

import com.missingtable.scorer.data.api.DivisionDto
import com.missingtable.scorer.data.api.LeagueDto
import com.missingtable.scorer.data.api.MatchSummary

/**
 * The Program filter on the Matches tab (SB-1680): Homegrown or Academy.
 *
 * A program is a top-level league. Flex is a child of Homegrown, so a Flex
 * fixture belongs to Homegrown here — it is where U15+ Homegrown teams play.
 *
 * Unlike the other rows, every program is always offered, even with no
 * matches: Academy U15+ has no fall fixtures during the high-school season,
 * and an Academy chip that silently vanished would read as a bug rather than
 * a quiet week. The caller disables an empty one and says why.
 */
object Programs {

    data class Option(val leagueId: Int, val name: String, val count: Int)

    /** Active top-level leagues, each with how many of [rows] belong to it. */
    fun options(
        rows: List<MatchSummary>,
        divisions: List<DivisionDto>,
        leagues: List<LeagueDto>,
    ): List<Option> {
        val counts = rows.groupingBy { programOf(it, divisions, leagues) }.eachCount()
        return Leagues.divisionChips(leagues).map { Option(it.id, it.name, counts[it.id] ?: 0) }
    }

    /** [rows] narrowed to the program [leagueId]; null filters nothing. */
    fun filter(
        rows: List<MatchSummary>,
        leagueId: Int?,
        divisions: List<DivisionDto>,
        leagues: List<LeagueDto>,
    ): List<MatchSummary> =
        if (leagueId == null) rows else rows.filter { programOf(it, divisions, leagues) == leagueId }

    /**
     * The top-level league [row] belongs to, through its conference's league,
     * else the league name the row carries; null when neither resolves.
     */
    fun programOf(row: MatchSummary, divisions: List<DivisionDto>, leagues: List<LeagueDto>): Int? {
        val viaDivision = divisions.firstOrNull { it.id == row.divisionId }?.leagueId
        val leagueId = viaDivision ?: leagues.firstOrNull { it.name == row.leagueName }?.id
        return root(leagueId, leagues)
    }

    /** Follows parent links to the top-level league. */
    fun root(leagueId: Int?, leagues: List<LeagueDto>): Int? {
        var id = leagueId ?: return null
        // Bounded, so a cycle in bad data cannot hang the UI thread.
        repeat(8) {
            val parent = leagues.firstOrNull { it.id == id }?.parentLeagueId ?: return id
            id = parent
        }
        return id
    }
}
