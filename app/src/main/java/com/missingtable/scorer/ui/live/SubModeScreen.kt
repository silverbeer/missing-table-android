package com.missingtable.scorer.ui.live

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.missingtable.scorer.data.api.RosterPlayer
import com.missingtable.scorer.domain.Formations
import com.missingtable.scorer.domain.OnPitch
import com.missingtable.scorer.ui.common.PitchCanvas

/** One team's current XI as sub mode needs it. */
data class SubTeam(
    val id: Int,
    val name: String,
    val formation: String,
    /** position code -> player id currently in that slot; null = no lineup saved. */
    val pitch: Map<String, Int>?,
    val roster: List<RosterPlayer>,
)

/**
 * Pitch-based substitutions (SB-1228). Same pitch as the lineup builder, but
 * it shows who is on the field now and never rewrites the starting XI: tap a
 * position, tap the player coming on, repeat, DONE. A player already on the
 * pitch can also swap positions with a teammate (SB-1352) — a sub often comes
 * with a reshuffle. DONE posts each sub and each net position change as an
 * event, all at the minute SUB was tapped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubModeScreen(
    teams: List<SubTeam>,
    initialTeamId: Int,
    minuteLabel: String?,
    onDone: (Map<Int, List<OnPitch.Change>>) -> Unit,
    onClose: () -> Unit,
    onSetLineup: () -> Unit,
) {
    var teamId by remember { mutableStateOf(initialTeamId) }
    // Staged changes per team, in the order they were made.
    var stagedByTeam by remember { mutableStateOf<Map<Int, List<OnPitch.Change>>>(emptyMap()) }
    var pickSlot by remember { mutableStateOf<String?>(null) }

    val team = teams.firstOrNull { it.id == teamId } ?: teams.first()
    val staged = stagedByTeam[team.id].orEmpty()
    // What DONE will post, per team: swapping and swapping back nets to nothing.
    val netByTeam = teams.associate { t ->
        t.id to (t.pitch?.let { OnPitch.net(it, stagedByTeam[t.id].orEmpty()) } ?: emptyList())
    }.filterValues { it.isNotEmpty() }
    val pending = netByTeam.values.sumOf { it.size }
    val byId = team.roster.associateBy { it.id }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(listOfNotNull("Subs", minuteLabel).joinToString(" · ")) },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(Icons.Filled.Close, contentDescription = "Cancel subs")
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = { onDone(netByTeam) },
                            enabled = pending > 0,
                        ) { Text(if (pending > 0) "DONE ($pending)" else "DONE") }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 12.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    teams.forEach { t ->
                        val n = netByTeam[t.id].orEmpty().size
                        FilterChip(
                            selected = t.id == team.id,
                            onClick = { teamId = t.id },
                            label = {
                                Text(
                                    if (n > 0) "${t.name} ($n)" else t.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))

                val pitch = team.pitch
                if (pitch == null) {
                    NoLineup(team.name, onSetLineup)
                    return@Column
                }
                val shown = OnPitch.current(pitch, staged)
                val marks = Marks.of(pitch, staged, shown)

                Text(
                    "Tap a player to sub them off or move them to another position.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))

                val slots = Formations.presets[team.formation]
                if (slots != null && shown.keys.all { code -> slots.any { it.code == code } }) {
                    PitchSlots(slots, shown, marks, byId, onTap = { pickSlot = it })
                } else {
                    // A formation this app doesn't draw (saved on the web):
                    // same data, as a list.
                    SlotList(shown, marks, byId, onTap = { pickSlot = it })
                }

                val net = netByTeam[team.id].orEmpty()
                if (net.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    net.forEach { change ->
                        Text(
                            when (change) {
                                is OnPitch.Sub -> "${byId[change.inId].tag()} ON for ${byId[change.outId].tag()}"
                                is OnPitch.Move -> "${byId[change.playerId].tag()} → ${change.position}"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }

        val slot = pickSlot
        val pitch = team.pitch
        val here = pitch?.let { OnPitch.current(it, staged)[slot] }
        if (slot != null && pitch != null && here != null) {
            val shown = OnPitch.current(pitch, staged)
            val stagedSub = OnPitch.stagedSubFor(staged, here)
            fun set(next: List<OnPitch.Change>) {
                stagedByTeam = stagedByTeam + (team.id to next)
                pickSlot = null
            }
            ModalBottomSheet(onDismissRequest = { pickSlot = null }) {
                Column {
                    PlayerPickSheet(
                        title = "ON for ${byId[stagedSub?.outId ?: here].tag()} ($slot)",
                        players = team.roster.filter { it.id !in shown.values },
                        extraOption = stagedSub?.let { "UNDO — keep ${byId[it.outId].tag()}" },
                        onExtra = { set(OnPitch.unstageSub(staged, pitch, slot)) },
                        onPick = { inn, _ ->
                            if (inn != null) {
                                set(OnPitch.stageSub(staged, pitch, slot, inn.id))
                            } else {
                                pickSlot = null
                            }
                        },
                    )
                    SwapWith(
                        player = byId[here].tag(),
                        others = shown.filterKeys { it != slot },
                        byId = byId,
                        onPick = { other -> set(OnPitch.stageSwap(staged, pitch, slot, other)) },
                    )
                }
            }
        }
    }
}

/** How sub mode marks a slot: who it replaced (staged sub) and where it moved from. */
private data class Marks(val cameOnFor: Map<String, Int?>, val movedFrom: Map<String, String>) {
    companion object {
        fun of(pitch: Map<String, Int>, staged: List<OnPitch.Change>, shown: Map<String, Int>): Marks {
            val before = OnPitch.current(pitch, staged.filterIsInstance<OnPitch.Sub>())
                .entries.associate { (pos, id) -> id to pos }
            return Marks(
                cameOnFor = shown.mapNotNull { (pos, id) ->
                    OnPitch.stagedSubFor(staged, id)?.let { pos to it.outId }
                }.toMap(),
                movedFrom = shown.mapNotNull { (pos, id) ->
                    before[id]?.takeIf { it != pos }?.let { pos to it }
                }.toMap(),
            )
        }
    }
}

