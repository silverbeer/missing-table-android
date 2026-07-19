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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.missingtable.scorer.AppContainer
import com.missingtable.scorer.data.api.CardRequest
import com.missingtable.scorer.data.api.ClockRequest
import com.missingtable.scorer.data.api.GoalRequest
import com.missingtable.scorer.data.api.LiveMatchState
import com.missingtable.scorer.data.api.RosterPlayer
import com.missingtable.scorer.data.api.SubstitutionRequest
import com.missingtable.scorer.domain.LiveClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

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
) {
    var state by remember { mutableStateOf<LiveMatchState?>(null) }
    var rosters by remember { mutableStateOf<Map<Int, List<RosterPlayer>>>(emptyMap()) }
    var starters by remember { mutableStateOf<Map<Int, Set<Int>>>(emptyMap()) }
    var flow by remember { mutableStateOf<ActionFlow>(ActionFlow.None) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }
    var nowTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        runCatching { container.api.liveState(matchId) }
            .onSuccess { state = it }
            .onFailure { snackbar.showSnackbar("Couldn't refresh match") }
    }

    // Initial load + 15s poll
    LaunchedEffect(matchId) {
        refresh()
        while (true) {
            delay(15_000)
            refresh()
        }
    }

    // Load rosters once both team ids are known. Fetch unfiltered by age
    // group first; the roster endpoint's age filter can hide players with
    // no age_group_id set, so only use it as a fallback refinement.
    LaunchedEffect(state?.homeTeamId, state?.awayTeamId) {
        val s = state ?: return@LaunchedEffect
        if (seasonId == null || rosters.isNotEmpty()) return@LaunchedEffect
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
                title = { Text(s?.let { "${it.homeTeamName} v ${it.awayTeamName}" } ?: "Match") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Clock actions")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        listOf(35, 40, 45).forEach { dur ->
                            DropdownMenuItem(
                                text = { Text("Start match (${dur}m halves)") },
                                onClick = {
                                    menuOpen = false
                                    act("Match started") {
                                        container.api.postClock(matchId, ClockRequest("start_first_half", dur))
                                    }
                                },
                            )
                        }
                        DropdownMenuItem(text = { Text("Halftime") }, onClick = {
                            menuOpen = false
                            act("Halftime") { container.api.postClock(matchId, ClockRequest("start_halftime")) }
                        })
                        DropdownMenuItem(text = { Text("Back to 1st half") }, onClick = {
                            menuOpen = false
                            act("Back to 1st half") { container.api.postClock(matchId, ClockRequest("cancel_halftime")) }
                        })
                        DropdownMenuItem(text = { Text("Start 2nd half") }, onClick = {
                            menuOpen = false
                            act("2nd half started") { container.api.postClock(matchId, ClockRequest("start_second_half")) }
                        })
                        DropdownMenuItem(text = { Text("End match") }, onClick = {
                            menuOpen = false
                            confirmEnd = true
                        })
                        DropdownMenuItem(text = { Text("Reopen match") }, onClick = {
                            menuOpen = false
                            act("Match reopened") { container.api.reopenMatch(matchId) }
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

            // Goal buttons
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GoalButton(s.homeTeamName, Modifier.weight(1f)) {
                    s.homeTeamId?.let { flow = ActionFlow.GoalPickScorer(it) }
                }
                GoalButton(s.awayTeamName, Modifier.weight(1f)) {
                    s.awayTeamId?.let { flow = ActionFlow.GoalPickScorer(it) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { flow = ActionFlow.SubPickTeam },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                ) { Text("SUB") }
                OutlinedButton(
                    onClick = { flow = ActionFlow.CardPickTeam },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                ) { Text("CARD") }
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
                            if (e.eventType != "status_change") {
                                IconButton(onClick = {
                                    act("Event deleted") { container.api.deleteEvent(matchId, e.id) }
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

    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text("End match?") },
            text = { Text("Final score: ${s?.homeScore ?: 0} – ${s?.awayScore ?: 0}") },
            confirmButton = {
                TextButton(onClick = {
                    confirmEnd = false
                    act("Full time") { container.api.postClock(matchId, ClockRequest("end_match")) }
                }) { Text("End match") }
            },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Cancel") } },
        )
    }

    // Action bottom sheets
    val currentFlow = flow
    if (currentFlow != ActionFlow.None && s != null) {
        ModalBottomSheet(onDismissRequest = { flow = ActionFlow.None }) {
            when (currentFlow) {
                is ActionFlow.GoalPickScorer -> PlayerPickSheet(
                    title = "Who scored?",
                    players = rosters[currentFlow.teamId].orEmpty(),
                    allowFreeText = true,
                    onPick = { player, freeText ->
                        flow = ActionFlow.GoalPickAssist(currentFlow.teamId, player, freeText)
                    },
                )

                is ActionFlow.GoalPickAssist -> PlayerPickSheet(
                    title = "Assist?",
                    players = rosters[currentFlow.teamId].orEmpty().filter { it.id != currentFlow.scorer?.id },
                    extraOption = "NO ASSIST",
                    onExtra = {
                        flow = ActionFlow.None
                        act("Goal recorded") {
                            container.api.postGoal(
                                matchId,
                                GoalRequest(
                                    teamId = currentFlow.teamId,
                                    playerId = currentFlow.scorer?.id,
                                    playerName = currentFlow.scorerName,
                                    clientEventId = UUID.randomUUID().toString(),
                                ),
                            )
                        }
                    },
                    onPick = { assist, _ ->
                        flow = ActionFlow.None
                        act("Goal recorded") {
                            container.api.postGoal(
                                matchId,
                                GoalRequest(
                                    teamId = currentFlow.teamId,
                                    playerId = currentFlow.scorer?.id,
                                    playerName = currentFlow.scorerName,
                                    assistPlayerId = assist?.id,
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
                        title = "Player OFF",
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
                            flow = ActionFlow.None
                            act("Substitution recorded") {
                                container.api.postSubstitution(
                                    matchId,
                                    SubstitutionRequest(
                                        teamId = currentFlow.teamId,
                                        playerInId = inn.id,
                                        playerOutId = currentFlow.out.id,
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
                        flow = ActionFlow.None
                        act("Card recorded") {
                            container.api.postCard(
                                matchId,
                                CardRequest(
                                    teamId = currentFlow.teamId,
                                    playerId = player?.id,
                                    playerName = freeText,
                                    cardType = currentFlow.cardType,
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
