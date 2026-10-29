package com.missingtable.scorer.ui.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.missingtable.scorer.AppContainer
import com.missingtable.scorer.data.api.CardRequest
import com.missingtable.scorer.data.api.ClockRequest
import com.missingtable.scorer.data.api.GoalRequest
import com.missingtable.scorer.data.api.LineupResponse
import com.missingtable.scorer.data.api.LiveMatchState
import com.missingtable.scorer.data.api.MatchEvent
import com.missingtable.scorer.data.api.MessageRequest
import com.missingtable.scorer.data.api.MatchPatchRequest
import com.missingtable.scorer.data.api.PositionChangeRequest
import com.missingtable.scorer.data.api.RosterPlayer
import com.missingtable.scorer.data.api.SubstitutionRequest
import com.missingtable.scorer.data.auth.Session
import com.missingtable.scorer.data.db.PendingAction
import com.missingtable.scorer.ui.common.StartMatchDialog
import com.missingtable.scorer.ui.common.TeamCrest
import com.missingtable.scorer.ui.theme.StatusColors
import com.missingtable.scorer.domain.ClockStage
import com.missingtable.scorer.domain.EventStamp
import com.missingtable.scorer.domain.HalfDuration
import com.missingtable.scorer.domain.ClockCorrection
import com.missingtable.scorer.domain.HalfTime
import com.missingtable.scorer.domain.LiveClock
import com.missingtable.scorer.domain.MatchClock
import com.missingtable.scorer.domain.OnPitch
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface ActionFlow {
    data object None : ActionFlow
    data class GoalPickScorer(val teamId: Int) : ActionFlow
    data class GoalPickAssist(
        val teamId: Int,
        val scorer: RosterPlayer?,
        val scorerName: String?,
        // SB-1432: the bench too, for a sub nobody logged.
        val everyone: Boolean = false,
    ) : ActionFlow
    data object CardPickTeam : ActionFlow
    data class CardPickType(val teamId: Int) : ActionFlow
    data class CardPickPlayer(val teamId: Int, val cardType: String) : ActionFlow
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(
    container: AppContainer,
    matchId: Int,
    seasonId: Int?,
    ageGroupId: Int?,
    onBack: () -> Unit,
    // Reaching the lineup after kickoff (SB-677) — forgetting it before the
    // whistle must not lock it away for the rest of the match.
    onOpenLineup: (LiveMatchState) -> Unit = {},
    // Fan view (SB-320): scoreboard + clock + timeline only — no scoring
    // controls, no clock menu, no deletes, no queue affordances.
    readOnly: Boolean = false,
) {
    var serverState by remember { mutableStateOf<LiveMatchState?>(null) }
    var rosters by remember { mutableStateOf<Map<Int, List<RosterPlayer>>>(emptyMap()) }
    // Saved starting lineups by team. Never rewritten once the match is under
    // way — subs are events applied on top (SB-1228).
    var lineups by remember { mutableStateOf<Map<Int, LineupResponse>>(emptyMap()) }
    // Sub mode (SB-1228): open flag, the minute SUB was tapped, and the team
    // last subbed so the next open lands on it.
    var subMode by remember { mutableStateOf(false) }
    var subStamp by remember { mutableStateOf(EventStamp.UNKNOWN) }
    var lastSubTeam by remember { mutableStateOf<Int?>(null) }
    var flow by remember { mutableStateOf<ActionFlow>(ActionFlow.None) }
    // Minute captured when an entry flow STARTS (SB-652). Reading the clock at
    // the end of the flow would stamp however long the pickers took onto the
    // event — worst exactly when the match is busiest.
    var stamp by remember { mutableStateOf(EventStamp.UNKNOWN) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }
    var startDialog by remember { mutableStateOf(false) }
    var halfLengthDialog by remember { mutableStateOf(false) }
    // HALFTIME tapped well short of the half length (SB-1448): the tap time,
    // so a confirmed halftime is stamped when it was tapped, not confirmed.
    var earlyHalftimeAt by remember { mutableStateOf<Instant?>(null) }
    // START 2ND HALF tapped moments into the break (SB-1450).
    var earlySecondHalf by remember { mutableStateOf(false) }
    // A clock correction from the menu, awaiting confirmation (SB-1450).
    var correction by remember { mutableStateOf<ClockCorrection?>(null) }
    // Second-half start prompt (SB-1350). Holds what to do once the half has
    // started — the goal/sub/card flow that was blocked — or a no-op when the
    // overdue START 2ND HALF button opened it.
    var secondHalfGate by remember { mutableStateOf<((EventStamp) -> Unit)?>(null) }
    var nowTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val repo = container.liveRepo
    val pending by repo.pendingForMatch(matchId).collectAsState(initial = emptyList())
    val online by container.connectivity.online.collectAsState()
    val session by container.tokenStore.sessionFlow.collectAsState(initial = Session())
    // Chat composer draft (SB-1294). Survives rotation like the rest of the screen.
    var draft by remember { mutableStateOf("") }

    // What the UI renders: last server state with the outstanding queue folded
    // in (scores, pending timeline entries, clock timestamps, hidden deletes).
    val me = OptimisticLive.Author(session.userId, session.displayName ?: session.username)
    val state = serverState?.let { OptimisticLive.merge(it, pending, container.json, rosters, me) }

    suspend fun refresh() {
        runCatching { container.api.liveState(matchId) }
            .onSuccess { serverState = it }
            .onFailure { if (online) snackbar.showSnackbar("Couldn't refresh match") }
    }

    // Initial load + 15s poll
    LaunchedEffect(matchId) {
        refresh()
        while (true) {
            delay(15_000)
            refresh()
        }
    }

    // When the queue drains (actions reached the server), pull fresh truth so
    // pending overlay rows are replaced by their server twins without a flicker
    // window growing to the next poll.
    LaunchedEffect(pending.size) {
        if (serverState != null) refresh()
    }

    // Load rosters once both team ids are known. Fetch unfiltered by age
    // group first; the roster endpoint's age filter can hide players with
    // no age_group_id set, so only use it as a fallback refinement.
    LaunchedEffect(state?.homeTeamId, state?.awayTeamId) {
        val s = state ?: return@LaunchedEffect
        if (readOnly || seasonId == null || rosters.isNotEmpty()) return@LaunchedEffect
        val loaded = mutableMapOf<Int, List<RosterPlayer>>()
        val saved = mutableMapOf<Int, LineupResponse>()
        listOfNotNull(s.homeTeamId, s.awayTeamId).forEach { teamId ->
            runCatching { container.api.roster(teamId, seasonId, null) }
                .onSuccess { loaded[teamId] = it.roster }
            runCatching { container.api.getLineup(matchId, teamId) }
                .onSuccess { lineup ->
                    if (lineup.positions.isNotEmpty()) saved[teamId] = lineup
                }
        }
        rosters = loaded
        lineups = saved
    }

    // Who is on the pitch now, by position: the starting lineup with
    // substitution and position-change events (SB-1352) applied oldest-first.
    // Null when no lineup was saved.
    fun currentPitch(teamId: Int): Map<String, Int>? {
        val lineup = lineups[teamId] ?: return null
        // The full pitch list when the backend sends it; the capped recent
        // list (newest first, chat included) only as a fallback (SB-1370).
        val history = state?.let { it.pitchEvents ?: it.recentEvents.reversed() }
        val changes = history
            ?.filter { it.teamId == teamId }
            ?.mapNotNull {
                when (it.eventType) {
                    "substitution" -> OnPitch.Sub(outId = it.playerOutId, inId = it.playerId)
                    "position_change" -> OnPitch.Move(playerId = it.playerId, position = it.position)
                    else -> null
                }
            }
            .orEmpty()
        return OnPitch.current(lineup.positions.associate { it.position to it.playerId }, changes)
    }

    // 1s clock tick
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            nowTick = System.currentTimeMillis()
        }
    }

    // Mutations land in the local queue (instant, works offline); the
    // optimistic merge shows them immediately and SyncEngine ships them FIFO.
    //
    // With [undo], the snackbar carries an Undo button and stays up longer: a
    // clock step taken by mistake is fixed in one tap, right where the eye is
    // (SB-1450), not by finding the right item in the overflow menu.
    fun act(label: String, undo: (suspend () -> Unit)? = null, block: suspend () -> Unit) {
        scope.launch {
            runCatching { block() }
                .onSuccess {
                    if (undo == null) {
                        snackbar.showSnackbar(label)
                    } else {
                        val result = snackbar.showSnackbar(label, actionLabel = "Undo", duration = SnackbarDuration.Long)
                        if (result == SnackbarResult.ActionPerformed) {
                            runCatching { undo() }
                                .onSuccess { snackbar.showSnackbar("Undone: $label") }
                                .onFailure { snackbar.showSnackbar("Failed to undo: $label") }
                        }
                    }
                }
                .onFailure { snackbar.showSnackbar("Failed: $label") }
        }
    }

    /** Queue a clock correction (SB-1449): the backend re-times events too. */
    suspend fun correct(c: ClockCorrection) {
        repo.enqueueClock(matchId, ClockRequest(c.action))
    }

    /** A forward clock step, undoable from its snackbar when it has an undo. */
    fun clockStep(label: String, action: String, at: Instant) {
        val undo = MatchClock.undoOf(action)
        act(label, undo = undo?.let { c -> { correct(c) } }) {
            repo.enqueueClock(matchId, ClockRequest(action, occurredAt = at.toString()))
        }
    }

    // Minute/extra_time captured at tap time so events synced later (offline
    // scoring) keep the minute they actually happened at.
    fun tapMinute(): LiveClock.ClockText {
        val cur = state ?: return LiveClock.ClockText("—", null, null)
        return LiveClock.derive(
            cur.kickoffTime, cur.halftimeStart, cur.secondHalfStart, cur.matchEndTime, cur.halfDuration
        )
    }

    /**
     * Open an entry flow, stamping the minute now (SB-652). Every event type
     * goes through here so goals, cards and subs all record the minute of the
     * first tap rather than the last.
     */
    fun beginFlow(next: ActionFlow, at: EventStamp = EventStamp.from(tapMinute())) {
        stamp = at
        flow = next
    }

    /** Close an entry flow and drop its stamp so a restart re-reads the clock. */
    fun endFlow() {
        flow = ActionFlow.None
        stamp = EventStamp.UNKNOWN
    }

    /** Open sub mode, stamping the minute now like every other entry flow. */
    fun openSubs(at: EventStamp = EventStamp.from(tapMinute())) {
        subStamp = at
        subMode = true
    }

    /**
     * Run an entry flow, but never at half time (SB-1350): an event entered
     * then means play has restarted and nobody started the clock. Ask for the
     * second half first, then run [start] with a second-half minute.
     */
    fun gated(start: (EventStamp) -> Unit) {
        val cur = state
        val atHalfTime = cur != null && MatchClock.stage(
            cur.kickoffTime, cur.halftimeStart, cur.secondHalfStart, cur.matchEndTime
        ) == ClockStage.HALFTIME
        if (atHalfTime) secondHalfGate = start else start(EventStamp.from(tapMinute()))
    }

    /**
     * Start the second half as of [at] and hand the blocked flow its minute.
     * The stamp is derived from [at] directly: the queued clock action hasn't
     * reached the merged state yet, so [tapMinute] would still read HT.
     */
    fun startSecondHalf(at: Instant, then: (EventStamp) -> Unit) {
        val cur = state ?: return
        val late = Duration.between(at, Instant.now()).toMinutes()
        clockStep(if (late > 0) "2nd half started ${late}m ago" else "2nd half started", "start_second_half", at)
        then(
            EventStamp.from(
                LiveClock.derive(cur.kickoffTime, cur.halftimeStart, at.toString(), null, cur.halfDuration)
            )
        )
    }

    /**
     * "Goal recorded 23'" — say what minute was captured, not just that it was.
     * Takes the stamp explicitly: callers snapshot it before ending the flow,
     * which clears it.
     */
    fun stamped(at: EventStamp, label: String): String =
        at.label()?.let { "$label $it" } ?: label

    /**
     * Post the draft as a chat message (SB-1294). Queued like a goal, so it
     * shows at once and survives a dead signal; no snackbar — the message
     * appearing in the timeline is the confirmation.
     */
    fun sendMessage() {
        val text = draft.trim()
        if (text.isEmpty()) return
        draft = ""
        scope.launch {
            runCatching {
                repo.enqueueMessage(matchId, MessageRequest(text, UUID.randomUUID().toString()))
            }.onFailure {
                draft = text
                snackbar.showSnackbar("Couldn't send message")
            }
        }
    }

    val s = state
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(s?.let { "${it.homeTeamName} v ${it.awayTeamName}" } ?: "Match")
                        // Match id, small and muted (SB-679). Useless to a
                        // normal user; decisive when something looks wrong and
                        // two fixtures share near-identical team names.
                        Text(
                            "#$matchId" + (s?.ageGroupName?.let { " · $it" } ?: "") +
                                (s?.halfDuration?.let { " · ${it}m halves" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (readOnly) return@TopAppBar
                    if (pending.isNotEmpty()) {
                        // A bare count is unactionable at a pitch — the head's
                        // last error and attempt count are what distinguish
                        // "syncing" from "stuck" (SB-779).
                        val head = pending.firstOrNull()
                        val why = head?.lastError?.let { err ->
                            " · $err" + (head.attemptCount.takeIf { it > 1 }?.let { " ×$it" } ?: "")
                        } ?: ""
                        Text(
                            "${pending.size} pending$why",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (head?.lastError != null) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                    // Connectivity dot: green = online, grey = offline (queueing).
                    Box(
                        Modifier
                            .padding(end = 8.dp)
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (online) StatusColors.online else StatusColors.offline),
                    )
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Clock actions")
                    }
                    // Corrections only (SB-653). The normal run of play —
                    // start, halftime, 2nd half, full time — is the primary
                    // button on the scoreboard, so this menu no longer offers
                    // a list of mostly-invalid actions with "Halftime" sitting
                    // next to "Back to 1st half".
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Change half length…") }, onClick = {
                            menuOpen = false
                            halfLengthDialog = true
                        })
                        DropdownMenuItem(text = { Text("Lineup…") }, onClick = {
                            menuOpen = false
                            // After kickoff the saved lineup is the starting XI
                            // and must not be overwritten: changes from here on
                            // are subs (SB-1228).
                            val started = s != null && MatchClock.stage(
                                s.kickoffTime, s.halftimeStart, s.secondHalfStart, s.matchEndTime
                            ) != ClockStage.NOT_STARTED
                            if (started) gated { openSubs(it) } else s?.let(onOpenLineup)
                        })
                        // Only the corrections valid right now (SB-1450): from
                        // the 2nd half, both half time and the 1st half.
                        val menuStage = s?.let {
                            MatchClock.stage(it.kickoffTime, it.halftimeStart, it.secondHalfStart, it.matchEndTime)
                        }
                        menuStage?.let(MatchClock::corrections)?.forEach { c ->
                            DropdownMenuItem(text = { Text("${c.label}…") }, onClick = {
                                menuOpen = false
                                correction = c
                            })
                        }
                        if (menuStage == ClockStage.ENDED) {
                            DropdownMenuItem(text = { Text("Reopen match") }, onClick = {
                                menuOpen = false
                                act("Match reopened") { repo.enqueueReopen(matchId) }
                            })
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (s == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            return@Scaffold
        }

        val clock = remember(s, nowTick) {
            LiveClock.derive(s.kickoffTime, s.halftimeStart, s.secondHalfStart, s.matchEndTime, s.halfDuration)
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                // Keep the chat composer above the keyboard (edge-to-edge).
                .imePadding()
                .padding(horizontal = 12.dp),
        ) {
            // A rejected (4xx) action pauses the whole queue — strict FIFO —
            // so it must be resolved before anything else syncs.
            // Stalled on transient errors: no FAILED row, so no banner, and the
            // engine may be deep in a 60s backoff with nothing to wake it
            // (SB-779). Give the user a way to force a drain.
            val stalled = pending.firstOrNull()?.takeIf {
                it.status != PendingAction.Status.FAILED && it.lastError != null
            }
            if (!readOnly && stalled != null) {
                Card(
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(
                            "Not synced yet: ${stalled.actionType} — ${stalled.lastError}" +
                                " (${stalled.attemptCount} attempts)",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(onClick = { container.syncEngine.kick() }) { Text("Retry now") }
                    }
                }
            }

            val failedAction = pending.firstOrNull { it.status == PendingAction.Status.FAILED }
            if (!readOnly && failedAction != null) {
                Card(
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(
                            "Sync blocked: ${failedAction.actionType} rejected" +
                                (failedAction.lastError?.let { " ($it)" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Row {
                            TextButton(onClick = {
                                scope.launch { repo.retryFailed(failedAction.id) }
                            }) { Text("Retry") }
                            TextButton(onClick = {
                                scope.launch { repo.discardFailed(failedAction.id) }
                            }) { Text("Discard") }
                        }
                    }
                }
            }

            // Scoreboard
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    TeamCrest(s.homeTeamLogo, s.homeTeamName, size = 32.dp)
                    Text(
                        s.homeTeamName,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${s.homeScore ?: 0} – ${s.awayScore ?: 0}",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(clock.display, style = MaterialTheme.typography.titleMedium)
                }
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    TeamCrest(s.awayTeamLogo, s.awayTeamName, size = 32.dp)
                    Text(
                        s.awayTeamName,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            // The next clock action, on the scoreboard rather than two taps
            // deep in the overflow (SB-653). One valid action at a time; the
            // corrections (back to 1st half, reopen) stay in the menu.
            if (!readOnly) {
                val stage = MatchClock.stage(
                    s.kickoffTime, s.halftimeStart, s.secondHalfStart, s.matchEndTime
                )
                // Half-time countdown (SB-1350): the interval is a fixed
                // length, so say when it's up — and shout once it's overdue.
                val breakLeft = if (stage == ClockStage.HALFTIME) {
                    HalfTime.remainingSeconds(s.halftimeStart, Instant.ofEpochMilli(nowTick))
                } else {
                    null
                }
                val overdue = breakLeft != null && breakLeft < 0
                if (breakLeft != null) {
                    Text(
                        if (overdue) {
                            "2nd half overdue · ${HalfTime.countdown(breakLeft)}"
                        } else {
                            "Half time · ${HalfTime.countdown(breakLeft)} left"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (overdue) FontWeight.Bold else FontWeight.Normal,
                        color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                    )
                }
                if (stage.hasPrimaryAction) {
                    Button(
                        onClick = {
                            // Late: it probably kicked off a while ago — offer
                            // to backdate rather than start it now (SB-1350).
                            when {
                                overdue -> secondHalfGate = {}
                                // Starting needs the half-length dialog (SB-645).
                                stage == ClockStage.NOT_STARTED -> startDialog = true
                                stage == ClockStage.SECOND_HALF -> confirmEnd = true
                                // A stray tap 14' in sent #10106 to half time (SB-1448).
                                stage == ClockStage.FIRST_HALF &&
                                    HalfTime.earlyHalftimeMinute(s.kickoffTime, s.halfDuration) != null ->
                                    earlyHalftimeAt = Instant.now()
                                // ...and to the second half 34s later (SB-1450).
                                stage == ClockStage.HALFTIME &&
                                    HalfTime.earlySecondHalfSeconds(s.halftimeStart) != null ->
                                    earlySecondHalf = true
                                else -> MatchClock.primaryAction(stage)?.let { action ->
                                    clockStep(stage.label, action, Instant.now())
                                }
                            }
                        },
                        colors = if (overdue) {
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            )
                        } else {
                            ButtonDefaults.buttonColors()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                    ) { Text(stage.label) }
                    Spacer(Modifier.height(8.dp))
                }
            }

            // Goal buttons (scorer only)
            if (!readOnly) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GoalButton(s.homeTeamName, Modifier.weight(1f)) {
                        s.homeTeamId?.let { id -> gated { beginFlow(ActionFlow.GoalPickScorer(id), it) } }
                    }
                    GoalButton(s.awayTeamName, Modifier.weight(1f)) {
                        s.awayTeamId?.let { id -> gated { beginFlow(ActionFlow.GoalPickScorer(id), it) } }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { gated { openSubs(it) } },
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                    ) { Text("SUB") }
                    OutlinedButton(
                        onClick = { gated { beginFlow(ActionFlow.CardPickTeam, it) } },
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                    ) { Text("CARD") }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("Timeline", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            if (s.recentEvents.isEmpty()) {
                Text(
                    "No events yet — kick off and start scoring.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            LazyColumn(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(s.recentEvents, key = { it.id }) { e ->
                    val isPending = OptimisticLive.isPending(e)
                    if (e.eventType == "message") {
                        ChatRow(
                            event = e,
                            isMine = e.createdBy != null && e.createdBy == session.userId,
                            isPending = isPending,
                            onDelete = if (readOnly) null else {
                                {
                                    act("Message deleted") {
                                        if (isPending) {
                                            repo.deletePendingRow(OptimisticLive.pendingRowId(e))
                                        } else {
                                            repo.enqueueDeleteEvent(matchId, e.id)
                                        }
                                    }
                                }
                            },
                        )
                        return@items
                    }
                    Card {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                e.matchMinute?.let { m ->
                                    e.extraTime?.takeIf { it > 0 }?.let { "$m+$it'" } ?: "$m'"
                                } ?: "",
                                modifier = Modifier.padding(end = 8.dp),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(e.message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            if (isPending) {
                                Icon(
                                    Icons.Filled.CloudUpload,
                                    contentDescription = "Waiting to sync",
                                    modifier = Modifier
                                        .padding(end = 4.dp)
                                        .size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (!readOnly && e.eventType != "status_change") {
                                IconButton(onClick = {
                                    if (isPending) {
                                        // Never synced — just drop the queued row.
                                        act("Event deleted") {
                                            repo.deletePendingRow(OptimisticLive.pendingRowId(e))
                                        }
                                    } else {
                                        act("Event deleted") { repo.enqueueDeleteEvent(matchId, e.id) }
                                    }
                                }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "Delete event",
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            ChatComposer(
                draft = draft,
                onDraftChange = { draft = it.take(MessageRequest.MAX_LENGTH) },
                onSend = ::sendMessage,
            )
        }
    }

    if (startDialog) {
        // Shared with the Lineup screen (SB-676) so both start paths offer the
        // same presets, the same age-group default and the same override.
        StartMatchDialog(
            ageGroupName = s?.ageGroupName,
            onDismiss = { startDialog = false },
            onConfirm = { dur ->
                startDialog = false
                act("Match started (${dur}m halves)") {
                    repo.enqueueClock(
                        matchId,
                        ClockRequest("start_first_half", dur, Instant.now().toString()),
                    )
                }
            },
        )
    }

    if (halfLengthDialog) {
        // Correcting a wrong kickoff choice (SB-678). Online-only: this PATCHes
        // rather than queueing, because a correction is not time-critical the
        // way a goal is, and the clock is derived so it takes effect at once.
        StartMatchDialog(
            ageGroupName = s?.ageGroupName,
            initial = s?.halfDuration,
            title = "Change half length",
            confirmLabel = "Save",
            onDismiss = { halfLengthDialog = false },
            onConfirm = { dur ->
                halfLengthDialog = false
                scope.launch {
                    runCatching { container.api.patchMatch(matchId, MatchPatchRequest(halfDuration = dur)) }
                        .onSuccess {
                            refresh()
                            snackbar.showSnackbar("Half length now ${dur}m")
                        }
                        .onFailure { snackbar.showSnackbar("Couldn't change half length — needs a connection") }
                }
            },
        )
    }

    val gate = secondHalfGate
    if (gate != null && s != null) {
        SecondHalfDialog(
            expectedStart = HalfTime.expectedSecondHalfStart(s.halftimeStart, Instant.ofEpochMilli(nowTick)),
            earlyBreakSeconds = HalfTime.earlySecondHalfSeconds(s.halftimeStart, Instant.ofEpochMilli(nowTick)),
            onStart = { at ->
                secondHalfGate = null
                startSecondHalf(at, gate)
            },
            onDismiss = { secondHalfGate = null },
        )
    }

    val earlyAt = earlyHalftimeAt
    if (earlyAt != null && s != null) {
        val played = HalfTime.earlyHalftimeMinute(s.kickoffTime, s.halfDuration, earlyAt) ?: 0
        AlertDialog(
            onDismissRequest = { earlyHalftimeAt = null },
            title = { Text("Go to half time?") },
            text = {
                Text("Only $played' played of a ${s.halfDuration}' half. Are you sure you want to go to half time?")
            },
            confirmButton = {
                TextButton(onClick = {
                    earlyHalftimeAt = null
                    clockStep(ClockStage.FIRST_HALF.label, "start_halftime", earlyAt)
                }) { Text("Half time") }
            },
            dismissButton = {
                // The safe choice gets the emphasis: a stray tap is the likely case.
                Button(onClick = { earlyHalftimeAt = null }) { Text("Keep playing") }
            },
        )
    }

    if (earlySecondHalf && s != null) {
        val into = HalfTime.earlySecondHalfSeconds(s.halftimeStart, Instant.ofEpochMilli(nowTick)) ?: 0
        AlertDialog(
            onDismissRequest = { earlySecondHalf = false },
            title = { Text("Start the 2nd half?") },
            text = {
                Text(
                    "Half time only began ${HalfTime.countdown(into)} ago. " +
                        "Are you sure the 2nd half has kicked off?"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    earlySecondHalf = false
                    startSecondHalf(Instant.now()) {}
                }) { Text("Start 2nd half") }
            },
            dismissButton = {
                Button(onClick = { earlySecondHalf = false }) { Text("Keep half time") }
            },
        )
    }

    val pendingCorrection = correction
    if (pendingCorrection != null && s != null) {
        // Say what the clock will read, so the fix can be checked before it's made.
        val after = when (pendingCorrection) {
            ClockCorrection.BACK_TO_FIRST_HALF -> {
                val clock = LiveClock.derive(s.kickoffTime, null, null, null, s.halfDuration, Instant.ofEpochMilli(nowTick))
                "The clock goes back to the 1st half and reads ${clock.display}, the time since kickoff."
            }
            ClockCorrection.BACK_TO_HALF_TIME -> {
                val left = HalfTime.remainingSeconds(s.halftimeStart, Instant.ofEpochMilli(nowTick))
                "The 2nd half is undone and the clock goes back to half time" +
                    (left?.let { if (it < 0) " (break overdue by ${HalfTime.countdown(-it)})." else " (${HalfTime.countdown(it)} left)." } ?: ".")
            }
        }
        AlertDialog(
            onDismissRequest = { correction = null },
            title = { Text("${pendingCorrection.label}?") },
            text = { Text("$after Goals, cards and subs logged since are re-timed to match.") },
            confirmButton = {
                Button(onClick = {
                    correction = null
                    act(pendingCorrection.label) { correct(pendingCorrection) }
                }) { Text(pendingCorrection.label) }
            },
            dismissButton = { TextButton(onClick = { correction = null }) { Text("Cancel") } },
        )
    }

    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text("End match?") },
            text = { Text("Final score: ${s?.homeScore ?: 0} – ${s?.awayScore ?: 0}") },
            confirmButton = {
                TextButton(onClick = {
                    confirmEnd = false
                    // Full time by mistake reopens in one tap (SB-1450).
                    act("Full time", undo = { repo.enqueueReopen(matchId) }) {
                        repo.enqueueClock(matchId, ClockRequest("end_match", occurredAt = Instant.now().toString()))
                    }
                }) { Text("End match") }
            },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Cancel") } },
        )
    }

    // Action bottom sheets
    val currentFlow = flow
    if (currentFlow != ActionFlow.None && s != null) {
        ModalBottomSheet(onDismissRequest = { endFlow() }) {
            when (currentFlow) {
                is ActionFlow.GoalPickScorer -> PlayerPickSheet(
                    title = stamped(stamp, "Who scored?"),
                    players = rosters[currentFlow.teamId].orEmpty(),
                    allowFreeText = true,
                    onPick = { player, freeText ->
                        flow = ActionFlow.GoalPickAssist(currentFlow.teamId, player, freeText)
                    },
                )

                is ActionFlow.GoalPickAssist -> {
                    val pitch = if (currentFlow.everyone) null else currentPitch(currentFlow.teamId)
                    PlayerPickSheet(
                        title = stamped(stamp, "Assist?"),
                        players = OnPitch.assistCandidates(
                            rosters[currentFlow.teamId].orEmpty(),
                            { it.id },
                            pitch,
                            currentFlow.scorer?.id,
                        ),
                        extraOption = "NO ASSIST",
                        moreOption = if (pitch != null) "Not on the field? Show everyone" else null,
                        onMore = { flow = currentFlow.copy(everyone = true) },
                        onExtra = {
                            val at = stamp
                            endFlow()
                            act(stamped(at, "Goal recorded")) {
                                repo.enqueueGoal(
                                    matchId,
                                    GoalRequest(
                                        teamId = currentFlow.teamId,
                                        playerId = currentFlow.scorer?.id,
                                        playerName = currentFlow.scorerName,
                                        matchMinute = at.minute,
                                        extraTime = at.extraTime,
                                        clientEventId = UUID.randomUUID().toString(),
                                    ),
                                )
                            }
                        },
                        onPick = { assist, _ ->
                            val at = stamp
                            endFlow()
                            act(stamped(at, "Goal recorded")) {
                                repo.enqueueGoal(
                                    matchId,
                                    GoalRequest(
                                        teamId = currentFlow.teamId,
                                        playerId = currentFlow.scorer?.id,
                                        playerName = currentFlow.scorerName,
                                        assistPlayerId = assist?.id,
                                        matchMinute = at.minute,
                                        extraTime = at.extraTime,
                                        clientEventId = UUID.randomUUID().toString(),
                                    ),
                                )
                            }
                        },
                    )
                }

                ActionFlow.CardPickTeam -> TeamPickSheet(s, onPick = { flow = ActionFlow.CardPickType(it) })

                is ActionFlow.CardPickType -> Column(Modifier.padding(16.dp)) {
                    Text("Card type", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { flow = ActionFlow.CardPickPlayer(currentFlow.teamId, "yellow_card") },
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp),
                        ) { Text("YELLOW") }
                        Button(
                            onClick = { flow = ActionFlow.CardPickPlayer(currentFlow.teamId, "red_card") },
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp),
                        ) { Text("RED") }
                    }
                    Spacer(Modifier.height(24.dp))
                }

                is ActionFlow.CardPickPlayer -> PlayerPickSheet(
                    title = "Who got the card?",
                    players = rosters[currentFlow.teamId].orEmpty(),
                    allowFreeText = true,
                    onPick = { player, freeText ->
                        val at = stamp
                        endFlow()
                        act(stamped(at, "Card recorded")) {
                            repo.enqueueCard(
                                matchId,
                                CardRequest(
                                    teamId = currentFlow.teamId,
                                    playerId = player?.id,
                                    playerName = freeText,
                                    cardType = currentFlow.cardType,
                                    matchMinute = at.minute,
                                    extraTime = at.extraTime,
                                    clientEventId = UUID.randomUUID().toString(),
                                ),
                            )
                        }
                    },
                )

                ActionFlow.None -> {}
            }
        }
    }

    if (subMode && s != null) {
        val teams = listOfNotNull(
            s.homeTeamId?.let { it to s.homeTeamName },
            s.awayTeamId?.let { it to s.awayTeamName },
        ).map { (id, name) ->
            SubTeam(
                id = id,
                name = name,
                formation = lineups[id]?.formationName.orEmpty(),
                pitch = currentPitch(id),
                roster = rosters[id].orEmpty(),
            )
        }
        if (teams.isNotEmpty()) {
            SubModeScreen(
                teams = teams,
                initialTeamId = lastSubTeam?.takeIf { id -> teams.any { it.id == id } }
                    ?: teams.firstOrNull { it.pitch != null }?.id
                    ?: teams.first().id,
                minuteLabel = subStamp.label(),
                onDone = { changesByTeam ->
                    val at = subStamp
                    subMode = false
                    lastSubTeam = changesByTeam.keys.lastOrNull() ?: lastSubTeam
                    val all = changesByTeam.values.flatten()
                    val subs = all.count { it is OnPitch.Sub }
                    val moves = all.size - subs
                    val label = listOfNotNull(
                        subs.takeIf { it > 0 }?.let { if (it == 1) "1 sub" else "$it subs" },
                        moves.takeIf { it > 0 }?.let { if (it == 1) "1 position change" else "$it position changes" },
                    ).joinToString(", ") + " recorded"
                    act(stamped(at, label)) {
                        // Queue order is replay order: subs first, then the
                        // moves that put everyone where they ended up.
                        changesByTeam.forEach { (teamId, changes) ->
                            changes.forEach { change ->
                                when (change) {
                                    is OnPitch.Sub -> repo.enqueueSubstitution(
                                        matchId,
                                        SubstitutionRequest(
                                            teamId = teamId,
                                            playerInId = requireNotNull(change.inId),
                                            playerOutId = requireNotNull(change.outId),
                                            matchMinute = at.minute,
                                            extraTime = at.extraTime,
                                            clientEventId = UUID.randomUUID().toString(),
                                        ),
                                    )
                                    is OnPitch.Move -> repo.enqueuePositionChange(
                                        matchId,
                                        PositionChangeRequest(
                                            teamId = teamId,
                                            playerId = requireNotNull(change.playerId),
                                            position = requireNotNull(change.position),
                                            matchMinute = at.minute,
                                            extraTime = at.extraTime,
                                            clientEventId = UUID.randomUUID().toString(),
                                        ),
                                    )
                                }
                            }
                        }
                    }
                },
                onClose = { subMode = false },
                onSetLineup = {
                    subMode = false
                    onOpenLineup(s)
                },
            )
        }
    }
}

/**
 * "Start the 2nd half first" (SB-1350). Offered when an event is entered at
 * half time, or START 2ND HALF is tapped after the break ran out. When the
 * break is over, the default is the moment it ended — that's when play most
 * likely restarted — with "now" as the alternative.
 */
@Composable
private fun SecondHalfDialog(
    expectedStart: Instant?,
    earlyBreakSeconds: Long?,
    onStart: (Instant) -> Unit,
    onDismiss: () -> Unit,
) {
    val now = Instant.now()
    // Within a minute of now there's nothing worth backdating.
    val backdate = expectedStart?.takeIf { Duration.between(it, now).toMinutes() >= 1 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start the 2nd half first") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (backdate != null) {
                        "The clock is still on half time. The ${HalfTime.BREAK_MINUTES}-minute break ended at " +
                            "${chatTime(backdate.toString())} — when did the 2nd half kick off?"
                    } else {
                        "The clock is still on half time."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                // Moments into the break, the likelier story is a half time
                // tapped by mistake (SB-1450): point at the way back.
                if (earlyBreakSeconds != null) {
                    Text(
                        "Half time only began ${HalfTime.countdown(earlyBreakSeconds)} ago. If the 1st half is " +
                            "still going, cancel and use ⋮ → Back to 1st half.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (backdate != null) {
                    Button(onClick = { onStart(backdate) }, modifier = Modifier.fillMaxWidth()) {
                        Text("At ${chatTime(backdate.toString())} (end of break)")
                    }
                    OutlinedButton(onClick = { onStart(Instant.now()) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Just now")
                    }
                } else {
                    Button(onClick = { onStart(Instant.now()) }, modifier = Modifier.fillMaxWidth()) {
                        Text("START 2ND HALF")
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Chat box under the timeline (SB-1294) — iOS LiveMatchView's composer. */
@Composable
private fun ChatComposer(draft: String, onDraftChange: (String) -> Unit, onSend: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            placeholder = { Text("Type a message…") },
            maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onSend, enabled = draft.isNotBlank()) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
        }
    }
}

/** A chat message: author, time, text. Your own messages are tinted (iOS ChatRow). */
@Composable
private fun ChatRow(event: MatchEvent, isMine: Boolean, isPending: Boolean, onDelete: (() -> Unit)?) {
    Card(
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = if (isMine) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        event.createdByUsername ?: "Anonymous",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    chatTime(event.createdAt)?.let {
                        Text(
                            " · $it",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(event.message, style = MaterialTheme.typography.bodyMedium)
            }
            if (isPending) {
                Icon(
                    Icons.Filled.CloudUpload,
                    contentDescription = "Waiting to sync",
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete message", modifier = Modifier.size(18.dp))
                }
            } else {
                Spacer(Modifier.size(10.dp))
            }
        }
    }
}

private val chatTimeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

/** Local wall-clock time of a server timestamp, or null for queued rows / bad input. */
private fun chatTime(createdAt: String?): String? = createdAt?.let {
    runCatching {
        OffsetDateTime.parse(it).atZoneSameInstant(ZoneId.systemDefault()).format(chatTimeFormat)
    }.getOrNull()
}

@Composable
private fun GoalButton(teamName: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier.height(72.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("GOAL", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                teamName,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TeamPickSheet(s: LiveMatchState, onPick: (Int) -> Unit) {
    Column(Modifier.padding(16.dp)) {
        Text("Which team?", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { s.homeTeamId?.let(onPick) },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) { Text(s.homeTeamName, maxLines = 1) }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { s.awayTeamId?.let(onPick) },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) { Text(s.awayTeamName, maxLines = 1) }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Jersey-number grid picker. onPick delivers (player, null) for a roster tap
 * or (null, freeText) when the free-text entry is used.
 */
@Composable
internal fun PlayerPickSheet(
    title: String,
    players: List<RosterPlayer>,
    allowFreeText: Boolean = false,
    extraOption: String? = null,
    onExtra: (() -> Unit)? = null,
    // A text link under the tiles, for widening a narrowed list (SB-1432).
    moreOption: String? = null,
    onMore: (() -> Unit)? = null,
    onPick: (RosterPlayer?, String?) -> Unit,
) {
    var freeText by remember { mutableStateOf("") }
    Column(Modifier.padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        if (extraOption != null && onExtra != null) {
            Button(
                onClick = onExtra,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) { Text(extraOption) }
            Spacer(Modifier.height(12.dp))
        }
        if (players.isNotEmpty()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
            ) {
                items(players, key = { it.id }) { p ->
                    androidx.compose.material3.Surface(
                        onClick = { onPick(p, null) },
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.height(76.dp),
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            Text(
                                "${p.jerseyNumber ?: "?"}",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                p.label,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        if (moreOption != null && onMore != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onMore, modifier = Modifier.fillMaxWidth()) { Text(moreOption) }
        }
        if (allowFreeText) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = freeText,
                    onValueChange = { freeText = it },
                    label = { Text("Name / number") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { if (freeText.isNotBlank()) onPick(null, freeText.trim()) }) { Text("OK") }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
