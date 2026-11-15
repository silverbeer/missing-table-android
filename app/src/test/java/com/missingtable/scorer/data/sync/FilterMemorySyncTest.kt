package com.missingtable.scorer.data.sync

import com.missingtable.scorer.data.api.MeProfile
import com.missingtable.scorer.data.api.MeResponse
import com.missingtable.scorer.data.api.MeUser
import com.missingtable.scorer.data.prefs.FilterPrefs
import com.missingtable.scorer.domain.FilterMemory.Local
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Account sync for the Matches filters (SB-1717), driven by hand: the tests
 * call onLocal/onPanel/flush directly instead of waiting on the collectors
 * and the debounce.
 */
class FilterMemorySyncTest {

    private class FakePrefs(initial: Local = Local()) : FilterPrefs {
        val local = MutableStateFlow(initial)
        val panel = MutableStateFlow<Boolean?>(null)
        var savedAt = 0L
        override val matchesLocal = local
        override val matchesPanelChoice = panel
        override suspend fun matchesSavedAt() = savedAt
        override suspend fun setMatchesSavedAt(at: Long) { savedAt = at }
        override suspend fun applyMatches(local: Local, savedAt: Long) {
            this.local.value = local
            this.savedAt = savedAt
        }
        override suspend fun adoptMatchesPanel(open: Boolean) { panel.value = open }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val sent = mutableListOf<Pair<String, JsonObject>>()
    private var loggedIn = true
    private var clock = 1_000L

    private fun sync(prefs: FakePrefs) = FilterMemorySync(
        prefs = prefs,
        put = { view, body -> sent += view to body },
        isLoggedIn = { loggedIn },
        scope = scope,
        now = { clock },
        debounceMs = 60_000, // never fires on its own; tests flush
    )

    private fun me(entry: String?) = MeResponse(
        user = MeUser(
            profile = MeProfile(
                filterMemory = entry?.let {
                    JsonObject(mapOf("matches" to Json.parseToJsonElement(it)))
                },
            ),
        ),
    )

    @After fun tearDown() = scope.cancel()

    @Test fun `the stored state at startup is not sent`() = runBlocking {
        val s = sync(FakePrefs())
        s.onLocal(Local(ageGroupId = 3))
        s.flush()
        assertTrue(sent.isEmpty())
    }

    @Test fun `a change sends only that field, stamped`() = runBlocking {
        val prefs = FakePrefs()
        val s = sync(prefs)
        s.onLocal(Local(ageGroupId = 3, conferenceIds = setOf(1)))
        clock = 2_000
        s.onLocal(Local(ageGroupId = 4, conferenceIds = setOf(1)))
        s.flush()

        assertEquals(1, sent.size)
        val (view, body) = sent.single()
        assertEquals("matches", view)
        val filters = body["filters"]!!.jsonObject
        assertEquals(JsonPrimitive(4), filters["ageGroupId"])
        assertFalse(filters.containsKey("conferenceIds"))
        assertEquals(2_000L, prefs.savedAt)
    }

    @Test fun `a burst becomes one request with the latest values`() = runBlocking {
        val s = sync(FakePrefs())
        s.onLocal(Local())
        s.onLocal(Local(ageGroupId = 2))
        s.onLocal(Local(ageGroupId = 3, programLeagueId = 1))
        s.flush()
        val filters = sent.single().second["filters"]!!.jsonObject
        assertEquals(JsonPrimitive(3), filters["ageGroupId"])
        assertEquals(JsonPrimitive(1), filters["programLeagueId"])
    }

    @Test fun `signed out sends nothing`() = runBlocking {
        loggedIn = false
        val s = sync(FakePrefs())
        s.onLocal(Local())
        s.onLocal(Local(ageGroupId = 2))
        s.flush()
        assertTrue(sent.isEmpty())
    }

