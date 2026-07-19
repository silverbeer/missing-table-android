package com.missingtable.scorer.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class RefreshRequest(@SerialName("refresh_token") val refreshToken: String)

@Serializable
data class AuthResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_at") val expiresAt: Long? = null,
)

@Serializable
data class MatchSummary(
    val id: Int,
    @SerialName("match_date") val matchDate: String,
    @SerialName("scheduled_kickoff") val scheduledKickoff: String? = null,
    @SerialName("home_team_id") val homeTeamId: Int,
    @SerialName("away_team_id") val awayTeamId: Int,
    @SerialName("home_team_name") val homeTeamName: String = "Home",
    @SerialName("away_team_name") val awayTeamName: String = "Away",
    @SerialName("home_score") val homeScore: Int? = null,
    @SerialName("away_score") val awayScore: Int? = null,
    @SerialName("season_id") val seasonId: Int? = null,
    @SerialName("age_group_id") val ageGroupId: Int? = null,
    @SerialName("age_group_name") val ageGroupName: String? = null,
    @SerialName("match_type_name") val matchTypeName: String? = null,
    @SerialName("match_status") val matchStatus: String? = null,
)

@Serializable
data class MatchEvent(
    val id: Int,
    @SerialName("match_id") val matchId: Int,
    @SerialName("event_type") val eventType: String,
    @SerialName("team_id") val teamId: Int? = null,
    @SerialName("player_name") val playerName: String? = null,
    @SerialName("player_id") val playerId: Int? = null,
    @SerialName("player_out_id") val playerOutId: Int? = null,
    @SerialName("assist_player_id") val assistPlayerId: Int? = null,
    @SerialName("assist_player_name") val assistPlayerName: String? = null,
    @SerialName("match_minute") val matchMinute: Int? = null,
    @SerialName("extra_time") val extraTime: Int? = null,
    val message: String = "",
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class LiveMatchState(
    @SerialName("match_id") val matchId: Int? = null,
    @SerialName("match_status") val matchStatus: String? = null,
    @SerialName("home_score") val homeScore: Int? = null,
    @SerialName("away_score") val awayScore: Int? = null,
    @SerialName("kickoff_time") val kickoffTime: String? = null,
    @SerialName("halftime_start") val halftimeStart: String? = null,
    @SerialName("second_half_start") val secondHalfStart: String? = null,
    @SerialName("match_end_time") val matchEndTime: String? = null,
    @SerialName("half_duration") val halfDuration: Int = 45,
    @SerialName("home_team_id") val homeTeamId: Int? = null,
    @SerialName("home_team_name") val homeTeamName: String = "Home",
    @SerialName("away_team_id") val awayTeamId: Int? = null,
    @SerialName("away_team_name") val awayTeamName: String = "Away",
    @SerialName("recent_events") val recentEvents: List<MatchEvent> = emptyList(),
)

@Serializable
data class RosterPlayer(
    val id: Int,
    @SerialName("team_id") val teamId: Int? = null,
    @SerialName("jersey_number") val jerseyNumber: Int? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
) {
    val label: String
        get() = displayName ?: listOfNotNull(firstName, lastName).joinToString(" ").ifBlank { "#$jerseyNumber" }
}

@Serializable
data class GoalRequest(
    @SerialName("team_id") val teamId: Int,
    @SerialName("player_id") val playerId: Int? = null,
    @SerialName("player_name") val playerName: String? = null,
    @SerialName("assist_player_id") val assistPlayerId: Int? = null,
    @SerialName("match_minute") val matchMinute: Int? = null,
    @SerialName("extra_time") val extraTime: Int? = null,
    @SerialName("client_event_id") val clientEventId: String,
)

@Serializable
data class CardRequest(
    @SerialName("team_id") val teamId: Int,
    @SerialName("player_id") val playerId: Int? = null,
    @SerialName("player_name") val playerName: String? = null,
    @SerialName("card_type") val cardType: String,
    @SerialName("match_minute") val matchMinute: Int? = null,
    @SerialName("extra_time") val extraTime: Int? = null,
    @SerialName("client_event_id") val clientEventId: String,
)

@Serializable
data class SubstitutionRequest(
    @SerialName("team_id") val teamId: Int,
    @SerialName("player_in_id") val playerInId: Int,
    @SerialName("player_out_id") val playerOutId: Int,
    @SerialName("match_minute") val matchMinute: Int? = null,
    @SerialName("extra_time") val extraTime: Int? = null,
    @SerialName("client_event_id") val clientEventId: String,
)

@Serializable
data class ClockRequest(
    val action: String,
    @SerialName("half_duration") val halfDuration: Int? = null,
    @SerialName("occurred_at") val occurredAt: String? = null,
)
