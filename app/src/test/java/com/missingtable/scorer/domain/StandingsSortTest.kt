package com.missingtable.scorer.domain

import com.missingtable.scorer.domain.StandingsSort.Sort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** SB-1651: tap-to-sort on the Table, as the web does it (SB-427). */
class StandingsSortTest {

    private data class Row(val name: String, val gf: Double?)

    // Standings order; GF deliberately disagrees with it.
    private val rows = listOf(
        Row("Alpha", 20.0),
        Row("Bravo", 31.0),
        Row("Charlie", 12.0),
        Row("Delta", 12.0),
        Row("Echo", null),
    )

    private fun names(sort: Sort?) = StandingsSort.apply(rows, sort) { it.gf }.map { it.value.name }

    @Test
    fun `tap cycles desc, asc, standings`() {
        val first = StandingsSort.next(null, "GF")
        assertEquals(Sort("GF", descending = true), first)
        val second = StandingsSort.next(first, "GF")
        assertEquals(Sort("GF", descending = false), second)
        assertNull(StandingsSort.next(second, "GF"))
    }

    @Test
    fun `tapping another column starts it descending`() {
        assertEquals(Sort("PPM", true), StandingsSort.next(Sort("GF", false), "PPM"))
    }

    @Test
    fun `no sort keeps standings order`() {
        assertEquals(listOf("Alpha", "Bravo", "Charlie", "Delta", "Echo"), names(null))
    }

    @Test
    fun `descending, ties keep league order, missing sinks`() {
        assertEquals(listOf("Bravo", "Alpha", "Charlie", "Delta", "Echo"), names(Sort("GF", true)))
    }

    @Test
    fun `ascending, ties keep league order, missing still sinks`() {
        assertEquals(listOf("Charlie", "Delta", "Alpha", "Bravo", "Echo"), names(Sort("GF", false)))
    }

    @Test
    fun `sorted rows keep their league position`() {
        val top = StandingsSort.apply(rows, Sort("GF", true)) { it.gf }.first()
        assertEquals("Bravo", top.value.name)
        assertEquals(1, top.index) // shown as #2
    }
}