/** "Or move <player> to…" — the on-pitch teammates to swap positions with (SB-1352). */
@Composable
private fun SwapWith(
    player: String,
    others: Map<String, Int>,
    byId: Map<Int, RosterPlayer>,
    onPick: (String) -> Unit,
) {
    if (others.isEmpty()) return
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
        Text("Or swap $player's position with", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp),
        ) {
            items(others.entries.toList(), key = { it.key }) { (code, id) ->
                OutlinedButton(
                    onClick = { onPick(code) },
                    contentPadding = PaddingValues(4.dp),
                    modifier = Modifier.height(64.dp),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(code, fontWeight = FontWeight.Bold)
                        Text(
                            byId[id]?.let { p -> p.jerseyNumber?.let { "#$it" } ?: p.nameOnly } ?: "?",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private fun RosterPlayer?.tag(): String =
    this?.let { p -> listOfNotNull(p.jerseyNumber?.let { "#$it" }, p.nameOnly).joinToString(" ").ifBlank { p.label } }
        ?: "?"

@Composable
private fun PitchSlots(
    slots: List<Formations.Slot>,
    shown: Map<String, Int>,
    marks: Marks,
    byId: Map<Int, RosterPlayer>,
    onTap: (String) -> Unit,
) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .aspectRatio(0.78f)
            .clip(MaterialTheme.shapes.medium),
    ) {
        val w = maxWidth
        val h = maxHeight
        PitchCanvas()
        slots.forEach { slot ->
            val playerId = shown[slot.code] ?: return@forEach
            val player = byId[playerId]
            val swapped = slot.code in marks.cameOnFor || slot.code in marks.movedFrom
            val markerSize = 46.dp
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(72.dp)
                    .offset(x = w * slot.x - 36.dp, y = h * slot.y - markerSize / 2),
            ) {
                Surface(
                    onClick = { onTap(slot.code) },
                    shape = CircleShape,
                    color = if (swapped) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                    border = BorderStroke(if (swapped) 3.dp else 1.dp, Color.White),
                    modifier = Modifier.size(markerSize),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            "${player?.jerseyNumber ?: "?"}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (swapped) {
                                MaterialTheme.colorScheme.onTertiary
                            } else {
                                MaterialTheme.colorScheme.onPrimary
                            },
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                Text(
                    player?.nameOnly ?: slot.code,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                marks.cameOnFor[slot.code]?.let { outId ->
                    Text(
                        "off ${byId[outId]?.jerseyNumber?.let { "#$it" } ?: "?"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
                marks.movedFrom[slot.code]?.let { from ->
                    Text(
                        "from $from",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun SlotList(
    shown: Map<String, Int>,
    marks: Marks,
    byId: Map<Int, RosterPlayer>,
    onTap: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        shown.forEach { (code, playerId) ->
            val swapped = code in marks.cameOnFor || code in marks.movedFrom
            Surface(
                onClick = { onTap(code) },
                shape = MaterialTheme.shapes.medium,
                color = if (swapped) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(horizontal = 12.dp),
                ) {
                    Text(code, fontWeight = FontWeight.Bold, modifier = Modifier.width(48.dp))
                    Text(byId[playerId].tag(), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    marks.cameOnFor[code]?.let { Text("off ${byId[it].tag()}", style = MaterialTheme.typography.labelSmall) }
                    marks.movedFrom[code]?.let { Text("from $it", style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }
}

@Composable
private fun NoLineup(teamName: String, onSetLineup: () -> Unit) {
    Column(Modifier.padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "No lineup saved for $teamName. Set the starting XI first — subs are tracked against it.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onSetLineup) { Text("SET LINEUP") }
    }
}
