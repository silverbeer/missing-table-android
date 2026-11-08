package com.missingtable.scorer.ui.lineup

import com.missingtable.scorer.data.db.PendingAction
import com.missingtable.scorer.data.api.getLineup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.missingtable.scorer.AppContainer
import com.missingtable.scorer.data.api.BulkRosterPlayer
import com.missingtable.scorer.data.api.BulkRosterRequest
import com.missingtable.scorer.data.api.ClockRequest
import com.missingtable.scorer.data.api.LineupPosition
import com.missingtable.scorer.data.api.LineupSaveRequest
import com.missingtable.scorer.data.api.RosterPlayer
import com.missingtable.scorer.domain.Formations
import com.missingtable.scorer.domain.JerseyList
import com.missingtable.scorer.ui.common.StartMatchDialog
import com.missingtable.scorer.domain.Positions
import com.missingtable.scorer.ui.common.PitchCanvas
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableLongStateOf
import com.missingtable.scorer.data.api.LiveMatchState
import com.missingtable.scorer.data.api.PositionChangeRequest
import com.missingtable.scorer.data.api.SubstitutionRequest
import com.missingtable.scorer.domain.EventStamp
import com.missingtable.scorer.domain.LineupSlots
import com.missingtable.scorer.domain.LiveClock
import com.missingtable.scorer.domain.OnPitch
import com.missingtable.scorer.ui.live.OptimisticLive
import com.missingtable.scorer.ui.live.SubPanel
import com.missingtable.scorer.ui.live.SubTeam
import com.missingtable.scorer.ui.live.pitchChanges
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val FitGreen = Color(0xFF4CAF50)
private val WarnAmber = Color(0xFFFBBF24)

// A jersey field that opens cold costs a tap to focus and another to raise the
// keyboard — eleven times over a lineup, at kickoff. Focus it on open instead.
// Popups and dialogs attach a frame or two after they compose and requestFocus
// throws on a detached node, so the request is retried briefly rather than
// fired once and lost.
@Composable
private fun rememberAutoFocus(): FocusRequester {
    val requester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        repeat(10) {
            if (runCatching { requester.requestFocus() }.isSuccess) {
                keyboard?.show()
                return@LaunchedEffect
            }
            delay(20)
        }
    }
    return requester
}

/** Starting XI edits the kickoff lineup; Now records subs on top of it (SB-1504). */
private enum class LineupTab { STARTING, NOW }

/** Autosave status line (SB-1504): no Save button, so say where things stand. */
private enum class SaveState { SAVED, SAVING, UNSAVED }

internal data class SaveLabel(val text: String, val isError: Boolean)

/**
 * The lineup's save line (SB-1592). Before, "Saved ✓" meant only that the
 * save was queued; a lineup the server never got still read as saved.
 */
internal fun lineupSaveLabel(
    unsaved: Boolean,
    saving: Boolean,
    problem: String?,
    queued: PendingAction?,
): SaveLabel = when {
    unsaved -> SaveLabel(problem ?: "Not saved", isError = true)
    saving -> SaveLabel("Saving…", isError = false)
    queued?.status == PendingAction.Status.FAILED ->
        SaveLabel("Not saved — server rejected it${queued.lastError?.let { " ($it)" } ?: ""}", isError = true)
    queued != null -> SaveLabel("Queued — not on the server yet", isError = false)
    else -> SaveLabel("Saved ✓", isError = false)
}

private const val NUMBERS_NEED_SIGNAL = "New shirt numbers need signal to save"
private const val LOAD_FAILED = "Couldn't load the saved lineup"

