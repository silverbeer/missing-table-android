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
    fun `staging adds a swap`() {
        val swaps = OnPitch.stage(emptyList(), xi, "ST", 14)
        assertEquals(listOf(OnPitch.Swap("ST", outId = 9, inId = 14)), swaps)
    }

    @Test
    fun `restaging a slot keeps the original outgoing player`() {
        val first = OnPitch.stage(emptyList(), xi, "ST", 14)
        val second = OnPitch.stage(first, xi, "ST", 15)
        assertEquals(listOf(OnPitch.Swap("ST", outId = 9, inId = 15)), second)
    }

    @Test
    fun `picking the original player cancels the swap`() {
        val staged = OnPitch.stage(emptyList(), xi, "ST", 14)
        assertEquals(emptyList<OnPitch.Swap>(), OnPitch.stage(staged, xi, "ST", 9))
    }

    @Test
    fun `staging an empty slot does nothing`() {
        assertEquals(emptyList<OnPitch.Swap>(), OnPitch.stage(emptyList(), xi, "RW", 14))
    }

    @Test
    fun `withSwaps overlays incoming players`() {
        val swaps = listOf(OnPitch.Swap("ST", 9, 14), OnPitch.Swap("LB", 2, 3))
        assertEquals(mapOf("GK" to 1, "LB" to 3, "ST" to 14), OnPitch.withSwaps(xi, swaps))
    }
}
