package com.missingtable.scorer.ui.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.missingtable.scorer.AppContainer
import com.missingtable.scorer.data.api.CardRequest
import com.missingtable.scorer.data.api.ClockRequest
import com.missingtable.scorer.data.api.GoalRequest
import com.missingtable.scorer.data.api.LiveMatchState
import com.missingtable.scorer.data.api.RosterPlayer
import com.missingtable.scorer.data.api.SubstitutionRequest
import com.missingtable.scorer.data.db.PendingAction
import com.missingtable.scorer.domain.ClockStage
import com.missingtable.scorer.domain.EventStamp
import com.missingtable.scorer.domain.HalfDuration
import com.missingtable.scorer.domain.LiveClock
import com.missingtable.scorer.domain.MatchClock
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface ActionFlow {
    data object None : ActionFlow
    data class GoalPickScorer(val teamId: Int) : ActionFlow
    data class GoalPickAssist(val teamId: Int, val scorer: RosterPlayer?, val scorerName: String?) : ActionFlow
    data object SubPickTeam : ActionFlow
    data class SubPickOut(val teamId: Int) : ActionFlow
    data class SubPickIn(val teamId: Int, val out: RosterPlayer) : ActionFlow
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
    // Fan view (SB-320): scoreboard + clock + timeline only — no scoring
    // controls, no clock menu, no deletes, no queue affordances.
    readOnly: Boolean = false,
) {
    var serverState by remember { mutableStateOf<LiveMatchState?>(null) }
    var rosters by remember { mutableStateOf<Map<Int, List<RosterPlayer>>>(emptyMap()) }
    var starters by remember { mutableStateOf<Map<Int, Set<Int>>>(emptyMap()) }
    var flow by remember { mutableStateOf<ActionFlow>(ActionFlow.None) }
    // Minute captured when an entry flow STARTS (SB-652). Reading the clock at
    // the end of the flow would stamp however long the pickers took onto the
    // event — worst exactly when the match is busiest.
    var stamp by remember { mutableStateOf(EventStamp.UNKNOWN) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }
    var startDialog by remember { mutableStateOf(false) }
    var nowTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val repo = container.liveRepo
    val pending by repo.pendingForMatch(matchId).collectAsState(initial = emptyList())
    val online by container.connectivity.online.collectAsState()

    // What the UI renders: last server state with the outstanding queue folded
    // in (scores, pending timeline entries, clock timestamps, hidden deletes).
    val state = serverState?.let { OptimisticLive.merge(it, pending, container.json, rosters) }

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
        val startingXi = mutableMapOf<Int, Set<Int>>()
        listOfNotNull(s.homeTeamId, s.awayTeamId).forEach { teamId ->
            runCatching { container.api.roster(teamId, seasonId, null) }
                .onSuccess { loaded[teamId] = it.roster }
            runCatching { container.api.getLineup(matchId, teamId) }
                .onSuccess { lineup ->
                    if (lineup.positions.isNotEmpty()) {
                        startingXi[teamId] = lineup.positions.map { it.playerId }.toSet()
                    }
                }
        }
        rosters = loaded
        starters = startingXi
    }

    // On-pitch set: starting XI adjusted by substitution events (oldest first).
    // Null when no lineup was saved — pickers then fall back to the full roster.
    fun onPitch(teamId: Int): Set<Int>? {
        val base = starters[teamId] ?: return null
        var current = base
        state?.recentEvents
            ?.filter { it.eventType == "substitution" && it.teamId == teamId }
            ?.reversed()
            ?.forEach { sub ->
                val inId = sub.playerId
                val outId = sub.playerOutId
                if (outId != null) current = current - outId
                if (inId != null) current = current + inId
            }
        return current
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
    fun act(label: String, block: suspend () -> Unit) {
        scope.launch {
            runCatching { block() }
                .onSuccess { snackbar.showSnackbar(label) }
                .onFailure { snackbar.showSnackbar("Failed: $label") }
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
    fun beginFlow(next: ActionFlow) {
        stamp = EventStamp.from(tapMinute())
        flow = next
    }

    /** Close an entry flow and drop its stamp so a restart re-reads the clock. */
    fun endFlow() {
        flow = ActionFlow.None
        stamp = EventStamp.UNKNOWN
    }

    /**
     * "Goal recorded 23'" — say what minute was captured, not just that it was.
     * Takes the stamp explicitly: callers snapshot it before ending the flow,
     * which clears it.
     */
    fun stamped(at: EventStamp, label: String): String =
        at.label()?.let { "$label $it" } ?: label

    val s = state
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(s?.let { "${it.homeTeamName} v ${it.awayTeamName}" } ?: "Match") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (readOnly) return@TopAppBar
                    if (pending.isNotEmpty()) {
                        Text(
                            "${pending.size} pending",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                    // Connectivity dot: green = online, grey = offline (queueing).
                    Box(
                        Modifier
                            .padding(end = 8.dp)
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (online) Color(0xFF2E7D32) else Color(0xFF9E9E9E)),
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
                        DropdownMenuItem(text = { Text("Back to 1st half") }, onClick = {
                            menuOpen = false
                            act("Back to 1st half") { repo.enqueueClock(matchId, ClockRequest("cancel_halftime")) }
                        })
                        DropdownMenuItem(text = { Text("Reopen match") }, onClick = {
                            menuOpen = false
                            act("Match reopened") { repo.enqueueReopen(matchId) }
                        })
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
                .padding(horizontal = 12.dp),
        ) {
            // A rejected (4xx) action pauses the whole queue — strict FIFO —
            // so it must be resolved before anything else syncs.
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
                Text(
                    s.homeTeamName,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium,
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${s.homeScore ?: 0} – ${s.awayScore ?: 0}",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(clock.display, style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    s.awayTeamName,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            // The next clock action, on the scoreboard rather than two taps
            // deep in the overflow (SB-653). One valid action at a time; the
            // corrections (back to 1st half, reopen) stay in the menu.
            if (!readOnly) {
                val stage = MatchClock.stage(
                    s.kickoffTime, s.halftimeStart, s.secondHalfStart, s.matchEndTime
                )
                if (stage.hasPrimaryAction) {
                    Button(
                        onClick = {
                            when (stage) {
                                // Starting needs the half-length dialog (SB-645).
                                ClockStage.NOT_STARTED -> startDialog = true
                                ClockStage.SECOND_HALF -> confirmEnd = true
                                else -> MatchClock.primaryAction(stage)?.let { action ->
                                    act(stage.label) {
                                        repo.enqueueClock(
                                            matchId,
                                            ClockRequest(action, occurredAt = Instant.now().toString()),
                                        )
                                    }
                                }
                            }
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
                        s.homeTeamId?.let { beginFlow(ActionFlow.GoalPickScorer(it)) }
                    }
                    GoalButton(s.awayTeamName, Modifier.weight(1f)) {
                        s.awayTeamId?.let { beginFlow(ActionFlow.GoalPickScorer(it)) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { beginFlow(ActionFlow.SubPickTeam) },
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                    ) { Text("SUB") }
                    OutlinedButton(
                        onClick = { beginFlow(ActionFlow.CardPickTeam) },
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
                    .fillMaxSize()
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(s.recentEvents, key = { it.id }) { e ->
                    val isPending = OptimisticLive.isPending(e)
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
        }
    }

    if (startDialog) {
        // Pre-selected from the age group, but every field stays editable —
        // a U15 side may play shorter halves in a tournament or friendly, and
        // the old fixed 35/40/45 menu could not express that at all (SB-645).
        val default = HalfDuration.defaultFor(s?.ageGroupName)
        var minutes by remember(startDialog) { mutableStateOf(default) }
        var typed by remember(startDialog) { mutableStateOf(default.toString()) }
        val parsed = typed.toIntOrNull()
        val valid = HalfDuration.isValid(parsed)

        AlertDialog(
            onDismissRequest = { startDialog = false },
            title = { Text("Start match") },
            text = {
                Column {
                    Text(
                        "Half length" + (s?.ageGroupName?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        HalfDuration.PRESETS.forEach { preset ->
                            FilterChip(
                                selected = parsed == preset,
                                onClick = {
                                    minutes = preset
                                    typed = preset.toString()
                                },
                                label = { Text("$preset · ${HalfDuration.presetLabel(preset)}") },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it.filter(Char::isDigit).take(2) },
                        label = { Text("Minutes per half") },
                        singleLine = true,
                        isError = typed.isNotEmpty() && !valid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    )
                    Text(
                        if (valid) "= ${parsed!! * 2} min total" else "Must be ${HalfDuration.MIN}–${HalfDuration.MAX}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (valid) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = valid,
                    onClick = {
                        startDialog = false
                        val dur = parsed ?: default
                        act("Match started (${dur}m halves)") {
                            repo.enqueueClock(
                                matchId,
                                ClockRequest("start_first_half", dur, Instant.now().toString()),
                            )
                        }
                    },
                ) { Text("Start match") }
            },
            dismissButton = { TextButton(onClick = { startDialog = false }) { Text("Cancel") } },
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
                    act("Full time") {
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

                is ActionFlow.GoalPickAssist -> PlayerPickSheet(
                    title = stamped(stamp, "Assist?"),
                    players = rosters[currentFlow.teamId].orEmpty().filter { it.id != currentFlow.scorer?.id },
                    extraOption = "NO ASSIST",
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

                ActionFlow.SubPickTeam -> TeamPickSheet(s, onPick = { flow = ActionFlow.SubPickOut(it) })

                is ActionFlow.SubPickOut -> {
                    val teamRoster = rosters[currentFlow.teamId].orEmpty()
                    val pitch = onPitch(currentFlow.teamId)
                    PlayerPickSheet(
                        title = stamped(stamp, "Player OFF"),
                        players = if (pitch != null) teamRoster.filter { it.id in pitch } else teamRoster,
                        onPick = { out, _ ->
                            if (out != null) flow = ActionFlow.SubPickIn(currentFlow.teamId, out)
                        },
                    )
                }

                is ActionFlow.SubPickIn -> PlayerPickSheet(
                    title = "Player ON (for ${currentFlow.out.label})",
                    players = run {
                        val pitch = onPitch(currentFlow.teamId)
                        rosters[currentFlow.teamId].orEmpty().filter {
                            it.id != currentFlow.out.id && (pitch == null || it.id !in pitch)
                        }
                    },
                    onPick = { inn, _ ->
                        if (inn != null) {
                            val at = stamp
                            // Multi-sub fast path (SB-282): chain straight back
                            // to Player OFF for the same team — halftime swaps
                            // are 3-4 subs in a row. Dismiss the sheet to stop.
                            // beginFlow re-stamps: the next sub is a new event,
                            // so it gets its own minute rather than inheriting
                            // this one (SB-652).
                            beginFlow(ActionFlow.SubPickOut(currentFlow.teamId))

                            act(stamped(at, "Substitution recorded")) {
                                repo.enqueueSubstitution(
                                    matchId,
                                    SubstitutionRequest(
                                        teamId = currentFlow.teamId,
                                        playerInId = inn.id,
                                        playerOutId = currentFlow.out.id,
                                        matchMinute = at.minute,
                                        extraTime = at.extraTime,
                                        clientEventId = UUID.randomUUID().toString(),
                                    ),
                                )
                            }
                        }
                    },
                )

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
private fun PlayerPickSheet(
    title: String,
    players: List<RosterPlayer>,
    allowFreeText: Boolean = false,
    extraOption: String? = null,
    onExtra: (() -> Unit)? = null,
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
