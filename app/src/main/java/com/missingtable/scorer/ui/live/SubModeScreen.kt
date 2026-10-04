package com.missingtable.scorer.ui.live

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
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
 * position, tap the player coming on, repeat, DONE. Each staged swap becomes
 * one substitution event, all at the minute SUB was tapped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubModeScreen(
    teams: List<SubTeam>,
    initialTeamId: Int,
    minuteLabel: String?,
    onDone: (Map<Int, List<OnPitch.Swap>>) -> Unit,
    onClose: () -> Unit,
    onSetLineup: () -> Unit,
) {
    var teamId by remember { mutableStateOf(initialTeamId) }
    var swapsByTeam by remember { mutableStateOf<Map<Int, List<OnPitch.Swap>>>(emptyMap()) }
    var pickSlot by remember { mutableStateOf<String?>(null) }

    val team = teams.firstOrNull { it.id == teamId } ?: teams.first()
    val swaps = swapsByTeam[team.id].orEmpty()
    val staged = swapsByTeam.values.sumOf { it.size }
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
                            onClick = { onDone(swapsByTeam.filterValues { it.isNotEmpty() }) },
                            enabled = staged > 0,
                        ) { Text(if (staged > 0) "DONE ($staged)" else "DONE") }
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
                        val n = swapsByTeam[t.id].orEmpty().size
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
                val shown = OnPitch.withSwaps(pitch, swaps)
                val swapAt = swaps.associateBy { it.position }

                Text(
                    "Tap the player coming off, then the player coming on.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))

                val slots = Formations.presets[team.formation]
                if (slots != null && shown.keys.all { code -> slots.any { it.code == code } }) {
                    PitchSlots(slots, shown, swapAt, byId, onTap = { pickSlot = it })
                } else {
                    // A formation this app doesn't draw (saved on the web):
                    // same data, as a list.
                    SlotList(shown, swapAt, byId, onTap = { pickSlot = it })
                }

                if (swaps.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    swaps.forEach { sw ->
                        Text(
                            "${byId[sw.inId].tag()} ON for ${byId[sw.outId].tag()} (${sw.position})",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }

        val slot = pickSlot
        val pitch = team.pitch
        if (slot != null && pitch != null) {
            val shown = OnPitch.withSwaps(pitch, swaps)
            val staging = swaps.firstOrNull { it.position == slot }
            val outId = staging?.outId ?: pitch[slot]
            ModalBottomSheet(onDismissRequest = { pickSlot = null }) {
                PlayerPickSheet(
                    title = "ON for ${byId[outId].tag()} ($slot)",
                    players = team.roster.filter { it.id !in shown.values },
                    extraOption = staging?.let { "UNDO — keep ${byId[it.outId].tag()}" },
                    onExtra = {
                        swapsByTeam = swapsByTeam + (team.id to swaps.filterNot { it.position == slot })
                        pickSlot = null
                    },
                    onPick = { inn, _ ->
                        if (inn != null) {
                            swapsByTeam = swapsByTeam + (team.id to OnPitch.stage(swaps, pitch, slot, inn.id))
                        }
                        pickSlot = null
                    },
                )
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
    swapAt: Map<String, OnPitch.Swap>,
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
            val swapped = slot.code in swapAt
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
                swapAt[slot.code]?.let { sw ->
                    Text(
                        "off ${byId[sw.outId]?.jerseyNumber?.let { "#$it" } ?: "?"}",
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
    swapAt: Map<String, OnPitch.Swap>,
    byId: Map<Int, RosterPlayer>,
    onTap: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        shown.forEach { (code, playerId) ->
            val swapped = code in swapAt
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
                    swapAt[code]?.let { Text("off ${byId[it.outId].tag()}", style = MaterialTheme.typography.labelSmall) }
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
