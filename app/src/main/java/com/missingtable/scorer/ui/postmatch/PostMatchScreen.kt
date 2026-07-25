package com.missingtable.scorer.ui.postmatch

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.missingtable.scorer.AppContainer
import com.missingtable.scorer.data.api.BatchPlayerStatsUpdate
import com.missingtable.scorer.data.api.GoalEventUpdateRequest
import com.missingtable.scorer.data.api.PlayerStatEntry
import com.missingtable.scorer.data.api.LiveMatchState
import com.missingtable.scorer.data.api.MatchEvent
import com.missingtable.scorer.data.api.PostMatchCardRequest
import com.missingtable.scorer.data.api.PostMatchGoalRequest
import com.missingtable.scorer.data.api.PostMatchSubRequest
import com.missingtable.scorer.data.api.RosterPlayer
import com.missingtable.scorer.domain.MinutesPlayed
import kotlinx.coroutines.launch

private sealed interface Sheet {
    data object None : Sheet
    data class AddGoal(val step: Int = 0, val teamId: Int? = null, val scorer: RosterPlayer? = null, val assist: RosterPlayer? = null) : Sheet
    data class AddSub(val step: Int = 0, val teamId: Int? = null, val out: RosterPlayer? = null, val inn: RosterPlayer? = null) : Sheet
    data class AddCard(val step: Int = 0, val teamId: Int? = null, val cardType: String? = null, val player: RosterPlayer? = null) : Sheet
    data class EditGoal(val event: MatchEvent, val scorer: RosterPlayer? = null, val assist: RosterPlayer? = null, val pickScorer: Boolean = false, val pickAssist: Boolean = false) : Sheet
}

