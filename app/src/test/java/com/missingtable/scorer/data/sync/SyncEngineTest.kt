package com.missingtable.scorer.data.sync

import androidx.test.core.app.ApplicationProvider
import androidx.room.Room
import com.missingtable.scorer.data.api.CardRequest
import com.missingtable.scorer.data.api.ClockRequest
import com.missingtable.scorer.data.api.GoalRequest
import com.missingtable.scorer.data.api.MessageRequest
import com.missingtable.scorer.data.api.MtApi
import com.missingtable.scorer.data.api.SubstitutionRequest
import com.missingtable.scorer.data.db.MtDatabase
import com.missingtable.scorer.data.db.PendingAction
import com.missingtable.scorer.data.db.PendingActionDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * SB-279 exit criteria, executable in CI: a match scored offline syncs
 * exactly-once, in order, when connectivity returns.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncEngineTest {

    private lateinit var server: MockWebServer
    private lateinit var db: MtDatabase
    private lateinit var dao: PendingActionDao
    private lateinit var engine: SyncEngine
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** Per-path response codes consumed in order; default 200. */
    private val responseQueue = ArrayDeque<Int>()
    private val received = mutableListOf<RecordedRequest>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                received.add(request)
                val code = responseQueue.removeFirstOrNull() ?: 200
                return MockResponse().setResponseCode(code).setBody("{}")
            }
        }
        server.start()

        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(MtApi::class.java)

        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), MtDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.pendingActionDao()

        engine = SyncEngine(dao, api, json, CoroutineScope(Job()), autoStart = false)
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    private fun goalRow(clientEventId: String, matchId: Int = 7) = PendingAction(
        clientEventId = clientEventId,
        matchId = matchId,
        actionType = PendingAction.ActionType.GOAL,
        payloadJson = json.encodeToString(
            GoalRequest(teamId = 1, playerId = 10, matchMinute = 12, clientEventId = clientEventId)
        ),
        createdAt = 1L,
    )

    @Test
    fun `five-action batch syncs in strict FIFO order`() = runBlocking {
        dao.insert(goalRow("g1"))
        dao.insert(
            PendingAction(
                clientEventId = "c1", matchId = 7,
                actionType = PendingAction.ActionType.CARD,
                payloadJson = json.encodeToString(
                    CardRequest(teamId = 1, playerId = 4, cardType = "yellow_card", clientEventId = "c1")
                ),
                createdAt = 2L,
            )
        )
        dao.insert(
            PendingAction(
                clientEventId = "s1", matchId = 7,
                actionType = PendingAction.ActionType.SUB,
                payloadJson = json.encodeToString(
                    SubstitutionRequest(teamId = 1, playerInId = 9, playerOutId = 10, clientEventId = "s1")
                ),
                createdAt = 3L,
            )
        )
        dao.insert(
            PendingAction(
                clientEventId = "k1", matchId = 7,
                actionType = PendingAction.ActionType.CLOCK,
                payloadJson = json.encodeToString(ClockRequest("start_halftime")),
                createdAt = 4L,
            )
        )
        dao.insert(goalRow("g2"))

        assertEquals(SyncEngine.DrainResult.EMPTY, engine.drain())

        assertEquals(
            listOf(
                "/api/matches/7/live/goal",
                "/api/matches/7/live/card",
                "/api/matches/7/live/substitution",
                "/api/matches/7/live/clock",
                "/api/matches/7/live/goal",
            ),
            received.map { it.path },
        )
        assertNull(dao.head())
    }

    @Test
    fun `503 then success retries the same action exactly once more`() = runBlocking {
        responseQueue.add(503)
        dao.insert(goalRow("g1"))

        assertEquals(SyncEngine.DrainResult.TRANSIENT_FAILURE, engine.drain())
        val head = dao.head()!!
        assertEquals(PendingAction.Status.PENDING, head.status)
        assertEquals(1, head.attemptCount)

        assertEquals(SyncEngine.DrainResult.EMPTY, engine.drain())
        assertNull(dao.head())

        // Both attempts carried the same idempotency key — replay-safe.
        assertEquals(2, received.size)
        val bodies = received.map { it.body.readUtf8() }
        assertTrue(bodies.all { it.contains("\"client_event_id\":\"g1\"") })
    }

    @Test
    fun `network failure is transient and keeps the action`() = runBlocking {
        dao.insert(goalRow("g1"))
        server.shutdown()

        assertEquals(SyncEngine.DrainResult.TRANSIENT_FAILURE, engine.drain())
        assertEquals(PendingAction.Status.PENDING, dao.head()!!.status)
    }

    @Test
    fun `4xx marks head FAILED and pauses the queue`() = runBlocking {
        responseQueue.add(422)
        dao.insert(goalRow("g1"))
        dao.insert(goalRow("g2"))

        assertEquals(SyncEngine.DrainResult.PAUSED, engine.drain())

        val head = dao.head()!!
        assertEquals(PendingAction.Status.FAILED, head.status)
        assertEquals("HTTP 422", head.lastError)
        // Strict ordering: the second action never went out.
        assertEquals(1, received.size)

        // Still paused on a repeat drain — no extra requests.
        assertEquals(SyncEngine.DrainResult.PAUSED, engine.drain())
        assertEquals(1, received.size)
    }

    @Test
    fun `retryFailed un-pauses and the queue flows again`() = runBlocking {
        responseQueue.add(400)
        dao.insert(goalRow("g1"))
        dao.insert(goalRow("g2"))
        assertEquals(SyncEngine.DrainResult.PAUSED, engine.drain())

        engine.retryFailed(dao.head()!!.id)
        assertEquals(SyncEngine.DrainResult.EMPTY, engine.drain())
        assertEquals(3, received.size)
        assertNull(dao.head())
    }

    @Test
    fun `discardFailed drops the bad action and the rest sync`() = runBlocking {
        responseQueue.add(400)
        dao.insert(goalRow("g1"))
        dao.insert(goalRow("g2"))
        assertEquals(SyncEngine.DrainResult.PAUSED, engine.drain())

        engine.discardFailed(dao.head()!!.id)
        assertEquals(SyncEngine.DrainResult.EMPTY, engine.drain())

        assertEquals(2, received.size)
        assertTrue(received.last().body.readUtf8().contains("\"client_event_id\":\"g2\""))
        assertNull(dao.head())
    }

    @Test
    fun `deleting a pending action never hits the network`() = runBlocking {
        dao.insert(goalRow("g1"))
        val row = dao.head()!!

        assertEquals(1, dao.deleteIfNotInFlight(row.id))
        assertEquals(SyncEngine.DrainResult.EMPTY, engine.drain())
        assertEquals(0, received.size)
    }

    @Test
    fun `crash recovery resets IN_FLIGHT rows to PENDING`() = runBlocking {
        dao.insert(goalRow("g1"))
        val row = dao.head()!!
        dao.setStatus(row.id, PendingAction.Status.IN_FLIGHT, 1, null)

        dao.resetInFlight()
        assertEquals(PendingAction.Status.PENDING, dao.head()!!.status)
        assertEquals(SyncEngine.DrainResult.EMPTY, engine.drain())
        assertEquals(1, received.size)
    }

    @Test
    fun `401 is transient and the queue keeps its place`() = runBlocking {
        // SB-779: a 401 means the token needed refreshing, not that this action
        // is invalid. Treating it as terminal marked the head FAILED and paused
        // the whole queue permanently — one auth hiccup silently stopped every
        // subsequent event in a match.
        responseQueue.add(401)
        dao.insert(goalRow("g1"))
        dao.insert(goalRow("g2"))

        assertEquals(SyncEngine.DrainResult.TRANSIENT_FAILURE, engine.drain())

        val head = dao.head()!!
        assertEquals(PendingAction.Status.PENDING, head.status)
        assertEquals("HTTP 401", head.lastError)
        assertEquals(1, head.attemptCount)
        // Strict ordering held: the second action did not jump the queue.
        assertEquals(1, received.size)
    }

    @Test
    fun `a 401 then success drains the whole queue`() = runBlocking {
        // The recovery that was impossible before: once auth is healthy the
        // same action succeeds and everything behind it flows.
        responseQueue.add(401)
        dao.insert(goalRow("g1"))
        dao.insert(goalRow("g2"))

        assertEquals(SyncEngine.DrainResult.TRANSIENT_FAILURE, engine.drain())
        assertEquals(SyncEngine.DrainResult.EMPTY, engine.drain())

        assertNull(dao.head())
        // 1 failed attempt + 2 successful sends.
        assertEquals(3, received.size)
        // The retry reused the idempotency key, so the server can dedupe.
        assertTrue(received[1].body.readUtf8().contains("\"client_event_id\":\"g1\""))
    }

    @Test
    fun `403 stays terminal`() = runBlocking {
        // A permission answer about this action is not a recoverable auth
        // state, so it must still pause for the user to resolve.
        responseQueue.add(403)
        dao.insert(goalRow("g1"))

        assertEquals(SyncEngine.DrainResult.PAUSED, engine.drain())
        assertEquals(PendingAction.Status.FAILED, dao.head()!!.status)
        assertEquals("HTTP 403", dao.head()!!.lastError)
    }

    @Test
    fun `chat message syncs in queue order with its idempotency key`() = runBlocking {
        dao.insert(goalRow("g1"))
        dao.insert(
            PendingAction(
                clientEventId = "m1", matchId = 7,
                actionType = PendingAction.ActionType.MESSAGE,
                payloadJson = json.encodeToString(MessageRequest("What a strike", "m1")),
                createdAt = 2L,
            )
        )

        assertEquals(SyncEngine.DrainResult.EMPTY, engine.drain())

        assertEquals(
            listOf("/api/matches/7/live/goal", "/api/matches/7/live/message"),
            received.map { it.path },
        )
        val body = received[1].body.readUtf8()
        assertTrue(body.contains("\"message\":\"What a strike\""))
        assertTrue(body.contains("\"client_event_id\":\"m1\""))
        assertNull(dao.head())
    }
}
