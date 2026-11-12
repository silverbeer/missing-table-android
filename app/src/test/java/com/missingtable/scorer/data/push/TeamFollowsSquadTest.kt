package com.missingtable.scorer.data.push

import com.missingtable.scorer.data.api.MtApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.Collections

/**
 * SB-1645: follows are per squad (team + age group). The fake server mirrors
 * the backend's rules: a squad follow is skipped when a whole-team follow
 * already covers it, and a DELETE without age_group_id removes every follow
 * of the team.
 */
class TeamFollowsSquadTest {

    private lateinit var server: MockWebServer
    private lateinit var api: MtApi
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
    private val received: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())
    private val serverKeys: MutableSet<FollowKey> = Collections.synchronizedSet(linkedSetOf())
    private val names = mapOf(3 to "U13", 4 to "U14")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                received.add(request)
                val url = request.requestUrl!!
                val path = url.encodedPath
                return when {
                    request.method == "GET" && path == "/api/users/me/team-follows" ->
                        MockResponse().setBody(body())

                    request.method == "POST" && path == "/api/users/me/team-follows" -> {
                        val o = json.parseToJsonElement(request.body.readUtf8()).jsonObject
                        val team = o.getValue("team_id").jsonPrimitive.int
                        val ag = o["age_group_id"]?.jsonPrimitive?.intOrNull
                        synchronized(serverKeys) {
                            if (FollowKey(team, null) !in serverKeys) serverKeys += FollowKey(team, ag)
                        }
                        MockResponse().setResponseCode(201).setBody("""{"team_id": $team, "following": true}""")
                    }

                    request.method == "DELETE" && path.startsWith("/api/users/me/team-follows/") -> {
                        val removal = FollowKey(
                            path.substringAfterLast('/').toInt(),
                            url.queryParameter("age_group_id")?.toInt(),
                        )
                        synchronized(serverKeys) { serverKeys.removeAll { removal.removes(it) } }
                        MockResponse().setResponseCode(204)
                    }

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(MtApi::class.java)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun body(): String {
        val items = synchronized(serverKeys) { serverKeys.toList() }.joinToString(",") { k ->
            val ag = k.ageGroupId?.let { """{"id": $it, "name": "${names[it]}"}""" } ?: "null"
            """{"team_id": ${k.teamId}, "age_group_id": ${k.ageGroupId}, "age_group": $ag,
                "team": {"id": ${k.teamId}, "name": "Team ${k.teamId}"}}"""
        }
        return """{"follows": [$items]}"""
    }

    @Test
    fun `refresh keys each follow by team and age group`() = runBlocking {
        serverKeys += listOf(FollowKey(1, null), FollowKey(2, 4))
        val follows = TeamFollows(api)
        assertTrue(follows.refresh())

        assertEquals(setOf(FollowKey(1, null), FollowKey(2, 4)), follows.keys.value)
        assertEquals(setOf(1, 2), follows.ids.value)
        assertEquals("U14", follows.follows.value.single { it.teamId == 2 }.ageGroup?.name)
    }

    @Test
    fun `squad follow posts the age group and covers only that squad`() = runBlocking {
        val follows = TeamFollows(api)
        assertTrue(follows.setFollowing(2, follow = true, ageGroupId = 4))

        assertEquals(setOf(FollowKey(2, 4)), serverKeys)
        assertTrue(follows.keys.value.covers(2, 4))
        assertFalse(follows.keys.value.covers(2, 3))
    }

    @Test
    fun `squad unfollow removes that squad and keeps the team's other squads`() = runBlocking {
        serverKeys += listOf(FollowKey(2, 3), FollowKey(2, 4))
        val follows = TeamFollows(api)
        follows.refresh()

        assertTrue(follows.setFollowing(2, follow = false, ageGroupId = 4))

        assertEquals(setOf(FollowKey(2, 3)), follows.keys.value)
        assertEquals(listOf(FollowKey(2, 3)), follows.follows.value.map { it.key })
        assertEquals(setOf(FollowKey(2, 3)), serverKeys)
        val delete = received.single { it.method == "DELETE" }
        assertEquals("/api/users/me/team-follows/2?age_group_id=4", delete.path)
    }

    @Test
    fun `whole-team unfollow removes every squad of the team`() = runBlocking {
        serverKeys += listOf(FollowKey(2, 3), FollowKey(2, 4), FollowKey(5, 4))
        val follows = TeamFollows(api)
        follows.refresh()

        assertTrue(follows.setFollowing(2, follow = false))

        assertEquals(setOf(FollowKey(5, 4)), follows.keys.value)
        assertEquals(setOf(5), follows.ids.value)
    }

    // ── pure rules ───────────────────────────────────────────────────────────

    @Test
    fun `a whole-team follow covers every age group`() {
        val keys = setOf(FollowKey(2, null))
        assertTrue(keys.covers(2, 3))
        assertTrue(keys.covers(2, 4))
        assertTrue(keys.covers(2, null))
        assertFalse(keys.covers(9, 3))
    }

    @Test
    fun `with no age group known any follow of the team counts`() {
        assertTrue(setOf(FollowKey(2, 4)).covers(2, null))
    }

    @Test
    fun `toggle target - squad when the squad follow is what covers it`() {
        assertEquals(FollowKey(2, 4), setOf(FollowKey(2, 4)).toggleTarget(2, 4))
    }

    @Test
    fun `toggle target - whole team when a whole-team follow covers it`() {
        assertEquals(FollowKey(2, null), setOf(FollowKey(2, null)).toggleTarget(2, 4))
    }

    @Test
    fun `toggle target - whole team when no age group is known`() {
        assertEquals(FollowKey(2, null), setOf(FollowKey(2, 4)).toggleTarget(2, null))
    }
}
