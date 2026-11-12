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
import retrofit2.Retrofit

/**
 * GET /api/motw in both shapes (SB-1641). Per-slot picks add `picks` and the
 * slot fields; prod runs the older server until it deploys, so a response
 * without any of them must still decode to the same pick.
 */
class MotwDecodeTest {
    private lateinit var server: MockWebServer
    private lateinit var api: MtApi
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

    private val match = """{"id":42,"match_date":"2026-11-14","home_team_id":1,"away_team_id":2,
        "home_team_name":"IFA","away_team_name":"NEFC","age_group_name":"U16"}"""

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

    @Test fun `old shape decodes with no picks and no slot`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"week_start":"2026-11-09","motw":{"week_start":"2026-11-09",
                   "pick_number":3,"blurb":"Top of the table","match":$match}}"""
            )
        )
        val r = api.motw("2026-11-09")
        assertEquals(42, r.motw?.match?.id)
        assertEquals(3, r.motw?.pickNumber)
        assertNull(r.motw?.pool)
        assertNull(r.motw?.ageGroupId)
        assertNull(r.motw?.ageGroup)
        assertTrue(r.picks.isEmpty())
    }

    @Test fun `new shape decodes the named slot and every pick`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"week_start":"2026-11-09",
                   "motw":{"week_start":"2026-11-09","pick_number":3,"blurb":null,
                     "reasons":["rivalry"],"pool":"pathway","age_group_id":5,"age_group":"U16",
                     "match":$match},
                   "picks":[
                     {"week_start":"2026-11-09","pick_number":3,"pool":"main","age_group_id":4,
                      "age_group":"U15","match":$match},
                     {"week_start":"2026-11-09","pick_number":3,"pool":"pathway","age_group_id":5,
                      "age_group":null,"match":$match}
                   ]}"""
            )
        )
        val r = api.motw("2026-11-09", ageGroupId = 5, pool = "pathway")
        assertEquals("pathway", r.motw?.pool)
        assertEquals(5, r.motw?.ageGroupId)
        assertEquals("U16", r.motw?.ageGroup)
        assertEquals(listOf("main", "pathway"), r.picks.map { it.pool })
        assertEquals(listOf(4, 5), r.picks.map { it.ageGroupId })
        // A slot whose age group name is missing still decodes.
        assertNull(r.picks[1].ageGroup)
    }

    @Test fun `no pick this week is a null, not an error`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"week_start":"2026-11-09","motw":null,"picks":[]}"""))
        val r = api.motw("2026-11-09", ageGroupId = 4)
        assertNull(r.motw)
        assertTrue(r.picks.isEmpty())
    }

    @Test fun `the slot rides in the query, and absent params are left off`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"motw":null}"""))
        server.enqueue(MockResponse().setBody("""{"motw":null}"""))
        api.motw("2026-11-09", ageGroupId = 5, pool = "pathway")
        api.motw("2026-11-09")
        assertEquals(
            "/api/motw?week_start=2026-11-09&age_group_id=5&pool=pathway",
            server.takeRequest().path,
        )
        assertEquals("/api/motw?week_start=2026-11-09", server.takeRequest().path)
    }
}
