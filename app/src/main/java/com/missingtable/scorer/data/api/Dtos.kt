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
data class MeResponse(val user: MeUser? = null)

@Serializable
data class MeUser(
    val id: String? = null,
    val email: String? = null,
    val profile: MeProfile? = null,
)

@Serializable
data class MeProfile(
    val username: String? = null,
    // Backend roles: admin, club_manager, team-manager (legacy team_manager),
    // team-player, team-fan, club_fan.
    val role: String? = null,
    @SerialName("team_id") val teamId: Int? = null,
    @SerialName("club_id") val clubId: Int? = null,
    @SerialName("display_name") val displayName: String? = null,
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
data class RosterResponse(
    val roster: List<RosterPlayer> = emptyList(),
)

@Serializable
data class RosterPlayer(
    val id: Int,
    @SerialName("team_id") val teamId: Int? = null,
    @SerialName("jersey_number") val jerseyNumber: Int? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
    // Ordered player position codes; first = primary (SB-284 taxonomy). The
    // backend roster endpoint returns players.positions (text[]).
    val positions: List<String> = emptyList(),
) {
    val label: String
        get() = displayName ?: listOfNotNull(firstName, lastName).joinToString(" ").ifBlank { "#$jerseyNumber" }

    /** Name without the jersey-number fallback; null when only a number is known. */
    val nameOnly: String?
        get() = (displayName ?: listOfNotNull(firstName, lastName).joinToString(" ")).ifBlank { null }
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
data class BulkRosterPlayer(@SerialName("jersey_number") val jerseyNumber: Int)

@Serializable
data class BulkRosterRequest(
    @SerialName("season_id") val seasonId: Int,
    val players: List<BulkRosterPlayer>,
)

@Serializable
data class BulkRosterResponse(
    val created: List<RosterPlayer> = emptyList(),
)

@Serializable
data class LineupPosition(
    @SerialName("player_id") val playerId: Int,
    val position: String,
    @SerialName("jersey_number") val jerseyNumber: Int? = null,
    @SerialName("display_name") val displayName: String? = null,
)

@Serializable
data class LineupSaveRequest(
    @SerialName("formation_name") val formationName: String,
    val positions: List<LineupPosition>,
)

@Serializable
data class LineupResponse(
    @SerialName("match_id") val matchId: Int? = null,
    @SerialName("team_id") val teamId: Int? = null,
    @SerialName("formation_name") val formationName: String = "",
    val positions: List<LineupPosition> = emptyList(),
)

@Serializable
data class TableResponse(val standings: List<StandingRow> = emptyList())

@Serializable
data class StandingRow(
    val team: String = "",
    @SerialName("team_id") val teamId: Int? = null,
    val played: Int = 0,
    val wins: Int = 0,
    val draws: Int = 0,
    val losses: Int = 0,
    @SerialName("goals_for") val goalsFor: Int = 0,
    @SerialName("goals_against") val goalsAgainst: Int = 0,
    @SerialName("goal_difference") val goalDifference: Int = 0,
    val points: Int = 0,
    // Last-5 results, e.g. ["W","D","L","W","W"]
    val form: List<String> = emptyList(),
    @SerialName("position_change") val positionChange: Int = 0,
)

@Serializable
data class LeaderboardEntry(
    val rank: Int = 0,
    @SerialName("player_id") val playerId: Int? = null,
    @SerialName("jersey_number") val jerseyNumber: Int? = null,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
    @SerialName("team_name") val teamName: String? = null,
    val goals: Int = 0,
    @SerialName("games_played") val gamesPlayed: Int = 0,
    @SerialName("goals_per_game") val goalsPerGame: Double = 0.0,
) {
    val playerLabel: String
        get() = listOfNotNull(firstName, lastName).joinToString(" ")
            .ifBlank { jerseyNumber?.let { "#$it" } ?: "Unknown" }
}

@Serializable
data class SeasonDto(
    val id: Int,
    val name: String? = null,
    // Authoritative current-season flag (admin-set, exactly one true).
    @SerialName("is_current") val isCurrent: Boolean = false,
    @SerialName("start_date") val startDate: String? = null,
)

@Serializable
data class LeagueDto(val id: Int, val name: String = "")

@Serializable
data class DivisionDto(
    val id: Int,
    val name: String = "",
    @SerialName("league_id") val leagueId: Int? = null,
)

// ── Post-match editing (SB-281) ─────────────────────────────────────────────
// match_minute is REQUIRED (1..130) on the post-match endpoints, unlike live.

@Serializable
data class PostMatchGoalRequest(
    @SerialName("team_id") val teamId: Int,
    @SerialName("player_id") val playerId: Int? = null,
    @SerialName("player_name") val playerName: String? = null,
    @SerialName("assist_player_id") val assistPlayerId: Int? = null,
    @SerialName("match_minute") val matchMinute: Int,
    @SerialName("extra_time") val extraTime: Int? = null,
)

@Serializable
data class PostMatchSubRequest(
    @SerialName("team_id") val teamId: Int,
    @SerialName("player_in_id") val playerInId: Int,
    @SerialName("player_out_id") val playerOutId: Int,
    @SerialName("match_minute") val matchMinute: Int,
    @SerialName("extra_time") val extraTime: Int? = null,
)

@Serializable
data class PostMatchCardRequest(
    @SerialName("team_id") val teamId: Int,
    @SerialName("player_id") val playerId: Int? = null,
    @SerialName("player_name") val playerName: String? = null,
    @SerialName("card_type") val cardType: String,
    @SerialName("match_minute") val matchMinute: Int,
    @SerialName("extra_time") val extraTime: Int? = null,
)

@Serializable
data class PlayerStatEntry(
    @SerialName("player_id") val playerId: Int,
    val started: Boolean,
    val played: Boolean,
    @SerialName("minutes_played") val minutesPlayed: Int,
    @SerialName("yellow_cards") val yellowCards: Int = 0,
    @SerialName("red_cards") val redCards: Int = 0,
)

@Serializable
data class BatchPlayerStatsUpdate(val players: List<PlayerStatEntry>)

/** PATCH /api/admin/goals/{event_id} — only non-null fields are applied. */
@Serializable
data class GoalEventUpdateRequest(
    @SerialName("match_minute") val matchMinute: Int? = null,
    @SerialName("extra_time") val extraTime: Int? = null,
    @SerialName("player_id") val playerId: Int? = null,
    @SerialName("player_name") val playerName: String? = null,
    @SerialName("assist_player_id") val assistPlayerId: Int? = null,
)

// ── Tournaments (SB-324, read-only) ─────────────────────────────────────────

@Serializable
data class TournamentSummary(
    val id: Int,
    val name: String = "",
    @SerialName("season_id") val seasonId: Int? = null,
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("end_date") val endDate: String? = null,
    val location: String? = null,
    val description: String? = null,
    @SerialName("logo_url") val logoUrl: String? = null,
    @SerialName("age_groups") val ageGroups: List<AgeGroupDto> = emptyList(),
    @SerialName("match_count") val matchCount: Int = 0,
)

@Serializable
data class TournamentDetail(
    val id: Int,
    val name: String = "",
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("end_date") val endDate: String? = null,
    val location: String? = null,
    val description: String? = null,
    @SerialName("age_groups") val ageGroups: List<AgeGroupDto> = emptyList(),
    val matches: List<TournamentMatch> = emptyList(),
)

@Serializable
data class TournamentMatch(
    val id: Int,
    @SerialName("match_date") val matchDate: String? = null,
    @SerialName("scheduled_kickoff") val scheduledKickoff: String? = null,
    @SerialName("match_status") val matchStatus: String? = null,
    @SerialName("home_score") val homeScore: Int? = null,
    @SerialName("away_score") val awayScore: Int? = null,
    @SerialName("home_penalty_score") val homePenaltyScore: Int? = null,
    @SerialName("away_penalty_score") val awayPenaltyScore: Int? = null,
    @SerialName("tournament_group") val tournamentGroup: String? = null,
    @SerialName("tournament_round") val tournamentRound: String? = null,
    @SerialName("tournament_round_order") val tournamentRoundOrder: Int? = null,
    @SerialName("age_group") val ageGroup: AgeGroupDto? = null,
    @SerialName("home_team") val homeTeam: TeamRef? = null,
    @SerialName("away_team") val awayTeam: TeamRef? = null,
)

@Serializable
data class TeamRef(val id: Int, val name: String = "")

@Serializable
data class PlayerStatsResponse(
    @SerialName("player_id") val playerId: Int? = null,
    @SerialName("jersey_number") val jerseyNumber: Int? = null,
    @SerialName("display_name") val displayName: String? = null,
    val stats: PlayerSeasonStats = PlayerSeasonStats(),
    // False when the login has no linked roster player (managers, fans).
    val linked: Boolean = false,
)

@Serializable
data class PlayerSeasonStats(
    @SerialName("games_played") val gamesPlayed: Int = 0,
    @SerialName("games_started") val gamesStarted: Int = 0,
    @SerialName("total_minutes") val totalMinutes: Int = 0,
    @SerialName("total_goals") val totalGoals: Int = 0,
)

@Serializable
data class ApkUrlResponse(
    @SerialName("download_url") val downloadUrl: String,
    // Null until the backend/release pipeline both carry SB-322/SB-328.
    @SerialName("version_code") val versionCode: Int? = null,
    @SerialName("min_version_code") val minVersionCode: Int? = null,
)

@Serializable
data class AgeGroupDto(val id: Int, val name: String = "")

@Serializable
data class ClockRequest(
    val action: String,
    @SerialName("half_duration") val halfDuration: Int? = null,
    @SerialName("occurred_at") val occurredAt: String? = null,
)
