package com.missingtable.scorer.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GET /api/matches/preview/{home}/{away}` (SB-1456), mirroring what
 * MatchPreview.vue reads.
 *
 * Every list defaults to empty: the server returns empty lists when it fails
 * to build a preview, and the web normalises a missing list to empty too, so a
 * partial payload renders as "nothing on record" rather than a parse error.
 * The QoP fields are only present when both teams share a division.
 */
@Serializable
data class MatchPreviewResponse(
    @SerialName("home_team_id") val homeTeamId: Int? = null,
    @SerialName("away_team_id") val awayTeamId: Int? = null,
    @SerialName("home_team_recent") val homeTeamRecent: List<PreviewMatch> = emptyList(),
    @SerialName("away_team_recent") val awayTeamRecent: List<PreviewMatch> = emptyList(),
    @SerialName("common_opponents") val commonOpponents: List<CommonOpponent> = emptyList(),
    @SerialName("head_to_head") val headToHead: List<PreviewMatch> = emptyList(),
    @SerialName("has_qop_data") val hasQopData: Boolean = false,
    @SerialName("home_qop_rank") val homeQopRank: Int? = null,
    // Positive = climbed (rank number went down), negative = fell.
    @SerialName("home_qop_rank_change") val homeQopRankChange: Int? = null,
    @SerialName("away_qop_rank") val awayQopRank: Int? = null,
    @SerialName("away_qop_rank_change") val awayQopRankChange: Int? = null,
)

/**
 * One completed match as `match_dao.get_match_preview` flattens it. Only the
 * fields the preview renders are kept; the rest are ignored on parse.
 */
@Serializable
data class PreviewMatch(
    val id: Int,
    @SerialName("match_date") val matchDate: String? = null,
    @SerialName("home_team_id") val homeTeamId: Int,
    @SerialName("away_team_id") val awayTeamId: Int,
    @SerialName("home_team_name") val homeTeamName: String = "Home",
    @SerialName("away_team_name") val awayTeamName: String = "Away",
    // Null for a "Not reported" match (SB-889) — rendered as "?" and an em dash.
    @SerialName("home_score") val homeScore: Int? = null,
    @SerialName("away_score") val awayScore: Int? = null,
    @SerialName("season_name") val seasonName: String? = null,
    @SerialName("match_type_id") val matchTypeId: Int? = null,
    @SerialName("match_type_name") val matchTypeName: String? = null,
)

/** A team both sides have played this season, with each side's results against it. */
@Serializable
data class CommonOpponent(
    @SerialName("opponent_id") val opponentId: Int,
    @SerialName("opponent_name") val opponentName: String = "",
    @SerialName("home_team_matches") val homeTeamMatches: List<PreviewMatch> = emptyList(),
    @SerialName("away_team_matches") val awayTeamMatches: List<PreviewMatch> = emptyList(),
)
