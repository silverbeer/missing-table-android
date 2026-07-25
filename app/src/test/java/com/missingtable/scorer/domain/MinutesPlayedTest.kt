package com.missingtable.scorer.domain

import com.missingtable.scorer.data.api.MatchEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MinutesPlayedTest {

    private fun sub(minute: Int, inId: Int, outId: Int, teamId: Int = 1) = MatchEvent(
        id = minute, matchId = 7, eventType = "substitution",
        teamId = teamId, playerId = inId, playerOutId = outId, matchMinute = minute,
    )

    private fun card(type: String, playerId: Int, teamId: Int = 1) = MatchEvent(
        id = 900 + playerId, matchId = 7, eventType = type, teamId = teamId, playerId = playerId,
    )

    @Test
    fun `starter never subbed plays the full match`() {
        val result = MinutesPlayed.derive(setOf(1), emptyList(), teamId = 1, matchLength = 70)
        val p = result.single()
        assertEquals(70, p.minutes)
        assertTrue(p.started)
        assertTrue(p.played)
    }

    @Test
    fun `subbed-off starter and sub split the match at the sub minute`() {
        val result = MinutesPlayed.derive(
            starters = setOf(1),
            events = listOf(sub(minute = 50, inId = 2, outId = 1)),
            teamId = 1,
            matchLength = 70,
        )
        val byId = result.associateBy { it.playerId }
        assertEquals(50, byId[1]!!.minutes)
        assertEquals(20, byId[2]!!.minutes)
        assertFalse(byId[2]!!.started)
        assertTrue(byId[2]!!.played)
    }

    @Test
    fun `re-entry accumulates minutes across spells`() {
        val result = MinutesPlayed.derive(
            starters = setOf(1),
            events = listOf(
                sub(minute = 30, inId = 2, outId = 1),
                sub(minute = 60, inId = 1, outId = 2),
            ),
            teamId = 1,
            matchLength = 70,
        )
        val byId = result.associateBy { it.playerId }
        assertEquals(30 + 10, byId[1]!!.minutes) // 0-30 + 60-70
        assertEquals(30, byId[2]!!.minutes) // 30-60
    }

    @Test
    fun `other team's subs and cards are ignored`() {
        val result = MinutesPlayed.derive(
            starters = setOf(1),
            events = listOf(sub(minute = 10, inId = 5, outId = 6, teamId = 2), card("yellow_card", 1, teamId = 2)),
            teamId = 1,
            matchLength = 70,
        )
        assertEquals(1, result.size)
        assertEquals(70, result.single().minutes)
        assertEquals(0, result.single().yellowCards)
    }

    @Test
    fun `cards are counted per player`() {
        val result = MinutesPlayed.derive(
            starters = setOf(1, 2),
            events = listOf(card("yellow_card", 1), card("yellow_card", 1), card("red_card", 2)),
            teamId = 1,
            matchLength = 70,
        )
        val byId = result.associateBy { it.playerId }
        assertEquals(2, byId[1]!!.yellowCards)
        assertEquals(1, byId[2]!!.redCards)
    }
}
