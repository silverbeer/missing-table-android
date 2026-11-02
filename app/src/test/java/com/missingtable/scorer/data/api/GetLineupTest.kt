package com.missingtable.scorer.data.api

import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Retrofit

/**
 * The backend answers GET lineup with 200 + `null` when no lineup has been
 * saved for that team yet. That must read as "no lineup", not as a failure:
 * the lineup screen treats a failed load as "don't overwrite what you can't
 * see" and refuses to save (SB-1511).
 */
class GetLineupTest {
    private lateinit var server: MockWebServer
    private lateinit var api: MtApi
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(NullBodyConverterFactory)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(MtApi::class.java)
    }

    @After fun tearDown() = server.shutdown()

    @Test fun `null body means no lineup yet`() = runBlocking {
        server.enqueue(MockResponse().setBody("null"))
        assertNull(api.getLineup(1, 2))
    }

    @Test fun `saved lineup decodes`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"match_id":1,"team_id":2,"formation_name":"4-3-3",
                   "positions":[{"player_id":7,"position":"GK"}]}"""
            )
        )
        val l = api.getLineup(1, 2)
        assertEquals("4-3-3", l?.formationName)
        assertEquals(7, l?.positions?.single()?.playerId)
    }

    @Test fun `server error is still a failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"detail":"boom"}"""))
        val r = runCatching { api.getLineup(1, 2) }
        assertTrue(r.exceptionOrNull() is HttpException)
    }
}
