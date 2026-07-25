package com.missingtable.scorer.data.auth

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.authDataStore by preferencesDataStore(name = "auth")

class TokenStore(private val context: Context) : TokenStorage {

    private val accessKey = stringPreferencesKey("access_token")
    private val refreshKey = stringPreferencesKey("refresh_token")
    private val usernameKey = stringPreferencesKey("username")

    val accessTokenFlow = context.authDataStore.data.map { it[accessKey] }

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