/** Rethrow cancellation: runCatching would swallow it and carry on half-done. */
private fun <T> Result<T>.rethrowCancel(): Result<T> =
    onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LineupScreen(
    container: AppContainer,
    matchId: Int,
    homeTeamId: Int,
    homeTeamName: String,
    awayTeamId: Int,
    awayTeamName: String,
    seasonId: Int?,
    // Drives the half-length default when starting from here (SB-676).
    ageGroupName: String? = null,
    // Each side's age group, for creating roster rows from typed shirt
    // numbers — a side playing up fields its own squad (SB-1504).
    ageGroupIds: Map<Int, Int?> = emptyMap(),
    // True once the match is under way — starting again is not on offer,
    // returning to it is (SB-677).
    alreadyStarted: Boolean = false,
    onBack: () -> Unit,
    onStartMatch: () -> Unit,
) {
    var startDialog by remember { mutableStateOf(false) }
    var teamId by remember { mutableStateOf(homeTeamId) }
    val teamName = if (teamId == homeTeamId) homeTeamName else awayTeamName
    var roster by remember { mutableStateOf<List<RosterPlayer>>(emptyList()) }
    var formation by remember { mutableStateOf(Formations.presets.keys.first()) }
    // slot index -> player id (negative id = placeholder jersey number)
    var assignments by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    var selectedSlot by remember { mutableStateOf(0) }
    // Slot whose on-pitch quick-pick menu is open (null = none).
    var menuSlot by remember { mutableStateOf<Int?>(null) }
    // Jersey number typed into the open quick-pick menu (reset each open).
    var menuJersey by remember { mutableStateOf("") }
    var bulkOpen by remember { mutableStateOf(false) }
    var bulkText by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    // Edited since the last queued save. Every edit sets it; autosave clears it.
    var dirty by remember { mutableStateOf(false) }
    var saveState by remember { mutableStateOf(SaveState.SAVED) }
    var saveProblem by remember { mutableStateOf<String?>(null) }
    // Where to go once the user accepts leaving with an unsaved lineup.
    var leaveAnyway by remember { mutableStateOf<(() -> Unit)?>(null) }
    val saveLock = remember { Mutex() }
    // Now tab: changes staged per team, not yet recorded.
    var stagedByTeam by remember { mutableStateOf<Map<Int, List<OnPitch.Change>>>(emptyMap()) }
    fun stagedByTeamNonEmpty() = stagedByTeam.values.any { it.isNotEmpty() }
    // After kickoff the screen opens on Now when there's a lineup to sub
    // against; set on the first load, then the user's choice.
    var tab by remember { mutableStateOf(LineupTab.STARTING) }
    var tabChosen by remember { mutableStateOf(false) }
    // The server's lineup couldn't be read. Autosave is a whole-lineup
    // overwrite, so saving now could replace a real XI with the two players
    // just tapped: hold saves until it loads or the user says start fresh.
    var loadFailed by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }
    // One leave at a time: a second back press must not pop twice.
    var leaving by remember { mutableStateOf(false) }
    // A formation chip tapped over a lineup already set: confirm first.
    var confirmFormation by remember { mutableStateOf<String?>(null) }
    // Leaving the Now tab with subs staged but not recorded.
    var discardStaged by remember { mutableStateOf<(() -> Unit)?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val slots = Formations.presets[formation] ?: emptyList()

    fun nextOpenSlot(from: Int = 0): Int {
        for (i in slots.indices) {
            val idx = (from + i) % slots.size
            if (!assignments.containsKey(idx)) return idx
        }
        return selectedSlot
    }

    LaunchedEffect(matchId, teamId, reloadKey) {
        loading = true
        assignments = emptyMap()
        // Never show (or pick from) the other team's players.
        roster = emptyList()
        dirty = false
        loadFailed = false
        saveState = SaveState.SAVED
        saveProblem = null
        if (seasonId != null) {
            runCatching { container.api.roster(teamId, seasonId, null) }
                .onSuccess { roster = it.roster }
                .onFailure { snackbar.showSnackbar("Couldn't load roster") }
        }
        // A save still in the queue is what the user last entered; the
        // server's copy is older until it syncs (SB-1504). Reading the server
        // first is what made a just-saved lineup look lost.
        val queued = runCatching { container.liveRepo.pendingLineup(matchId, teamId) }.getOrNull()
            ?.let { q -> LineupSlots.load(q.formationName, q.positions.map { it.position to it.playerId }) }
        val loaded = queued ?: runCatching { container.api.getLineup(matchId, teamId) }
            .onFailure { loadFailed = true }
            .getOrNull()
            ?.takeIf { it.positions.isNotEmpty() }
            ?.let { l -> LineupSlots.load(l.formationName, l.positions.map { it.position to it.playerId }) }
        if (loaded != null) {
            formation = loaded.formation
            assignments = loaded.assignments
        }
        if (!tabChosen) {
            tab = if (alreadyStarted && assignments.isNotEmpty()) LineupTab.NOW else LineupTab.STARTING
            tabChosen = true
        }
        selectedSlot = nextOpenSlot()
        loading = false
    }

    // Placeholder players (entered by jersey number, no roster row yet) are
    // held as negative ids: -jerseyNumber. On save they become real roster
    // entries via the bulk endpoint, then the lineup references the new ids.
    suspend fun materializePlaceholders(): Boolean {
        val pendingNumbers = assignments.values.filter { it < 0 }.map { -it }.distinct()
        if (pendingNumbers.isEmpty()) return true
        if (seasonId == null) return false
        val ok = runCatching {
            container.api.bulkCreateRoster(
                teamId,
                BulkRosterRequest(seasonId, pendingNumbers.map { BulkRosterPlayer(it) }, ageGroupIds[teamId]),
            )
            // Refetch: bulk skips numbers that already exist, so the roster is
            // the one source of truth for number -> id
            val fresh = container.api.roster(teamId, seasonId, null).roster
            roster = fresh
            val byNumber = fresh.associateBy { it.jerseyNumber }
            // Read assignments only now: edits made while the network calls
            // were out must survive.
            assignments = assignments.mapValues { (_, id) ->
                if (id < 0) byNumber[-id]?.id ?: id else id
            }
            assignments.values.none { it < 0 }
        }.rethrowCancel().getOrDefault(false)
        return ok
    }

    /**
     * Queue the lineup as it stands (SB-1504). Called by autosave after every
     * edit and before leaving the screen or switching team. Queued, not
     * direct, so it works offline; each save replaces the previous unsent one.
     *
     * Shirt numbers with no roster row need the network to become players.
     * Without it the rest of the lineup still saves, the numbers stay on
     * screen, and the save retries — returns false so leaving can warn.
     */
    suspend fun saveNow(): Boolean = saveLock.withLock {
        if (!dirty) return@withLock true
        if (loadFailed) {
            saveState = SaveState.UNSAVED
            saveProblem = LOAD_FAILED
            return@withLock false
        }
        saveState = SaveState.SAVING
        val numbersOk = materializePlaceholders()
        val savedFormation = formation
        val savedAssignments = assignments
        val positions = LineupSlots.positions(savedFormation, savedAssignments.filterValues { it > 0 })
            .map { (code, id) -> LineupPosition(playerId = id, position = code) }
        val queued = runCatching {
            container.liveRepo.enqueueLineupSave(matchId, teamId, LineupSaveRequest(savedFormation, positions))
        }.rethrowCancel().isSuccess
        val done = queued && numbersOk
        // Edits made while saving keep the lineup dirty for the next pass.
        if (done && formation == savedFormation && assignments == savedAssignments) dirty = false
        saveState = if (done) SaveState.SAVED else SaveState.UNSAVED
        saveProblem = when {
            !queued -> "Couldn't save lineup"
            !numbersOk -> NUMBERS_NEED_SIGNAL
            else -> null
        }
        done
    }

    // Autosave: a short pause after the last edit, so a run of taps queues one
    // save. Launched on the screen scope — an edit restarting this effect must
    // not cancel a save already under way.
    LaunchedEffect(dirty, formation, assignments, loading) {
        if (!dirty || loading) return@LaunchedEffect
        delay(700)
        scope.launch { saveNow() }
    }
    // Typed numbers waiting on signal: keep trying while the screen is open.
    // On the screen scope for the same reason: saving changes saveState,
    // which restarts this effect.
    LaunchedEffect(saveState, loadFailed) {
        while (saveState == SaveState.UNSAVED && !loadFailed) {
            delay(10_000)
            if (dirty) scope.launch { saveNow() }
        }
    }

    /**
     * Save first, then [then]; if it can't all be saved, ask before going.
     * Subs staged on Now but not recorded are asked about first too.
     */
    fun leave(checkStaged: Boolean = true, then: () -> Unit) {
        if (leaving) return
        val proceed = {
            leaving = true
            scope.launch {
                if (saveNow()) {
                    then()
                } else {
                    leaveAnyway = then
                }
                leaving = false
            }
            Unit
        }
        if (checkStaged && stagedByTeamNonEmpty()) discardStaged = proceed else proceed()
    }

    BackHandler { leave(then = onBack) }

    // --- Now tab (SB-1504): live state, so subs replay onto the XI above. ---
    var live by remember { mutableStateOf<LiveMatchState?>(null) }
    val pending by container.liveRepo.pendingForMatch(matchId).collectAsState(initial = emptyList())
    // Minute stamped per team when the first change of a batch is staged —
    // the moment the sub happened, not when RECORD was tapped (SB-652).
    var nowStamps by remember { mutableStateOf<Map<Int, EventStamp>>(emptyMap()) }
    var recording by remember { mutableStateOf(false) }
    var liveFailed by remember { mutableStateOf(false) }
    // Responses can land out of order (poll vs post-sync refresh); keep the
    // newest request's answer only, or a sub can flicker off the pitch.
    val liveSeq = remember { intArrayOf(0, 0) } // [issued, applied]
    var nowTick by remember { mutableLongStateOf(System.currentTimeMillis()) }

    suspend fun refreshLive() {
        val seq = ++liveSeq[0]
        runCatching { container.api.liveState(matchId) }
            .rethrowCancel()
            .onSuccess {
                if (seq > liveSeq[1]) {
                    liveSeq[1] = seq
                    live = it
                    liveFailed = false
                }
            }
            .onFailure { if (live == null) liveFailed = true }
    }
    LaunchedEffect(matchId, alreadyStarted) {
        if (!alreadyStarted) return@LaunchedEffect
        while (true) {
            refreshLive()
            delay(15_000)
        }
    }
    // Queued subs reaching the server: pull fresh truth.
    LaunchedEffect(pending.size) { if (live != null) refreshLive() }
    // The Now tab's minute; before kickoff there is no clock to show.
    LaunchedEffect(alreadyStarted) {
        while (alreadyStarted) {
            delay(1_000)
            nowTick = System.currentTimeMillis()
        }
    }
    val merged = live?.let {
        OptimisticLive.merge(it, pending, container.json, mapOf(teamId to roster))
    }
    // Tabs once the match has kicked off. While live state is still loading
    // they show (Now waits for it); a Lineup… opened before kickoff gets none.
    val showTabs = alreadyStarted && (live == null || merged?.kickoffTime != null)

    fun clockNow(): EventStamp = merged?.let {
        EventStamp.from(
            LiveClock.derive(
                it.kickoffTime, it.halftimeStart, it.secondHalfStart, it.matchEndTime, it.halfDuration,
                java.time.Instant.ofEpochMilli(nowTick),
            )
        )
    } ?: EventStamp.UNKNOWN

    fun record(pitch: Map<String, Int>) {
        if (recording) return
        val net = OnPitch.net(pitch, stagedByTeam[teamId].orEmpty())
        if (net.isEmpty()) return
        val at = nowStamps[teamId]?.takeIf { it.minute != null } ?: clockNow()
        val forTeam = teamId
        recording = true
        scope.launch {
            runCatching {
                // Queue order is replay order: subs first, then the moves
                // that put everyone where they ended up.
                net.forEach { change ->
                    when (change) {
                        is OnPitch.Sub -> container.liveRepo.enqueueSubstitution(
                            matchId,
                            SubstitutionRequest(
                                teamId = forTeam,
                                playerInId = requireNotNull(change.inId),
                                playerOutId = requireNotNull(change.outId),
                                matchMinute = at.minute,
                                extraTime = at.extraTime,
                                clientEventId = UUID.randomUUID().toString(),
                            ),
                        )
                        is OnPitch.Move -> container.liveRepo.enqueuePositionChange(
                            matchId,
                            PositionChangeRequest(
                                teamId = forTeam,
                                playerId = requireNotNull(change.playerId),
                                position = requireNotNull(change.position),
                                matchMinute = at.minute,
                                extraTime = at.extraTime,
                                clientEventId = UUID.randomUUID().toString(),
                            ),
                        )
                    }
                }
            }.rethrowCancel().onSuccess {
                stagedByTeam = stagedByTeam - forTeam
                nowStamps = nowStamps - forTeam
                recording = false
                val subs = net.count { it is OnPitch.Sub }
                val moves = net.size - subs
                val label = listOfNotNull(
                    subs.takeIf { it > 0 }?.let { if (it == 1) "1 sub" else "$it subs" },
                    moves.takeIf { it > 0 }?.let { if (it == 1) "1 position change" else "$it position changes" },
                ).joinToString(", ")
                snackbar.showSnackbar("$label recorded" + (at.label()?.let { " $it" } ?: ""))
            }.onFailure {
                recording = false
                snackbar.showSnackbar("Failed to record subs")
            }
        }
    }

    // SB-280: one-tap "Copy last" — pull this team's most recent saved lineup
    // from another match (checked newest-first) into the builder.
    suspend fun copyLastLineup() {
        val today = java.time.LocalDate.now()
        val candidates = runCatching {
            container.api.matches(
                startDate = today.minusDays(120).toString(),
                endDate = today.plusDays(1).toString(),
            )
        }.getOrDefault(emptyList())
            .filter { it.id != matchId && (it.homeTeamId == teamId || it.awayTeamId == teamId) }
            .sortedByDescending { it.matchDate }
            .take(10)

        for (m in candidates) {
            val lineup = runCatching { container.api.getLineup(m.id, teamId) }.getOrNull() ?: continue
            if (lineup.positions.isEmpty()) continue
            val loaded = LineupSlots.load(lineup.formationName, lineup.positions.map { it.position to it.playerId })
                ?: continue
            formation = loaded.formation
            assignments = loaded.assignments
            dirty = true
            snackbar.showSnackbar("Copied lineup from ${m.matchDate}")
            return
        }
        snackbar.showSnackbar("No previous lineup found")
    }

    // SB-288+: one-tap "Auto-fill" — assign best-fit unassigned players to every
    // OPEN slot (existing assignments kept). Slots fill in formation order
    // (GK->DEF->MID->FWD), each taking the highest-fit remaining player; ties
    // break on jersey number. Non-matching fills still complete the XI but show
    // an amber marker for the user to fix.
    fun autoFill() {
        val pool = roster.filter { it.id !in assignments.values }.toMutableList()
        if (pool.isEmpty()) return
        val next = assignments.toMutableMap()
        slots.forEachIndexed { idx, slot ->
            if (next.containsKey(idx) || pool.isEmpty()) return@forEachIndexed
            val group = Positions.SLOT_TO_GROUP[slot.code]
            val best = pool.maxWithOrNull(
                compareBy<RosterPlayer>(
                    { Positions.fitScore(it.positions, group) },
                    { -(it.jerseyNumber ?: 999) },
                ),
            ) ?: return@forEachIndexed
            next[idx] = best.id
            pool.remove(best)
        }
        assignments = next
        selectedSlot = nextOpenSlot()
        dirty = true
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("$teamName lineup") },
                navigationIcon = {
                    IconButton(onClick = { leave(then = onBack) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (loading) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            return@Scaffold
        }

        val assignedIds = assignments.values.toSet()

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // Team toggle
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = teamId == homeTeamId,
                    onClick = { if (teamId != homeTeamId) leave(checkStaged = false) { teamId = homeTeamId } },
                    label = { Text(homeTeamName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = teamId == awayTeamId,
                    onClick = { if (teamId != awayTeamId) leave(checkStaged = false) { teamId = awayTeamId } },
                    label = { Text(awayTeamName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(6.dp))

            // After kickoff (SB-1504): the kickoff lineup stays editable — it
            // may be unfinished or wrong — and Now is where subs are made.
            if (showTabs) {
                val minute = clockNow().label()
                TabRow(selectedTabIndex = tab.ordinal) {
                    Tab(
                        selected = tab == LineupTab.STARTING,
                        onClick = { tab = LineupTab.STARTING },
                        text = { Text("Starting XI") },
                    )
                    Tab(
                        selected = tab == LineupTab.NOW,
                        onClick = { tab = LineupTab.NOW },
                        text = { Text(listOfNotNull("Now", minute).joinToString(" · ")) },
                    )
                }
                Spacer(Modifier.height(6.dp))
            }

            if (loadFailed) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "$LOAD_FAILED. Changes won't save until it loads, so they can't overwrite it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { reloadKey++ }) { Text("RETRY") }
                            TextButton(onClick = {
                                loadFailed = false
                                saveState = SaveState.SAVED
                                saveProblem = null
                            }) { Text("START FRESH") }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            if (showTabs && tab == LineupTab.NOW && merged == null) {
                // No live state yet: the pitch would be the kickoff XI with
                // no subs applied, and a sub made against it would be wrong.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (liveFailed) {
                        Text(
                            "Couldn't reach the match. Subs need the current pitch.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                        TextButton(onClick = { scope.launch { refreshLive() } }) { Text("RETRY") }
                    } else {
                        CircularProgressIndicator()
                    }
                }
                return@Column
            }

            if (showTabs && tab == LineupTab.NOW) {
                val changes = merged?.let { pitchChanges(it, teamId) }.orEmpty()
                val pitch = LineupSlots.currentPitch(formation, assignments, changes)
                    .takeIf { it.isNotEmpty() }
                val staged = stagedByTeam[teamId].orEmpty()
                val net = pitch?.let { OnPitch.net(it, staged) }.orEmpty()
                val open = slots.size - assignments.size
                if (open > 0 && pitch != null) {
                    Text(
                        "$open starting ${if (open == 1) "slot is" else "slots are"} empty — fill them in Starting XI.",
                        style = MaterialTheme.typography.bodySmall,
                        color = WarnAmber,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                SubPanel(
                    team = SubTeam(teamId, teamName, formation, pitch, roster),
                    staged = staged,
                    onStagedChange = { next ->
                        if (staged.isEmpty() && next.isNotEmpty()) nowStamps = nowStamps + (teamId to clockNow())
                        stagedByTeam = stagedByTeam + (teamId to next)
                    },
                    onSetLineup = { tab = LineupTab.STARTING },
                )
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { stagedByTeam = stagedByTeam - teamId; nowStamps = nowStamps - teamId },
                        enabled = staged.isNotEmpty(),
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                    ) { Text("Clear") }
                    Button(
                        onClick = { pitch?.let { record(it) } },
                        enabled = net.isNotEmpty() && !recording,
                        modifier = Modifier
                            .weight(2f)
                            .height(52.dp),
                    ) {
                        val at = (nowStamps[teamId]?.takeIf { it.minute != null } ?: clockNow()).label()
                        Text(
                            if (net.isEmpty()) "RECORD"
                            else "RECORD ${net.size}" + (at?.let { " at $it" } ?: ""),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { leave(then = onStartMatch) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) { Text("BACK TO MATCH") }
                Spacer(Modifier.height(12.dp))
                return@Column
            }

            if (showTabs) {
                Text(
                    "Changes here rewrite the kickoff lineup. For a sub, use Now.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
            }

            // Formation chips
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Formations.presets.keys.forEach { name ->
                    FilterChip(
                        selected = formation == name,
                        onClick = {
                            if (formation != name) {
                                // Switching clears the pitch, and autosave would
                                // persist that: ask when there's a lineup to lose.
                                if (assignments.isEmpty()) {
                                    formation = name
                                    selectedSlot = 0
                                    dirty = true
                                } else {
                                    confirmFormation = name
                                }
                            }
                        },
                        label = { Text(name) },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))

            // The pitch: tap a position marker to select it, then assign via
            // the grid or the number field below.
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.78f)
                    .clip(MaterialTheme.shapes.medium),
            ) {
                val w = maxWidth
                val h = maxHeight
                PitchCanvas()

                slots.forEachIndexed { idx, slot ->
                    val playerId = assignments[idx]
                    val player = roster.find { it.id == playerId }
                    val selected = idx == selectedSlot
                    // Amber ring when the assigned player is out of position for
                    // this slot's group (players with no positions set aren't flagged).
                    val slotGroup = Positions.SLOT_TO_GROUP[slot.code]
                    val outOfPos = player != null &&
                        Positions.parse(player.positions).isNotEmpty() &&
                        Positions.fitScore(player.positions, slotGroup) == 0
                    val markerSize = 46.dp
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .offset(
                                x = w * slot.x - markerSize / 2,
                                y = h * slot.y - markerSize / 2,
                            ),
                    ) {
                        Surface(
                            onClick = { selectedSlot = idx; menuSlot = idx; menuJersey = "" },
                            shape = CircleShape,
                            color = when {
                                selected -> MaterialTheme.colorScheme.tertiary
                                playerId != null -> MaterialTheme.colorScheme.primary
                                else -> Color(0x66FFFFFF)
                            },
                            border = androidx.compose.foundation.BorderStroke(
                                if (selected || outOfPos) 3.dp else 1.dp,
                                if (outOfPos && !selected) WarnAmber else Color.White,
                            ),
                            modifier = Modifier.size(markerSize),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    when {
                                        player != null -> "${player.jerseyNumber ?: "?"}"
                                        playerId != null && playerId < 0 -> "${-playerId}"
                                        else -> slot.code
                                    },
                                    style = if (playerId != null) {
                                        MaterialTheme.typography.titleMedium
                                    } else {
                                        MaterialTheme.typography.labelSmall
                                    },
                                    fontWeight = FontWeight.Bold,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.onTertiary
                                    } else if (playerId != null) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        Color.White
                                    },
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                        if (playerId != null) {
                            Text(
                                player?.nameOnly ?: slot.code,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        // Quick-pick: tap the marker to pick a player right here
                        // instead of reaching for the grid below. Candidates are
                        // fit-sorted for this slot (matches first, primary ahead).
                        DropdownMenu(
                            expanded = menuSlot == idx,
                            onDismissRequest = { menuSlot = null },
                        ) {
                            // Type a jersey number right here — works even with an
                            // empty roster; unknown numbers become placeholders that
                            // are materialized into roster rows on save.
                            fun assignJersey() {
                                val num = menuJersey.toIntOrNull() ?: return
                                if (num !in 1..99) return
                                val existing = roster.find { it.jerseyNumber == num }
                                assignments = assignments + (idx to (existing?.id ?: -num))
                                menuSlot = null
                                selectedSlot = nextOpenSlot(idx + 1)
                                dirty = true
                            }
                            val jerseyFocus = rememberAutoFocus()
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            ) {
                                OutlinedTextField(
                                    value = menuJersey,
                                    onValueChange = { v ->
                                        menuJersey = v.filter { it.isDigit() }.take(2)
                                    },
                                    label = { Text("Jersey # for ${slot.code}") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Number,
                                        imeAction = ImeAction.Done,
                                    ),
                                    keyboardActions = KeyboardActions(onDone = { assignJersey() }),
                                    modifier = Modifier
                                        .width(140.dp)
                                        .focusRequester(jerseyFocus),
                                )
                                TextButton(
                                    onClick = { assignJersey() },
                                    enabled = menuJersey.isNotBlank(),
                                ) { Text("Add") }
                            }

                            if (playerId != null) {
                                DropdownMenuItem(
                                    text = { Text("Clear ${slot.code}") },
                                    onClick = {
                                        assignments = assignments - idx
                                        menuSlot = null
                                        dirty = true
                                    },
                                )
                            }
                            val takenElsewhere = assignments
                                .filterKeys { it != idx }.values.toSet()
                            val available = roster.filter { it.id !in takenElsewhere }
                            // Only players who play this slot's position group. Fall
                            // back to everyone only when none fit (e.g. a roster with
                            // no positions set) so the menu never dead-ends.
                            val fitting = available.filter {
                                slotGroup != null &&
                                    Positions.fitScore(it.positions, slotGroup) > 0
                            }
                            val candidates = (if (fitting.isNotEmpty()) fitting else available)
                                .sortedWith(
                                    compareByDescending<RosterPlayer> {
                                        Positions.fitScore(it.positions, slotGroup)
                                    }.thenBy { it.jerseyNumber ?: 999 },
                                )
                            if (candidates.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("No players available") },
                                    enabled = false,
                                    onClick = {},
                                )
                            }
                            candidates.forEach { p ->
                                val fits = slotGroup != null &&
                                    Positions.fitScore(p.positions, slotGroup) > 0
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Text(
                                                "#${p.jerseyNumber ?: "?"}",
                                                fontWeight = FontWeight.Bold,
                                                color = if (fits) {
                                                    FitGreen
                                                } else {
                                                    MaterialTheme.colorScheme.onSurface
                                                },
                                            )
                                            p.nameOnly?.let {
                                                Text(
                                                    it,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            val pos = Positions.parse(p.positions)
                                                .joinToString(" ")
                                            if (pos.isNotEmpty()) {
                                                Text(
                                                    pos,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (fits) {
                                                        FitGreen
                                                    } else {
                                                        MaterialTheme.colorScheme.onSurfaceVariant
                                                    },
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        assignments = assignments + (idx to p.id)
                                        menuSlot = null
                                        selectedSlot = nextOpenSlot(idx + 1)
                                        dirty = true
                                    },
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Number entry — works with or without a roster: an existing
            // number assigns that player; an unknown number creates a roster
            // entry when the lineup is saved.
            var numberInput by remember { mutableStateOf("") }
            fun assignNumber() {
                val num = numberInput.toIntOrNull() ?: return
                if (num !in 1..99) return
                val existing = roster.find { it.jerseyNumber == num }
                val id = existing?.id ?: -num
                if (id in assignments.values) {
                    numberInput = ""
                    return
                }
                assignments = assignments + (selectedSlot to id)
                selectedSlot = nextOpenSlot(selectedSlot + 1)
                numberInput = ""
                dirty = true
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = numberInput,
                    onValueChange = { v -> numberInput = v.filter { it.isDigit() }.take(2) },
                    label = { Text("Jersey # for ${slots.getOrNull(selectedSlot)?.code ?: ""}") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { assignNumber() }),
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { assignNumber() },
                    enabled = numberInput.isNotBlank(),
                    modifier = Modifier.height(52.dp),
                ) { Text("ASSIGN") }
            }

            // Roster grid (when a roster exists): tap assigns to the selected
            // marker; tapping an assigned (dark) player unassigns them.
            // SB-288+: players whose position group matches the selected slot are
            // sorted first (primary matches ahead of secondary) and green-ringed.
            if (roster.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                val slotCode = slots.getOrNull(selectedSlot)?.code
                val slotGroup = Positions.SLOT_TO_GROUP[slotCode]
                val gridPlayers = roster.sortedWith(
                    compareByDescending<RosterPlayer> {
                        Positions.fitScore(it.positions, slotGroup)
                    }.thenBy { it.jerseyNumber ?: 999 },
                )
                val groupName = slotGroup?.let { Positions.GROUP_NAMES[it] }
                Text(
                    if (slotCode != null && groupName != null) {
                        "Tap a player for $slotCode · ${groupName}s first"
                    } else if (slotCode != null) {
                        "Tap a player for $slotCode"
                    } else {
                        "Tap a player to assign"
                    },
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.height(4.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp),
                ) {
                    items(gridPlayers, key = { it.id }) { p ->
                        val assigned = p.id in assignedIds
                        val fits = slotGroup != null &&
                            Positions.fitScore(p.positions, slotGroup) > 0
                        Surface(
                            onClick = {
                                dirty = true
                                if (assigned) {
                                    assignments = assignments.filterValues { it != p.id }
                                } else {
                                    assignments = assignments + (selectedSlot to p.id)
                                    selectedSlot = nextOpenSlot(selectedSlot + 1)
                                }
                            },
                            shape = MaterialTheme.shapes.medium,
                            color = if (assigned) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.primaryContainer
                            },
                            border = if (fits && !assigned) {
                                androidx.compose.foundation.BorderStroke(2.dp, FitGreen)
                            } else {
                                null
                            },
                            modifier = Modifier.height(64.dp),
                        ) {
                            val onColor = if (assigned) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            }
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                Text(
                                    "${p.jerseyNumber ?: "?"}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = onColor,
                                )
                                p.nameOnly?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = onColor,
                                    )
                                }
                                val posText = Positions.parse(p.positions).joinToString(" ")
                                if (posText.isNotEmpty()) {
                                    Text(
                                        posText,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = if (fits && !assigned) FitGreen else onColor,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${assignments.size}/${slots.size} assigned",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    // Autosave status (SB-1504) — there is no Save button.
                    // "Saved ✓" only once the server has it (SB-1592): a save
                    // still in the queue is "Queued", a rejected one an error.
                    val queuedSave = pending.lastOrNull {
                        it.actionType == PendingAction.ActionType.LINEUP_SAVE && it.teamId == teamId
                    }
                    val label = lineupSaveLabel(
                        unsaved = saveState == SaveState.UNSAVED,
                        saving = saveState == SaveState.SAVING || dirty,
                        problem = saveProblem,
                        queued = queuedSave,
                    )
                    Text(
                        label.text,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (label.isError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 2,
                    )
                }
                OutlinedButton(
                    onClick = { scope.launch { copyLastLineup() } },
                    enabled = roster.isNotEmpty(),
                ) { Text("Copy last") }
                OutlinedButton(onClick = { bulkOpen = true }) { Text("Numbers") }
                OutlinedButton(
                    onClick = { autoFill() },
                    enabled = roster.isNotEmpty() && assignments.size < slots.size,
                ) { Text("Auto-fill") }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    // Already running: save and go back to it rather than
                    // offering to start it again (SB-677).
                    if (alreadyStarted) leave(then = onStartMatch) else startDialog = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) { Text(if (alreadyStarted) "BACK TO MATCH" else "START MATCH") }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (startDialog) {
        StartMatchDialog(
            ageGroupName = ageGroupName,
            onDismiss = { startDialog = false },
            onConfirm = { dur ->
                startDialog = false
                // Kickoff is stamped at the tap, not after the save. The
                // lineup is queued ahead of the clock — and a half-done one is
                // fine: it stays editable from the match (SB-1504).
                val kickoff = java.time.Instant.now().toString()
                leave {
                    scope.launch {
                        runCatching {
                            container.liveRepo.enqueueClock(
                                matchId,
                                ClockRequest("start_first_half", halfDuration = dur, occurredAt = kickoff),
                            )
                        }.onSuccess { onStartMatch() }
                            .onFailure { snackbar.showSnackbar("Failed to start match") }
                    }
                }
            },
        )
    }

    confirmFormation?.let { name ->
        AlertDialog(
            onDismissRequest = { confirmFormation = null },
            title = { Text("Switch to $name?") },
            text = { Text("This clears the ${assignments.size} players placed in $formation.") },
            confirmButton = {
                TextButton(onClick = { confirmFormation = null }) { Text("Keep $formation") }
            },
            dismissButton = {
                TextButton(onClick = {
                    confirmFormation = null
                    formation = name
                    assignments = emptyMap()
                    selectedSlot = 0
                    dirty = true
                }) { Text("Switch") }
            },
        )
    }

    discardStaged?.let { go ->
        val n = stagedByTeam.values.sumOf { it.size }
        AlertDialog(
            onDismissRequest = { discardStaged = null },
            title = { Text("Subs not recorded") },
            text = { Text("$n staged ${if (n == 1) "change hasn't" else "changes haven't"} been recorded. Leave without them?") },
            confirmButton = {
                TextButton(onClick = { discardStaged = null }) { Text("Stay") }
            },
            dismissButton = {
                TextButton(onClick = {
                    discardStaged = null
                    stagedByTeam = emptyMap()
                    nowStamps = emptyMap()
                    go()
                }) { Text("Discard") }
            },
        )
    }

    leaveAnyway?.let { go ->
        AlertDialog(
            onDismissRequest = { leaveAnyway = null },
            title = { Text("Lineup not fully saved") },
            text = {
                Text(
                    if (saveProblem == NUMBERS_NEED_SIGNAL) {
                        "Shirt numbers with no roster entry need signal to save. The rest of the " +
                            "lineup is saved; those numbers are lost if you leave now."
                    } else {
                        "The lineup couldn't be saved. Leaving now loses your changes."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { leaveAnyway = null }) { Text("Stay") }
            },
            dismissButton = {
                TextButton(onClick = { leaveAnyway = null; go() }) { Text("Leave anyway") }
            },
        )
    }

    if (bulkOpen) {
        // Bulk jersey entry (SB-787). The opposition rarely has a roster, so
        // their sheet is typed at kickoff — one field beats 11 trips through a
        // dropdown on a pitch diagram.
        val parsed = JerseyList.parse(bulkText, slots.size)
        AlertDialog(
            onDismissRequest = { bulkOpen = false },
            title = { Text("Enter shirt numbers") },
            text = {
                Column {
                    Text(
                        "Type them in order — first is ${slots.firstOrNull()?.code ?: "GK"}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val bulkFocus = rememberAutoFocus()
                    OutlinedTextField(
                        value = bulkText,
                        onValueChange = { bulkText = it },
                        placeholder = { Text("1 4 5 6 8 9 10 11 14 17 22") },
                        singleLine = false,
                        isError = parsed.problem != null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .focusRequester(bulkFocus),
                    )
                    // Say what will happen before it happens: which number lands
                    // in which slot, and what is still missing.
                    val preview = parsed.numbers.take(slots.size)
                        .mapIndexed { i, n -> "${slots[i].code} $n" }
                        .joinToString("  ")
                    Text(
                        parsed.problem
                            ?: if (preview.isEmpty()) "Nothing entered yet"
                            else preview + JerseyList.remaining(parsed, slots.size)
                                .takeIf { it > 0 }?.let { "   ($it more)" }.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (parsed.problem != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = parsed.isUsable,
                    onClick = {
                        // Unknown numbers become placeholders (negative id) and
                        // are materialised into roster rows on save — the same
                        // path the per-slot entry already uses.
                        var next = assignments
                        parsed.numbers.take(slots.size).forEachIndexed { i, num ->
                            val existing = roster.find { it.jerseyNumber == num }
                            next = next + (i to (existing?.id ?: -num))
                        }
                        assignments = next
                        dirty = true
                        bulkOpen = false
                        bulkText = ""
                    },
                ) { Text("Fill lineup") }
            },
            dismissButton = { TextButton(onClick = { bulkOpen = false }) { Text("Cancel") } },
        )
    }
}
