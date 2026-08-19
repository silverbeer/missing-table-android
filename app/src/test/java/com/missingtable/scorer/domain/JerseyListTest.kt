package com.missingtable.scorer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JerseyListTest {

    @Test
    fun `reads a plain space separated sheet`() {
        val r = JerseyList.parse("1 4 5 6 8 9 10 11 14 17 22", slots = 11)
        assertEquals(listOf(1, 4, 5, 6, 8, 9, 10, 11, 14, 17, 22), r.numbers)
        assertTrue(r.isUsable)
    }

    @Test
    fun `tolerates how numbers actually get typed`() {
        // Commas, double spaces, a trailing separator, a hash prefix, newlines
        // — all of these happen when copying off a board in a hurry.
        val expected = listOf(1, 4, 5)
        listOf("1,4,5", "1, 4, 5", "1  4   5", "1 4 5 ", "#1 #4 #5", "1\n4\n5", ",1,4,5,")
            .forEach { input ->
                assertEquals("failed on '$input'", expected, JerseyList.parse(input, 11).numbers)
            }
    }

    @Test
    fun `empty input is not an error, just nothing yet`() {
        val r = JerseyList.parse("", slots = 11)
        assertTrue(r.numbers.isEmpty())
        assertNull(r.problem)
        assertFalse(r.isUsable)
    }

    @Test
    fun `a duplicate is named rather than silently dropped`() {
        // Silently deduping would hand back ten players for an eleven-number
        // sheet, and the scorer would not know which one went missing.
        val r = JerseyList.parse("1 4 5 5 8", slots = 11)
        assertEquals("5 appears twice", r.problem)
        assertFalse(r.isUsable)
    }

    @Test
    fun `out of range numbers are rejected with the value`() {
        assertEquals("0 isn't a shirt number (1–99)", JerseyList.parse("1 0 5", 11).problem)
        assertEquals("100 isn't a shirt number (1–99)", JerseyList.parse("100", 11).problem)
    }

    @Test
    fun `too many numbers is reported, not truncated`() {
        // Truncating would quietly discard whoever was typed last.
        val r = JerseyList.parse("1 2 3 4 5 6 7 8 9 10 11 12", slots = 11)
        assertEquals("12 numbers for 11 slots", r.problem)
    }

    @Test
    fun `a partial sheet is usable and reports what is left`() {
        val r = JerseyList.parse("1 4 5", slots = 11)
        assertTrue(r.isUsable)
        assertEquals(8, JerseyList.remaining(r, 11))
    }

    @Test
    fun `a complete sheet needs nothing more`() {
        val r = JerseyList.parse("1 2 3 4 5 6 7 8 9 10 11", slots = 11)
        assertEquals(0, JerseyList.remaining(r, 11))
    }

    @Test
    fun `high numbers are accepted because real squads have them`() {
        // The TSC seed deliberately includes a 30 for exactly this reason.
        val r = JerseyList.parse("1 30 77 99", slots = 11)
        assertTrue(r.isUsable)
        assertEquals(listOf(1, 30, 77, 99), r.numbers)
    }

    @Test
    fun `order is preserved so slot assignment is predictable`() {
        // First number goes to GK; a sheet is read top-down and the mapping
        // must be guessable without looking it up.
        assertEquals(listOf(7, 1, 4), JerseyList.parse("7 1 4", 11).numbers)
    }
}
