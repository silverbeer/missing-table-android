package com.missingtable.scorer.data.auth

import com.missingtable.scorer.data.api.MeResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTest {

    @Test
    fun `scoring roles mirror require_match_management_permission`() {
        assertTrue(Session(role = "admin").canScore)
        assertTrue(Session(role = "club_manager").canScore)
        assertTrue(Session(role = "team-manager").canScore)
        assertTrue(Session(role = "team_manager").canScore) // legacy alias
        assertFalse(Session(role = "team-player").canScore)
        assertFalse(Session(role = "team-fan").canScore)
        assertFalse(Session(role = "club_fan").canScore)
    }

    @Test
    fun `unknown role defaults open so the scorer flow works before first me()`() {
        assertTrue(Session(role = null).canScore)
    }

    @Test
    fun `me response parses the nested profile shape`() {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val parsed = json.decodeFromString<MeResponse>(
            """
            {"success": true, "user": {"id": "abc", "email": "x@y.z", "profile": {
                "username": "silver", "role": "team-manager", "team_id": 12,
                "club_id": 3, "display_name": "Silver", "positions": ["ST"],
                "photo_1_url": null, "instagram_handle": "ig"
            }}}
            """.trimIndent()
        )
        val p = parsed.user?.profile!!
        assertEquals("team-manager", p.role)
        assertEquals(12, p.teamId)
        assertEquals(3, p.clubId)
        assertEquals("Silver", p.displayName)
    }
}
