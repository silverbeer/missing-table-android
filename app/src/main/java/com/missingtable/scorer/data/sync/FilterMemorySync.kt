package com.missingtable.scorer.data.sync

import com.missingtable.scorer.data.api.MeResponse
import com.missingtable.scorer.data.prefs.FilterPrefs
import com.missingtable.scorer.domain.FilterMemory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Keeps the Matches filters in step with the account (SB-1717), so a choice
 * made on the laptop is here on the phone and the other way round.
 *
 * - **Pull**: [adopt] reads /me's `filter_memory` (at sign-in and every return
 *   to the foreground). The account copy wins only when it was saved later.
 * - **Push**: every local change is diffed against the last state this class
 *   saw, and only the changed fields are sent, debounced. The server merges
 *   them key by key, so the fields this app does not own (season, My Club)
 *   are never touched. A failure is dropped — DataStore already has it.
 *
 * Adopting writes DataStore in one edit and records the result first, so the
 * change it causes is recognised and never sent back.
 */
class FilterMemorySync(
    private val prefs: FilterPrefs,
    private val put: suspend (view: String, body: JsonObject) -> Unit,
    private val isLoggedIn: suspend () -> Boolean,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val debounceMs: Long = 1_500,
) {
    private val lock = Mutex()
    private var lastLocal: FilterMemory.Local? = null
    private var lastPanel: Boolean? = null
    private var panelSeen = false
    private var adopting: FilterMemory.Local? = null

    private val pendingFilters = linkedMapOf<String, JsonElement>()
    private var pendingPanel: Boolean? = null
    private var sendJob: Job? = null

    /** Start watching local changes. Call once. */
    fun start() {
        scope.launch { prefs.matchesLocal.collect { onLocal(it) } }
        scope.launch { prefs.matchesPanelChoice.collect { onPanel(it) } }
    }

    internal suspend fun onLocal(state: FilterMemory.Local) {
        val changes = lock.withLock {
            val before = lastLocal
            lastLocal = state
            // While an adopted copy is being written, every emission is that
            // write (or the state it replaces), never the viewer's change.
            adopting?.let { target ->
                if (state == target) adopting = null
                return
            }
            // The first value is what was already stored, not a change.
            if (before == null) return
            FilterMemory.changes(before, state)
        }
        if (changes.isEmpty()) return
        prefs.setMatchesSavedAt(now())
        lock.withLock { pendingFilters.putAll(changes) }
        schedule()
    }

    internal suspend fun onPanel(open: Boolean?) {
        lock.withLock {
            val before = lastPanel
            val first = !panelSeen
            panelSeen = true
            lastPanel = open
            if (first || open == null || open == before) return
            pendingPanel = open
        }
        schedule()
    }

    /** Take the account copy from a /me response when it is newer. */
    suspend fun adopt(me: MeResponse) {
        val entry = me.user?.profile?.filterMemory?.get(FilterMemory.VIEW_MATCHES)
        val remote = FilterMemory.parse(entry) ?: return

        // This device's own panel choice stands; the account's fills a gap.
        val panel = remote.panelOpen
        if (panel != null && prefs.matchesPanelChoice.first() == null) {
            lock.withLock {
                panelSeen = true
                lastPanel = panel
            }
            prefs.adoptMatchesPanel(panel)
        }

        if (!FilterMemory.shouldAdopt(prefs.matchesSavedAt(), remote)) return
        val current = prefs.matchesLocal.first()
        val target = FilterMemory.merge(current, remote)
        if (target == current) {
            // Same filters: DataStore would emit nothing to clear `adopting`
            // with, so only the stamp moves.
            prefs.setMatchesSavedAt(remote.savedAt)
            return
        }
        lock.withLock {
            adopting = target
            // Anything queued here is older than what is being adopted.
            pendingFilters.clear()
        }
        try {
            prefs.applyMatches(target, remote.savedAt)
        } catch (e: Exception) {
            lock.withLock { adopting = null }
            throw e
        }
    }

    private suspend fun schedule() {
        if (!isLoggedIn()) {
            scope.launch { lock.withLock { pendingFilters.clear(); pendingPanel = null } }
            return
        }
        sendJob?.cancel()
        sendJob = scope.launch {
            delay(debounceMs)
            flush()
        }
    }

    /** Send what is queued now. */
    internal suspend fun flush() {
        val body = lock.withLock {
            if (pendingFilters.isEmpty() && pendingPanel == null) return
            FilterMemory.body(pendingFilters.toMap(), pendingPanel, now()).also {
                pendingFilters.clear()
                pendingPanel = null
            }
        }
        if (!isLoggedIn()) return
        runCatching { put(FilterMemory.VIEW_MATCHES, body) }
    }
}
