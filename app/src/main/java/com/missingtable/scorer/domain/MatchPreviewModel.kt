package com.missingtable.scorer.domain

import com.missingtable.scorer.data.api.MatchPreviewResponse
import com.missingtable.scorer.data.api.PreviewMatch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The pure half of the match preview (SB-1456), mirroring the helpers in
 * MatchPreview.vue so a fan reading the phone and the web sees the same rows.
 *
 * Every row is read from ONE team's perspective. The server returns raw
 * home/away scores, and a team is the away side in half its matches, so W/D/L,
 * the scoreline and the opponent all swap on whether that team was home.
 * Printing the raw "1–3" beside a W was the web bug SB-892 fixed.
 */
object MatchPreviewModel {

    enum class Outcome(val label: String) { WIN("W"), DRAW("D"), LOSS("L"), UNKNOWN("?") }

    enum class Tab(val label: String) {
        FORM("Recent Form"),
        COMMON("Common Opponents"),
        HEAD_TO_HEAD("Head-to-Head"),
    }

    /** @param count null renders no badge — Recent Form has none on the web. */
    data class TabSpec(val tab: Tab, val count: Int?)

    /** A QoP rank movement worth drawing; [up] is a climb. */
    data class RankMove(val up: Boolean, val amount: Int) {
        val label: String get() = (if (up) "▲" else "▼") + amount
    }

    data class HeadToHeadTally(val homeWins: Int, val draws: Int, val awayWins: Int)

    private val shortFormat = DateTimeFormatter.ofPattern("MMM d", Locale.US)
    private val longFormat = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

    /** W/D/L for [teamId]; "?" when either score is missing (not reported). */
    fun outcome(m: PreviewMatch, teamId: Int): Outcome {
        val (own, opp) = ownAndOpp(m, teamId) ?: return Outcome.UNKNOWN
        return when {
            own > opp -> Outcome.WIN
            own < opp -> Outcome.LOSS
            else -> Outcome.DRAW
        }
    }

    /** "3–1" as [teamId] played it, own score first; null when unscored. */
    fun scoreline(m: PreviewMatch, teamId: Int): String? =
        ownAndOpp(m, teamId)?.let { (own, opp) -> "$own–$opp" }

    /** H or A, so "3–1" can only be read one way. */
    fun venue(m: PreviewMatch, teamId: Int): String = if (m.homeTeamId == teamId) "H" else "A"

    /** Who [teamId] played. */
    fun opponentName(m: PreviewMatch, teamId: Int): String =
        if (m.homeTeamId == teamId) m.awayTeamName else m.homeTeamName

    /**
     * Recent Form shows every competition badge, but League reads neutral and
     * anything else (Flex, a tournament) stands out.
     */
    fun isLeague(matchTypeName: String?): Boolean = matchTypeName == "League"

    /**
     * Common Opponents and Head-to-Head only label the exceptions: a League
     * badge on every row there is noise.
     */
    fun exceptionalCompetition(matchTypeName: String?): String? =
        matchTypeName?.takeIf { it.isNotBlank() && !isLeague(it) }

    /**
     * The QoP rank shows only when the server has QoP data AND ranks this
     * team — the two teams must share a division for the ranks to compare.
     */
    fun showsQopRank(hasQopData: Boolean, rank: Int?): Boolean = hasQopData && rank != null

    /** Arrow for a rank change; null for no change or no prior ranking. */
    fun rankMove(change: Int?): RankMove? = when {
        change == null || change == 0 -> null
        change > 0 -> RankMove(up = true, amount = change)
        else -> RankMove(up = false, amount = -change)
    }

    /**
     * Only tabs that hold something (web SB-892): a "0" badge advertises its
     * own emptiness and invites a tap that leads nowhere. Recent Form always
     * shows — it is the default view and has per-team empty states.
     */
    fun tabs(preview: MatchPreviewResponse): List<TabSpec> = buildList {
        add(TabSpec(Tab.FORM, null))
        if (preview.commonOpponents.isNotEmpty()) add(TabSpec(Tab.COMMON, preview.commonOpponents.size))
        if (preview.headToHead.isNotEmpty()) add(TabSpec(Tab.HEAD_TO_HEAD, preview.headToHead.size))
    }

    /**
     * The tab to open on: the first with content, as the web picks it.
     *
     * The web can land on Head-to-Head even when it has no rows (and so no
     * tab), showing its empty state; here that falls back to Recent Form so
     * the selected panel always has a tab to match it.
     */
    fun defaultTab(preview: MatchPreviewResponse): Tab = when {
        preview.homeTeamRecent.isNotEmpty() || preview.awayTeamRecent.isNotEmpty() -> Tab.FORM
        preview.commonOpponents.isNotEmpty() -> Tab.COMMON
        preview.headToHead.isNotEmpty() -> Tab.HEAD_TO_HEAD
        else -> Tab.FORM
    }

    /** [selected] if it is still offered, else Recent Form — never an orphaned panel. */
    fun resolveTab(preview: MatchPreviewResponse, selected: Tab): Tab =
        if (tabs(preview).any { it.tab == selected }) selected else Tab.FORM

    /**
     * Wins for the previewed home side, draws, wins for the previewed away
     * side. Past meetings swap venues, so each is read from [homeTeamId]'s
     * side rather than the meeting's own home/away. Unscored meetings count
     * for nothing.
     */
    fun headToHeadTally(matches: List<PreviewMatch>, homeTeamId: Int): HeadToHeadTally {
        var home = 0
        var draws = 0
        var away = 0
        matches.forEach {
            when (outcome(it, homeTeamId)) {
                Outcome.WIN -> home++
                Outcome.LOSS -> away++
                Outcome.DRAW -> draws++
                Outcome.UNKNOWN -> Unit
            }
        }
        return HeadToHeadTally(home, draws, away)
    }

    /** "Sep 19"; blank when the date is missing or unparseable. */
    fun shortDate(date: String?): String = format(date, shortFormat)

    /** "Sep 19, 2026". */
    fun longDate(date: String?): String = format(date, longFormat)

    private fun format(date: String?, formatter: DateTimeFormatter): String {
        if (date.isNullOrBlank()) return ""
        return runCatching { LocalDate.parse(date.take(10)).format(formatter) }.getOrDefault("")
    }

    private fun ownAndOpp(m: PreviewMatch, teamId: Int): Pair<Int, Int>? {
        val home = m.homeScore ?: return null
        val away = m.awayScore ?: return null
        return if (m.homeTeamId == teamId) home to away else away to home
    }
}
