package com.missingtable.scorer.data.push

import com.missingtable.scorer.data.api.MtApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.Collections

private class FakeDeviceIdStore(@Volatile var id: String? = null) : DeviceIdStore {
    @Volatile var fid: String? = null
    override suspend fun fcmDeviceId() = id
    override suspend fun saveDevice(id: String, fid: String) {
        this.id = id
        this.fid = fid
    }
    override suspend fun clearDevice() {
        id = null
        fid = null
    }
}

/** FID source whose fid() can be held open, to put a register mid-flight. */
private class FakeFidSource(@Volatile var fid: String? = "fid-abc") : FidSource {
    @Volatile var gate: CompletableDeferred<Unit>? = null
    val entered = CompletableDeferred<Unit>()
    @Volatile var deleted = 0

    override suspend fun fid(): String? {
        entered.complete(Unit)
        gate?.await()
        return fid
    }

    override suspend fun deleteInstallation(): Boolean {
        deleted++
        return true
    }
}

/** SB-1464: register stores the server's id; teardown detaches by it, or by FID. */
class PushRegistrarTest {

    private lateinit var server: MockWebServer
    private lateinit var api: MtApi
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val received: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var postCode = 201
    @Volatile private var deleteCode = 204
    @Volatile private var loggedIn = true

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                received.add(request)
                return when (request.method) {
                    "POST" -> MockResponse().setResponseCode(postCode).setBody(
                        """{"id": "dev-1", "device_label": "Pixel", "app_version": "0.2.18",
                            "created_at": "2026-10-31T00:00:00Z", "last_seen_at": "2026-10-31T00:00:00Z"}"""
                    )
                    "DELETE" -> MockResponse().setResponseCode(deleteCode)
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
        scope.cancel()
        server.shutdown()
    }

    private fun registrar(
        store: DeviceIdStore,
        fids: FidSource = FakeFidSource(),
        label: String = "Pixel 8",
    ) = PushRegistrar(
        api = api,
        store = store,
        isLoggedIn = { loggedIn },
        fidSource = fids,
        appVersion = "0.2.18",
        deviceLabel = label,
        scope = scope,
    )

    private fun requests(method: String) = synchronized(received) { received.filter { it.method == method } }

    // ── register ─────────────────────────────────────────────────────────────

    @Test
    fun `register posts the fid and stores the returned id`() = runBlocking {
        val store = FakeDeviceIdStore()
        assertTrue(registrar(store).register())

        val req = received.single()
        assertEquals("POST", req.method)
        assertEquals("/api/users/me/fcm-devices", req.path)
        val body = json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("fid-abc", body.getValue("fid").jsonPrimitive.content)
        assertEquals("0.2.18", body.getValue("app_version").jsonPrimitive.content)
        assertEquals("Pixel 8", body.getValue("device_label").jsonPrimitive.content)
        assertEquals("dev-1", store.id)
        assertEquals("fid-abc", store.fid)
    }

    @Test
    fun `device label is capped at the server's 100 chars`() = runBlocking {
        registrar(FakeDeviceIdStore(), label = "x".repeat(150)).register()
        val body = json.parseToJsonElement(received.single().body.readUtf8()).jsonObject
        assertEquals(100, body.getValue("device_label").jsonPrimitive.content.length)
    }

    @Test
    fun `no session or no fid sends nothing`() = runBlocking {
        val store = FakeDeviceIdStore()
        loggedIn = false
        assertFalse(registrar(store).register())
        loggedIn = true
        assertFalse(registrar(store, FakeFidSource(fid = null)).register())
        assertTrue(received.isEmpty())
        assertNull(store.id)
    }

    @Test
    fun `a server error is swallowed and stores nothing`() = runBlocking {
        postCode = 500
        val store = FakeDeviceIdStore()
        assertFalse(registrar(store).register())
        assertNull(store.id)
    }

    @Test
    fun `the same fid is posted once per session - onRegistered echo is not a second POST`() = runBlocking {
        val r = registrar(FakeDeviceIdStore())
        assertTrue(r.register())
        assertTrue(r.register())
        assertEquals(1, requests("POST").size)
    }

    @Test
    fun `a rotated fid is posted again`() = runBlocking {
        val fids = FakeFidSource()
        val r = registrar(FakeDeviceIdStore(), fids)
        r.register()
        fids.fid = "fid-new"
        r.register()
        assertEquals(2, requests("POST").size)
    }

    @Test
    fun `after teardown the next session posts the fid again`() = runBlocking {
        val r = registrar(FakeDeviceIdStore())
        r.register()
        r.teardown(canCallServer = true)
        assertTrue(r.register())
        assertEquals(2, requests("POST").size)
    }

    // ── teardown: server detach ──────────────────────────────────────────────

    @Test
    fun `teardown deletes the stored id, clears it, then clears the session`() = runBlocking {
        val store = FakeDeviceIdStore(id = "dev-1")
        val fids = FakeFidSource()
        var sessionCleared = false
        registrar(store, fids).teardown(canCallServer = true) {
            // The DELETE has to go out while the token is still there.
            assertEquals(1, requests("DELETE").size)
            sessionCleared = true
        }

        assertEquals("/api/users/me/fcm-devices/dev-1", requests("DELETE").single().path)
        assertNull(store.id)
        assertTrue(sessionCleared)
        assertEquals("a confirmed DELETE needs no FID delete", 0, fids.deleted)
    }

