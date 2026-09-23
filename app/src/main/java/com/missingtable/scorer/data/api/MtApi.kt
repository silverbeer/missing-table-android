package com.missingtable.scorer.data.api

import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
// JsonObject stays for the live-action posts, whose bodies we never read.
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface MtApi {

    @POST("api/auth/login")
    suspend fun login(@Body body: LoginRequest): AuthResponse

    @POST("api/auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): AuthResponse

    @GET("api/auth/me")
    suspend fun me(): MeResponse

    @GET("api/matches")
    suspend fun matches(
        @Query("season_id") seasonId: Int? = null,
        @Query("start_date") startDate: String? = null,
        @Query("end_date") endDate: String? = null,
    ): List<MatchSummary>

    /**
     * Correct the half length after kickoff (SB-678). start_first_half is
     * idempotent, so the clock endpoint cannot revise it — this is the only
     * route. Online-only: unlike scoring actions it is not queued, because a
     * correction is not time-critical the way a goal is.
     */
    @PATCH("api/matches/{id}")
    suspend fun patchMatch(@Path("id") matchId: Int, @Body body: MatchPatchRequest)

    @GET("api/matches/{id}/live")
    suspend fun liveState(@Path("id") matchId: Int): LiveMatchState

    @GET("api/table")
    suspend fun table(
        @Query("season_id") seasonId: Int? = null,
        @Query("age_group_id") ageGroupId: Int? = null,
        @Query("division_id") divisionId: Int? = null,
        @Query("match_type") matchType: String? = null,
    ): TableResponse

    @GET("api/leaderboards/goals")
    suspend fun goalsLeaderboard(
        @Query("season_id") seasonId: Int,
        @Query("age_group_id") ageGroupId: Int? = null,
        // Singular by design server-side, which is why Leaders has no
        // "League + Flex" chip the way Matches does (SB-1119).
        @Query("match_type_id") matchTypeId: Int? = null,
        @Query("limit") limit: Int = 50,
    ): List<LeaderboardEntry>

    @GET("api/current-season")
    suspend fun currentSeason(): SeasonDto

    @GET("api/seasons")
    suspend fun seasons(): List<SeasonDto>

    @GET("api/leagues")
    suspend fun leagues(): List<LeagueDto>

    @GET("api/divisions")
    suspend fun divisions(): List<DivisionDto>

    @GET("api/age-groups")
    suspend fun ageGroups(): List<AgeGroupDto>

    @GET("api/match-types")
    suspend fun matchTypes(): List<MatchTypeDto>

    /**
     * The competitions this season and age group actually play (SB-1119).
     *
     * Leaders filters server-side, so its rows arrive already narrowed and it
     * cannot build chips from what is present the way Matches does. This is
     * how it still avoids offering a chip that can only return nothing — U13
     * plays no Flex, and the client must learn that from the data rather than
     * from hardcoded age-group ids that rot.
     */
    @GET("api/match-types/available")
    suspend fun availableMatchTypes(
        @Query("season_id") seasonId: Int? = null,
        @Query("age_group_id") ageGroupId: Int? = null,
    ): List<MatchTypeDto>

    /**
     * The featured match for a week (SB-1108). `week_start` is any date in the
     * week of interest — the server snaps it to that week's Monday — so the
     * Matches tab passes the Monday it is already showing.
     */
    @GET("api/motw")
    suspend fun motw(@Query("week_start") weekStart: String? = null): MotwResponse

    @GET("api/android/apk-url")
    suspend fun apkUrl(): ApkUrlResponse

    @GET("api/me/player-stats")
    suspend fun myPlayerStats(@Query("season_id") seasonId: Int): PlayerStatsResponse

    @GET("api/tournaments")
    suspend fun tournaments(@Query("season_id") seasonId: Int? = null): List<TournamentSummary>

    @GET("api/tournaments/{id}")
    suspend fun tournament(@Path("id") tournamentId: Int): TournamentDetail

    @GET("api/teams/{teamId}/roster")
    suspend fun roster(
        @Path("teamId") teamId: Int,
        @Query("season_id") seasonId: Int,
        @Query("age_group_id") ageGroupId: Int? = null,
    ): RosterResponse

    @POST("api/teams/{teamId}/roster/bulk")
    suspend fun bulkCreateRoster(
        @Path("teamId") teamId: Int,
        @Body body: BulkRosterRequest,
    ): BulkRosterResponse

    @GET("api/matches/{id}/lineup/{teamId}")
    suspend fun getLineup(@Path("id") matchId: Int, @Path("teamId") teamId: Int): LineupResponse

    @retrofit2.http.PUT("api/matches/{id}/lineup/{teamId}")
    suspend fun putLineup(
        @Path("id") matchId: Int,
        @Path("teamId") teamId: Int,
        @Body body: LineupSaveRequest,
    ): LineupResponse

    @POST("api/matches/{id}/live/goal")
    suspend fun postGoal(@Path("id") matchId: Int, @Body body: GoalRequest): JsonObject

    @POST("api/matches/{id}/live/card")
    suspend fun postCard(@Path("id") matchId: Int, @Body body: CardRequest): JsonObject

    @POST("api/matches/{id}/live/substitution")
    suspend fun postSubstitution(@Path("id") matchId: Int, @Body body: SubstitutionRequest): JsonObject

    @POST("api/matches/{id}/live/clock")
    suspend fun postClock(@Path("id") matchId: Int, @Body body: ClockRequest): JsonObject

    @POST("api/matches/{id}/live/reopen")
    suspend fun reopenMatch(@Path("id") matchId: Int): JsonObject

    @DELETE("api/matches/{id}/live/events/{eventId}")
    suspend fun deleteEvent(@Path("id") matchId: Int, @Path("eventId") eventId: Int): JsonObject

    // ── Post-match editing (SB-281) — completed matches only ─────────────────

    @GET("api/matches/{id}/live/events")
    suspend fun matchEvents(
        @Path("id") matchId: Int,
        @Query("limit") limit: Int = 100,
    ): List<MatchEvent>

    @POST("api/matches/{id}/post-match/goal")
    suspend fun postMatchGoal(@Path("id") matchId: Int, @Body body: PostMatchGoalRequest): JsonObject

    @DELETE("api/matches/{id}/post-match/goal/{eventId}")
    suspend fun deletePostMatchGoal(@Path("id") matchId: Int, @Path("eventId") eventId: Int): JsonObject

    @POST("api/matches/{id}/post-match/substitution")
    suspend fun postMatchSubstitution(@Path("id") matchId: Int, @Body body: PostMatchSubRequest): JsonObject

    @DELETE("api/matches/{id}/post-match/substitution/{eventId}")
    suspend fun deletePostMatchSubstitution(@Path("id") matchId: Int, @Path("eventId") eventId: Int): JsonObject

    @POST("api/matches/{id}/post-match/card")
    suspend fun postMatchCard(@Path("id") matchId: Int, @Body body: PostMatchCardRequest): JsonObject

    @DELETE("api/matches/{id}/post-match/card/{eventId}")
    suspend fun deletePostMatchCard(@Path("id") matchId: Int, @Path("eventId") eventId: Int): JsonObject

    @retrofit2.http.PUT("api/matches/{id}/post-match/stats/{teamId}")
    suspend fun putPostMatchStats(
        @Path("id") matchId: Int,
        @Path("teamId") teamId: Int,
        @Body body: BatchPlayerStatsUpdate,
    ): JsonObject

    // Despite the /admin/ path this is gated by match-management permission,
    // so team managers can correct scorer/assister/minute.
    @PATCH("api/admin/goals/{eventId}")
    suspend fun patchGoalEvent(@Path("eventId") eventId: Int, @Body body: GoalEventUpdateRequest): JsonObject
}
