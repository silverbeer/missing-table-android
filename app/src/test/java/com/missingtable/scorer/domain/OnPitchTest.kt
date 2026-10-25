package com.missingtable.scorer.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class OnPitchTest {

    private val xi = mapOf("GK" to 1, "LB" to 2, "ST" to 9)

    @Test
    fun `no subs is the starting XI`() {
        assertEquals(xi, OnPitch.current(xi, emptyList()))
    }

    @Test
    fun `a sub takes the outgoing player slot`() {
        val now = OnPitch.current(xi, listOf(OnPitch.Sub(outId = 9, inId = 14)))
        assertEquals(mapOf("GK" to 1, "LB" to 2, "ST" to 14), now)
    }

    @Test
    fun `chained subs on the same slot apply in order`() {
        val now = OnPitch.current(
            xi,
            listOf(OnPitch.Sub(9, 14), OnPitch.Sub(14, 15)),
        )
        assertEquals(15, now["ST"])
    }

    @Test
    fun `a rolling sub can come back on in another slot`() {
        val now = OnPitch.current(
            xi,
            listOf(OnPitch.Sub(9, 14), OnPitch.Sub(2, 9)),
        )
        assertEquals(mapOf("GK" to 1, "LB" to 9, "ST" to 14), now)
    }

    @Test
    fun `a sub for a player not on the pitch is ignored`() {
        assertEquals(xi, OnPitch.current(xi, listOf(OnPitch.Sub(outId = 77, inId = 14))))
        assertEquals(xi, OnPitch.current(xi, listOf(OnPitch.Sub(outId = null, inId = 14))))
    }

    @Test
    fun `a move into an occupied slot swaps the two players`() {
        val now = OnPitch.current(xi, listOf(OnPitch.Move(playerId = 2, position = "ST")))
        assertEquals(mapOf("GK" to 1, "LB" to 9, "ST" to 2), now)
    }

    @Test
    fun `a move for a player not on the pitch is ignored`() {
        assertEquals(xi, OnPitch.current(xi, listOf(OnPitch.Move(77, "ST"))))
        assertEquals(xi, OnPitch.current(xi, listOf(OnPitch.Move(2, null))))
    }

    // The match that prompted SB-1352: Sebby on for Jack (ST), Josh RW -> ST.
    private val sebby = 20
    private val front = mapOf("GK" to 1, "RW" to 7, "ST" to 9) // Josh 7, Jack 9

    @Test
    fun `sub then reshuffle replays to the real XI`() {
        val events = listOf(OnPitch.Sub(outId = 9, inId = sebby), OnPitch.Move(7, "ST"), OnPitch.Move(sebby, "RW"))
        assertEquals(mapOf("GK" to 1, "RW" to sebby, "ST" to 7), OnPitch.current(front, events))
    }

    @Test
    fun `staging a sub and a swap nets to one sub and two moves`() {
        var staged = OnPitch.stageSub(emptyList(), front, "ST", sebby)
        staged = OnPitch.stageSwap(staged, front, "ST", "RW")
        val net = OnPitch.net(front, staged)
        assertEquals(OnPitch.Sub(9, sebby), net.first())
        assertEquals(
            setOf(OnPitch.Move(sebby, "RW"), OnPitch.Move(7, "ST")),
            net.drop(1).toSet(),
        )
        assertEquals(OnPitch.current(front, staged), OnPitch.current(front, net))
    }

    @Test
    fun `swap staged before the sub nets the same`() {
        var staged = OnPitch.stageSwap(emptyList(), front, "ST", "RW") // Jack RW, Josh ST
        staged = OnPitch.stageSub(staged, front, "RW", sebby) // Sebby on for Jack
        val net = OnPitch.net(front, staged)
        assertEquals(mapOf("GK" to 1, "RW" to sebby, "ST" to 7), OnPitch.current(front, net))
        assertEquals(OnPitch.Sub(9, sebby), net.first())
    }

    @Test
    fun `swapping back nets to nothing`() {
        var staged = OnPitch.stageSwap(emptyList(), front, "ST", "RW")
        staged = OnPitch.stageSwap(staged, front, "ST", "RW")
        assertEquals(emptyList<OnPitch.Change>(), OnPitch.net(front, staged))
    }

    @Test
    fun `staging adds a sub for whoever is in the slot`() {
        assertEquals(listOf(OnPitch.Sub(outId = 9, inId = 14)), OnPitch.stageSub(emptyList(), xi, "ST", 14))
    }

    @Test
    fun `restaging a slot keeps the original outgoing player`() {
        val first = OnPitch.stageSub(emptyList(), xi, "ST", 14)
        assertEquals(listOf(OnPitch.Sub(9, 15)), OnPitch.stageSub(first, xi, "ST", 15))
    }

    @Test
    fun `picking the original player cancels the sub`() {
        val staged = OnPitch.stageSub(emptyList(), xi, "ST", 14)
        assertEquals(emptyList<OnPitch.Change>(), OnPitch.stageSub(staged, xi, "ST", 9))
    }

    @Test
    fun `undoing a sub hands its later moves back to the original player`() {
        var staged = OnPitch.stageSub(emptyList(), front, "ST", sebby)
        staged = OnPitch.stageSwap(staged, front, "ST", "RW") // Sebby RW, Josh ST
        staged = OnPitch.unstageSub(staged, front, "RW")
        // Jack stays on, still swapped with Josh.
        assertEquals(mapOf("GK" to 1, "RW" to 9, "ST" to 7), OnPitch.current(front, staged))
    }

    @Test
    fun `staging an empty slot does nothing`() {
        assertEquals(emptyList<OnPitch.Change>(), OnPitch.stageSub(emptyList(), xi, "RW", 14))
    }

    // Roster in jersey order, with two on the bench (14, 15).
    private val roster = listOf(1, 2, 9, 14, 15)

    private fun assists(pitch: Map<String, Int>?, scorer: Int?) =
        OnPitch.assistCandidates(roster, { it }, pitch, scorer)

    @Test
    fun `assist candidates are the players on the pitch minus the scorer, keeper included`() {
        assertEquals(listOf(1, 2), assists(xi, scorer = 9))
    }

    @Test
    fun `assist candidates follow subs`() {
        val now = OnPitch.current(xi, listOf(OnPitch.Sub(outId = 2, inId = 14)))
        assertEquals(listOf(1, 14), assists(now, scorer = 9))
    }

    @Test
    fun `with no lineup every roster player but the scorer can assist`() {
        assertEquals(listOf(1, 2, 14, 15), assists(null, scorer = 9))
    }

    @Test
    fun `a free-text scorer leaves the whole pitch available`() {
        assertEquals(listOf(1, 2, 9), assists(xi, scorer = null))
    }
}
