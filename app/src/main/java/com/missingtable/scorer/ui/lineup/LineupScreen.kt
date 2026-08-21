package com.missingtable.scorer.ui.lineup

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val PitchGreen = Color(0xFF2E7D46)
private val PitchLine = Color(0xCCFFFFFF)
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
                        assignments = preset.withIndex().mapNotNull { (idx, slot) ->
                            byPosition[slot.code]?.let { idx to it.playerId }
                        }.toMap()
                    }
                }
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
                BulkRosterRequest(seasonId, pendingNumbers.map { BulkRosterPlayer(it) }),
            )
            // Refetch: bulk skips numbers that already exist, so the roster is
            // the one source of truth for number -> id
            val fresh = container.api.roster(teamId, seasonId, null).roster
            roster = fresh
            val byNumber = fresh.associateBy { it.jerseyNumber }
            assignments = assignments.mapValues { (_, id) ->
                if (id < 0) byNumber[-id]?.id ?: id else id
            }
            assignments.values.none { it < 0 }
        }.getOrDefault(false)
        return ok
    }

    fun save(showConfirmation: Boolean = true, then: (() -> Unit)? = null) {
        scope.launch {
            if (!materializePlaceholders()) {
                snackbar.showSnackbar("Couldn't create roster entries for entered numbers")
                return@launch
            }
            val positions = assignments.mapNotNull { (idx, playerId) ->
                slots.getOrNull(idx)?.let { LineupPosition(playerId = playerId, position = it.code) }
            }
            // Queued, not direct — works offline (placeholder materialization
            // above still needs network, but a roster-only lineup queues fine).
            runCatching {
                container.liveRepo.enqueueLineupSave(
                    matchId, teamId, LineupSaveRequest(formation, positions)
                )
            }.onSuccess {
                dirty = false
                if (showConfirmation) snackbar.showSnackbar("Lineup saved")
                then?.invoke()
            }.onFailure {
                snackbar.showSnackbar("Failed to save lineup")
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
            val preset = Formations.presets[lineup.formationName] ?: continue
            formation = lineup.formationName
            val byPosition = lineup.positions.associateBy { it.position }
            assignments = preset.withIndex().mapNotNull { (idx, slot) ->
                byPosition[slot.code]?.let { idx to it.playerId }
            }.toMap()
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
                .padding(horizontal = 12.dp)
                .verticalScroll(rememberScrollState()),
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
            Spacer(Modifier.height(6.dp))

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
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(PitchGreen)
                    val stroke = 3f
                    // outline + halfway + center circle (attacking half up top)
                    drawRect(PitchLine, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                    drawLine(PitchLine, Offset(0f, size.height * 0.5f), Offset(size.width, size.height * 0.5f), stroke)
                    drawCircle(PitchLine, radius = size.width * 0.12f, center = Offset(size.width / 2, size.height / 2), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                    // penalty boxes
                    drawRect(
                        PitchLine,
                        topLeft = Offset(size.width * 0.22f, size.height * 0.88f),
                        size = androidx.compose.ui.geometry.Size(size.width * 0.56f, size.height * 0.12f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
                    )
                    drawRect(
                        PitchLine,
                        topLeft = Offset(size.width * 0.22f, 0f),
                        size = androidx.compose.ui.geometry.Size(size.width * 0.56f, size.height * 0.12f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
                    )
                }

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
                Text(
                    "${assignments.size}/${slots.size} assigned",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { save() },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                ) { Text("Save lineup") }
                Button(
                    onClick = {
                        // Already running: save and go back to it rather than
                        // offering to start it again (SB-677).
                        if (alreadyStarted) save(showConfirmation = false) { onStartMatch() }
                        else startDialog = true
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                ) { Text(if (alreadyStarted) "BACK TO MATCH" else "START MATCH") }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (startDialog) {
        StartMatchDialog(
            ageGroupName = ageGroupName,
            onDismiss = { startDialog = false },
            onConfirm = { dur ->
                startDialog = false
                // Save the lineup before kickoff — that ordering is the whole
                // reason this screen has a start button at all.
                save(showConfirmation = false) {
                    scope.launch {
                        runCatching {
                            container.liveRepo.enqueueClock(
                                matchId,
                                ClockRequest(
                                    "start_first_half",
                                    halfDuration = dur,
                                    occurredAt = java.time.Instant.now().toString(),
                                ),
                            )
                        }.onSuccess { onStartMatch() }
                            .onFailure { snackbar.showSnackbar("Failed to start match") }
                    }
                }
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
