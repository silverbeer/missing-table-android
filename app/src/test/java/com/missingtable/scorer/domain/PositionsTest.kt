package com.missingtable.scorer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PositionsTest {

    @Test
    fun groupForPosition_mapsCanonicalCodes() {
        assertEquals("GK", Positions.groupForPosition("GK"))
        assertEquals("DEF", Positions.groupForPosition("CB"))
        assertEquals("MID", Positions.groupForPosition("CM"))
        assertEquals("FWD", Positions.groupForPosition("ST"))
    }

    @Test
    fun groupForPosition_remapsLegacyAndHandlesUnknown() {
        // Legacy side-specific codes fold into their canonical group.
        assertEquals("DEF", Positions.groupForPosition("LCB"))
        assertEquals("DEF", Positions.groupForPosition("RCB"))
        assertEquals("MID", Positions.groupForPosition("LCM"))
        assertNull(Positions.groupForPosition("XYZ"))
        assertNull(Positions.groupForPosition(null))
    }

    @Test
    fun parse_remapsLegacyDedupesPreservingOrderAndHandlesNull() {
        assertEquals(emptyList<String>(), Positions.parse(null))
        assertEquals(emptyList<String>(), Positions.parse(emptyList()))
        // LCB -> CB, RCB -> CB dedupes to a single CB; order preserved.
        assertEquals(listOf("CB", "ST"), Positions.parse(listOf("LCB", "RCB", "ST")))
        assertEquals(listOf("ST", "CM"), Positions.parse(listOf("ST", "LCM", "RCM")))
    }

    @Test
    fun primary_isFirstCanonicalEntry() {
        assertEquals("CB", Positions.primary(listOf("LCB", "RB")))
        assertNull(Positions.primary(null))
        assertNull(Positions.primary(emptyList()))
    }

    @Test
    fun fitScore_ranksPrimaryThenSecondaryThenNone() {
        // Primary in group -> 2.
        assertEquals(2, Positions.fitScore(listOf("CB", "RB"), "DEF"))
        // Group only via a secondary position -> 1.
        assertEquals(1, Positions.fitScore(listOf("ST", "RB"), "DEF"))
        // No position in the group -> 0.
        assertEquals(0, Positions.fitScore(listOf("ST"), "DEF"))
        // No positions set, or null group -> 0.
        assertEquals(0, Positions.fitScore(emptyList(), "DEF"))
        assertEquals(0, Positions.fitScore(listOf("CB"), null))
    }

    @Test
    fun slotToGroup_coversEveryFormationSlotCode() {
        val slotCodes = Formations.presets.values
            .flatten()
            .map { it.code }
            .toSet()
        assertTrue("no formation slots found", slotCodes.isNotEmpty())
        for (code in slotCodes) {
            assertTrue(
                "missing SLOT_TO_GROUP entry for $code",
                Positions.SLOT_TO_GROUP.containsKey(code),
            )
        }
    }
}
