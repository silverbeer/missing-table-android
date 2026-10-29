package com.missingtable.scorer.data.auth

import com.missingtable.scorer.data.api.MeResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionTest {

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
