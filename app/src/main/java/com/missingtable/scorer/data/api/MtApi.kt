package com.missingtable.scorer.data.api

import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface MtApi {

    @POST("api/auth/login")
    suspend fun login(@Body body: LoginRequest): AuthResponse

    @POST("api/auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): AuthResponse

    @GET("api/auth/me")
    suspend fun me(): JsonObject

    @GET("api/matches")
    suspend fun matches(
        @Query("season_id") seasonId: Int? = null,
        @Query("start_date") startDate: String? = null,
        @Query("end_date") endDate: String? = null,
    ): List<MatchSummary>

    @GET("api/matches/{id}/live")
    suspend fun liveState(@Path("id") matchId: Int): LiveMatchState

    @GET("api/teams/{teamId}/roster")
    suspend fun roster(
        @Path("teamId") teamId: Int,
        @Query("season_id") seasonId: Int,
        @Query("age_group_id") ageGroupId: Int? = null,
    ): RosterResponse

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
}
