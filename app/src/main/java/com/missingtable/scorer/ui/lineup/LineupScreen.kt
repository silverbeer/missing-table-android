package com.missingtable.scorer.ui.lineup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.missingtable.scorer.AppContainer
import com.missingtable.scorer.data.api.ClockRequest
import com.missingtable.scorer.data.api.LineupPosition
import com.missingtable.scorer.data.api.LineupSaveRequest
import com.missingtable.scorer.data.api.RosterPlayer
import com.missingtable.scorer.domain.Formations
import kotlinx.coroutines.launch

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
    onBack: () -> Unit,
    onStartMatch: () -> Unit,
) {
    var teamId by remember { mutableStateOf(homeTeamId) }
    val teamName = if (teamId == homeTeamId) homeTeamName else awayTeamName
    var roster by remember { mutableStateOf<List<RosterPlayer>>(emptyList()) }
    var formation by remember { mutableStateOf(Formations.presets.keys.first()) }
    // slot index -> player id
    var assignments by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    var selectedSlot by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var dirty by remember { mutableStateOf(false) }
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

    LaunchedEffect(matchId, teamId) {
        loading = true
        assignments = emptyMap()
        if (seasonId != null) {
            runCatching { container.api.roster(teamId, seasonId, null) }
                .onSuccess { roster = it.roster }
                .onFailure { snackbar.showSnackbar("Couldn't load roster") }
        }
        // Existing lineup (edit flow)
        runCatching { container.api.getLineup(matchId, teamId) }
            .onSuccess { lineup ->
                if (lineup.positions.isNotEmpty()) {
                    val preset = Formations.presets[lineup.formationName]
                    if (preset != null) {
                        formation = lineup.formationName
                        val byPosition = lineup.positions.associateBy { it.position }
                        assignments = preset.withIndex().mapNotNull { (idx, code) ->
                            byPosition[code]?.let { idx to it.playerId }
                        }.toMap()
                    }
                }
            }
        selectedSlot = nextOpenSlot()
        loading = false
    }

    fun save(showConfirmation: Boolean = true, then: (() -> Unit)? = null) {
        scope.launch {
            val positions = assignments.mapNotNull { (idx, playerId) ->
                slots.getOrNull(idx)?.let { LineupPosition(playerId = playerId, position = it) }
            }
            runCatching {
                container.api.putLineup(matchId, teamId, LineupSaveRequest(formation, positions))
            }.onSuccess {
                dirty = false
                if (showConfirmation) snackbar.showSnackbar("Lineup saved")
                then?.invoke()
            }.onFailure {
                snackbar.showSnackbar("Failed to save lineup")
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("$teamName lineup") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
                .padding(horizontal = 12.dp),
        ) {
            // Team toggle
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = teamId == homeTeamId,
                    onClick = { teamId = homeTeamId },
                    label = { Text(homeTeamName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = teamId == awayTeamId,
                    onClick = { teamId = awayTeamId },
                    label = { Text(awayTeamName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))

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
                                formation = name
                                assignments = emptyMap()
                                selectedSlot = 0
                                dirty = true
                            }
                        },
                        label = { Text(name) },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(Modifier.fillMaxWidth()) {
                // Slot list
                LazyColumn(
                    Modifier
                        .width(150.dp)
                        .weight(0.45f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(slots.indices.toList(), key = { it }) { idx ->
                        val playerId = assignments[idx]
                        val player = roster.find { it.id == playerId }
                        Surface(
                            onClick = { selectedSlot = idx },
                            shape = MaterialTheme.shapes.small,
                            color = when {
                                idx == selectedSlot -> MaterialTheme.colorScheme.tertiaryContainer
                                player != null -> MaterialTheme.colorScheme.surfaceVariant
                                else -> MaterialTheme.colorScheme.surface
                            },
                            tonalElevation = if (idx == selectedSlot) 4.dp else 1.dp,
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    slots[idx],
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.width(44.dp),
                                )
                                Text(
                                    player?.let { p ->
                                        listOfNotNull("#${p.jerseyNumber}", p.nameOnly).joinToString(" ")
                                    } ?: "—",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.width(8.dp))

                // Jersey grid: tap assigns to selected slot and auto-advances;
                // tapping an already-assigned player unassigns them.
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(0.55f),
                ) {
                    items(roster, key = { it.id }) { p ->
                        val assigned = p.id in assignedIds
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
                            modifier = Modifier.height(64.dp),
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                Text(
                                    "${p.jerseyNumber ?: "?"}",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = if (assigned) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    },
                                )
                                Text(
                                    p.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (assigned) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    },
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "${assignments.size}/${slots.size} assigned",
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { save() },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                ) { Text("Save lineup") }
                Button(
                    onClick = {
                        save(showConfirmation = false) {
                            scope.launch {
                                runCatching {
                                    container.api.postClock(
                                        matchId,
                                        ClockRequest("start_first_half", halfDuration = null),
                                    )
                                }.onSuccess { onStartMatch() }
                                    .onFailure { snackbar.showSnackbar("Failed to start match") }
                            }
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                ) { Text("START MATCH") }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
