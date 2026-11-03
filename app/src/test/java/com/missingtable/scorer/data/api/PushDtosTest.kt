package com.missingtable.scorer.data.api

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SB-1464 wire shapes, decoded with the app's own Json settings. */
class PushDtosTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    @Test
    fun `follows decode the full embed`() {
        val resp = json.decodeFromString<TeamFollowsResponse>(
            """
            {"follows": [{"team_id": 7, "created_at": "2026-10-01T00:00:00Z", "team": {
                "id": 7, "name": "IFA U14", "age_group_id": 3,
                "club": {"id": 2, "name": "IFA"},
                "division": {"id": 5, "name": "Northeast", "leagues": {"id": 1, "name": "Homegrown"}}
            }}]}
            """.trimIndent()
        )
        val f = resp.follows.single()
        assertEquals(7, f.teamId)
        assertEquals("IFA U14", f.team?.name)
        assertEquals("IFA", f.team?.club?.name)
        assertEquals("Northeast", f.team?.division?.name)
        assertEquals("Homegrown", f.team?.division?.leagueName)
    }

    @Test
    fun `follows tolerate missing leagues, null embeds and a list-shaped league`() {
        val resp = json.decodeFromString<TeamFollowsResponse>(
            """
            {"follows": [
                {"team_id": 1, "team": {"id": 1, "name": "A", "club": null, "division": {"id": 5, "name": "NE"}}},
                {"team_id": 2, "team": null},
                {"team_id": 3},
                {"team_id": 4, "team": {"name": "D", "division": {"name": "SE", "leagues": [{"id": 1, "name": "Flex"}]}}},
                {"team_id": 5, "team": {"name": "E", "division": {"name": "SE", "leagues": null}}}
            ]}
            """.trimIndent()
        )
        assertEquals(listOf(1, 2, 3, 4, 5), resp.follows.map { it.teamId })
        assertNull(resp.follows[0].team?.division?.leagueName)
        assertNull(resp.follows[0].team?.club)
        assertNull(resp.follows[1].team)
        assertNull(resp.follows[2].team)
        assertEquals("Flex", resp.follows[3].team?.division?.leagueName)
        assertNull(resp.follows[4].team?.division?.leagueName)
    }

    @Test
    fun `empty follows body is an empty list`() {
        assertTrue(json.decodeFromString<TeamFollowsResponse>("{}").follows.isEmpty())
    }

    @Test
    fun `prefs round-trip as a nested map`() {
        val resp = json.decodeFromString<NotificationPrefs>(
            """{"preferences": {"kickoff": true, "goal": true, "halftime": false,
                "fulltime": true, "yellow_card": false, "red_card": true}}"""
        )
        assertEquals(6, resp.preferences.size)
        assertFalse(resp.preferences.getValue("halftime"))
        assertEquals(
            """{"preferences":{"yellow_card":true}}""",
            json.encodeToString(NotificationPrefs(mapOf("yellow_card" to true))),
        )
    }

    @Test
    fun `fcm device response keeps the id and request is snake_case`() {
        val resp = json.decodeFromString<FcmDeviceResponse>(
            """{"id": "0b9a6c1e-7d2f-4c1a-9e0f-1a2b3c4d5e6f", "device_label": "Pixel 8",
                "app_version": "0.2.18", "created_at": "2026-10-31T00:00:00Z", "last_seen_at": null}"""
        )
        assertEquals("0b9a6c1e-7d2f-4c1a-9e0f-1a2b3c4d5e6f", resp.id)
        assertEquals("Pixel 8", resp.deviceLabel)
        assertNull(resp.lastSeenAt)
        assertEquals(
            """{"fid":"f1","app_version":"0.2.18","device_label":"Pixel 8"}""",
            json.encodeToString(FcmDeviceRequest("f1", "0.2.18", "Pixel 8")),
        )
    }

    @Test
    fun `test notification response counts`() {
        val r = json.decodeFromString<TestNotificationResponse>(
            """{"sent": 2, "failed": 0, "expired": 1, "subscriptions": 1, "apns_devices": 0, "fcm_devices": 2}"""
        )
        assertEquals(2, r.sent)
        assertEquals(1, r.expired)
        assertEquals(2, r.fcmDevices)
    }
}
