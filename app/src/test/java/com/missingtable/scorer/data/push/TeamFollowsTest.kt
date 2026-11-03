package com.missingtable.scorer.data.push

import com.missingtable.scorer.data.api.MtApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** SB-1464: the shared follows cache — optimistic, per-team rollback. */
class TeamFollowsTest {

    private lateinit var server: MockWebServer
    private lateinit var api: MtApi
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
    private val received: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())

    /** What the server believes the user follows. */
    private val serverFollows: MutableSet<Int> = Collections.synchronizedSet(linkedSetOf())

    /** Per-team failure codes for POST/DELETE; GET failure code. */
    private val failFollow = ConcurrentHashMap<Int, Int>()
    private val failUnfollow = ConcurrentHashMap<Int, Int>()
    @Volatile private var getCode = 200

    /** Per-team latches that hold a POST until released. */
    private val holdFollow = ConcurrentHashMap<Int, CountDownLatch>()
    private val arrivedFollow = ConcurrentHashMap<Int, CountDownLatch>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                received.add(request)
                val path = request.path.orEmpty()
                return when {
                    request.method == "GET" && path == "/api/users/me/team-follows" ->
                        if (getCode != 200) MockResponse().setResponseCode(getCode)
                        else MockResponse().setBody(followsBody())

                    request.method == "POST" && path == "/api/users/me/team-follows" -> {
                        val id = json.parseToJsonElement(request.body.readUtf8())
                            .jsonObject.getValue("team_id").jsonPrimitive.int
                        arrivedFollow[id]?.countDown()
                        holdFollow[id]?.await(5, TimeUnit.SECONDS)
                        val code = failFollow[id]
                        if (code != null) {
                            MockResponse().setResponseCode(code)
                        } else {
                            serverFollows.add(id)
                            MockResponse().setResponseCode(201).setBody("""{"team_id": $id, "following": true}""")
                        }
                    }

                    request.method == "DELETE" && path.startsWith("/api/users/me/team-follows/") -> {
                        val id = path.substringAfterLast('/').toInt()
                        val code = failUnfollow[id]
                        if (code != null) {
                            MockResponse().setResponseCode(code)
                        } else {
                            serverFollows.remove(id)
                            MockResponse().setResponseCode(204)
                        }
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
    fun tearDown() {
        holdFollow.values.forEach { it.countDown() }
        server.shutdown()
    }

    private fun followsBody(): String {
        val items = synchronized(serverFollows) { serverFollows.toList() }.joinToString(",") { id ->
            """{"team_id": $id, "team": {"id": $id, "name": "Team $id", "club": {"id": 1, "name": "Club $id"}}}"""
        }
        return """{"follows": [$items]}"""
    }

    private fun requests(method: String) = synchronized(received) { received.filter { it.method == method } }

    // ── refresh ──────────────────────────────────────────────────────────────

    @Test
    fun `refresh loads ids and embeds from the server`() = runBlocking {
        serverFollows += listOf(3, 7)
        val follows = TeamFollows(api)

        assertTrue(follows.refresh())

        assertEquals(setOf(3, 7), follows.ids.value)
        assertEquals(listOf(3, 7), follows.follows.value.map { it.teamId })
        assertEquals("Team 7", follows.follows.value[1].team?.name)
    }

    @Test
    fun `refresh with no follows is empty`() = runBlocking {
        val follows = TeamFollows(api)
        assertTrue(follows.refresh())
        assertTrue(follows.ids.value.isEmpty())
        assertTrue(follows.follows.value.isEmpty())
    }

    @Test
    fun `refresh failure returns false and keeps the previous state`() = runBlocking {
        serverFollows += 3
        val follows = TeamFollows(api)
        follows.refresh()

        getCode = 500
        serverFollows += 9
        assertFalse(follows.refresh())

        assertEquals(setOf(3), follows.ids.value)
        assertEquals(listOf(3), follows.follows.value.map { it.teamId })
    }

    @Test
    fun `refresh replaces rather than merges`() = runBlocking {
        serverFollows += listOf(1, 2)
        val follows = TeamFollows(api)
        follows.refresh()

        serverFollows.remove(1)
        follows.refresh()

        assertEquals(setOf(2), follows.ids.value)
        assertEquals(listOf(2), follows.follows.value.map { it.teamId })
    }

    // ── follow ───────────────────────────────────────────────────────────────

    @Test
    fun `follow posts team_id and refreshes the embeds`() = runBlocking {
        val follows = TeamFollows(api)

        assertTrue(follows.setFollowing(5, follow = true))

        val post = requests("POST").single()
        assertEquals("/api/users/me/team-follows", post.path)
        assertEquals(setOf(5), follows.ids.value)
        assertEquals(listOf(5), follows.follows.value.map { it.teamId })
        assertEquals("Club 5", follows.follows.value.single().team?.club?.name)
        assertEquals(1, requests("GET").size)
    }

    @Test
    fun `follow is optimistic - the id shows before the server answers`() = runBlocking {
        val follows = TeamFollows(api)
        holdFollow[5] = CountDownLatch(1)
        arrivedFollow[5] = CountDownLatch(1)

        val call = async(kotlinx.coroutines.Dispatchers.IO) { follows.setFollowing(5, follow = true) }
        assertTrue("POST never reached the server", arrivedFollow.getValue(5).await(5, TimeUnit.SECONDS))
        assertEquals(setOf(5), follows.ids.value)
        // The embed list only ever comes from the server.
        assertTrue(follows.follows.value.isEmpty())

        holdFollow.getValue(5).countDown()
        assertTrue(call.await())
        assertEquals(setOf(5), follows.ids.value)
    }

    @Test
    fun `follow failure rolls back and returns false`() = runBlocking {
        failFollow[5] = 500
        val follows = TeamFollows(api)

        assertFalse(follows.setFollowing(5, follow = true))

        assertTrue(follows.ids.value.isEmpty())
        assertTrue(follows.follows.value.isEmpty())
        assertTrue("no refresh after a failed follow", requests("GET").isEmpty())
    }

    @Test
    fun `follow failure on a dead server rolls back`() = runBlocking {
        val follows = TeamFollows(api)
        server.shutdown()
        assertFalse(follows.setFollowing(5, follow = true))
        assertTrue(follows.ids.value.isEmpty())
    }

    @Test
    fun `follow failure rolls back only that team, keeping existing follows`() = runBlocking {
        serverFollows += listOf(1, 2)
        val follows = TeamFollows(api)
        follows.refresh()
        failFollow[9] = 422

        assertFalse(follows.setFollowing(9, follow = true))

        assertEquals(setOf(1, 2), follows.ids.value)
        assertEquals(listOf(1, 2), follows.follows.value.map { it.teamId })
    }

    @Test
    fun `follow failure does not undo another team that moved meanwhile`() = runBlocking {
        val follows = TeamFollows(api)
        failFollow[5] = 500
        holdFollow[5] = CountDownLatch(1)
        arrivedFollow[5] = CountDownLatch(1)

        val failing = async(kotlinx.coroutines.Dispatchers.IO) { follows.setFollowing(5, follow = true) }
        assertTrue(arrivedFollow.getValue(5).await(5, TimeUnit.SECONDS))

        // While team 5 is in flight, team 6 is followed successfully; 6's
        // post-follow refresh() must keep 5's optimistic entry.
        assertTrue(follows.setFollowing(6, follow = true))
        assertEquals(setOf(5, 6), follows.ids.value)

        holdFollow.getValue(5).countDown()
        assertFalse(failing.await())
        assertEquals(setOf(6), follows.ids.value)
    }

    // Was a known race (QE repro): a refresh landing while an unfollow is in
    // flight re-added the team from the server's not-yet-updated set. Pending
    // overrides now sit on top of every refresh.
    @Test
    fun `unfollow wins over a refresh that raced it`() = runBlocking {
        serverFollows += listOf(1, 2)
        val follows = TeamFollows(api)
        follows.refresh()
        // Simulate the race: unfollow 1 is in flight, server hasn't applied it,
        // and another refresh lands.
        val deleteGate = CountDownLatch(1)
        val deleteArrived = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            val inner = server.dispatcher
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "DELETE") {
                    deleteArrived.countDown()
                    deleteGate.await(5, TimeUnit.SECONDS)
                }
                return inner.dispatch(request)
            }
        }
        val call = async(kotlinx.coroutines.Dispatchers.IO) { follows.setFollowing(1, follow = false) }
        assertTrue(deleteArrived.await(5, TimeUnit.SECONDS))
        follows.refresh()
        deleteGate.countDown()
        assertTrue(call.await())

        assertEquals(setOf(2), follows.ids.value)
    }

    @Test
    fun `an in-flight follow survives a refresh that does not know it yet`() = runBlocking {
        serverFollows += 1
        val follows = TeamFollows(api)
        follows.refresh()
        holdFollow[5] = CountDownLatch(1)
        arrivedFollow[5] = CountDownLatch(1)

        val call = async(kotlinx.coroutines.Dispatchers.IO) { follows.setFollowing(5, follow = true) }
        assertTrue(arrivedFollow.getValue(5).await(5, TimeUnit.SECONDS))
        follows.refresh()
        assertEquals(setOf(1, 5), follows.ids.value)

        holdFollow.getValue(5).countDown()
        assertTrue(call.await())
        assertEquals(setOf(1, 5), follows.ids.value)
    }

    // ── unfollow ─────────────────────────────────────────────────────────────

    @Test
    fun `unfollow deletes by team id and drops it locally without a refetch`() = runBlocking {
        serverFollows += listOf(1, 2)
        val follows = TeamFollows(api)
        follows.refresh()
        val getsBefore = requests("GET").size

        assertTrue(follows.setFollowing(1, follow = false))

        val del = requests("DELETE").single()
        assertEquals("/api/users/me/team-follows/1", del.path)
        assertEquals(setOf(2), follows.ids.value)
        assertEquals(listOf(2), follows.follows.value.map { it.teamId })
        assertEquals(getsBefore, requests("GET").size)
    }

    @Test
    fun `unfollow of a team not followed still succeeds - server is idempotent`() = runBlocking {
        val follows = TeamFollows(api)
        assertTrue(follows.setFollowing(4, follow = false))
        assertTrue(follows.ids.value.isEmpty())
    }

    @Test
    fun `unfollow non-2xx rolls back that team and returns false`() = runBlocking {
        serverFollows += listOf(1, 2)
        val follows = TeamFollows(api)
        follows.refresh()
        failUnfollow[1] = 500

        assertFalse(follows.setFollowing(1, follow = false))

        assertEquals(setOf(1, 2), follows.ids.value)
        assertEquals(listOf(1, 2), follows.follows.value.map { it.teamId })
    }

    @Test
    fun `unfollow 401 is a failure too, not silently accepted`() = runBlocking {
        serverFollows += 1
        val follows = TeamFollows(api)
        follows.refresh()
        failUnfollow[1] = 401

        assertFalse(follows.setFollowing(1, follow = false))
        assertEquals(setOf(1), follows.ids.value)
    }

    // ── loaded ───────────────────────────────────────────────────────────────

    @Test
    fun `loaded flips on the first successful refresh and back on clear`() = runBlocking {
        val follows = TeamFollows(api)
        assertFalse(follows.loaded.value)
        getCode = 500
        follows.ensureLoaded()
        assertFalse("a failed load stays not-loaded, so it is retried", follows.loaded.value)
        getCode = 200
        follows.ensureLoaded()
        assertTrue(follows.loaded.value)
        follows.ensureLoaded()
        assertEquals("loaded once, not refetched", 2, requests("GET").size)
        follows.clear()
        assertFalse(follows.loaded.value)
    }

    // ── clear ────────────────────────────────────────────────────────────────

    @Test
    fun `clear empties ids and follows without touching the server`() = runBlocking {
        serverFollows += listOf(1, 2)
        val follows = TeamFollows(api)
        follows.refresh()
        val before = received.size

        follows.clear()

        assertTrue(follows.ids.value.isEmpty())
        assertTrue(follows.follows.value.isEmpty())
        assertEquals(before, received.size)
    }

    @Test
    fun `a refresh that lands after clear does not restore the old user's follows`() = runBlocking {
        serverFollows += listOf(1, 2)
        val follows = TeamFollows(api)
        val getGate = CountDownLatch(1)
        val getArrived = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            val inner = server.dispatcher
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "GET") {
                    getArrived.countDown()
                    getGate.await(5, TimeUnit.SECONDS)
                }
                return inner.dispatch(request)
            }
        }
        val late = async(kotlinx.coroutines.Dispatchers.IO) { follows.refresh() }
        assertTrue(getArrived.await(5, TimeUnit.SECONDS))
        follows.clear()
        getGate.countDown()

        assertFalse(late.await())
        assertTrue(follows.ids.value.isEmpty())
        assertTrue(follows.follows.value.isEmpty())
        assertFalse(follows.loaded.value)
    }

    @Test
    fun `a follow that settles after clear changes nothing`() = runBlocking {
        val follows = TeamFollows(api)
        holdFollow[5] = CountDownLatch(1)
        arrivedFollow[5] = CountDownLatch(1)

        val call = async(kotlinx.coroutines.Dispatchers.IO) { follows.setFollowing(5, follow = true) }
        assertTrue(arrivedFollow.getValue(5).await(5, TimeUnit.SECONDS))
        follows.clear()
        holdFollow.getValue(5).countDown()

        assertFalse(call.await())
        assertTrue(follows.ids.value.isEmpty())
        assertTrue("no refresh for a cleared session", requests("GET").isEmpty())
    }
}
