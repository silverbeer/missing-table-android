package com.missingtable.scorer.ui.team

import com.missingtable.scorer.data.api.AgeGroupDto
import com.missingtable.scorer.data.api.TeamFollow
import org.junit.Assert.assertEquals
import org.junit.Test

/** SB-1645: Profile and the team screen say which age groups a follow covers. */
class FollowScopeTest {

    @Test
    fun `whole-team follow reads All age groups`() {
        assertEquals("All age groups", TeamFollow(teamId = 1).scopeLabel())
    }

    @Test
    fun `squad follow reads its age group`() {
        assertEquals("U14 only", TeamFollow(teamId = 1, ageGroupId = 4, ageGroup = AgeGroupDto(4, "U14")).scopeLabel())
    }

    @Test
    fun `squad follow without the embed still says it is one age group`() {
        assertEquals("One age group", TeamFollow(teamId = 1, ageGroupId = 4).scopeLabel())
    }

    @Test
    fun `team screen hint names the squad`() {
        assertEquals(
            "Follow to get kickoff, goals and results for this team's U14 matches.",
            followHint(4, "U14"),
        )
        assertEquals(
            "Follow to get kickoff, goals and results for every age group of this team.",
            followHint(null, null),
        )
    }
}
