package com.missingtable.scorer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MotwSlotTest {

    @Test
    fun `only a Pathway-only selection asks for the pathway pool`() {
        assertEquals("pathway", MotwSlot.pool(listOf("Northeast (Pro Player Pathway)")))
    }

    @Test
    fun `no selection or a mixed one leaves the server's main default`() {
        assertNull(MotwSlot.pool(emptyList()))
        assertNull(MotwSlot.pool(listOf("Northeast")))
        assertNull(MotwSlot.pool(listOf("Northeast", "Northeast (Pro Player Pathway)")))
    }
}