/**
 * Post-match corrections for completed matches (SB-281, scorer roles only).
 * Direct API calls — this happens at home on wifi, not on the sideline, so
 * the offline queue's complexity isn't warranted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostMatchScreen(
    container: AppContainer,
    matchId: Int,
    seasonId: Int?,
    onBack: () -> Unit,
    onReopen: () -> Unit,
) {
    var state by remember { mutableStateOf<LiveMatchState?>(null) }
    var events by remember { mutableStateOf<List<MatchEvent>>(emptyList()) }
    var rosters by remember { mutableStateOf<Map<Int, List<RosterPlayer>>>(emptyMap()) }
    var sheet by remember { mutableStateOf<Sheet>(Sheet.None) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmReopen by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        runCatching { container.api.liveState(matchId) }.onSuccess { state = it }
        runCatching { container.api.matchEvents(matchId) }.onSuccess { events = it }
    }

    LaunchedEffect(matchId) { refresh() }

    LaunchedEffect(state?.homeTeamId, state?.awayTeamId) {
        val s = state ?: return@LaunchedEffect
        if (seasonId == null || rosters.isNotEmpty()) return@LaunchedEffect
        val loaded = mutableMapOf<Int, List<RosterPlayer>>()
        listOfNotNull(s.homeTeamId, s.awayTeamId).forEach { teamId ->
            runCatching { container.api.roster(teamId, seasonId, null) }
                .onSuccess { loaded[teamId] = it.roster }
        }
        rosters = loaded
    }

    fun act(label: String, block: suspend () -> Unit) {
        scope.launch {
            runCatching { block() }
                .onSuccess {
                    refresh()
                    snackbar.showSnackbar(label)
                }
                .onFailure { snackbar.showSnackbar("Failed: $label") }
        }
    }

    val s = state
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(s?.let { "Edit: ${it.homeTeamName} v ${it.awayTeamName}" } ?: "Edit match") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        // SB-282: derive minutes played (+cards) from the
                        // lineup + sub timeline and push to player stats.
                        listOfNotNull(
                            s?.homeTeamId?.let { it to s.homeTeamName },
                            s?.awayTeamId?.let { it to s.awayTeamName },
                        ).forEach { (teamId, teamName) ->
                            DropdownMenuItem(text = { Text("Fill minutes: $teamName") }, onClick = {
                                menuOpen = false
                                act("Minutes filled for $teamName") {
                                    val lineup = container.api.getLineup(matchId, teamId)
                                    require(lineup.positions.isNotEmpty()) { "No saved lineup" }
                                    val matchLength = (state?.halfDuration ?: 45) * 2
                                    val derived = MinutesPlayed.derive(
                                        starters = lineup.positions.map { it.playerId }.toSet(),
                                        events = events,
                                        teamId = teamId,
                                        matchLength = matchLength,
                                    )
                                    container.api.putPostMatchStats(
                                        matchId, teamId,
                                        BatchPlayerStatsUpdate(
                                            derived.map {
                                                PlayerStatEntry(
                                                    playerId = it.playerId,
                                                    started = it.started,
                                                    played = it.played,
                                                    minutesPlayed = it.minutes,
                                                    yellowCards = it.yellowCards,
                                                    redCards = it.redCards,
                                                )
                                            }
                                        ),
                                    )
                                }
                            })
                        }
                        DropdownMenuItem(text = { Text("Reopen match") }, onClick = {
                            menuOpen = false
                            confirmReopen = true
                        })
                    }
                },
            )
        },
    ) { padding ->
        if (s == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
            Text(
                "${s.homeTeamName} ${s.homeScore ?: 0} – ${s.awayScore ?: 0} ${s.awayTeamName}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 10.dp),
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { sheet = Sheet.AddGoal() }, modifier = Modifier.weight(1f)) { Text("+ GOAL") }
                OutlinedButton(onClick = { sheet = Sheet.AddSub() }, modifier = Modifier.weight(1f)) { Text("+ SUB") }
                OutlinedButton(onClick = { sheet = Sheet.AddCard() }, modifier = Modifier.weight(1f)) { Text("+ CARD") }
            }

            Spacer(Modifier.height(10.dp))
            Text("Timeline", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            LazyColumn(Modifier.fillMaxSize().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(events, key = { it.id }) { e ->
                    Card {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                e.matchMinute?.let { m ->
                                    e.extraTime?.takeIf { it > 0 }?.let { "$m+$it'" } ?: "$m'"
                                } ?: "",
                                modifier = Modifier.padding(end = 8.dp),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(
                                e.message,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (e.eventType == "goal") {
                                IconButton(onClick = { sheet = Sheet.EditGoal(e) }) {
                                    Icon(Icons.Filled.Edit, contentDescription = "Edit goal", modifier = Modifier.size(18.dp))
                                }
                            }
                            if (e.eventType in setOf("goal", "substitution", "yellow_card", "red_card")) {
                                IconButton(onClick = {
                                    act("Event removed") {
                                        when (e.eventType) {
                                            "goal" -> container.api.deletePostMatchGoal(matchId, e.id)
                                            "substitution" -> container.api.deletePostMatchSubstitution(matchId, e.id)
                                            else -> container.api.deletePostMatchCard(matchId, e.id)
                                        }
                                    }
                                }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete event", modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmReopen) {
        AlertDialog(
            onDismissRequest = { confirmReopen = false },
            title = { Text("Reopen match?") },
            text = { Text("The match goes back to live scoring.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReopen = false
                    scope.launch {
                        runCatching { container.api.reopenMatch(matchId) }
                            .onSuccess { onReopen() }
                            .onFailure { snackbar.showSnackbar("Failed: reopen") }
                    }
                }) { Text("Reopen") }
            },
            dismissButton = { TextButton(onClick = { confirmReopen = false }) { Text("Cancel") } },
        )
    }

    val current = sheet
    if (current != Sheet.None && s != null) {
        ModalBottomSheet(onDismissRequest = { sheet = Sheet.None }) {
            when (current) {
                is Sheet.AddGoal -> when {
                    current.teamId == null -> TeamPick(s) { sheet = current.copy(step = 1, teamId = it) }
                    current.step == 1 -> PlayerGrid("Who scored?", rosters[current.teamId].orEmpty()) {
                        sheet = current.copy(step = 2, scorer = it)
                    }
                    current.step == 2 -> PlayerGrid(
                        "Assist?",
                        rosters[current.teamId].orEmpty().filter { it.id != current.scorer?.id },
                        extraOption = "NO ASSIST",
                        onExtra = { sheet = current.copy(step = 3, assist = null) },
                    ) { sheet = current.copy(step = 3, assist = it) }
                    else -> MinutePick(maxMinute(s)) { minute ->
                        sheet = Sheet.None
                        act("Goal added") {
                            container.api.postMatchGoal(
                                matchId,
                                PostMatchGoalRequest(
                                    teamId = current.teamId,
                                    playerId = current.scorer?.id,
                                    assistPlayerId = current.assist?.id,
                                    matchMinute = minute,
                                ),
                            )
                        }
                    }
                }

                is Sheet.AddSub -> when {
                    current.teamId == null -> TeamPick(s) { sheet = current.copy(step = 1, teamId = it) }
                    current.step == 1 -> PlayerGrid("Player OFF", rosters[current.teamId].orEmpty()) {
                        sheet = current.copy(step = 2, out = it)
                    }
                    current.step == 2 -> PlayerGrid(
                        "Player ON",
                        rosters[current.teamId].orEmpty().filter { it.id != current.out?.id },
                    ) { sheet = current.copy(step = 3, inn = it) }
                    else -> MinutePick(maxMinute(s)) { minute ->
                        sheet = Sheet.None
                        act("Substitution added") {
                            container.api.postMatchSubstitution(
                                matchId,
                                PostMatchSubRequest(
                                    teamId = current.teamId,
                                    playerInId = requireNotNull(current.inn).id,
                                    playerOutId = requireNotNull(current.out).id,
                                    matchMinute = minute,
                                ),
                            )
                        }
                    }
                }

                is Sheet.AddCard -> when {
                    current.teamId == null -> TeamPick(s) { sheet = current.copy(step = 1, teamId = it) }
                    current.step == 1 -> Column(Modifier.padding(16.dp)) {
                        Text("Card type", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { sheet = current.copy(step = 2, cardType = "yellow_card") },
                                modifier = Modifier.weight(1f).height(56.dp),
                            ) { Text("YELLOW") }
                            Button(
                                onClick = { sheet = current.copy(step = 2, cardType = "red_card") },
                                modifier = Modifier.weight(1f).height(56.dp),
                            ) { Text("RED") }
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                    current.step == 2 -> PlayerGrid("Who got the card?", rosters[current.teamId].orEmpty()) {
                        sheet = current.copy(step = 3, player = it)
                    }
                    else -> MinutePick(maxMinute(s)) { minute ->
                        sheet = Sheet.None
                        act("Card added") {
                            container.api.postMatchCard(
                                matchId,
                                PostMatchCardRequest(
                                    teamId = current.teamId,
                                    playerId = current.player?.id,
                                    cardType = requireNotNull(current.cardType),
                                    matchMinute = minute,
                                ),
                            )
                        }
                    }
                }

                is Sheet.EditGoal -> when {
                    current.pickScorer -> PlayerGrid(
                        "New scorer",
                        rosters[current.event.teamId].orEmpty(),
                    ) { sheet = current.copy(scorer = it, pickScorer = false) }
                    current.pickAssist -> PlayerGrid(
                        "New assist",
                        rosters[current.event.teamId].orEmpty()
                            .filter { it.id != (current.scorer?.id ?: current.event.playerId) },
                    ) { sheet = current.copy(assist = it, pickAssist = false) }
                    else -> EditGoalSheet(
                        event = current.event,
                        newScorer = current.scorer,
                        newAssist = current.assist,
                        maxMinute = maxMinute(s),
                        onPickScorer = { sheet = current.copy(pickScorer = true) },
                        onPickAssist = { sheet = current.copy(pickAssist = true) },
                        onSave = { minute ->
                            sheet = Sheet.None
                            act("Goal updated") {
                                container.api.patchGoalEvent(
                                    current.event.id,
                                    GoalEventUpdateRequest(
                                        matchMinute = minute,
                                        playerId = current.scorer?.id,
                                        assistPlayerId = current.assist?.id,
                                    ),
                                )
                            }
                        },
                    )
                }

                Sheet.None -> {}
            }
        }
    }
}

/** Sensible minute ceiling: full time incl. a generous stoppage allowance. */
private fun maxMinute(s: LiveMatchState): Int = s.halfDuration * 2 + 15

