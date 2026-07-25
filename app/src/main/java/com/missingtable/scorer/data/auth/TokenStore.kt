package com.missingtable.scorer.data.auth

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.authDataStore by preferencesDataStore(name = "auth")

/** Cached identity from /api/auth/me — who this login is and what they may do. */
data class Session(
    val username: String? = null,
    val role: String? = null,
    val teamId: Int? = null,
    val clubId: Int? = null,
    val displayName: String? = null,
) {
    /**
     * Mirrors the backend's require_match_management_permission. A null role
     * (me() never succeeded yet) defaults to true so the historical scorer
     * flow keeps working offline — the server still enforces the real answer.
     */
    val canScore: Boolean
        get() = role == null || role in setOf("admin", "club_manager", "team-manager", "team_manager")
}

class TokenStore(private val context: Context) : TokenStorage {

    private val accessKey = stringPreferencesKey("access_token")
    private val refreshKey = stringPreferencesKey("refresh_token")
    private val usernameKey = stringPreferencesKey("username")
    private val roleKey = stringPreferencesKey("role")
    private val teamIdKey = intPreferencesKey("team_id")
    private val clubIdKey = intPreferencesKey("club_id")
    private val displayNameKey = stringPreferencesKey("display_name")

    val accessTokenFlow = context.authDataStore.data.map { it[accessKey] }

    val sessionFlow: Flow<Session> = context.authDataStore.data.map { prefs ->
        Session(
            username = prefs[usernameKey],
            role = prefs[roleKey],
            teamId = prefs[teamIdKey],
            clubId = prefs[clubIdKey],
            displayName = prefs[displayNameKey],
        )
    }

    suspend fun saveSession(role: String?, teamId: Int?, clubId: Int?, displayName: String?) {
        context.authDataStore.edit { prefs ->
            if (role != null) prefs[roleKey] = role else prefs.remove(roleKey)
            if (teamId != null) prefs[teamIdKey] = teamId else prefs.remove(teamIdKey)
            if (clubId != null) prefs[clubIdKey] = clubId else prefs.remove(clubIdKey)
            if (displayName != null) prefs[displayNameKey] = displayName else prefs.remove(displayNameKey)
        }
    }

    suspend fun accessToken(): String? = context.authDataStore.data.first()[accessKey]

    suspend fun refreshToken(): String? = context.authDataStore.data.first()[refreshKey]

    suspend fun username(): String? = context.authDataStore.data.first()[usernameKey]

    // OkHttp's Authenticator/Interceptor run on network threads, not in a
    // coroutine — blocking reads are the accepted pattern there.
    override fun accessTokenBlocking(): String? = runBlocking { accessToken() }

    override fun refreshTokenBlocking(): String? = runBlocking { refreshToken() }

    suspend fun save(access: String, refresh: String?, username: String? = null) {
        context.authDataStore.edit { prefs ->
            prefs[accessKey] = access
            if (refresh != null) prefs[refreshKey] = refresh
            if (username != null) prefs[usernameKey] = username
        }
    }

    override fun saveBlocking(access: String, refresh: String?) = runBlocking { save(access, refresh) }

    suspend fun clear() {
        context.authDataStore.edit { it.clear() }
    }

    override fun clearBlocking() = runBlocking { clear() }
}
