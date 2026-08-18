package com.missingtable.scorer.domain

import com.missingtable.scorer.data.api.MatchSummary

/**
 * Which match type the Matches tab shows (SB-681).
 *
 * The web hardcodes League. That is right mid-season and wrong the rest of the
 * time — every fixture in the current preseason is a Friendly, so a fixed
 * League default would render an empty list on match day. The default follows
 * the data instead: it can only ever select something that has matches.
 */
object MatchTypeFilter {

    /** Types present in the loaded rows, so no chip leads to an empty list. */
    fun options(rows: List<MatchSummary>): List<String> =
        rows.mapNotNull { it.matchTypeName }.distinct().sorted()

    /**
     * The type to show, given what is loaded and what the user last chose.
     *
     * @param saved an explicit previous choice; null means "All".
     * @return the type name to filter by, or null for All.
     */
    fun resolve(rows: List<MatchSummary>, saved: String?, userHasChosen: Boolean): String? {
        val present = options(rows)
        // An explicit choice wins — unless it would show nothing, in which case
        // falling back to All beats a blank screen the user cannot explain.
        if (userHasChosen) return saved?.takeIf { it in present }
        if (saved != null && saved in present) return saved
        // No choice yet: a single type selects itself; several means All, so
        // nothing is hidden. When everything is League this reads as League.
        return present.singleOrNull()
    }

    /** True when a saved choice had to be dropped because it matches nothing. */
    fun savedChoiceUnavailable(rows: List<MatchSummary>, saved: String?): Boolean =
        saved != null && rows.isNotEmpty() && saved !in options(rows)
}
