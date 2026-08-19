package com.missingtable.scorer.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The crest falls back to initials whenever there is no logo — which at a
 * pitch is the common case, not the edge case (SB-651).
 */
class TeamCrestTest {

    @Test
    fun `two words give one letter each`() {
        assertEquals("TA", initials("TSC A-Team"))
        assertEquals("NY", initials("New York Red Bulls"))
    }

    @Test
    fun `a single word gives its first two letters`() {
        assertEquals("IF", initials("IFA"))
        assertEquals("PD", initials("PDA"))
    }

    @Test
    fun `hyphens split like spaces`() {
        assertEquals("BT", initials("B-Team"))
    }

    @Test
    fun `always uppercase`() {
        assertEquals("MT", initials("missing table"))
    }

    @Test
    fun `a blank name never renders empty`() {
        // An empty circle reads as a broken image; "?" reads as unknown.
        assertEquals("?", initials(""))
        assertEquals("?", initials("   "))
    }

    @Test
    fun `never longer than two characters`() {
        listOf("TSC A-Team", "IFA", "New York City FC", "X", "", "a b c d").forEach {
            assertTrue("'$it' produced ${initials(it)}", initials(it).length <= 2)
        }
    }

    @Test
    fun `a single letter name is not padded`() {
        assertEquals("X", initials("X"))
    }
}
