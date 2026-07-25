package com.missingtable.scorer.data.api

import com.missingtable.scorer.data.auth.TokenStorage
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakeTokenStorage(
    var access: String? = "old-access",
    var refresh: String? = "old-refresh",
) : TokenStorage {
    var cleared = false

    override fun accessTokenBlocking() = access
    override fun refreshTokenBlocking() = refresh
    override fun saveBlocking(access: String, refresh: String?) {
        this.access = access
        if (refresh != null) this.refresh = refresh
    }
    override fun clearBlocking() {
        access = null
        refresh = null
        cleared = true
    }
}

class TokenAuthenticatorTest {

    private lateinit var server: MockWebServer
    private lateinit var storage: FakeTokenStorage

    /** Response the mock returns for POST /api/auth/refresh. */
    private var refreshResponse: MockResponse = MockResponse().setResponseCode(500)

    /** Whether /api/data succeeds once a request arrives bearing the new token. */
    private var acceptNewToken = true

    @Before
    fun setUp() {
        storage = FakeTokenStorage()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                when (request.path) {
                    "/api/auth/refresh" -> refreshResponse
                    else ->
                        if (acceptNewToken && request.getHeader("Authorization") == "Bearer new-access") {
                            MockResponse().setResponseCode(200).setBody("ok")
                        } else {
                            MockResponse().setResponseCode(401)
                        }
                }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(): OkHttpClient {
        val baseUrl = server.url("/").toString().removeSuffix("/")
        return OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(storage))
            .authenticator(TokenAuthenticator(baseUrl, storage))
            .build()
    }

    private fun get(path: String) =
        client().newCall(Request.Builder().url(server.url(path)).build()).execute()

    @Test
    fun `401 refreshes with session shape and retries with new token`() {
        refreshResponse = MockResponse().setResponseCode(200).setBody(
            """{"success":true,"session":{"access_token":"new-access","refresh_token":"new-refresh"}}"""
        )

        get("/api/data").use { assertEquals(200, it.code) }

        assertEquals("new-access", storage.access)
        assertEquals("new-refresh", storage.refresh)
        // original + refresh + retry
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `401 refreshes with flat login shape and retries`() {
        refreshResponse = MockResponse().setResponseCode(200).setBody(
            """{"access_token":"new-access","refresh_token":"new-refresh"}"""
        )

        get("/api/data").use { assertEquals(200, it.code) }

        assertEquals("new-access", storage.access)
    }

    @Test
    fun `refresh 503 keeps tokens and fails the request`() {
        refreshResponse = MockResponse().setResponseCode(503)

        get("/api/data").use { assertEquals(401, it.code) }

        assertEquals("old-access", storage.access)
        assertEquals("old-refresh", storage.refresh)
        assertFalse(storage.cleared)
    }

    @Test
    fun `refresh 401 clears tokens - session is dead`() {
        refreshResponse = MockResponse().setResponseCode(401)

        get("/api/data").use { assertEquals(401, it.code) }

        assertTrue(storage.cleared)
        assertNull(storage.access)
        assertNull(storage.refresh)
    }

    @Test
    fun `retries only once when server keeps returning 401`() {
        refreshResponse = MockResponse().setResponseCode(200).setBody(
            """{"session":{"access_token":"new-access","refresh_token":"new-refresh"}}"""
        )
        acceptNewToken = false

        get("/api/data").use { assertEquals(401, it.code) }

        // original + refresh + one retry — no loop
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `no refresh token means no refresh attempt`() {
        storage.refresh = null

        get("/api/data").use { assertEquals(401, it.code) }

        assertEquals(1, server.requestCount)
    }
}
