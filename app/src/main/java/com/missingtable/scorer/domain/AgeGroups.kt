package com.missingtable.scorer.domain

import com.missingtable.scorer.data.api.MatchSummary

/**
 * The age-group chip on the Matches tab (SB-642), aware of playing up (SB-1446).
 *
 * A match carries one age group, but a side can field another: IFA U15 against
 * the New England Revolution U16 in a friendly is a U15 match whose away side
 * is U16. The backend stores that side's age group in `home_age_group_id` /
 * `away_age_group_id` (NULL = same as the match, SB-1444), and the chip keeps a
 * match when any side fielded the selected group — the same rule as the web's
 * team filter (`team_age_group_filter`).
 */
object AgeGroups {

    /** Every age group that took the field in [m]: the match's, plus any side's own. */
    fun fielded(m: MatchSummary): Set<Int> =
        setOfNotNull(m.ageGroupId, m.homeAgeGroupId, m.awayAgeGroupId)

    /** Rows for the selected age group; null means "All". */
    fun filter(rows: List<MatchSummary>, ageGroupId: Int?): List<MatchSummary> =
        ageGroupId?.let { id -> rows.filter { id in fielded(it) } } ?: rows
}
