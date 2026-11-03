package com.missingtable.scorer.ui.team

import com.missingtable.scorer.data.api.FollowDivision
import com.missingtable.scorer.data.api.FollowTeam
import com.missingtable.scorer.data.api.NamedRef
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** SB-1464: "Club · League · Division" under a team name. */
class TeamSubtitleTest {

    @Test
    fun `all parts joined with a middle dot`() {
        assertEquals("IFA · Homegrown · Northeast", teamSubtitle("IFA U14", "IFA", "Homegrown", "Northeast"))
    }

    @Test
    fun `club dropped when it reads the same as the team`() {
        assertEquals("Homegrown · Northeast", teamSubtitle("IFA", "IFA", "Homegrown", "Northeast"))
    }

    @Test
    fun `club comparison is exact - case differences keep the club`() {
        assertEquals("ifa · Northeast", teamSubtitle("IFA", "ifa", null, "Northeast"))
    }

    @Test
    fun `club kept when team name is null`() {
        assertEquals("IFA", teamSubtitle(null, "IFA", null, null))
    }

    @Test
    fun `missing parts are skipped`() {
        assertEquals("IFA · Northeast", teamSubtitle("IFA U14", "IFA", null, "Northeast"))
        assertEquals("Homegrown", teamSubtitle("IFA U14", null, "Homegrown", null))
    }

    @Test
    fun `blank parts are skipped`() {
        assertEquals("Northeast", teamSubtitle("IFA U14", "  ", "", "Northeast"))
    }

    @Test
    fun `nothing usable is null, not an empty string`() {
        assertNull(teamSubtitle("IFA", null, null, null))
        assertNull(teamSubtitle("IFA", "IFA", null, null))
        assertNull(teamSubtitle(null, "", " ", ""))
    }

    @Test
    fun `FollowTeam subtitle reads club, league and division from the embed`() {
        val team = FollowTeam(
            id = 7,
            name = "IFA U14",
            club = NamedRef(2, "IFA"),
            division = FollowDivision(5, "Northeast", buildJsonObject { put("name", JsonPrimitive("Homegrown")) }),
        )
        assertEquals("IFA · Homegrown · Northeast", team.subtitle())
    }

    @Test
    fun `FollowTeam subtitle with no embeds is null`() {
        assertNull(FollowTeam(id = 7, name = "IFA U14").subtitle())
    }
}