@Composable
private fun TeamPick(s: LiveMatchState, onPick: (Int) -> Unit) {
    Column(Modifier.padding(16.dp)) {
        Text("Which team?", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { s.homeTeamId?.let(onPick) },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text(s.homeTeamName, maxLines = 1) }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { s.awayTeamId?.let(onPick) },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text(s.awayTeamName, maxLines = 1) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PlayerGrid(
    title: String,
    players: List<RosterPlayer>,
    extraOption: String? = null,
    onExtra: (() -> Unit)? = null,
    onPick: (RosterPlayer) -> Unit,
) {
    Column(Modifier.padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        if (extraOption != null && onExtra != null) {
            Button(onClick = onExtra, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(extraOption) }
            Spacer(Modifier.height(12.dp))
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
        ) {
            items(players, key = { it.id }) { p ->
                Surface(
                    onClick = { onPick(p) },
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.height(76.dp),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Text("${p.jerseyNumber ?: "?"}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(p.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun MinutePick(maxMinute: Int, initial: Int = 1, onConfirm: (Int) -> Unit) {
    var minute by remember { mutableIntStateOf(initial) }
    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Minute", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        MinuteStepper(minute, maxMinute) { minute = it }
        Spacer(Modifier.height(16.dp))
        Button(onClick = { onConfirm(minute) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("SAVE")
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun MinuteStepper(minute: Int, maxMinute: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = { onChange((minute - 5).coerceAtLeast(1)) }) { Text("-5") }
        OutlinedButton(onClick = { onChange((minute - 1).coerceAtLeast(1)) }) { Text("-1") }
        Text(
            "$minute'",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(72.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        OutlinedButton(onClick = { onChange((minute + 1).coerceAtMost(maxMinute)) }) { Text("+1") }
        OutlinedButton(onClick = { onChange((minute + 5).coerceAtMost(maxMinute)) }) { Text("+5") }
    }
}

@Composable
private fun EditGoalSheet(
    event: MatchEvent,
    newScorer: RosterPlayer?,
    newAssist: RosterPlayer?,
    maxMinute: Int,
    onPickScorer: () -> Unit,
    onPickAssist: () -> Unit,
    onSave: (Int) -> Unit,
) {
    var minute by remember { mutableIntStateOf(event.matchMinute ?: 1) }
    Column(Modifier.padding(16.dp)) {
        Text("Edit goal", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(event.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            MinuteStepper(minute, maxMinute) { minute = it }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onPickScorer, modifier = Modifier.fillMaxWidth()) {
            Text(newScorer?.let { "Scorer: ${it.label}" } ?: "Change scorer")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onPickAssist, modifier = Modifier.fillMaxWidth()) {
            Text(newAssist?.let { "Assist: ${it.label}" } ?: "Change assist")
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = { onSave(minute) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("SAVE CHANGES")
        }
        Spacer(Modifier.height(24.dp))
    }
}
