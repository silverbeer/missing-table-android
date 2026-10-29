package com.missingtable.scorer.domain

import com.missingtable.scorer.domain.MatchDetail.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchDetailTest {

    @Test
    fun `a fan gets no scoring action on any match`() {
        listOf("scheduled", "tbd", "completed", "postponed", "cancelled", "forfeit", null).forEach {
            assertEquals("$it", emptyList<Action>(), MatchDetail.actions(it, canManage = false))
        }
    }

    @Test
    fun `a fan can watch a live match`() {
        assertEquals(listOf(Action.WATCH_LIVE), MatchDetail.actions("live", canManage = false))
    }

    @Test
    fun `a manager gets the scoring action for the match's stage`() {
        assertEquals(listOf(Action.LINEUP_AND_KICKOFF), MatchDetail.actions("scheduled", canManage = true))
        assertEquals(listOf(Action.LINEUP_AND_KICKOFF), MatchDetail.actions("tbd", canManage = true))
        assertEquals(listOf(Action.SCORE_LIVE), MatchDetail.actions("live", canManage = true))
        assertEquals(listOf(Action.EDIT_RESULT), MatchDetail.actions("completed", canManage = true))
    }

    @Test
    fun `nothing to do on a match that will not be played`() {
        listOf("postponed", "cancelled", "forfeit").forEach {
            assertEquals("$it", emptyList<Action>(), MatchDetail.actions(it, canManage = true))
        }
    }

    @Test
    fun `the full preview is only for matches not yet kicked off`() {
        assertFalse(MatchDetail.previewHeadToHeadOnly("scheduled"))
        assertFalse(MatchDetail.previewHeadToHeadOnly("tbd"))
        assertTrue(MatchDetail.previewHeadToHeadOnly("live"))
        assertTrue(MatchDetail.previewHeadToHeadOnly("completed"))
    }

    @Test
    fun `timeline lists goals and cards, not chat or clock changes`() {
        assertTrue(MatchDetail.isTimelineEvent("goal"))
        assertTrue(MatchDetail.isTimelineEvent("red_card"))
        assertFalse(MatchDetail.isTimelineEvent("message"))
        assertFalse(MatchDetail.isTimelineEvent("status_change"))
        assertFalse(MatchDetail.isTimelineEvent("substitution"))
    }
}
