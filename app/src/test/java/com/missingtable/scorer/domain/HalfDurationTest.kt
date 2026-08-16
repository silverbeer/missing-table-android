package com.missingtable.scorer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HalfDurationTest {

    @Test
    fun `u13 plays 35 minute halves`() {
        assertEquals(35, HalfDuration.defaultFor("U13"))
    }

    @Test
    fun `u14 plays 40 minute halves`() {
        assertEquals(40, HalfDuration.defaultFor("U14"))
    }

    @Test
    fun `u15 plays 45 minute halves`() {
        // SB-645/SB-646: the web app defaulted U15 to 40, which was wrong and
        // silently skewed the clock for the whole match.
        assertEquals(45, HalfDuration.defaultFor("U15"))
    }

    @Test
    fun `older age groups play 45 minute halves`() {
        assertEquals(45, HalfDuration.defaultFor("U16"))
        assertEquals(45, HalfDuration.defaultFor("U17"))
        assertEquals(45, HalfDuration.defaultFor("U19"))
    }

    @Test
    fun `age group matching is case insensitive`() {
        assertEquals(35, HalfDuration.defaultFor("u13"))
        assertEquals(40, HalfDuration.defaultFor("u14"))
        assertEquals(45, HalfDuration.defaultFor("u15"))
    }

    @Test
    fun `hyphenated spellings match too`() {
        assertEquals(35, HalfDuration.defaultFor("U-13"))
        assertEquals(40, HalfDuration.defaultFor("U-14"))
    }

    @Test
    fun `matches inside a longer display name`() {
        // The API returns a display name, not a code — "U14 Boys" is a real value.
        assertEquals(40, HalfDuration.defaultFor("U14 Boys"))
        assertEquals(45, HalfDuration.defaultFor("U15 Boys"))
    }

    @Test
    fun `null or unrecognised age group falls back to 45`() {
        assertEquals(HalfDuration.FALLBACK, HalfDuration.defaultFor(null))
        assertEquals(HalfDuration.FALLBACK, HalfDuration.defaultFor(""))
        assertEquals(HalfDuration.FALLBACK, HalfDuration.defaultFor("Open Age"))
        assertEquals(45, HalfDuration.FALLBACK)
    }

    @Test
    fun `fallback agrees with the backend and the DTO default`() {
        // backend app.py: half_duration = match.get("half_duration") or 45
        // Dtos.kt LiveMatchState: halfDuration: Int = 45
        assertEquals(45, HalfDuration.FALLBACK)
    }

    @Test
    fun `custom values are accepted within the 20 to 60 range`() {
        assertTrue(HalfDuration.isValid(20))
        assertTrue(HalfDuration.isValid(30))
        assertTrue(HalfDuration.isValid(60))
    }

    @Test
    fun `values outside the range and null are rejected`() {
        assertFalse(HalfDuration.isValid(19))
        assertFalse(HalfDuration.isValid(61))
        assertFalse(HalfDuration.isValid(0))
        assertFalse(HalfDuration.isValid(null))
    }

    @Test
    fun `every preset is a valid duration and is labelled`() {
        assertEquals(listOf(35, 40, 45), HalfDuration.PRESETS)
        HalfDuration.PRESETS.forEach { preset ->
            assertTrue("preset $preset out of range", HalfDuration.isValid(preset))
            assertTrue("preset $preset has no label", HalfDuration.presetLabel(preset).isNotEmpty())
        }
    }

    @Test
    fun `preset labels name the age groups that default to them`() {
        assertEquals("U13", HalfDuration.presetLabel(35))
        assertEquals("U14", HalfDuration.presetLabel(40))
        assertEquals("U15+", HalfDuration.presetLabel(45))
    }

    @Test
    fun `every age group default is one of the presets`() {
        // A default the dialog cannot show as a selected chip would look broken.
        listOf("U13", "U14", "U15", "U16", "U17", "U19", null).forEach { ag ->
            assertTrue(
                "default for $ag is not a preset",
                HalfDuration.defaultFor(ag) in HalfDuration.PRESETS,
            )
        }
    }
}
