package com.missingtable.scorer.data.api

import com.missingtable.scorer.data.auth.TokenStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route

/** Adds the bearer token to every request. */
class AuthInterceptor(private val tokenStore: TokenStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.encodedPath.startsWith("/api/auth/login") ||
            request.url.encodedPath.startsWith("/api/auth/refresh")
        ) {
            return chain.proceed(request)
        }
        val token = tokenStore.accessTokenBlocking() ?: return chain.proceed(request)
        return chain.proceed(
            request.newBuilder().header("Authorization", "Bearer $token").build()
        )
    }
}

/**
 * On 401, refresh the token (with rotation) and retry once.
 * Uses a bare OkHttp client so the refresh call skips this authenticator.
 */
class TokenAuthenticator(
    private val baseUrl: String,
    private val tokenStore: TokenStore,
) : Authenticator {

    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.request.url.encodedPath.startsWith("/api/auth/")) return null
        // Give up after one retry
        if (response.priorResponse != null) return null

        val refreshToken = tokenStore.refreshTokenBlocking() ?: return null

        val refreshed = runCatching {
            val body = """{"refresh_token":"$refreshToken"}"""
                .toRequestBody("application/json".toMediaType())
            val call = OkHttpClient().newCall(
                Request.Builder().url("$baseUrl/api/auth/refresh").post(body).build()
            )
            call.execute().use { r ->
                if (!r.isSuccessful) return@runCatching null
                val obj = json.parseToJsonElement(r.body!!.string()).jsonObject
                // POST /api/auth/refresh nests the rotated tokens under "session"
                // ({"success":true,"session":{"access_token":..,"refresh_token":..}}),
                // unlike /login which returns them flat. Read session first, then
                // fall back to the flat shape so both are handled.
                val tokens = obj["session"]?.jsonObject ?: obj
                val access = tokens["access_token"]?.jsonPrimitive?.content
                    ?: return@runCatching null
                val newRefresh = tokens["refresh_token"]?.jsonPrimitive?.content
                tokenStore.saveBlocking(access, newRefresh)
                access
            }
        }.getOrNull() ?: return null

        return response.request.newBuilder()
            .header("Authorization", "Bearer $refreshed")
            .build()
    }
}