    @Test
    fun `teardown tolerates 404 - already gone`() = runBlocking {
        deleteCode = 404
        val store = FakeDeviceIdStore(id = "dev-1")
        val fids = FakeFidSource()
        registrar(store, fids).teardown(canCallServer = true)
        assertEquals(1, received.size)
        assertNull(store.id)
        assertEquals(0, fids.deleted)
    }

    @Test
    fun `teardown with nothing stored sends nothing and keeps the fid`() = runBlocking {
        val store = FakeDeviceIdStore()
        val fids = FakeFidSource()
        registrar(store, fids).teardown(canCallServer = true)
        assertTrue(received.isEmpty())
        assertEquals(0, fids.deleted)
    }

    // ── teardown: FID fallback (orphaned row) ────────────────────────────────

    @Test
    fun `a failed DELETE falls back to deleting the fid`() = runBlocking {
        deleteCode = 500
        val store = FakeDeviceIdStore(id = "dev-1")
        val fids = FakeFidSource()
        registrar(store, fids).teardown(canCallServer = true)
        assertEquals(1, fids.deleted)
        assertNull(store.id)
    }

    @Test
    fun `a dead server falls back to deleting the fid, and still clears`() = runBlocking {
        server.shutdown()
        val store = FakeDeviceIdStore(id = "dev-1")
        val fids = FakeFidSource()
        registrar(store, fids).teardown(canCallServer = true)
        assertEquals(1, fids.deleted)
        assertNull(store.id)
    }

    @Test
    fun `session expiry sends no DELETE and deletes the fid`() = runBlocking {
        val store = FakeDeviceIdStore(id = "dev-1")
        val fids = FakeFidSource()
        registrar(store, fids).teardown(canCallServer = false)
        assertTrue(requests("DELETE").isEmpty())
        assertEquals(1, fids.deleted)
        assertNull(store.id)
    }

    // ── teardown vs an in-flight register ────────────────────────────────────

    @Test
    fun `teardown is bounded even when a register holds the lock and never returns`() = runBlocking {
        val fids = FakeFidSource().apply { gate = CompletableDeferred() }
        val store = FakeDeviceIdStore(id = "dev-1")
        val r = registrar(store, fids)
        // Called directly, not launched: teardown cannot cancel it.
        val hung = async(Dispatchers.IO) { r.register() }
        withTimeout(5_000) { fids.entered.await() }

        val started = System.nanoTime()
        var sessionCleared = false
        r.teardown(canCallServer = true) { sessionCleared = true }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertTrue("teardown took ${elapsedMs}ms", elapsedMs < PushRegistrar.UNREGISTER_TIMEOUT_MS + 1_500)
        assertTrue(sessionCleared)
        assertNull(store.id)
        assertEquals("lock never acquired, so detach by FID", 1, fids.deleted)

        // Released after teardown: the stale register must not POST.
        fids.gate!!.complete(Unit)
        assertFalse(hung.await())
        assertTrue(requests("POST").isEmpty())
    }

    @Test
    fun `a register queued behind teardown does not re-post under the old token`() = runBlocking {
        val fids = FakeFidSource().apply { gate = CompletableDeferred() }
        val store = FakeDeviceIdStore()
        val r = registrar(store, fids)
        val inFlight = async(Dispatchers.IO) { r.register() }
        withTimeout(5_000) { fids.entered.await() }

        val teardown = async(Dispatchers.IO) { r.teardown(canCallServer = true) }
        // Let teardown start and block on the lock, then let the register resume.
        Thread.sleep(200)
        fids.gate!!.complete(Unit)

        assertFalse(inFlight.await())
        teardown.await()
        assertTrue(requests("POST").isEmpty())
        assertNull(store.id)
    }

    @Test
    fun `teardown cancels a launched register, so it does not wait out the bound`() = runBlocking {
        val fids = FakeFidSource().apply { gate = CompletableDeferred() }
        val r = registrar(FakeDeviceIdStore(), fids)
        r.launchRegister()
        withTimeout(5_000) { fids.entered.await() }

        val started = System.nanoTime()
        r.teardown(canCallServer = true)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertTrue("teardown took ${elapsedMs}ms", elapsedMs < 1_000)
        fids.gate!!.complete(Unit)
        assertTrue(requests("POST").isEmpty())
    }

    @Test
    fun `registers asked for during teardown are ignored`() = runBlocking {
        val r = registrar(FakeDeviceIdStore())
        var direct: Boolean? = null
        r.teardown(canCallServer = true) {
            // e.g. onRegistered/onNewToken landing mid-logout, token still present.
            r.launchRegister()
            direct = r.register()
        }
        Thread.sleep(200)
        assertEquals(false, direct)
        assertTrue(requests("POST").isEmpty())
    }
}
