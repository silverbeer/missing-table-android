package com.missingtable.scorer.domain

import com.missingtable.scorer.domain.FilterMemory.Local
import com.missingtable.scorer.domain.FilterMemory.Present
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared v1 filter-memory format (SB-1717). The default fixture is an
 * account with nothing remembered — every account until its owner touches a
 * filter on some device.
 */
class FilterMemoryTest {

    private fun parse(json: String) = FilterMemory.parse(Json.parseToJsonElement(json))

    // --- competition mapping -------------------------------------------------

    @Test fun `never chose is no competition, not All`() {
        assertNull(FilterMemory.competitionOf(savedType = null, chosen = false))
    }

    @Test fun `chose All is all`() {
        assertEquals("all", FilterMemory.competitionOf(savedType = null, chosen = true))
    }

    @Test fun `chip keys pass through`() {
        assertEquals("qualifying", FilterMemory.competitionOf("qualifying", chosen = true))
        assertEquals("5", FilterMemory.competitionOf("5", chosen = true))
    }

    @Test fun `a pre-id label stays on the device`() {
        assertNull(FilterMemory.competitionOf("League", chosen = true))
    }

    @Test fun `all maps back to no saved type`() {
        assertNull(FilterMemory.savedTypeOf("all"))
        assertEquals("qualifying", FilterMemory.savedTypeOf("qualifying"))
    }

    // --- changes: send only what moved -------------------------------------

    @Test fun `nothing changed sends nothing`() {
        assertTrue(FilterMemory.changes(Local(ageGroupId = 3), Local(ageGroupId = 3)).isEmpty())
    }

    @Test fun `only the changed field is sent`() {
        val changes = FilterMemory.changes(
            Local(ageGroupId = 3, conferenceIds = setOf(1)),
            Local(ageGroupId = 4, conferenceIds = setOf(1)),
        )
        assertEquals(mapOf("ageGroupId" to JsonPrimitive(4)), changes)
    }

    @Test fun `choosing All ages is sent as null, not left out`() {
        val changes = FilterMemory.changes(Local(ageGroupId = 3), Local(ageGroupId = null))
        assertEquals(JsonNull, changes["ageGroupId"])
    }

    @Test fun `conferences are sent sorted`() {
        val changes = FilterMemory.changes(Local(), Local(conferenceIds = setOf(9, 1)))
        assertEquals(JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(9))), changes["conferenceIds"])
    }

    @Test fun `a reset to never-chose sends no competition`() {
        val changes = FilterMemory.changes(Local(competition = "5"), Local(competition = null))
        assertFalse(changes.containsKey("competition"))
    }

    @Test fun `body stamps v and savedAt and never seasonId`() {
        val body = FilterMemory.body(mapOf("ageGroupId" to JsonPrimitive(3)), panelOpen = null, savedAt = 42)
        val filters = body["filters"]!!.jsonObject
        assertEquals(JsonPrimitive(1), filters["v"])
        assertEquals(JsonPrimitive(42), filters["savedAt"])
        assertEquals(JsonPrimitive(42), body["saved_at"])
        assertFalse(filters.containsKey("seasonId"))
        assertFalse(body.containsKey("panel_open"))
    }

    @Test fun `panel-only body carries no filters`() {
        val body = FilterMemory.body(emptyMap(), panelOpen = false, savedAt = 1)
        assertFalse(body.containsKey("filters"))
        assertEquals(JsonPrimitive(false), body["panel_open"])
    }

    // --- parse ---------------------------------------------------------------

    @Test fun `nothing remembered is null`() {
        assertNull(FilterMemory.parse(null))
        assertNull(parse("{}"))
    }

    @Test fun `v1 entry`() {
        val r = parse(
            """{"filters":{"v":1,"savedAt":5,"ageGroupId":3,"competition":"qualifying",
               "conferenceIds":[1,7],"programLeagueId":1,"seasonId":3},"panel_open":false,"saved_at":6}""",
        )!!
        assertEquals(5L, r.savedAt)
        assertEquals(Present<Int?>(3), r.ageGroupId)
        assertEquals("qualifying", r.competition)
        assertEquals(setOf(1, 7), r.conferenceIds)
        assertEquals(Present<Int?>(1), r.programLeagueId)
        assertEquals(false, r.panelOpen)
    }

    @Test fun `null age group is All, absent is unknown`() {
        assertEquals(Present<Int?>(null), parse("""{"filters":{"savedAt":1,"ageGroupId":null}}""")!!.ageGroupId)
        assertNull(parse("""{"filters":{"savedAt":1}}""")!!.ageGroupId)
    }

    @Test fun `pre-v1 web names are read when v1 is absent`() {
        val r = parse("""{"filters":{"savedAt":2,"divisionIds":[4],"matchTypeId":5}}""")!!
        assertEquals(setOf(4), r.conferenceIds)
        assertEquals("5", r.competition)
        assertEquals("all", parse("""{"filters":{"savedAt":2,"matchTypeId":null}}""")!!.competition)
    }

    @Test fun `v1 wins over pre-v1 when both are there`() {
        val r = parse(
            """{"filters":{"savedAt":2,"divisionIds":[4],"conferenceIds":[8],"matchTypeId":5,"competition":"all"}}""",
        )!!
        assertEquals(setOf(8), r.conferenceIds)
        assertEquals("all", r.competition)
    }

    @Test fun `cleared on another device`() {
        val r = parse("""{"filters":null,"saved_at":9}""")!!
        assertTrue(r.cleared)
        assertEquals(9L, r.savedAt)
    }

    @Test fun `junk competition is ignored`() {
        assertNull(parse("""{"filters":{"savedAt":1,"competition":"League"}}""")!!.competition)
    }

    // --- adopt / merge -------------------------------------------------------

    @Test fun `newer account copy is adopted, ties stay local`() {
        val remote = FilterMemory.Remote(savedAt = 10)
        assertTrue(FilterMemory.shouldAdopt(localSavedAt = 9, remote = remote))
        assertFalse(FilterMemory.shouldAdopt(localSavedAt = 10, remote = remote))
    }

    @Test fun `merge keeps local fields the account does not have`() {
        val local = Local(ageGroupId = 3, competition = "5", conferenceIds = setOf(1), programLeagueId = 2)
        val merged = FilterMemory.merge(
            local,
            FilterMemory.Remote(savedAt = 1, ageGroupId = Present(4), conferenceIds = setOf(7)),
        )
        assertEquals(Local(ageGroupId = 4, competition = "5", conferenceIds = setOf(7), programLeagueId = 2), merged)
    }

    @Test fun `account All ages overrides a local age group`() {
        val merged = FilterMemory.merge(Local(ageGroupId = 3), FilterMemory.Remote(savedAt = 1, ageGroupId = Present(null)))
        assertNull(merged.ageGroupId)
    }

    @Test fun `cleared resets every filter`() {
        val merged = FilterMemory.merge(Local(ageGroupId = 3, conferenceIds = setOf(1)), FilterMemory.Remote(1, cleared = true))
        assertEquals(Local(), merged)
    }
}
