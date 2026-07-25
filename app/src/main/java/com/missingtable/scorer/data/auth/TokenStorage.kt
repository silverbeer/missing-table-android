package com.missingtable.scorer.data.auth

/**
 * Blocking token accessors used by OkHttp's Interceptor/Authenticator, which
 * run on network threads. Interface so auth plumbing is unit-testable without
 * an Android Context (TokenStore needs DataStore).
 */
interface TokenStorage {
    fun accessTokenBlocking(): String?
    fun refreshTokenBlocking(): String?
    fun saveBlocking(access: String, refresh: String?)
    fun clearBlocking()
}
