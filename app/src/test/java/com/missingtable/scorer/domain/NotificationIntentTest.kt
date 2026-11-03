package com.missingtable.scorer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationIntentTest {

    @Test
    fun `matchId extra wins`() {
        assertEquals(123, NotificationIntent.matchIdFrom(mapOf("matchId" to "123", "url" to "/?matchId=9")))
    }

    @Test
    fun `falls back to the url query when the extra is missing or junk`() {
        assertEquals(9, NotificationIntent.matchIdFrom(mapOf("url" to "/?matchId=9")))
        assertEquals(9, NotificationIntent.matchIdFrom(mapOf("matchId" to "abc", "url" to "/?matchId=9")))
        assertEquals(9, NotificationIntent.matchIdFrom(mapOf("url" to "https://missingtable.com/?tab=live&matchId=9#x")))
    }

    @Test
    fun `nothing usable is null, not a crash`() {
        assertNull(NotificationIntent.matchIdFrom(emptyMap()))
        assertNull(NotificationIntent.matchIdFrom(mapOf("matchId" to null, "url" to null)))
        assertNull(NotificationIntent.matchIdFrom(mapOf("matchId" to "0")))
        assertNull(NotificationIntent.matchIdFrom(mapOf("matchId" to "-4")))
        assertNull(NotificationIntent.matchIdFromUrl("/"))
        assertNull(NotificationIntent.matchIdFromUrl("/?matchIdx=5"))
        assertNull(NotificationIntent.matchIdFromUrl("/?matchId="))
    }

    @Test
    fun `whitespace around the id is tolerated`() {
        assertEquals(42, NotificationIntent.matchIdFrom(mapOf("matchId" to " 42 ")))
    }

    @Test
    fun `fallback tag matches the server's shape`() {
        assertEquals("match-123-goal", NotificationIntent.tagFor(123, "goal"))
        assertEquals("match-123-update", NotificationIntent.tagFor(123, null))
    }

    @Test
    fun `prefs are the six server keys in display order with server defaults`() {
        assertEquals(
            listOf("kickoff", "goal", "halftime", "fulltime", "yellow_card", "red_card"),
            NotificationIntent.PREFS.map { it.key },
        )
        assertEquals(
            listOf(true, true, true, true, false, false),
            NotificationIntent.PREFS.map { it.default },
        )
    }

    @Test
    fun `withDefaults fills gaps and drops unknown keys`() {
        val merged = NotificationIntent.withDefaults(mapOf("goal" to false, "red_card" to true, "corner" to true))
        assertEquals(
            mapOf(
                "kickoff" to true, "goal" to false, "halftime" to true,
                "fulltime" to true, "yellow_card" to false, "red_card" to true,
            ),
            merged,
        )
    }

    @Test
    fun `prefs carry the display labels`() {
        assertEquals(
            listOf("Kickoff", "Goals", "Halftime", "Full time", "Yellow cards", "Red cards"),
            NotificationIntent.PREFS.map { it.label },
        )
    }

    @Test
    fun `withDefaults of an empty map is all server defaults`() {
        assertEquals(
            NotificationIntent.PREFS.associate { it.key to it.default },
            NotificationIntent.withDefaults(emptyMap()),
        )
    }

    @Test
    fun `withDefaults keeps display order`() {
        val reversed = NotificationIntent.PREFS.reversed().associate { it.key to !it.default }
        assertEquals(
            NotificationIntent.PREFS.map { it.key },
            NotificationIntent.withDefaults(reversed).keys.toList(),
        )
    }

    @Test
    fun `withDefaults lets the server override every default`() {
        val flipped = NotificationIntent.PREFS.associate { it.key to !it.default }
        assertEquals(flipped, NotificationIntent.withDefaults(flipped))
    }

    @Test
    fun `server tag wins over every fallback`() {
        assertEquals("srv", NotificationIntent.tagOrFallback("srv", 1, "goal", "msg-1", "Title"))
    }

    @Test
    fun `no server tag falls back to match-id-event`() {
        assertEquals("match-12-goal", NotificationIntent.tagOrFallback(null, 12, "goal", "msg-1", "Title"))
        assertEquals("match-12-update", NotificationIntent.tagOrFallback(null, 12, null, "msg-1", "Title"))
    }

    @Test
    fun `no match falls back to message id, then title`() {
        assertEquals("msg-1", NotificationIntent.tagOrFallback(null, null, "goal", "msg-1", "Title"))
        assertEquals("Title", NotificationIntent.tagOrFallback(null, null, "goal", null, "Title"))
    }

    @Test
    fun `requestCode is the tag hash - stable and shared by same tag`() {
        assertEquals("match-12-goal".hashCode(), NotificationIntent.requestCodeFor("match-12-goal"))
        assertEquals(
            NotificationIntent.requestCodeFor(NotificationIntent.tagFor(12, "goal")),
            NotificationIntent.requestCodeFor("match-12-goal"),
        )
        org.junit.Assert.assertNotEquals(
            NotificationIntent.requestCodeFor("match-12-goal"),
            NotificationIntent.requestCodeFor("match-12-red_card"),
        )
    }
}
