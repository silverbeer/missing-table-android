package com.missingtable.scorer.data.api

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/** SB-1464: method, path and body shape for each push endpoint. */
class PushApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: MtApi
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(MtApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueue(code: Int = 200, body: String? = null) {
        server.enqueue(MockResponse().setResponseCode(code).apply { if (body != null) setBody(body) })
    }

    private fun taken(): RecordedRequest = server.takeRequest(5, TimeUnit.SECONDS)!!

    private fun RecordedRequest.jsonBody(): JsonObject = json.parseToJsonElement(body.readUtf8()).jsonObject

    // ── fcm-devices ──────────────────────────────────────────────────────────

    @Test
    fun `registerFcmDevice POSTs fid, app_version, device_label`() = runBlocking {
        enqueue(201, """{"id": "dev-1"}""")
        val resp = api.registerFcmDevice(FcmDeviceRequest("fid-1", "0.2.18", "Pixel 8"))

        val req = taken()
        assertEquals("POST", req.method)
        assertEquals("/api/users/me/fcm-devices", req.path)
        val body = req.jsonBody()
        assertEquals(setOf("fid", "app_version", "device_label"), body.keys)
        assertEquals("fid-1", body.getValue("fid").jsonPrimitive.content)
        assertEquals("dev-1", resp.id)
    }

    @Test
    fun `registerFcmDevice omits null optional fields`() = runBlocking {
        enqueue(201, """{"id": "dev-1"}""")
        api.registerFcmDevice(FcmDeviceRequest("fid-1"))
        assertEquals(setOf("fid"), taken().jsonBody().keys)
    }

    @Test
    fun `fcmDevices GETs the device list`() = runBlocking {
        enqueue(body = """{"devices": [{"id": "a"}, {"id": "b"}]}""")
        val resp = api.fcmDevices()

        val req = taken()
        assertEquals("GET", req.method)
        assertEquals("/api/users/me/fcm-devices", req.path)
        assertEquals(listOf("a", "b"), resp.devices.map { it.id })
    }

    @Test
    fun `deleteFcmDevice DELETEs by device id`() = runBlocking {
        enqueue(204)
        val resp = api.deleteFcmDevice("0b9a6c1e-7d2f")

        val req = taken()
        assertEquals("DELETE", req.method)
        assertEquals("/api/users/me/fcm-devices/0b9a6c1e-7d2f", req.path)
        assertTrue(resp.isSuccessful)
    }

    @Test
    fun `deleteFcmDevice surfaces a 404 as a Response, not a throw`() = runBlocking {
        enqueue(404)
        val resp = api.deleteFcmDevice("gone")
        assertFalse(resp.isSuccessful)
        assertEquals(404, resp.code())
    }

    // ── team-follows ─────────────────────────────────────────────────────────

    @Test
    fun `teamFollows GETs the follows list`() = runBlocking {
        enqueue(body = """{"follows": [{"team_id": 7}]}""")
        val resp = api.teamFollows()

        val req = taken()
        assertEquals("GET", req.method)
        assertEquals("/api/users/me/team-follows", req.path)
        assertEquals(listOf(7), resp.follows.map { it.teamId })
    }

    @Test
    fun `followTeam POSTs team_id only`() = runBlocking {
        enqueue(201, """{"team_id": 7, "following": true}""")
        val resp = api.followTeam(TeamFollowRequest(7))

        val req = taken()
        assertEquals("POST", req.method)
        assertEquals("/api/users/me/team-follows", req.path)
        val body = req.jsonBody()
        assertEquals(setOf("team_id"), body.keys)
        assertEquals(7, body.getValue("team_id").jsonPrimitive.int)
        assertEquals(7, resp.teamId)
        assertTrue(resp.following)
    }

    @Test
    fun `followTeam throws HttpException on a server error`() = runBlocking {
        enqueue(500)
        try {
            api.followTeam(TeamFollowRequest(7))
            fail("expected HttpException")
        } catch (e: HttpException) {
            assertEquals(500, e.code())
        }
    }

    @Test
    fun `unfollowTeam DELETEs by team id`() = runBlocking {
        enqueue(204)
        val resp = api.unfollowTeam(7)

        val req = taken()
        assertEquals("DELETE", req.method)
        assertEquals("/api/users/me/team-follows/7", req.path)
        assertTrue(resp.isSuccessful)
    }

    // ── notification-preferences ─────────────────────────────────────────────

    @Test
    fun `notificationPrefs GETs the nested map`() = runBlocking {
        enqueue(body = """{"preferences": {"goal": true, "red_card": false}}""")
        val resp = api.notificationPrefs()

        val req = taken()
        assertEquals("GET", req.method)
        assertEquals("/api/users/me/notification-preferences", req.path)
        assertEquals(mapOf("goal" to true, "red_card" to false), resp.preferences)
    }

    @Test
    fun `putNotificationPrefs PUTs a partial map under preferences`() = runBlocking {
        enqueue(body = """{"preferences": {"kickoff": true, "yellow_card": true}}""")
        val resp = api.putNotificationPrefs(NotificationPrefs(mapOf("yellow_card" to true)))

        val req = taken()
        assertEquals("PUT", req.method)
        assertEquals("/api/users/me/notification-preferences", req.path)
        val body = req.jsonBody()
        assertEquals(setOf("preferences"), body.keys)
        val prefs = body.getValue("preferences").jsonObject
        assertEquals(setOf("yellow_card"), prefs.keys)
        assertTrue(prefs.getValue("yellow_card").jsonPrimitive.boolean)
        assertEquals(mapOf("kickoff" to true, "yellow_card" to true), resp.preferences)
    }

    // ── notifications/test ───────────────────────────────────────────────────

    @Test
    fun `testNotification POSTs to notifications test`() = runBlocking {
        enqueue(body = """{"sent": 1, "fcm_devices": 1}""")
        val resp = api.testNotification()

        val req = taken()
        assertEquals("POST", req.method)
        assertEquals("/api/users/me/notifications/test", req.path)
        assertEquals(1, resp.sent)
        assertEquals(1, resp.fcmDevices)
    }

    @Test
    fun `testNotification 429 surfaces as HttpException with the code`() = runBlocking {
        enqueue(429)
        try {
            api.testNotification()
            fail("expected HttpException")
        } catch (e: HttpException) {
            assertEquals(429, e.code())
        }
    }
}