    @Test fun `a failed request is dropped, not thrown`() = runBlocking {
        val s = FilterMemorySync(
            prefs = FakePrefs(),
            put = { _, _ -> error("503") },
            isLoggedIn = { true },
            scope = scope,
            debounceMs = 60_000,
        )
        s.onLocal(Local())
        s.onLocal(Local(ageGroupId = 2))
        s.flush() // must not throw
    }

    @Test fun `a newer account copy is adopted and not sent back`() = runBlocking {
        val prefs = FakePrefs(Local(ageGroupId = 3, programLeagueId = 2)).apply { savedAt = 100 }
        val s = sync(prefs)
        s.onLocal(prefs.local.value)

        s.adopt(me("""{"filters":{"v":1,"savedAt":200,"ageGroupId":5,"competition":"qualifying"}}"""))
        // The write it causes arrives as a local change…
        s.onLocal(prefs.local.value)
        s.flush()

        assertEquals(Local(ageGroupId = 5, competition = "qualifying", programLeagueId = 2), prefs.local.value)
        assertEquals(200L, prefs.savedAt)
        assertTrue("adopted copy echoed back: $sent", sent.isEmpty())
    }

    @Test fun `after adopting, the viewer's next change is sent`() = runBlocking {
        val prefs = FakePrefs().apply { savedAt = 100 }
        val s = sync(prefs)
        s.onLocal(prefs.local.value)
        s.adopt(me("""{"filters":{"savedAt":200,"ageGroupId":5}}"""))
        s.onLocal(prefs.local.value)
        s.onLocal(prefs.local.value.copy(ageGroupId = 6))
        s.flush()
        assertEquals(JsonPrimitive(6), sent.single().second["filters"]!!.jsonObject["ageGroupId"])
    }

    @Test fun `an adopt that changes nothing does not stall later changes`() = runBlocking {
        val prefs = FakePrefs(Local(ageGroupId = 5)).apply { savedAt = 100 }
        val s = sync(prefs)
        s.onLocal(prefs.local.value)
        s.adopt(me("""{"filters":{"savedAt":200,"ageGroupId":5}}"""))
        assertEquals(200L, prefs.savedAt)
        s.onLocal(Local(ageGroupId = 6))
        s.flush()
        assertEquals(1, sent.size)
    }

    @Test fun `an older account copy is ignored`() = runBlocking {
        val prefs = FakePrefs(Local(ageGroupId = 3)).apply { savedAt = 300 }
        val s = sync(prefs)
        s.adopt(me("""{"filters":{"savedAt":200,"ageGroupId":5}}"""))
        assertEquals(3, prefs.local.value.ageGroupId)
    }

    @Test fun `no account copy is the normal state`() = runBlocking {
        val prefs = FakePrefs(Local(ageGroupId = 3))
        sync(prefs).adopt(me(null))
        assertEquals(3, prefs.local.value.ageGroupId)
    }

    @Test fun `cleared elsewhere, newer, resets here`() = runBlocking {
        val prefs = FakePrefs(Local(ageGroupId = 3, conferenceIds = setOf(1))).apply { savedAt = 100 }
        sync(prefs).adopt(me("""{"filters":null,"saved_at":200}"""))
        assertEquals(Local(), prefs.local.value)
    }

    @Test fun `account panel choice fills a gap but never overrides this device`() = runBlocking {
        val fresh = FakePrefs()
        sync(fresh).adopt(me("""{"panel_open":false,"saved_at":1}"""))
        assertEquals(false, fresh.panel.value)

        val chosen = FakePrefs().apply { panel.value = true }
        sync(chosen).adopt(me("""{"panel_open":false,"saved_at":1}"""))
        assertEquals(true, chosen.panel.value)
    }

    @Test fun `toggling the panel sends panel_open without filters`() = runBlocking {
        val s = sync(FakePrefs())
        s.onPanel(null)
        s.onPanel(false)
        s.flush()
        val body = sent.single().second
        assertEquals(JsonPrimitive(false), body["panel_open"])
        assertNull(body["filters"])
    }
}
